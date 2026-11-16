/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.timeline.history;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twitter.util.user.UserIdentifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.crimera.CustomCrashHandler;
import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.entity.Media;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * View-layer timeline history tracker.
 *
 * Records posts only when their bound views are attached and genuinely visible in the viewport,
 * filtering out offscreen RecyclerView prefetches and ballistic flings, while flushing any
 * currently onscreen posts immediately upon pull-to-refresh, app pause/stop (e.g. Digital
 * Wellbeing pause or returning to launcher), and uncaught crashes.
 */
public final class TimelineHistoryManager {

    private static final long STAGE_DELAY_MS = 60L;
    private static final long DWELL_DELAY_MS = 220L;
    private static final long WRITE_DEBOUNCE_MS = 350L;
    private static final long REWRITE_COOLDOWN_MS = 8_000L;
    private static final int MAX_TEXT_LENGTH = 600;
    private static final int MAX_STAGED_ENTRIES = 64;
    private static final int MAX_RECENT_CACHE = 1500;

    private static final int FETCH_TYPE_REFRESH_OLDER = 1;
    private static final int FETCH_TYPE_PULL_TO_REFRESH = 2;
    private static final int FETCH_TYPE_AUTO_REFRESH = 4;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService WRITER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "piko-history-writer");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean WRITE_SCHEDULED = new AtomicBoolean(false);

    // Main-thread only: tracks currently bound TweetHeaderView / QuoteView instances.
    private static final WeakHashMap<View, TrackedBinding> TRACKED_VIEWS = new WeakHashMap<>();

    // Thread-safe staged onscreen snapshots (safe to flush from any crashing thread).
    private static final ConcurrentHashMap<String, TimelineHistoryEntry> STAGED_ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<TimelineHistoryEntry> PENDING_QUEUE = new ConcurrentLinkedDeque<>();
    private static final ConcurrentHashMap<String, Long> RECENT_WRITTEN = new ConcurrentHashMap<>();

    private static final Object SENTINEL_MISSING = new Object();
    private static volatile Object cachedHeaderViewField;
    private static volatile Object cachedAdField;
    private static volatile long lastAssignedTimestamp = 0L;

    private static final Runnable ASYNC_FLUSH_RUNNABLE = () -> {
        WRITE_SCHEDULED.set(false);
        submitAsyncWrite();
    };

    private TimelineHistoryManager() {
    }

    /** Called from Application.onCreate via twitterInitHook. */
    public static void init(@Nullable Context context) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        try {
            Context appCtx = context != null ? context.getApplicationContext() : Utils.getContext();
            if (appCtx instanceof Application) {
                ((Application) appCtx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(@NonNull Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(@NonNull Activity activity) {
                    }

                    @Override
                    public void onActivityPaused(@NonNull Activity activity) {
                        flushVisibleOnPauseOrBackground(false);
                    }

                    @Override
                    public void onActivityStopped(@NonNull Activity activity) {
                        flushVisibleOnPauseOrBackground(true);
                    }

                    @Override
                    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                        flushVisibleOnPauseOrBackground(false);
                    }

                    @Override
                    public void onActivityDestroyed(@NonNull Activity activity) {
                    }
                });
            }

            CustomCrashHandler.registerCrashListener(TimelineHistoryManager::flushOnCrash);

            // Ensure crash flushing also works when CrashLogsPatch is not applied.
            Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
            if (!(current instanceof CustomCrashHandler)) {
                Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                    flushOnCrash();
                    if (current != null) {
                        current.uncaughtException(thread, throwable);
                    }
                });
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** Hooked at TweetHeaderViewDelegateBinder.d(tweet, holder, timestamp, username). */
    public static void onTweetHeaderBound(@Nullable Object tweetObj, @Nullable Object headerHolderObj) {
        onHeaderBoundInternal(tweetObj, headerHolderObj, TimelineHistoryEntry.SOURCE_TIMELINE);
    }

    /** Hooked at FocalTweetHeaderViewDelegateBinder.d(tweet, holder, timestamp, username). */
    public static void onFocalTweetHeaderBound(@Nullable Object tweetObj, @Nullable Object headerHolderObj) {
        onHeaderBoundInternal(tweetObj, headerHolderObj, TimelineHistoryEntry.SOURCE_FOCAL);
    }

    /** Hooked at QuoteView.l(tweet, renderable, color). */
    public static void onQuoteBound(@Nullable View quoteView, @Nullable Object tweetObj) {
        if (quoteView == null || tweetObj == null || !isEnabled()) {
            return;
        }
        trackViewBound(quoteView, quoteView, tweetObj, TimelineHistoryEntry.SOURCE_QUOTE);
    }

    /** Hooked at c0.l0(int fetchType) right before timeline fetches replace or prepend items. */
    public static void onTimelineFetch(int fetchType) {
        if (!isEnabled()) {
            return;
        }
        if (fetchType == FETCH_TYPE_PULL_TO_REFRESH
                || fetchType == FETCH_TYPE_AUTO_REFRESH
                || fetchType == FETCH_TYPE_REFRESH_OLDER) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                captureVisibleViewsOnUiThread();
                scheduleAsyncFlush(0L);
            } else {
                MAIN_HANDLER.post(() -> {
                    captureVisibleViewsOnUiThread();
                    scheduleAsyncFlush(0L);
                });
            }
        }
    }

    private static void onHeaderBoundInternal(
            @Nullable Object tweetObj,
            @Nullable Object headerHolderObj,
            int sourceType
    ) {
        if (tweetObj == null || headerHolderObj == null || !isEnabled()) {
            return;
        }
        View headerView = extractHeaderView(headerHolderObj);
        if (headerView == null) {
            return;
        }
        View measureTarget = findEnclosingTweetView(headerView);
        trackViewBound(headerView, measureTarget, tweetObj, sourceType);
    }

    private static void trackViewBound(
            @NonNull View anchorView,
            @NonNull View measureTarget,
            @NonNull Object tweetObj,
            int sourceType
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN_HANDLER.post(() -> trackViewBound(anchorView, measureTarget, tweetObj, sourceType));
            return;
        }

        TrackedBinding binding = TRACKED_VIEWS.get(anchorView);
        if (binding == null) {
            binding = new TrackedBinding();
            TRACKED_VIEWS.put(anchorView, binding);
            installAttachListener(anchorView, binding);
        } else {
            cancelTimers(binding);
            removeStagedKey(binding.stagedKey);
            binding.stagedKey = null;
        }

        binding.anchorView = anchorView;
        binding.measureTarget = measureTarget;
        binding.tweetObj = tweetObj;
        binding.sourceType = sourceType;
        binding.committed = false;

        if (anchorView.isAttachedToWindow()) {
            scheduleExposureChecks(binding);
        }
    }

    private static void installAttachListener(@NonNull View anchorView, @NonNull TrackedBinding binding) {
        anchorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                if (binding.tweetObj != null && !binding.committed) {
                    scheduleExposureChecks(binding);
                }
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                cancelTimers(binding);
                removeStagedKey(binding.stagedKey);
                binding.stagedKey = null;
                binding.tweetObj = null;
                binding.measureTarget = null;
            }
        });
    }

    private static void scheduleExposureChecks(@NonNull TrackedBinding binding) {
        cancelTimers(binding);

        Runnable stageTask = () -> {
            binding.stageRunnable = null;
            if (!isEnabled() || binding.committed || binding.tweetObj == null) {
                return;
            }
            if (!isActuallyVisibleOnScreen(binding.measureTarget)) {
                return;
            }
            TimelineHistoryEntry entry = extractEntry(binding.tweetObj, binding.sourceType);
            if (entry != null) {
                stageEntry(binding, entry);
            }
        };

        Runnable dwellTask = () -> {
            binding.dwellRunnable = null;
            if (!isEnabled() || binding.committed || binding.tweetObj == null) {
                return;
            }
            if (!isActuallyVisibleOnScreen(binding.measureTarget)) {
                removeStagedKey(binding.stagedKey);
                binding.stagedKey = null;
                return;
            }
            TimelineHistoryEntry entry = removeStagedKey(binding.stagedKey);
            binding.stagedKey = null;
            if (entry == null) {
                entry = extractEntry(binding.tweetObj, binding.sourceType);
            }
            if (entry != null) {
                binding.committed = true;
                enqueueIfNotRecent(entry);
                scheduleAsyncFlush(WRITE_DEBOUNCE_MS);
            }
        };

        binding.stageRunnable = stageTask;
        binding.dwellRunnable = dwellTask;
        MAIN_HANDLER.postDelayed(stageTask, STAGE_DELAY_MS);
        MAIN_HANDLER.postDelayed(dwellTask, DWELL_DELAY_MS);
    }

    private static void cancelTimers(@NonNull TrackedBinding binding) {
        if (binding.stageRunnable != null) {
            MAIN_HANDLER.removeCallbacks(binding.stageRunnable);
            binding.stageRunnable = null;
        }
        if (binding.dwellRunnable != null) {
            MAIN_HANDLER.removeCallbacks(binding.dwellRunnable);
            binding.dwellRunnable = null;
        }
    }

    private static void stageEntry(@NonNull TrackedBinding binding, @NonNull TimelineHistoryEntry entry) {
        String key = dedupeKey(entry.accountId, entry.tweetId);
        if (STAGED_ENTRIES.size() >= MAX_STAGED_ENTRIES && !STAGED_ENTRIES.containsKey(key)) {
            return;
        }
        binding.stagedKey = key;
        STAGED_ENTRIES.put(key, entry);
    }

    @Nullable
    private static TimelineHistoryEntry removeStagedKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        return STAGED_ENTRIES.remove(key);
    }

    private static void flushVisibleOnPauseOrBackground(boolean waitBriefly) {
        if (!isEnabled()) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            captureVisibleViewsOnUiThread();
            promoteStagedEntries();
            Future<?> future = submitAsyncWrite();
            if (waitBriefly && future != null) {
                try {
                    future.get(120, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Public synchronous flush usable from UI/lifecycle callers before reading history. */
    public static void flushVisibleNow() {
        if (!isEnabled()) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            captureVisibleViewsOnUiThread();
        }
        promoteStagedEntries();
        drainAndWriteBatchSync();
    }

    private static void flushOnCrash() {
        try {
            if (!SettingsStatus.timelineHistory) {
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                captureVisibleViewsOnUiThread();
            }
            promoteStagedEntries();
            drainAndWriteBatchSync();
        } catch (Throwable ignored) {
        }
    }

    private static void captureVisibleViewsOnUiThread() {
        for (Map.Entry<View, TrackedBinding> mapEntry : TRACKED_VIEWS.entrySet()) {
            TrackedBinding binding = mapEntry.getValue();
            if (binding == null || binding.committed || binding.tweetObj == null) {
                continue;
            }
            if (!isActuallyVisibleOnScreen(binding.measureTarget)) {
                continue;
            }
            TimelineHistoryEntry entry = removeStagedKey(binding.stagedKey);
            binding.stagedKey = null;
            if (entry == null) {
                entry = extractEntry(binding.tweetObj, binding.sourceType);
            }
            if (entry != null) {
                binding.committed = true;
                cancelTimers(binding);
                enqueueIfNotRecent(entry);
            }
        }
    }

    private static void promoteStagedEntries() {
        if (STAGED_ENTRIES.isEmpty()) {
            return;
        }
        List<TimelineHistoryEntry> staged = new ArrayList<>(STAGED_ENTRIES.values());
        STAGED_ENTRIES.clear();
        for (TimelineHistoryEntry entry : staged) {
            enqueueIfNotRecent(entry);
        }
    }

    private static void enqueueIfNotRecent(@NonNull TimelineHistoryEntry entry) {
        String key = dedupeKey(entry.accountId, entry.tweetId);
        long now = entry.lastSeenAt;
        Long prev = RECENT_WRITTEN.get(key);
        if (prev != null && (now - prev) < REWRITE_COOLDOWN_MS) {
            return;
        }
        if (RECENT_WRITTEN.size() > MAX_RECENT_CACHE) {
            long cutoff = now - (REWRITE_COOLDOWN_MS * 4);
            RECENT_WRITTEN.entrySet().removeIf(e -> e.getValue() < cutoff);
            if (RECENT_WRITTEN.size() > MAX_RECENT_CACHE) {
                RECENT_WRITTEN.clear();
            }
        }
        RECENT_WRITTEN.put(key, now);
        PENDING_QUEUE.addLast(entry);
    }

    private static void scheduleAsyncFlush(long delayMs) {
        if (PENDING_QUEUE.isEmpty()) {
            return;
        }
        if (delayMs <= 0L) {
            MAIN_HANDLER.removeCallbacks(ASYNC_FLUSH_RUNNABLE);
            WRITE_SCHEDULED.set(false);
            submitAsyncWrite();
            return;
        }
        if (WRITE_SCHEDULED.compareAndSet(false, true)) {
            MAIN_HANDLER.postDelayed(ASYNC_FLUSH_RUNNABLE, delayMs);
        }
    }

    @Nullable
    private static Future<?> submitAsyncWrite() {
        if (PENDING_QUEUE.isEmpty()) {
            return null;
        }
        try {
            return WRITER_EXECUTOR.submit(TimelineHistoryManager::drainAndWriteBatchSync);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    private static void drainAndWriteBatchSync() {
        if (PENDING_QUEUE.isEmpty()) {
            return;
        }
        Context context = Utils.getContext();
        if (context == null) {
            return;
        }
        Map<String, TimelineHistoryEntry> deduped = new LinkedHashMap<>();
        while (true) {
            TimelineHistoryEntry item = PENDING_QUEUE.pollFirst();
            if (item == null) {
                break;
            }
            deduped.put(dedupeKey(item.accountId, item.tweetId), item);
        }
        if (deduped.isEmpty()) {
            return;
        }
        TimelineHistoryDatabase.getInstance(context).upsertBatch(
                new ArrayList<>(deduped.values()),
                getHistoryLimit()
        );
    }

    private static boolean isEnabled() {
        return SettingsStatus.timelineHistory && Pref.enableTimelineHistory();
    }

    @NonNull
    public static String getCurrentAccountId() {
        try {
            UserIdentifier id = UserIdentifier.getCurrent();
            if (id != null) {
                String strId = id.getStringId();
                if (strId != null && !strId.isEmpty()) {
                    return strId;
                }
            }
        } catch (Throwable ignored) {
        }
        return "0";
    }

    public static int getHistoryLimit() {
        try {
            String raw = Pref.timelineHistoryLimit();
            if (raw != null && !raw.trim().isEmpty()) {
                int parsed = Integer.parseInt(raw.trim());
                return Math.max(50, Math.min(5000, parsed));
            }
        } catch (Throwable ignored) {
        }
        return 500;
    }

    @NonNull
    public static List<TimelineHistoryEntry> queryHistory(
            @Nullable String searchQuery,
            int filterMode
    ) {
        Context context = Utils.getContext();
        if (context == null) {
            return new ArrayList<>();
        }
        flushVisibleNow();
        int limit = getHistoryLimit();
        TimelineHistoryDatabase db = TimelineHistoryDatabase.getInstance(context);
        String accountId = getCurrentAccountId();
        db.pruneAccount(accountId, limit);
        return db.query(accountId, searchQuery, filterMode, limit);
    }

    public static int getHistoryCount() {
        Context context = Utils.getContext();
        if (context == null) {
            return 0;
        }
        return TimelineHistoryDatabase.getInstance(context).getCount(getCurrentAccountId());
    }

    public static void pruneCurrentAccount() {
        Context context = Utils.getContext();
        if (context == null) {
            return;
        }
        int limit = getHistoryLimit();
        WRITER_EXECUTOR.submit(() ->
                TimelineHistoryDatabase.getInstance(context).pruneAccount(getCurrentAccountId(), limit));
    }

    public static void deleteEntry(long tweetId) {
        Context context = Utils.getContext();
        if (context == null) {
            return;
        }
        String accountId = getCurrentAccountId();
        String key = dedupeKey(accountId, tweetId);
        STAGED_ENTRIES.remove(key);
        RECENT_WRITTEN.remove(key);
        TimelineHistoryDatabase.getInstance(context).deleteEntry(accountId, tweetId);
    }

    public static void clearCurrentAccountHistory() {
        Context context = Utils.getContext();
        if (context == null) {
            return;
        }
        String accountId = getCurrentAccountId();
        String prefix = accountId + ":";
        STAGED_ENTRIES.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        RECENT_WRITTEN.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        PENDING_QUEUE.removeIf(e -> accountId.equals(e.accountId));
        TimelineHistoryDatabase.getInstance(context).clearAccount(accountId);
    }

    /** Shows confirmation dialog, clears current account history on background thread, then runs callback. */
    public static void showClearConfirmDialog(
            @NonNull Context context,
            @Nullable Runnable onClearedOnUiThread
    ) {
        new android.app.AlertDialog.Builder(context)
                .setTitle(app.morphe.extension.shared.StringRef.str("piko_history_clear_confirm_title"))
                .setMessage(app.morphe.extension.shared.StringRef.str("piko_history_clear_confirm_desc"))
                .setPositiveButton(app.morphe.extension.shared.StringRef.str("piko_history_clear_btn"), (dialog, which) ->
                        WRITER_EXECUTOR.submit(() -> {
                            clearCurrentAccountHistory();
                            MAIN_HANDLER.post(() -> {
                                PikoUtils.toast(app.morphe.extension.shared.StringRef.str("piko_history_cleared_toast"));
                                if (onClearedOnUiThread != null) {
                                    onClearedOnUiThread.run();
                                }
                            });
                        })
                )
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void openTweetInApp(@Nullable Context context, @NonNull TimelineHistoryEntry entry) {
        openInAppUrl(context, entry.buildTweetUrl());
    }

    public static void openAuthorInApp(@Nullable Context context, @NonNull TimelineHistoryEntry entry) {
        String url = entry.buildAuthorUrl();
        if (!url.isEmpty()) {
            openInAppUrl(context, url);
        }
    }

    private static void openInAppUrl(@Nullable Context context, @NonNull String url) {
        Context launchContext = context != null ? context : Utils.getContext();
        if (launchContext == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(launchContext.getPackageName());
            if (!(launchContext instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            launchContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            PikoUtils.openUrl(url, true);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    @Nullable
    private static TimelineHistoryEntry extractEntry(@NonNull Object tweetObj, int sourceType) {
        try {
            if (Pref.skipTimelineHistoryPromoted() && isPromotedTweet(tweetObj)) {
                return null;
            }
            Tweet tweet = new Tweet(tweetObj);
            Long idObj = tweet.getTweetId();
            if (idObj == null || idObj <= 0L) {
                return null;
            }

            String handle = cleanHandle(tweet.getTweetUsername());
            String name = normalizeSingleLine(tweet.getTweetProfileName());
            String text = truncateText(tweet.getText(), MAX_TEXT_LENGTH);
            int mediaCount = countMedia(tweet);

            long ts = allocateMonotonicTimestamp();
            return new TimelineHistoryEntry(
                    idObj,
                    getCurrentAccountId(),
                    handle,
                    name,
                    text,
                    mediaCount,
                    sourceType,
                    false,
                    ts,
                    ts
            );
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    private static synchronized long allocateMonotonicTimestamp() {
        long now = System.currentTimeMillis();
        if (now <= lastAssignedTimestamp) {
            lastAssignedTimestamp++;
        } else {
            lastAssignedTimestamp = now;
        }
        return lastAssignedTimestamp;
    }

    @Nullable
    private static View extractHeaderView(@NonNull Object headerHolderObj) {
        try {
            Object resolved = cachedHeaderViewField;
            if (resolved == null) {
                Field found = null;
                for (Field f : headerHolderObj.getClass().getDeclaredFields()) {
                    if (View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        found = f;
                        break;
                    }
                }
                resolved = found != null ? found : SENTINEL_MISSING;
                cachedHeaderViewField = resolved;
            }
            if (resolved instanceof Field) {
                Object value = ((Field) resolved).get(headerHolderObj);
                if (value instanceof View) {
                    return (View) value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @NonNull
    private static View findEnclosingTweetView(@NonNull View childView) {
        ViewParent parent = childView.getParent();
        for (int i = 0; i < 6 && parent != null; i++) {
            if (parent instanceof View) {
                View view = (View) parent;
                if ("TweetView".equals(view.getClass().getSimpleName())) {
                    return view;
                }
                parent = view.getParent();
            } else {
                break;
            }
        }
        return childView;
    }

    private static boolean isActuallyVisibleOnScreen(@Nullable View target) {
        if (target == null || !target.isAttachedToWindow() || !target.isShown()) {
            return false;
        }
        if (target.getWindowVisibility() != View.VISIBLE) {
            return false;
        }
        Rect rect = new Rect();
        if (!target.getGlobalVisibleRect(rect)) {
            return false;
        }
        float density = target.getResources().getDisplayMetrics().density;
        int minVisiblePx = Math.max(8, Math.round(20f * density));
        return rect.width() > 0 && rect.height() >= minVisiblePx;
    }

    private static boolean isPromotedTweet(@NonNull Object tweetObj) {
        try {
            Object resolved = cachedAdField;
            if (resolved == null) {
                Field found = null;
                for (Field f : tweetObj.getClass().getDeclaredFields()) {
                    String typeName = f.getType().getName();
                    if (typeName.contains(".entity.ad.")) {
                        f.setAccessible(true);
                        found = f;
                        break;
                    }
                }
                resolved = found != null ? found : SENTINEL_MISSING;
                cachedAdField = resolved;
            }
            if (resolved instanceof Field) {
                return ((Field) resolved).get(tweetObj) != null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int countMedia(@NonNull Tweet tweet) {
        try {
            ArrayList<ArrayList<Media>> groups = tweet.getMediaList();
            if (groups == null || groups.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (ArrayList<Media> group : groups) {
                if (group != null) {
                    count += group.size();
                }
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @NonNull
    private static String cleanHandle(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    @NonNull
    private static String normalizeSingleLine(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    @NonNull
    private static String truncateText(@Nullable String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen).trim() + "…";
    }

    @NonNull
    private static String dedupeKey(@NonNull String accountId, long tweetId) {
        return accountId + ":" + tweetId;
    }

    private static final class TrackedBinding {
        @Nullable
        View anchorView;
        @Nullable
        View measureTarget;
        @Nullable
        Object tweetObj;
        int sourceType;
        boolean committed;
        @Nullable
        String stagedKey;
        @Nullable
        Runnable stageRunnable;
        @Nullable
        Runnable dwellRunnable;
    }
}
