/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.timeline.moderation;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.patches.timeline.moderation.BatchModerationBridge.ReplyItemData;

/**
 * Coordinates multi-select batch mute/block inside TweetDetail reply threads.
 */
public final class BatchModerationManager {

    private static final int MENU_ITEM_BATCH_MOD_ID = 0x710B4201;
    private static final long BATCH_STEP_DELAY_MS = 180L;
    private static final long REQUEST_TIMEOUT_MS = 15000L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<Activity, Session> SESSIONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Activity>> CTRL_TO_ACTIVITY =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, RowBinding> VIEW_BINDINGS = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile int activeSessionCount = 0;
    private static volatile int suppressBlockToastCount = 0;

    private BatchModerationManager() {
    }

    // -------------------------------------------------------------------------
    // Hooks invoked from TweetDetail bytecode
    // -------------------------------------------------------------------------

    /**
     * Injects the batch moderation toggle item into the TweetDetail toolbar menu.
     */
    public static void onPrepareOptionsMenu(@Nullable Object controller, @Nullable Object navComponent, @Nullable Menu menu) {
        if (!Pref.enableBatchModeration() || !BatchModerationBridge.isReady()) return;
        if (controller == null || menu == null) return;

        Activity activity = BatchModerationBridge.getControllerActivity(controller);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        Session session = getOrCreateSession(activity, controller);
        session.toolbarRef = new WeakReference<>(BatchModerationBridge.getToolbar(navComponent));
        session.ensureAdapterRef();

        MenuItem item = menu.findItem(MENU_ITEM_BATCH_MOD_ID);
        if (item == null) {
            item = menu.add(Menu.NONE, MENU_ITEM_BATCH_MOD_ID, 0, str("piko_batch_mod_menu_title"));
        }
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        int iconResId = ResourceUtils.getDrawableIdentifier("piko_ic_batch_moderation");
        if (iconResId != 0) {
            try {
                Drawable icon = activity.getDrawable(iconResId);
                if (icon != null) {
                    icon = DrawableCompat.wrap(icon.mutate());
                    item.setIcon(icon);
                }
            } catch (Throwable ignored) {
            }
        }
        session.menuItemRef = new WeakReference<>(item);
        session.updateMenuIconState();
    }

    /**
     * Handles clicks on the injected batch moderation toolbar button.
     */
    public static boolean onOptionsItemSelected(@Nullable Object controller, @Nullable MenuItem item) {
        if (controller == null || item == null) return false;
        if (item.getItemId() != MENU_ITEM_BATCH_MOD_ID) return false;
        if (!Pref.enableBatchModeration() || !BatchModerationBridge.isReady()) return false;

        Activity activity = resolveActivity(controller);
        if (activity == null) return false;
        Session session = getOrCreateSession(activity, controller);
        session.toggleMultiSelect();
        return true;
    }

    /**
     * Captures the RecyclerView adapter attached to the TweetDetail controller.
     */
    public static void onAdapterAttached(@Nullable Object controller, @Nullable Object adapter) {
        if (!Pref.enableBatchModeration() || !BatchModerationBridge.isReady()) return;
        if (controller == null || adapter == null) return;
        Activity activity = resolveActivity(controller);
        if (activity == null) return;
        Session session = getOrCreateSession(activity, controller);
        session.adapterRef = new WeakReference<>(adapter);
    }

    /**
     * Indexes newly loaded conversation items before rows rebind.
     */
    public static void onTimelineLoaded(@Nullable Object controller, @Nullable Object collection) {
        if (!Pref.enableBatchModeration() || !BatchModerationBridge.isReady()) return;
        if (controller == null || collection == null) return;
        Activity activity = resolveActivity(controller);
        if (activity == null) return;
        Session session = getOrCreateSession(activity, controller);
        session.updateCollectionSnapshot(collection);
    }

    /**
     * Attaches row metadata and updates visual selection foreground when an item binds.
     */
    public static void onTweetDetailItemBound(@Nullable Object controller, @Nullable Object bindEvent) {
        if (controller == null || bindEvent == null) return;
        if (!BatchModerationBridge.isReady()) return;

        View rowView = BatchModerationBridge.getBoundView(bindEvent);
        if (rowView == null) return;

        if (!Pref.enableBatchModeration()) {
            clearRowBinding(rowView);
            return;
        }

        Activity activity = resolveActivity(controller);
        if (activity == null) {
            clearRowBinding(rowView);
            return;
        }

        Session session = SESSIONS.get(activity);
        if (session == null) {
            session = getOrCreateSession(activity, controller);
        }

        Object item = BatchModerationBridge.getBoundItem(bindEvent);
        ReplyItemData reply = BatchModerationBridge.extractReplyItem(item);
        if (reply == null) {
            clearRowBinding(rowView);
            return;
        }

        boolean selectable = session.isReplySelectable(reply);
        if (!selectable) {
            clearRowBinding(rowView);
            return;
        }

        RowBinding binding = new RowBinding(session, rowView, reply);
        attachRowBinding(rowView, binding);
        session.applyRowVisual(rowView, binding);
    }

    /**
     * Intercepts touch down/move/up on tweet rows while multi-select is active on that row.
     */
    public static boolean shouldInterceptTweetTouch(@Nullable View view, @Nullable MotionEvent ev) {
        if (activeSessionCount <= 0 || view == null || ev == null) return false;
        RowBinding binding = VIEW_BINDINGS.get(view);
        if (binding == null) return false;
        Session session = binding.session;
        if (!session.multiSelectActive || session.batchRunning) return false;

        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            binding.touchDownX = ev.getRawX();
            binding.touchDownY = ev.getRawY();
            binding.touchDownTime = ev.getEventTime();
            binding.touchCancelled = false;
            return true;
        }
        return action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }

    /**
     * Handles intercepted touch events so child buttons/links do not fire during selection.
     */
    public static boolean onInterceptedTweetTouch(@Nullable View view, @Nullable MotionEvent ev) {
        if (activeSessionCount <= 0 || view == null || ev == null) return false;
        RowBinding binding = VIEW_BINDINGS.get(view);
        if (binding == null) return false;
        Session session = binding.session;
        if (!session.multiSelectActive || session.batchRunning) return false;

        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            binding.touchDownX = ev.getRawX();
            binding.touchDownY = ev.getRawY();
            binding.touchDownTime = ev.getEventTime();
            binding.touchCancelled = false;
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            binding.touchCancelled = true;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            if (Math.hypot(ev.getRawX() - binding.touchDownX, ev.getRawY() - binding.touchDownY) > slop) {
                binding.touchCancelled = true;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!binding.touchCancelled) {
                float slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                boolean withinSlop = Math.hypot(ev.getRawX() - binding.touchDownX, ev.getRawY() - binding.touchDownY) <= slop;
                boolean withinTapTime = (ev.getEventTime() - binding.touchDownTime) < 600L;
                if (withinSlop && withinTapTime) {
                    view.playSoundEffect(SoundEffectConstants.CLICK);
                    session.toggleAuthorSelection(binding.reply);
                }
            }
            binding.touchCancelled = false;
            return true;
        }
        return false;
    }

    /**
     * Exits multi-select mode when the hardware/gesture back button is pressed.
     */
    public static boolean onActivityBackPressed(@Nullable Activity activity) {
        if (activeSessionCount <= 0 || activity == null) return false;
        Session session = SESSIONS.get(activity);
        if (session == null || !session.multiSelectActive) return false;
        if (session.batchRunning) {
            session.cancelRunningBatch();
            return true;
        }
        session.exitMultiSelect();
        return true;
    }

    /**
     * Tears down UI state and pending tasks when the TweetDetail controller is destroyed.
     */
    public static void onControllerDestroyed(@Nullable Object controller) {
        if (controller == null) return;
        Activity activity = resolveActivity(controller);
        CTRL_TO_ACTIVITY.remove(controller);
        if (activity == null) return;
        Session session = SESSIONS.remove(activity);
        if (session != null) {
            session.destroy();
        }
    }

    /**
     * Suppresses individual native "Blocked @user" toasts while executing a batch block queue.
     */
    public static boolean shouldSuppressNativeBlockToast() {
        return suppressBlockToastCount > 0;
    }

    // -------------------------------------------------------------------------
    // Internal Session Management
    // -------------------------------------------------------------------------

    @Nullable
    private static Activity resolveActivity(@NonNull Object controller) {
        WeakReference<Activity> ref = CTRL_TO_ACTIVITY.get(controller);
        Activity activity = ref != null ? ref.get() : null;
        if (activity == null) {
            activity = BatchModerationBridge.getControllerActivity(controller);
            if (activity != null) {
                CTRL_TO_ACTIVITY.put(controller, new WeakReference<>(activity));
            }
        }
        return activity;
    }

    @NonNull
    private static Session getOrCreateSession(@NonNull Activity activity, @NonNull Object controller) {
        CTRL_TO_ACTIVITY.put(controller, new WeakReference<>(activity));
        Session existing = SESSIONS.get(activity);
        if (existing != null) {
            existing.updateController(controller);
            return existing;
        }
        Session created = new Session(activity, controller);
        SESSIONS.put(activity, created);
        return created;
    }

    private static void attachRowBinding(@NonNull View rowView, @NonNull RowBinding binding) {
        VIEW_BINDINGS.put(rowView, binding);
        tagInteractiveDescendants(rowView, binding, 0);
    }

    private static void clearRowBinding(@NonNull View rowView) {
        RowBinding prev = VIEW_BINDINGS.remove(rowView);
        if (prev != null) {
            clearInteractiveDescendants(rowView, 0);
        }
        Drawable fg = rowView.getForeground();
        if (fg instanceof SelectionForegroundDrawable) {
            rowView.setForeground(((SelectionForegroundDrawable) fg).delegate);
            rowView.invalidate();
        }
    }

    private static void tagInteractiveDescendants(@NonNull View view, @NonNull RowBinding binding, int depth) {
        if (view != binding.rowView && isTouchInterceptTweetView(view)) {
            VIEW_BINDINGS.put(view, binding);
        }
        if (depth >= 4 || !(view instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) view;
        for (int i = 0; i < vg.getChildCount(); i++) {
            tagInteractiveDescendants(vg.getChildAt(i), binding, depth + 1);
        }
    }

    private static void clearInteractiveDescendants(@NonNull View view, int depth) {
        if (depth > 0) {
            VIEW_BINDINGS.remove(view);
        }
        if (depth >= 4 || !(view instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) view;
        for (int i = 0; i < vg.getChildCount(); i++) {
            clearInteractiveDescendants(vg.getChildAt(i), depth + 1);
        }
    }

    private static boolean isTouchInterceptTweetView(@NonNull View view) {
        Class<?> cls = view.getClass();
        while (cls != null && cls != View.class) {
            String name = cls.getName();
            if (name.startsWith("com.twitter.ui.widget.touchintercept.")
                    || "com.twitter.tweetview.core.TweetView".equals(name)
                    || "com.twitter.tweetview.core.LinearLayoutTweetView".equals(name)) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    private static final class RowBinding {
        @NonNull
        final Session session;
        @NonNull
        final View rowView;
        @NonNull
        final ReplyItemData reply;
        float touchDownX;
        float touchDownY;
        long touchDownTime;
        boolean touchCancelled;

        RowBinding(@NonNull Session session, @NonNull View rowView, @NonNull ReplyItemData reply) {
            this.session = session;
            this.rowView = rowView;
            this.reply = reply;
        }
    }

    private static final class AuthorSelection {
        final long userId;
        @NonNull
        String handle;
        @NonNull
        String displayName;
        @Nullable
        Object adContent;
        int replyCount;

        AuthorSelection(@NonNull ReplyItemData item) {
            this.userId = item.authorId;
            this.handle = item.handle;
            this.displayName = item.displayName;
            this.adContent = item.adContent;
            this.replyCount = 1;
        }

        void merge(@NonNull ReplyItemData item) {
            if (handle.isEmpty() && !item.handle.isEmpty()) handle = item.handle;
            if (displayName.isEmpty() && !item.displayName.isEmpty()) displayName = item.displayName;
            if (adContent == null && item.adContent != null) adContent = item.adContent;
            replyCount++;
        }
    }

    // -------------------------------------------------------------------------
    // Per-Activity TweetDetail Multi-Select Session
    // -------------------------------------------------------------------------

    private static final class Session {
        @NonNull
        final WeakReference<Activity> activityRef;
        @NonNull
        WeakReference<Object> controllerRef;
        @Nullable
        WeakReference<Toolbar> toolbarRef;
        @Nullable
        WeakReference<MenuItem> menuItemRef;
        @Nullable
        WeakReference<Object> adapterRef;
        @Nullable
        WeakReference<View> recyclerViewRef;

        final Map<Long, Integer> replyIndicesByTweetId = new HashMap<>();
        final LinkedHashMap<Long, AuthorSelection> loadedAuthors = new LinkedHashMap<>();
        final LinkedHashMap<Long, AuthorSelection> selectedAuthors = new LinkedHashMap<>();
        final Set<Long> sessionMutedIds = new HashSet<>();
        final Set<Long> sessionBlockedIds = new HashSet<>();

        int focalIndex = -1;
        long focalTweetId = -1L;

        boolean multiSelectActive = false;
        boolean batchRunning = false;
        volatile boolean batchCancelled = false;
        boolean suppressingToastForThisSession = false;

        // Docked bar UI
        @Nullable
        View dockedBar;
        @Nullable
        TextView summaryView;
        @Nullable
        TextView selectAllBtn;
        @Nullable
        TextView cancelBtn;
        @Nullable
        View progressRow;
        @Nullable
        ProgressBar progressBar;
        @Nullable
        TextView progressText;
        @Nullable
        TextView stopBtn;
        @Nullable
        TextView muteBtn;
        @Nullable
        TextView blockBtn;

        @Nullable
        SheetBottomDialog.SlideDialog activeDialog;

        int savedRecyclerPaddingBottom = 0;
        boolean hasSavedRecyclerPadding = false;

        // Pending request watchdog
        @Nullable
        Runnable activeTimeoutRunnable;
        @Nullable
        Runnable pendingNextStepRunnable;

        Session(@NonNull Activity activity, @NonNull Object controller) {
            this.activityRef = new WeakReference<>(activity);
            this.controllerRef = new WeakReference<>(controller);
        }

        void updateController(@NonNull Object controller) {
            this.controllerRef = new WeakReference<>(controller);
        }

        void ensureAdapterRef() {
            Object ctrl = controllerRef.get();
            if (ctrl == null) return;
            if (adapterRef == null || adapterRef.get() == null) {
                Object adapter = BatchModerationBridge.getAdapter(ctrl);
                if (adapter != null) {
                    adapterRef = new WeakReference<>(adapter);
                }
            }
            if (recyclerViewRef == null || recyclerViewRef.get() == null) {
                View rv = BatchModerationBridge.getRecyclerView(ctrl);
                if (rv != null) {
                    recyclerViewRef = new WeakReference<>(rv);
                }
            }
        }

        void updateCollectionSnapshot(@NonNull Object collection) {
            Object ctrl = controllerRef.get();
            long selfId = BatchModerationBridge.getCurrentUserId();

            int size = BatchModerationBridge.getCollectionSize(collection);
            int detectedFocal = -1;
            long detectedFocalTweetId = ctrl != null ? BatchModerationBridge.getFocalTweetIdFromController(ctrl) : -1L;

            for (int i = 0; i < size; i++) {
                if (BatchModerationBridge.isFocalItemAt(collection, i)) {
                    detectedFocal = i;
                    Object item = BatchModerationBridge.getCollectionItem(collection, i);
                    ReplyItemData data = BatchModerationBridge.extractReplyItem(item);
                    if (data != null) {
                        detectedFocalTweetId = data.tweetId;
                    }
                    break;
                }
            }

            if (detectedFocal < 0 && detectedFocalTweetId > 0) {
                for (int i = 0; i < size; i++) {
                    Object item = BatchModerationBridge.getCollectionItem(collection, i);
                    ReplyItemData data = BatchModerationBridge.extractReplyItem(item);
                    if (data != null && data.tweetId == detectedFocalTweetId) {
                        detectedFocal = i;
                        break;
                    }
                }
            }

            this.focalIndex = detectedFocal;
            this.focalTweetId = detectedFocalTweetId;
            this.replyIndicesByTweetId.clear();
            this.loadedAuthors.clear();

            for (int i = 0; i < size; i++) {
                if (detectedFocal >= 0 && i <= detectedFocal) continue;
                Object item = BatchModerationBridge.getCollectionItem(collection, i);
                ReplyItemData data = BatchModerationBridge.extractReplyItem(item);
                if (data == null || data.authorId <= 0 || data.authorId == selfId) continue;
                if (detectedFocalTweetId > 0 && data.tweetId == detectedFocalTweetId) continue;

                replyIndicesByTweetId.put(data.tweetId, i);
                AuthorSelection existing = loadedAuthors.get(data.authorId);
                if (existing == null) {
                    loadedAuthors.put(data.authorId, new AuthorSelection(data));
                } else {
                    existing.merge(data);
                    // Update selected entry metadata if already selected
                    AuthorSelection sel = selectedAuthors.get(data.authorId);
                    if (sel != null) {
                        sel.replyCount = existing.replyCount;
                    }
                }
            }

            if (multiSelectActive) {
                MAIN_HANDLER.post(() -> {
                    reconcileVisibleBindings();
                    updateBarState();
                });
            }
        }

        boolean isReplySelectable(@NonNull ReplyItemData reply) {
            if (reply.authorId <= 0) return false;
            long selfId = BatchModerationBridge.getCurrentUserId();
            if (reply.authorId == selfId) return false;
            if (focalTweetId > 0 && reply.tweetId == focalTweetId) return false;
            if (!replyIndicesByTweetId.isEmpty()) {
                return replyIndicesByTweetId.containsKey(reply.tweetId);
            }
            // Snapshot may not have arrived yet; conservatively allow non-focal items
            return focalIndex != 0;
        }

        boolean isAuthorMuted(@NonNull ReplyItemData reply) {
            if (sessionMutedIds.contains(reply.authorId)) return true;
            if ((reply.friendshipFlags & BatchModerationBridge.FLAG_MUTED) != 0) return true;
            Object ctrl = controllerRef.get();
            Object cache = ctrl != null ? BatchModerationBridge.getUserCache(ctrl) : null;
            return BatchModerationBridge.hasUserFlag(cache, BatchModerationBridge.FLAG_MUTED, reply.authorId);
        }

        boolean isAuthorBlocked(@NonNull ReplyItemData reply) {
            if (sessionBlockedIds.contains(reply.authorId)) return true;
            if ((reply.friendshipFlags & BatchModerationBridge.FLAG_BLOCKED) != 0) return true;
            Object ctrl = controllerRef.get();
            Object cache = ctrl != null ? BatchModerationBridge.getUserCache(ctrl) : null;
            return BatchModerationBridge.hasUserFlag(cache, BatchModerationBridge.FLAG_BLOCKED, reply.authorId);
        }

        boolean isAuthorMutedById(long userId) {
            if (sessionMutedIds.contains(userId)) return true;
            Object ctrl = controllerRef.get();
            Object cache = ctrl != null ? BatchModerationBridge.getUserCache(ctrl) : null;
            return BatchModerationBridge.hasUserFlag(cache, BatchModerationBridge.FLAG_MUTED, userId);
        }

        boolean isAuthorBlockedById(long userId) {
            if (sessionBlockedIds.contains(userId)) return true;
            Object ctrl = controllerRef.get();
            Object cache = ctrl != null ? BatchModerationBridge.getUserCache(ctrl) : null;
            return BatchModerationBridge.hasUserFlag(cache, BatchModerationBridge.FLAG_BLOCKED, userId);
        }

        void toggleMultiSelect() {
            if (multiSelectActive) {
                if (batchRunning) {
                    cancelRunningBatch();
                } else {
                    exitMultiSelect();
                }
            } else {
                enterMultiSelect();
            }
        }

        void enterMultiSelect() {
            Activity activity = activityRef.get();
            Object ctrl = controllerRef.get();
            if (activity == null || ctrl == null || activity.isFinishing()) return;

            ensureAdapterRef();
            Object collection = BatchModerationBridge.getControllerItems(ctrl);
            if (collection != null) {
                updateCollectionSnapshot(collection);
            }

            if (loadedAuthors.isEmpty() && countSelectableVisibleChildren() == 0) {
                showToast(activity, str("piko_batch_mod_no_replies"));
                return;
            }

            if (!multiSelectActive) {
                multiSelectActive = true;
                activeSessionCount++;
            }
            showDockedBar(activity);
            refreshAllVisibleRows();
            updateMenuIconState();
            updateBarState();
        }

        void exitMultiSelect() {
            if (!multiSelectActive) return;
            dismissDialog();
            clearTimeoutAndNextStep();
            setSuppressToast(false);

            multiSelectActive = false;
            batchRunning = false;
            batchCancelled = false;
            activeSessionCount = Math.max(0, activeSessionCount - 1);
            selectedAuthors.clear();

            hideDockedBar();
            refreshAllVisibleRows();
            updateMenuIconState();
        }

        void toggleAuthorSelection(@NonNull ReplyItemData item) {
            if (!multiSelectActive || batchRunning) return;
            AuthorSelection existing = selectedAuthors.get(item.authorId);
            if (existing != null) {
                selectedAuthors.remove(item.authorId);
            } else {
                AuthorSelection fromLoaded = loadedAuthors.get(item.authorId);
                if (fromLoaded != null) {
                    selectedAuthors.put(item.authorId, fromLoaded);
                } else {
                    selectedAuthors.put(item.authorId, new AuthorSelection(item));
                }
            }
            refreshAllVisibleRows();
            updateBarState();
        }

        void selectOrClearAll() {
            if (!multiSelectActive || batchRunning) return;
            // Also include any visible bound rows if collection wasn't scanned yet
            Map<Long, AuthorSelection> candidates = buildSelectableCandidateMap();
            if (candidates.isEmpty()) {
                Activity activity = activityRef.get();
                if (activity != null) showToast(activity, str("piko_batch_mod_no_replies"));
                return;
            }

            boolean allSelected = true;
            for (Long uid : candidates.keySet()) {
                if (!selectedAuthors.containsKey(uid)) {
                    allSelected = false;
                    break;
                }
            }

            if (allSelected && !selectedAuthors.isEmpty()) {
                selectedAuthors.clear();
            } else {
                for (Map.Entry<Long, AuthorSelection> entry : candidates.entrySet()) {
                    if (!selectedAuthors.containsKey(entry.getKey())) {
                        selectedAuthors.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            refreshAllVisibleRows();
            updateBarState();
        }

        @NonNull
        private Map<Long, AuthorSelection> buildSelectableCandidateMap() {
            LinkedHashMap<Long, AuthorSelection> map = new LinkedHashMap<>();
            for (AuthorSelection a : loadedAuthors.values()) {
                if (!isAuthorBlockedById(a.userId) && !isAuthorMutedById(a.userId)) {
                    map.put(a.userId, a);
                }
            }
            View rv = recyclerViewRef != null ? recyclerViewRef.get() : null;
            if (rv instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) rv;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    RowBinding rb = VIEW_BINDINGS.get(child);
                    if (rb != null && isReplySelectable(rb.reply)
                            && !isAuthorBlocked(rb.reply) && !isAuthorMuted(rb.reply)) {
                        if (!map.containsKey(rb.reply.authorId)) {
                            map.put(rb.reply.authorId, new AuthorSelection(rb.reply));
                        }
                    }
                }
            }
            return map;
        }

        private int countSelectableVisibleChildren() {
            View rv = recyclerViewRef != null ? recyclerViewRef.get() : null;
            if (!(rv instanceof ViewGroup)) return 0;
            int count = 0;
            ViewGroup vg = (ViewGroup) rv;
            for (int i = 0; i < vg.getChildCount(); i++) {
                RowBinding rb = VIEW_BINDINGS.get(vg.getChildAt(i));
                if (rb != null && isReplySelectable(rb.reply)) count++;
            }
            return count;
        }

        void applyRowVisual(@NonNull View rowView, @NonNull RowBinding binding) {
            Drawable currentFg = rowView.getForeground();
            if (!multiSelectActive || !isReplySelectable(binding.reply)) {
                if (currentFg instanceof SelectionForegroundDrawable) {
                    rowView.setForeground(((SelectionForegroundDrawable) currentFg).delegate);
                    rowView.invalidate();
                }
                return;
            }

            SelectionForegroundDrawable sfd;
            if (currentFg instanceof SelectionForegroundDrawable) {
                sfd = (SelectionForegroundDrawable) currentFg;
            } else {
                sfd = new SelectionForegroundDrawable(rowView.getContext(), currentFg);
                rowView.setForeground(sfd);
            }

            boolean selected = selectedAuthors.containsKey(binding.reply.authorId);
            boolean muted = isAuthorMuted(binding.reply);
            boolean blocked = isAuthorBlocked(binding.reply);
            sfd.updateState(rowView.getContext(), selected, muted, blocked);
            rowView.invalidate();
        }

        void refreshAllVisibleRows() {
            View rv = recyclerViewRef != null ? recyclerViewRef.get() : null;
            if (!(rv instanceof ViewGroup)) {
                Object ctrl = controllerRef.get();
                if (ctrl != null) {
                    rv = BatchModerationBridge.getRecyclerView(ctrl);
                    if (rv != null) recyclerViewRef = new WeakReference<>(rv);
                }
            }
            if (!(rv instanceof ViewGroup)) return;

            ViewGroup vg = (ViewGroup) rv;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                RowBinding rb = VIEW_BINDINGS.get(child);
                if (rb != null) {
                    applyRowVisual(child, rb);
                } else {
                    Drawable fg = child.getForeground();
                    if (fg instanceof SelectionForegroundDrawable) {
                        child.setForeground(((SelectionForegroundDrawable) fg).delegate);
                        child.invalidate();
                    }
                }
            }
        }

        void reconcileVisibleBindings() {
            View rv = recyclerViewRef != null ? recyclerViewRef.get() : null;
            if (!(rv instanceof ViewGroup)) return;
            ViewGroup vg = (ViewGroup) rv;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                RowBinding rb = VIEW_BINDINGS.get(child);
                if (rb != null) {
                    if (!isReplySelectable(rb.reply)) {
                        clearRowBinding(child);
                    } else {
                        applyRowVisual(child, rb);
                    }
                }
            }
        }

        void updateMenuIconState() {
            MenuItem item = menuItemRef != null ? menuItemRef.get() : null;
            Activity activity = activityRef.get();
            if (item == null || activity == null) return;

            Drawable icon = item.getIcon();
            if (icon != null) {
                ThemePalette palette = ThemePalette.fromContext(activity);
                int tintColor = multiSelectActive ? palette.accentColor : palette.primaryTextColor;
                Drawable wrapped = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrapped, tintColor);
                item.setIcon(wrapped);
            }
        }

        // ---------------------------------------------------------------------
        // Docked Bottom Bar UI
        // ---------------------------------------------------------------------

        private void showDockedBar(@NonNull Activity activity) {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;

            if (dockedBar == null) {
                buildDockedBar(activity, content);
            }
            if (dockedBar.getParent() == null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                );
                content.addView(dockedBar, lp);
            }

            dockedBar.setVisibility(View.VISIBLE);
            dockedBar.setAlpha(0f);
            dockedBar.animate().alpha(1f).setDuration(180L).start();

            dockedBar.post(() -> {
                if (!multiSelectActive || dockedBar == null) return;
                applyRecyclerBottomOffset(dockedBar.getHeight());
            });
        }

        private void hideDockedBar() {
            restoreRecyclerBottomOffset();
            if (dockedBar == null) return;
            View bar = dockedBar;
            bar.animate().cancel();
            ViewGroup parent = (ViewGroup) bar.getParent();
            if (parent != null) {
                parent.removeView(bar);
            }
        }

        private void applyRecyclerBottomOffset(int extraPx) {
            View rv = recyclerViewRef != null ? recyclerViewRef.get() : null;
            if (rv == null || extraPx <= 0) return;
            if (!hasSavedRecyclerPadding) {
                savedRecyclerPaddingBottom = rv.getPaddingBottom();
                hasSavedRecyclerPadding = true;
            }
            int targetBottom = savedRecyclerPaddingBottom + extraPx;
            if (rv.getPaddingBottom() != targetBottom) {
                rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(), targetBottom);
            }
        }

        private void restoreRecyclerBottomOffset() {
            View rv = recyclerViewRef != null ? recyclerViewRef.get() : null;
            if (rv != null && hasSavedRecyclerPadding) {
                rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(), savedRecyclerPaddingBottom);
            }
            hasSavedRecyclerPadding = false;
        }

        private void buildDockedBar(@NonNull Activity activity, @NonNull ViewGroup host) {
            ThemePalette p = ThemePalette.fromContext(activity);

            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setClickable(true);
            container.setFocusable(true);
            container.setElevation(dp(activity, 12));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(p.barBgColor);
            container.setBackground(bg);

            // Top hairline divider
            View divider = new View(activity);
            divider.setBackgroundColor(p.borderColor);
            container.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 1))));

            LinearLayout inner = new LinearLayout(activity);
            inner.setOrientation(LinearLayout.VERTICAL);
            int padH = dp(activity, 14);
            int padTop = dp(activity, 10);
            int basePadBottom = dp(activity, 12);
            inner.setPadding(padH, padTop, padH, basePadBottom);

            // Row 1: selection summary pill + select all + cancel
            LinearLayout topRow = new LinearLayout(activity);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            topLp.bottomMargin = dp(activity, 10);
            topRow.setLayoutParams(topLp);

            summaryView = new TextView(activity);
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            summaryView.setTypeface(Typeface.DEFAULT_BOLD);
            summaryView.setTextColor(p.primaryTextColor);
            summaryView.setSingleLine(true);
            summaryView.setEllipsize(TextUtils.TruncateAt.END);
            summaryView.setOnClickListener(v -> {
                if (!batchRunning && !selectedAuthors.isEmpty()) {
                    showReviewSheet();
                }
            });
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            summaryView.setLayoutParams(sumLp);
            topRow.addView(summaryView);

            selectAllBtn = makeHeaderActionText(activity, p.accentColor, dp(activity, 10));
            selectAllBtn.setOnClickListener(v -> selectOrClearAll());
            topRow.addView(selectAllBtn);

            cancelBtn = makeHeaderActionText(activity, p.secondaryTextColor, dp(activity, 6));
            cancelBtn.setText(str("piko_batch_mod_cancel"));
            cancelBtn.setOnClickListener(v -> {
                if (batchRunning) {
                    cancelRunningBatch();
                } else {
                    exitMultiSelect();
                }
            });
            topRow.addView(cancelBtn);

            inner.addView(topRow);

            // Progress strip (hidden unless batchRunning)
            LinearLayout pRow = new LinearLayout(activity);
            pRow.setOrientation(LinearLayout.HORIZONTAL);
            pRow.setGravity(Gravity.CENTER_VERTICAL);
            pRow.setVisibility(View.GONE);
            LinearLayout.LayoutParams pRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pRowLp.bottomMargin = dp(activity, 10);
            pRow.setLayoutParams(pRowLp);

            progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setProgressTintList(ColorStateList.valueOf(p.accentColor));
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(activity, 76), dp(activity, 6));
            pbLp.setMarginEnd(dp(activity, 10));
            progressBar.setLayoutParams(pbLp);
            pRow.addView(progressBar);

            progressText = new TextView(activity);
            progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            progressText.setTextColor(p.secondaryTextColor);
            progressText.setSingleLine(true);
            progressText.setEllipsize(TextUtils.TruncateAt.END);
            progressText.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pRow.addView(progressText);

            stopBtn = makeHeaderActionText(activity, p.dangerColor, dp(activity, 8));
            stopBtn.setText(str("piko_batch_mod_stop"));
            stopBtn.setOnClickListener(v -> cancelRunningBatch());
            pRow.addView(stopBtn);

            progressRow = pRow;
            inner.addView(pRow);

            // Row 2: Mute button + Block button
            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            muteBtn = makePillButton(activity, p.warnBgColor, p.warnTextColor, p.warnBorderColor);
            LinearLayout.LayoutParams muteLp = new LinearLayout.LayoutParams(0, dp(activity, 42), 1f);
            muteLp.setMarginEnd(dp(activity, 8));
            muteBtn.setLayoutParams(muteLp);
            muteBtn.setOnClickListener(v -> {
                if (!batchRunning && !selectedAuthors.isEmpty()) {
                    showConfirmDialog(false);
                }
            });
            btnRow.addView(muteBtn);

            blockBtn = makePillButton(activity, p.dangerColor, Color.WHITE, 0);
            LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(0, dp(activity, 42), 1f);
            blockBtn.setLayoutParams(blockLp);
            blockBtn.setOnClickListener(v -> {
                if (!batchRunning && !selectedAuthors.isEmpty()) {
                    showConfirmDialog(true);
                }
            });
            btnRow.addView(blockBtn);

            inner.addView(btnRow);
            container.addView(inner);

            // Respect system navigation / gesture bar inset cleanly
            container.setOnApplyWindowInsetsListener((v, insets) -> {
                int bottomInset = insets.getSystemWindowInsetBottom();
                inner.setPadding(padH, padTop, padH, basePadBottom + bottomInset);
                v.post(() -> {
                    if (multiSelectActive) applyRecyclerBottomOffset(v.getHeight());
                });
                return insets;
            });
            WindowInsets rootInsets = host.getRootWindowInsets();
            if (rootInsets != null) {
                inner.setPadding(padH, padTop, padH, basePadBottom + rootInsets.getSystemWindowInsetBottom());
            }

            dockedBar = container;
        }

        private void updateBarState() {
            if (dockedBar == null) return;

            int selectedCount = selectedAuthors.size();
            int replyTotal = 0;
            for (AuthorSelection sel : selectedAuthors.values()) {
                replyTotal += Math.max(1, sel.replyCount);
            }

            if (summaryView != null) {
                if (selectedCount == 0) {
                    summaryView.setText(str("piko_batch_mod_hint_empty"));
                } else {
                    summaryView.setText(String.format(
                            Locale.getDefault(),
                            str("piko_batch_mod_selected_summary"),
                            selectedCount,
                            replyTotal
                    ));
                }
            }

            if (selectAllBtn != null) {
                Map<Long, AuthorSelection> candidates = buildSelectableCandidateMap();
                boolean allSelected = !candidates.isEmpty() && selectedAuthors.keySet().containsAll(candidates.keySet());
                selectAllBtn.setText(str(allSelected && selectedCount > 0
                        ? "piko_batch_mod_deselect_all"
                        : "piko_batch_mod_select_all"));
                selectAllBtn.setEnabled(!batchRunning);
                selectAllBtn.setAlpha(batchRunning ? 0.45f : 1f);
            }

            if (muteBtn != null) {
                muteBtn.setText(selectedCount > 0
                        ? String.format(Locale.getDefault(), str("piko_batch_mod_mute_btn"), selectedCount)
                        : str("piko_batch_mod_mute_idle"));
                boolean enabled = selectedCount > 0 && !batchRunning;
                muteBtn.setEnabled(enabled);
                muteBtn.setAlpha(enabled ? 1f : 0.45f);
            }

            if (blockBtn != null) {
                blockBtn.setText(selectedCount > 0
                        ? String.format(Locale.getDefault(), str("piko_batch_mod_block_btn"), selectedCount)
                        : str("piko_batch_mod_block_idle"));
                boolean enabled = selectedCount > 0 && !batchRunning;
                blockBtn.setEnabled(enabled);
                blockBtn.setAlpha(enabled ? 1f : 0.45f);
            }

            if (progressRow != null) {
                progressRow.setVisibility(batchRunning ? View.VISIBLE : View.GONE);
            }
        }

        // ---------------------------------------------------------------------
        // Selected Users Review Sheet & Confirmation Dialog
        // ---------------------------------------------------------------------

        private void showReviewSheet() {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || selectedAuthors.isEmpty()) return;
            dismissDialog();

            ThemePalette p = ThemePalette.fromContext(activity);
            SheetBottomDialog.DraggableLinearLayout main = SheetBottomDialog.createMainLayout(activity, p.sheetBgColor);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 16);
            content.setPadding(pad, dp(activity, 10), pad, dp(activity, 18));

            TextView title = new TextView(activity);
            title.setText(String.format(Locale.getDefault(), str("piko_batch_mod_review_title"), selectedAuthors.size()));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(p.primaryTextColor);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.bottomMargin = dp(activity, 12);
            title.setLayoutParams(tLp);
            content.addView(title);

            ScrollView scroll = new ScrollView(activity);
            int maxListHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.45f);
            LinearLayout listLayout = new LinearLayout(activity);
            listLayout.setOrientation(LinearLayout.VERTICAL);

            List<AuthorSelection> snapshot = new ArrayList<>(selectedAuthors.values());
            for (AuthorSelection user : snapshot) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 10), dp(activity, 10));

                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(p.surfaceColor);
                rowBg.setCornerRadius(dp(activity, 10));
                row.setBackground(rowBg);
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.bottomMargin = dp(activity, 8);
                row.setLayoutParams(rLp);

                LinearLayout textCol = new LinearLayout(activity);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView nameView = new TextView(activity);
                String name = user.displayName.isEmpty() ? ("@" + user.handle) : user.displayName;
                nameView.setText(name);
                nameView.setTypeface(Typeface.DEFAULT_BOLD);
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                nameView.setTextColor(p.primaryTextColor);
                nameView.setSingleLine(true);
                nameView.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(nameView);

                TextView subView = new TextView(activity);
                String replyText = String.format(Locale.getDefault(), str("piko_batch_mod_reply_count"), Math.max(1, user.replyCount));
                subView.setText(user.handle.isEmpty() ? replyText : ("@" + user.handle + " · " + replyText));
                subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                subView.setTextColor(p.secondaryTextColor);
                subView.setSingleLine(true);
                subView.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(subView);

                row.addView(textCol);

                TextView removeBtn = makeHeaderActionText(activity, p.dangerColor, dp(activity, 8));
                removeBtn.setText("✕");
                removeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                removeBtn.setOnClickListener(v -> {
                    selectedAuthors.remove(user.userId);
                    listLayout.removeView(row);
                    title.setText(String.format(
                            Locale.getDefault(),
                            str("piko_batch_mod_review_title"),
                            selectedAuthors.size()
                    ));
                    refreshAllVisibleRows();
                    updateBarState();
                    if (selectedAuthors.isEmpty()) {
                        dismissDialog();
                    }
                });
                row.addView(removeBtn);
                listLayout.addView(row);
            }

            scroll.addView(listLayout);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scroll.setLayoutParams(scrollLp);
            // Measure to cap height without clipping short lists
            listLayout.measure(
                    View.MeasureSpec.makeMeasureSpec(activity.getResources().getDisplayMetrics().widthPixels - pad * 2, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            if (listLayout.getMeasuredHeight() > maxListHeight) {
                scrollLp.height = maxListHeight;
            }
            content.addView(scroll);

            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams bRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bRowLp.topMargin = dp(activity, 12);
            btnRow.setLayoutParams(bRowLp);

            TextView sheetMute = makePillButton(activity, p.warnBgColor, p.warnTextColor, p.warnBorderColor);
            sheetMute.setText(String.format(Locale.getDefault(), str("piko_batch_mod_mute_btn"), selectedAuthors.size()));
            LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(0, dp(activity, 42), 1f);
            smLp.setMarginEnd(dp(activity, 8));
            sheetMute.setLayoutParams(smLp);
            sheetMute.setOnClickListener(v -> {
                dismissDialog();
                showConfirmDialog(false);
            });
            btnRow.addView(sheetMute);

            TextView sheetBlock = makePillButton(activity, p.dangerColor, Color.WHITE, 0);
            sheetBlock.setText(String.format(Locale.getDefault(), str("piko_batch_mod_block_btn"), selectedAuthors.size()));
            sheetBlock.setLayoutParams(new LinearLayout.LayoutParams(0, dp(activity, 42), 1f));
            sheetBlock.setOnClickListener(v -> {
                dismissDialog();
                showConfirmDialog(true);
            });
            btnRow.addView(sheetBlock);

            content.addView(btnRow);
            main.addView(content);

            activeDialog = SheetBottomDialog.createSlideDialog(activity, main, 220);
            activeDialog.show();
        }

        private void showConfirmDialog(boolean isBlock) {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || selectedAuthors.isEmpty()) return;
            dismissDialog();

            int count = selectedAuthors.size();
            ThemePalette p = ThemePalette.fromContext(activity);
            SheetBottomDialog.DraggableLinearLayout main = SheetBottomDialog.createMainLayout(activity, p.sheetBgColor);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 18);
            content.setPadding(pad, dp(activity, 12), pad, dp(activity, 18));

            TextView title = new TextView(activity);
            title.setText(String.format(
                    Locale.getDefault(),
                    str(isBlock ? "piko_batch_mod_confirm_block_title" : "piko_batch_mod_confirm_mute_title"),
                    count
            ));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(p.primaryTextColor);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.bottomMargin = dp(activity, 8);
            title.setLayoutParams(tLp);
            content.addView(title);

            TextView desc = new TextView(activity);
            desc.setText(str(isBlock ? "piko_batch_mod_confirm_block_desc" : "piko_batch_mod_confirm_mute_desc"));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            desc.setTextColor(p.secondaryTextColor);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.bottomMargin = dp(activity, 12);
            desc.setLayoutParams(dLp);
            content.addView(desc);

            // Preview up to 6 handles
            StringBuilder preview = new StringBuilder();
            int shown = 0;
            for (AuthorSelection user : selectedAuthors.values()) {
                if (shown >= 6) break;
                if (shown > 0) preview.append(" · ");
                preview.append("@").append(user.handle.isEmpty() ? String.valueOf(user.userId) : user.handle);
                shown++;
            }
            if (count > 6) {
                preview.append(" ").append(String.format(
                        Locale.getDefault(), str("piko_batch_mod_and_more"), count - 6));
            }
            TextView previewBox = new TextView(activity);
            previewBox.setText(preview.toString());
            previewBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            previewBox.setTextColor(p.primaryTextColor);
            previewBox.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            GradientDrawable pBg = new GradientDrawable();
            pBg.setColor(p.surfaceColor);
            pBg.setCornerRadius(dp(activity, 10));
            previewBox.setBackground(pBg);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.bottomMargin = dp(activity, 16);
            previewBox.setLayoutParams(pLp);
            content.addView(previewBox);

            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView cancel = makePillButton(activity, p.surfaceColor, p.primaryTextColor, p.borderColor);
            cancel.setText(str("piko_batch_mod_cancel"));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(activity, 42), 1f);
            cLp.setMarginEnd(dp(activity, 8));
            cancel.setLayoutParams(cLp);
            cancel.setOnClickListener(v -> dismissDialog());
            btnRow.addView(cancel);

            TextView confirm = isBlock
                    ? makePillButton(activity, p.dangerColor, Color.WHITE, 0)
                    : makePillButton(activity, p.accentColor, Color.WHITE, 0);
            confirm.setText(String.format(
                    Locale.getDefault(),
                    str(isBlock ? "piko_batch_mod_block_btn" : "piko_batch_mod_mute_btn"),
                    count
            ));
            confirm.setLayoutParams(new LinearLayout.LayoutParams(0, dp(activity, 42), 1f));
            confirm.setOnClickListener(v -> {
                dismissDialog();
                startBatch(isBlock);
            });
            btnRow.addView(confirm);

            content.addView(btnRow);
            main.addView(content);

            activeDialog = SheetBottomDialog.createSlideDialog(activity, main, 220);
            activeDialog.show();
        }

        // ---------------------------------------------------------------------
        // Sequential Batch Request Runner
        // ---------------------------------------------------------------------

        private void startBatch(boolean isBlock) {
            if (batchRunning || selectedAuthors.isEmpty()) return;
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;

            List<AuthorSelection> queue = new ArrayList<>(selectedAuthors.values());
            batchRunning = true;
            batchCancelled = false;
            setSuppressToast(isBlock);

            if (progressBar != null) {
                progressBar.setMax(queue.size());
                progressBar.setProgress(0);
            }
            updateBarState();

            executeNextInQueue(queue, 0, isBlock, 0, 0);
        }

        private void executeNextInQueue(
                @NonNull List<AuthorSelection> queue,
                int index,
                boolean isBlock,
                int succeeded,
                int failed
        ) {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                finishBatch(isBlock, succeeded, failed, queue.size(), true);
                return;
            }
            if (batchCancelled || index >= queue.size()) {
                finishBatch(isBlock, succeeded, failed, queue.size(), batchCancelled);
                return;
            }

            AuthorSelection target = queue.get(index);
            if (progressBar != null) {
                progressBar.setProgress(index);
            }
            if (progressText != null) {
                String label = target.handle.isEmpty() ? String.valueOf(target.userId) : target.handle;
                progressText.setText(String.format(
                        Locale.getDefault(),
                        str(isBlock ? "piko_batch_mod_progress_block" : "piko_batch_mod_progress_mute"),
                        label,
                        index + 1,
                        queue.size()
                ));
            }

            final boolean[] completedOnce = {false};
            final Runnable timeoutTask = () -> {
                if (completedOnce[0]) return;
                completedOnce[0] = true;
                activeTimeoutRunnable = null;
                onSingleResult(queue, index, isBlock, target, false, succeeded, failed);
            };
            activeTimeoutRunnable = timeoutTask;
            MAIN_HANDLER.postDelayed(timeoutTask, REQUEST_TIMEOUT_MS);

            BatchModerationBridge.dispatchBlockOrMute(
                    activity,
                    target.userId,
                    target.adContent,
                    isBlock,
                    success -> MAIN_HANDLER.post(() -> {
                        if (completedOnce[0]) return;
                        completedOnce[0] = true;
                        if (activeTimeoutRunnable != null) {
                            MAIN_HANDLER.removeCallbacks(activeTimeoutRunnable);
                            activeTimeoutRunnable = null;
                        }
                        onSingleResult(queue, index, isBlock, target, success, succeeded, failed);
                    })
            );
        }

        private void onSingleResult(
                @NonNull List<AuthorSelection> queue,
                int index,
                boolean isBlock,
                @NonNull AuthorSelection target,
                boolean success,
                int prevSucceeded,
                int prevFailed
        ) {
            if (!multiSelectActive) {
                finishBatch(isBlock, prevSucceeded + (success ? 1 : 0), prevFailed + (success ? 0 : 1), queue.size(), true);
                return;
            }

            int nextSucceeded = prevSucceeded + (success ? 1 : 0);
            int nextFailed = prevFailed + (success ? 0 : 1);

            if (success) {
                selectedAuthors.remove(target.userId);
                if (isBlock) {
                    sessionBlockedIds.add(target.userId);
                } else {
                    sessionMutedIds.add(target.userId);
                }
                Object ctrl = controllerRef.get();
                Object cache = ctrl != null ? BatchModerationBridge.getUserCache(ctrl) : null;
                BatchModerationBridge.addUserFlag(
                        cache,
                        isBlock ? BatchModerationBridge.FLAG_BLOCKED : BatchModerationBridge.FLAG_MUTED,
                        target.userId
                );
                refreshAllVisibleRows();
                updateBarState();
            }

            if (progressBar != null) {
                progressBar.setProgress(index + 1);
            }

            if (batchCancelled || index + 1 >= queue.size()) {
                finishBatch(isBlock, nextSucceeded, nextFailed, queue.size(), batchCancelled);
                return;
            }

            Runnable next = () -> {
                pendingNextStepRunnable = null;
                executeNextInQueue(queue, index + 1, isBlock, nextSucceeded, nextFailed);
            };
            pendingNextStepRunnable = next;
            MAIN_HANDLER.postDelayed(next, BATCH_STEP_DELAY_MS);
        }

        private void cancelRunningBatch() {
            if (!batchRunning) return;
            batchCancelled = true;
        }

        private void finishBatch(boolean isBlock, int succeeded, int failed, int total, boolean cancelled) {
            clearTimeoutAndNextStep();
            setSuppressToast(false);
            batchRunning = false;
            batchCancelled = false;

            Object adapter = adapterRef != null ? adapterRef.get() : null;
            if (adapter != null) {
                BatchModerationBridge.notifyAdapterChanged(adapter);
            }

            Activity activity = activityRef.get();
            if (activity != null && !activity.isFinishing()) {
                String msg;
                if (cancelled) {
                    msg = String.format(Locale.getDefault(), str("piko_batch_mod_cancelled"), succeeded + failed, total);
                } else if (failed == 0) {
                    msg = String.format(
                            Locale.getDefault(),
                            str(isBlock ? "piko_batch_mod_done_block" : "piko_batch_mod_done_mute"),
                            succeeded
                    );
                } else {
                    msg = String.format(Locale.getDefault(), str("piko_batch_mod_done_partial"), succeeded, failed);
                }
                showToast(activity, msg);
            }

            if (selectedAuthors.isEmpty() && !cancelled) {
                exitMultiSelect();
            } else {
                refreshAllVisibleRows();
                updateBarState();
            }
        }

        private void setSuppressToast(boolean suppress) {
            if (suppress && !suppressingToastForThisSession) {
                suppressingToastForThisSession = true;
                suppressBlockToastCount++;
            } else if (!suppress && suppressingToastForThisSession) {
                suppressingToastForThisSession = false;
                suppressBlockToastCount = Math.max(0, suppressBlockToastCount - 1);
            }
        }

        private void clearTimeoutAndNextStep() {
            if (activeTimeoutRunnable != null) {
                MAIN_HANDLER.removeCallbacks(activeTimeoutRunnable);
                activeTimeoutRunnable = null;
            }
            if (pendingNextStepRunnable != null) {
                MAIN_HANDLER.removeCallbacks(pendingNextStepRunnable);
                pendingNextStepRunnable = null;
            }
        }

        private void dismissDialog() {
            if (activeDialog != null) {
                try {
                    if (activeDialog.isShowing()) {
                        activeDialog.dismiss();
                    }
                } catch (Throwable ignored) {
                }
                activeDialog = null;
            }
        }

        void destroy() {
            clearTimeoutAndNextStep();
            setSuppressToast(false);
            dismissDialog();
            if (multiSelectActive) {
                multiSelectActive = false;
                activeSessionCount = Math.max(0, activeSessionCount - 1);
            }
            batchRunning = false;
            selectedAuthors.clear();
            hideDockedBar();
        }
    }

    // -------------------------------------------------------------------------
    // Row Overlay Foreground Drawable (Selection Checkmark & Highlight)
    // -------------------------------------------------------------------------

    private static final class SelectionForegroundDrawable extends Drawable {
        @Nullable
        final Drawable delegate;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private final RectF badgeRect = new RectF();

        private boolean selected;
        private boolean muted;
        private boolean blocked;
        private boolean isRtl;
        private float density;
        private int accentColor = 0xFF1D9BF0;
        private int surfaceHaloColor = Color.WHITE;
        private int outlineColor = 0x88536471;

        SelectionForegroundDrawable(@NonNull Context context, @Nullable Drawable delegate) {
            this.delegate = delegate;
            this.density = context.getResources().getDisplayMetrics().density;
            this.isRtl = context.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.8f * density);

            haloPaint.setStyle(Paint.Style.STROKE);
            haloPaint.setStrokeWidth(2.0f * density);

            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setColor(Color.WHITE);
            checkPaint.setStrokeWidth(2.1f * density);
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);

            badgeTextPaint.setTextSize(10.5f * density);
            badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        void updateState(@NonNull Context context, boolean selected, boolean muted, boolean blocked) {
            this.selected = selected;
            this.muted = muted;
            this.blocked = blocked;
            this.isRtl = context.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

            ThemePalette p = ThemePalette.fromContext(context);
            this.accentColor = p.accentColor;
            this.surfaceHaloColor = p.dark ? 0xFF000000 : 0xFFFFFFFF;
            this.outlineColor = p.dark ? 0x9971767B : 0x99536471;
            this.fillPaint.setColor(Color.argb(p.dark ? 32 : 20, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            this.accentPaint.setColor(accentColor);
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (delegate != null) {
                delegate.setBounds(getBounds());
                delegate.draw(canvas);
            }

            Rect b = getBounds();
            if (b.isEmpty()) return;

            if (selected) {
                canvas.drawRect(b, fillPaint);
                float barWidth = 3.5f * density;
                if (isRtl) {
                    canvas.drawRect(b.right - barWidth, b.top, b.right, b.bottom, accentPaint);
                } else {
                    canvas.drawRect(b.left, b.top, b.left + barWidth, b.bottom, accentPaint);
                }
            }

            float radius = 10f * density;
            float marginEnd = 16f * density;
            float cx = isRtl ? (b.left + marginEnd + radius) : (b.right - marginEnd - radius);
            float height = b.height();
            float cy = height >= 84f * density ? (b.top + 48f * density) : (b.top + height * 0.65f);

            // Soft backdrop halo for readability over media
            haloPaint.setColor(surfaceHaloColor);
            canvas.drawCircle(cx, cy, radius + density, haloPaint);

            if (selected) {
                canvas.drawCircle(cx, cy, radius, accentPaint);
                float r = radius;
                checkPath.reset();
                checkPath.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                checkPath.lineTo(cx - r * 0.12f, cy + r * 0.34f);
                checkPath.lineTo(cx + r * 0.44f, cy - r * 0.32f);
                canvas.drawPath(checkPath, checkPaint);
            } else {
                strokePaint.setColor(outlineColor);
                canvas.drawCircle(cx, cy, radius, strokePaint);
            }

            // Status pill ("Muted" or "Blocked") if processed in current session or already flagged
            if (blocked || muted) {
                String label = str(blocked ? "piko_batch_mod_tag_blocked" : "piko_batch_mod_tag_muted");
                float textW = badgeTextPaint.measureText(label);
                float pillW = textW + 12f * density;
                float pillH = 18f * density;
                float gap = 8f * density;

                float pillLeft = isRtl ? (cx + radius + gap) : (cx - radius - gap - pillW);
                float pillTop = cy - pillH / 2f;
                badgeRect.set(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH);

                if (blocked) {
                    badgeBgPaint.setColor(0xE6F4212E);
                    badgeTextPaint.setColor(Color.WHITE);
                } else {
                    badgeBgPaint.setColor(0xE6FF9F0A);
                    badgeTextPaint.setColor(0xFF0F1419);
                }
                canvas.drawRoundRect(badgeRect, pillH / 2f, pillH / 2f, badgeBgPaint);

                Paint.FontMetrics fm = badgeTextPaint.getFontMetrics();
                float textY = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(label, badgeRect.centerX(), textY, badgeTextPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            if (delegate != null) delegate.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            if (delegate != null) delegate.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // -------------------------------------------------------------------------
    // UI Styling Helpers
    // -------------------------------------------------------------------------

    private static final class ThemePalette {
        final boolean dark;
        final int sheetBgColor;
        final int barBgColor;
        final int primaryTextColor;
        final int secondaryTextColor;
        final int surfaceColor;
        final int borderColor;
        final int accentColor;
        final int dangerColor;
        final int warnBgColor;
        final int warnTextColor;
        final int warnBorderColor;

        private ThemePalette(
                boolean dark,
                int sheetBgColor,
                int barBgColor,
                int primaryTextColor,
                int secondaryTextColor,
                int surfaceColor,
                int borderColor,
                int accentColor
        ) {
            this.dark = dark;
            this.sheetBgColor = sheetBgColor;
            this.barBgColor = barBgColor;
            this.primaryTextColor = primaryTextColor;
            this.secondaryTextColor = secondaryTextColor;
            this.surfaceColor = surfaceColor;
            this.borderColor = borderColor;
            this.accentColor = accentColor;
            this.dangerColor = 0xFFF4212E;
            this.warnBgColor = dark ? 0x30FFAD1F : 0x20FF9500;
            this.warnTextColor = dark ? 0xFFFFB84D : 0xFFB25900;
            this.warnBorderColor = dark ? 0x66FFAD1F : 0x55FF9500;
        }

        @NonNull
        static ThemePalette fromContext(@NonNull Context context) {
            int bg = resolveColorSafe(context, "coreColorAppBackground", 0xFFFFFFFF);
            int primary = resolveColorSafe(context, "textColorPrimary", 0xFF0F1419);
            int secondary = resolveColorSafe(context, "textColorSecondary", 0xFF536471);

            int accent = 0xFF1D9BF0;
            try {
                int resolved = ResourceUtils.getColor("twitter_blue_fill_pressed");
                if (resolved != 0) accent = resolved;
            } catch (Throwable ignored) {
            }

            double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
            boolean dark = lum < 0.5;

            int barBg;
            if (!dark) {
                barBg = 0xFFFFFFFF;
            } else if (Color.red(bg) < 12 && Color.green(bg) < 12 && Color.blue(bg) < 12) {
                // Lights-out pure black -> subtle lifted surface
                barBg = 0xFF16181C;
            } else {
                // Dim navy -> subtle lifted surface
                barBg = 0xFF1E2732;
            }

            int surface = dark ? Color.argb(34, 255, 255, 255) : Color.argb(14, 0, 0, 0);
            int border = dark ? Color.argb(56, 255, 255, 255) : Color.argb(32, 0, 0, 0);
            return new ThemePalette(dark, bg, barBg, primary, secondary, surface, border, accent);
        }

        private static int resolveColorSafe(@NonNull Context context, @NonNull String attr, int fallback) {
            try {
                return Utils.resolveColor(context, attr);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    @NonNull
    private static TextView makeHeaderActionText(@NonNull Context context, int textColor, int padH) {
        TextView tv = new TextView(context);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(padH, dp(context, 4), padH, dp(context, 4));
        return tv;
    }

    @NonNull
    private static TextView makePillButton(@NonNull Context context, int bgColor, int textColor, int borderColor) {
        TextView tv = new TextView(context);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(context, 21));
        if (borderColor != 0) {
            gd.setStroke(dp(context, 1), borderColor);
        }
        tv.setBackground(gd);
        return tv;
    }

    private static void showToast(@NonNull Context context, @NonNull String msg) {
        MAIN_HANDLER.post(() -> {
            try {
                Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                PikoUtils.toast(msg);
            }
        });
    }

    private static int dp(@NonNull Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
