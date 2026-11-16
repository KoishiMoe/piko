/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.timeline.history;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.ActivityHook;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Searchable local timeline browsing history screen hosted inside AuthorizeAppActivity.
 */
@SuppressWarnings("deprecation")
public class TimelineHistoryFragment extends Fragment {

    private static final long SEARCH_DEBOUNCE_MS = 140L;
    private static final ExecutorService QUERY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "piko-history-ui");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger queryGeneration = new AtomicInteger(0);

    private ThemePalette palette;
    private HistoryAdapter adapter;
    private ListView listView;
    private TextView emptyView;
    private TextView countView;
    private EditText searchInput;
    private ImageButton clearSearchBtn;

    private TextView chipAll;
    private TextView chipMedia;
    private TextView chipQuotes;

    private int activeFilter = TimelineHistoryDatabase.FILTER_ALL;
    private String currentQuery = "";
    private Runnable pendingSearchRunnable;

    @Override
    public void onResume() {
        super.onResume();
        if (ActivityHook.toolbar != null) {
            ActivityHook.toolbar.setTitle(str("piko_title_timeline_history"));
        }
        reloadHistory(true);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        Context context = getActivity();
        if (context == null) {
            context = getContext();
        }
        palette = ThemePalette.fromContext(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bgColor);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildHeaderControls(context));

        View headerDivider = new View(context);
        headerDivider.setBackgroundColor(palette.borderColor);
        headerDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1) / 2)));
        root.addView(headerDivider);

        FrameLayout listContainer = new FrameLayout(context);
        listContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new ListView(context);
        listView.setDivider(new ColorDrawable(palette.borderColor));
        listView.setDividerHeight(Math.max(1, dp(context, 1) / 2));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setFooterDividersEnabled(false);

        emptyView = new TextView(context);
        emptyView.setText(str("piko_history_empty"));
        emptyView.setTextColor(palette.secondaryTextColor);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptyView.setGravity(Gravity.CENTER);
        int emptyPad = dp(context, 32);
        emptyView.setPadding(emptyPad, emptyPad, emptyPad, emptyPad);
        emptyView.setVisibility(View.GONE);

        listContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        adapter = new HistoryAdapter(context);
        listView.setAdapter(adapter);
        root.addView(listContainer);

        TimelineHistoryManager.flushVisibleNow();
        reloadHistory(false);

        return root;
    }

    @Override
    public void onDestroyView() {
        if (pendingSearchRunnable != null) {
            mainHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        super.onDestroyView();
    }

    private View buildHeaderControls(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 14);
        header.setPadding(padH, dp(context, 10), padH, dp(context, 10));

        // Search input row
        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(palette.surfaceColor);
        searchBg.setCornerRadius(dp(context, 20));
        searchBg.setStroke(dp(context, 1), palette.borderColor);
        searchRow.setBackground(searchBg);
        searchRow.setPadding(dp(context, 12), dp(context, 4), dp(context, 6), dp(context, 4));

        searchInput = new EditText(context);
        searchInput.setBackground(null);
        searchInput.setHint(str("piko_history_search_hint"));
        searchInput.setHintTextColor(palette.hintColor);
        searchInput.setTextColor(palette.primaryTextColor);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6));
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clearSearchBtn = new ImageButton(context);
        clearSearchBtn.setBackground(null);
        clearSearchBtn.setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6));
        clearSearchBtn.setContentDescription(str("piko_history_clear_search"));
        int closeRes = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "ic_vector_toolbar_close");
        if (closeRes != 0) {
            clearSearchBtn.setImageResource(closeRes);
            clearSearchBtn.setColorFilter(palette.secondaryTextColor);
        }
        clearSearchBtn.setVisibility(View.GONE);
        clearSearchBtn.setOnClickListener(v -> {
            if (searchInput != null) {
                searchInput.setText("");
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                currentQuery = s == null ? "" : s.toString();
                if (clearSearchBtn != null) {
                    clearSearchBtn.setVisibility(currentQuery.isEmpty() ? View.GONE : View.VISIBLE);
                }
                scheduleDebouncedSearch();
            }
        });

        searchRow.addView(searchInput);
        searchRow.addView(clearSearchBtn);
        header.addView(searchRow);

        // Filter chips + count + clear all row
        LinearLayout barRow = new LinearLayout(context);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(context, 10);
        barRow.setLayoutParams(barParams);

        chipAll = createFilterChip(context, str("piko_history_filter_all"), TimelineHistoryDatabase.FILTER_ALL);
        chipMedia = createFilterChip(context, str("piko_history_filter_media"), TimelineHistoryDatabase.FILTER_MEDIA);
        chipQuotes = createFilterChip(context, str("piko_history_filter_quotes"), TimelineHistoryDatabase.FILTER_QUOTES);

        barRow.addView(chipAll);
        barRow.addView(chipMedia);
        barRow.addView(chipQuotes);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        barRow.addView(spacer);

        countView = new TextView(context);
        countView.setTextColor(palette.secondaryTextColor);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        countView.setPadding(0, 0, dp(context, 10), 0);
        barRow.addView(countView);

        TextView clearAllBtn = new TextView(context);
        clearAllBtn.setText(str("piko_history_clear_btn"));
        clearAllBtn.setTextColor(0xFFE0245E);
        clearAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clearAllBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearAllBtn.setPadding(dp(context, 8), dp(context, 4), dp(context, 6), dp(context, 4));
        clearAllBtn.setOnClickListener(v -> confirmClearAll(context));
        barRow.addView(clearAllBtn);

        header.addView(barRow);
        updateChipStyles();
        return header;
    }

    private TextView createFilterChip(Context context, String label, int filterValue) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(context, 11), dp(context, 5), dp(context, 11), dp(context, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(context, 6));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            if (activeFilter != filterValue) {
                activeFilter = filterValue;
                updateChipStyles();
                reloadHistory(false);
            }
        });
        return chip;
    }

    private void updateChipStyles() {
        applyChipStyle(chipAll, activeFilter == TimelineHistoryDatabase.FILTER_ALL);
        applyChipStyle(chipMedia, activeFilter == TimelineHistoryDatabase.FILTER_MEDIA);
        applyChipStyle(chipQuotes, activeFilter == TimelineHistoryDatabase.FILTER_QUOTES);
    }

    private void applyChipStyle(@Nullable TextView chip, boolean selected) {
        if (chip == null) {
            return;
        }
        Context context = chip.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 16));
        if (selected) {
            bg.setColor(palette.chipSelectedBg);
            bg.setStroke(dp(context, 1), palette.accentColor);
            chip.setTextColor(palette.accentColor);
        } else {
            bg.setColor(palette.surfaceColor);
            bg.setStroke(dp(context, 1), palette.borderColor);
            chip.setTextColor(palette.secondaryTextColor);
        }
        chip.setBackground(bg);
    }

    private void scheduleDebouncedSearch() {
        if (pendingSearchRunnable != null) {
            mainHandler.removeCallbacks(pendingSearchRunnable);
        }
        pendingSearchRunnable = () -> reloadHistory(false);
        mainHandler.postDelayed(pendingSearchRunnable, SEARCH_DEBOUNCE_MS);
    }

    private void reloadHistory(boolean flushFirst) {
        if (flushFirst) {
            TimelineHistoryManager.flushVisibleNow();
        }
        final int gen = queryGeneration.incrementAndGet();
        final String querySnapshot = currentQuery;
        final int filterSnapshot = activeFilter;

        QUERY_EXECUTOR.submit(() -> {
            List<TimelineHistoryEntry> entries =
                    TimelineHistoryManager.queryHistory(querySnapshot, filterSnapshot);
            int totalCount = TimelineHistoryManager.getHistoryCount();
            int limit = TimelineHistoryManager.getHistoryLimit();

            mainHandler.post(() -> {
                if (!isAdded() || gen != queryGeneration.get()) {
                    return;
                }
                adapter.setItems(entries);
                countView.setText(String.format(Locale.US, "%d/%d", totalCount, limit));
                boolean isEmpty = entries.isEmpty();
                emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                listView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void confirmClearAll(Context context) {
        TimelineHistoryManager.showClearConfirmDialog(context, () -> reloadHistory(false));
    }

    private void showEntryActions(@NonNull Context context, @NonNull TimelineHistoryEntry entry) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(str("piko_history_action_open"));
        actions.add(() -> TimelineHistoryManager.openTweetInApp(context, entry));

        if (SettingsStatus.nativeReaderMode) {
            labels.add(str("piko_history_action_reader_mode"));
            actions.add(() -> ActivityHook.startReaderMode(String.valueOf(entry.tweetId)));
        }

        if (!entry.authorHandle.isEmpty()) {
            labels.add(str("piko_history_action_open_author", entry.authorHandle));
            actions.add(() -> TimelineHistoryManager.openAuthorInApp(context, entry));
        }

        labels.add(str("piko_history_action_copy_link"));
        actions.add(() -> {
            app.morphe.extension.shared.Utils.setClipboard(entry.buildTweetUrl());
            PikoUtils.toast(str("copied_to_clipboard"));
        });

        if (!entry.text.isEmpty()) {
            labels.add(str("piko_history_action_copy_text"));
            actions.add(() -> {
                app.morphe.extension.shared.Utils.setClipboard(entry.text);
                PikoUtils.toast(str("copied_to_clipboard"));
            });
        }

        labels.add(str("piko_history_action_delete"));
        actions.add(() -> QUERY_EXECUTOR.submit(() -> {
            TimelineHistoryManager.deleteEntry(entry.tweetId);
            mainHandler.post(() -> reloadHistory(false));
        }));

        String title = entry.authorHandle.isEmpty()
                ? str("piko_title_timeline_history")
                : "@" + entry.authorHandle;

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private final class HistoryAdapter extends BaseAdapter {
        private final Context context;
        private final List<TimelineHistoryEntry> items = new ArrayList<>();
        private final SimpleDateFormat olderFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        HistoryAdapter(Context context) {
            this.context = context;
        }

        void setItems(List<TimelineHistoryEntry> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public TimelineHistoryEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).tweetId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                holder = createRow(context);
                convertView = holder.root;
                convertView.setTag(holder);
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            TimelineHistoryEntry entry = items.get(position);
            bindRow(holder, entry);
            return convertView;
        }

        private RowHolder createRow(Context ctx) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            int padH = dp(ctx, 14);
            int padV = dp(ctx, 11);
            row.setPadding(padH, padV, padH, padV);

            LinearLayout topLine = new LinearLayout(ctx);
            topLine.setOrientation(LinearLayout.HORIZONTAL);
            topLine.setGravity(Gravity.CENTER_VERTICAL);

            TextView authorView = new TextView(ctx);
            authorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            authorView.setTypeface(Typeface.DEFAULT_BOLD);
            authorView.setTextColor(palette.primaryTextColor);
            authorView.setSingleLine(true);
            authorView.setEllipsize(TextUtils.TruncateAt.END);
            topLine.addView(authorView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView handleView = new TextView(ctx);
            handleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            handleView.setTextColor(palette.secondaryTextColor);
            handleView.setSingleLine(true);
            handleView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            handleLp.setMarginStart(dp(ctx, 5));
            topLine.addView(handleView, handleLp);

            TextView badgeView = new TextView(ctx);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badgeView.setTextColor(palette.accentColor);
            badgeView.setPadding(dp(ctx, 6), 0, dp(ctx, 4), 0);
            topLine.addView(badgeView);

            TextView timeView = new TextView(ctx);
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            timeView.setTextColor(palette.secondaryTextColor);
            timeView.setPadding(dp(ctx, 4), 0, dp(ctx, 2), 0);
            topLine.addView(timeView);

            TextView moreView = new TextView(ctx);
            moreView.setText("⋮");
            moreView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            moreView.setTextColor(palette.secondaryTextColor);
            moreView.setGravity(Gravity.CENTER);
            moreView.setPadding(dp(ctx, 8), 0, dp(ctx, 4), 0);
            topLine.addView(moreView);

            TextView bodyView = new TextView(ctx);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            bodyView.setMaxLines(4);
            bodyView.setEllipsize(TextUtils.TruncateAt.END);
            bodyView.setLineSpacing(dp(ctx, 2), 1.0f);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = dp(ctx, 4);
            row.addView(topLine);
            row.addView(bodyView, bodyLp);

            return new RowHolder(row, authorView, handleView, badgeView, timeView, moreView, bodyView);
        }

        private void bindRow(RowHolder holder, TimelineHistoryEntry entry) {
            String displayName = !entry.authorName.isEmpty()
                    ? entry.authorName
                    : (!entry.authorHandle.isEmpty() ? "@" + entry.authorHandle : str("piko_history_unknown_author"));
            holder.authorView.setText(displayName);
            holder.authorView.setMaxWidth(dp(context, 150));

            if (!entry.authorHandle.isEmpty()) {
                holder.handleView.setText("@" + entry.authorHandle);
                holder.handleView.setVisibility(View.VISIBLE);
            } else {
                holder.handleView.setText("");
                holder.handleView.setVisibility(View.GONE);
            }

            String badge = buildBadgeText(entry);
            if (!badge.isEmpty()) {
                holder.badgeView.setText(badge);
                holder.badgeView.setVisibility(View.VISIBLE);
            } else {
                holder.badgeView.setVisibility(View.GONE);
            }

            holder.timeView.setText(formatRelativeTime(entry.lastSeenAt));

            if (!entry.text.isEmpty()) {
                holder.bodyView.setText(entry.text);
                holder.bodyView.setTextColor(palette.primaryTextColor);
                holder.bodyView.setTypeface(Typeface.DEFAULT);
            } else if (entry.mediaCount > 0) {
                holder.bodyView.setText(str("piko_history_media_only_placeholder"));
                holder.bodyView.setTextColor(palette.secondaryTextColor);
                holder.bodyView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            } else {
                holder.bodyView.setText("ID: " + entry.tweetId);
                holder.bodyView.setTextColor(palette.secondaryTextColor);
                holder.bodyView.setTypeface(Typeface.DEFAULT);
            }

            Activity activity = getActivity();
            Context dialogContext = activity != null ? activity : context;

            holder.root.setOnClickListener(v -> TimelineHistoryManager.openTweetInApp(dialogContext, entry));
            holder.root.setOnLongClickListener(v -> {
                showEntryActions(dialogContext, entry);
                return true;
            });
            holder.moreView.setOnClickListener(v -> showEntryActions(dialogContext, entry));
        }

        private String buildBadgeText(TimelineHistoryEntry entry) {
            StringBuilder sb = new StringBuilder();
            if (entry.sourceType == TimelineHistoryEntry.SOURCE_QUOTE) {
                sb.append(str("piko_history_badge_quote"));
            }
            if (entry.mediaCount > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(str("piko_history_badge_media", entry.mediaCount));
            }
            return sb.toString();
        }

        private String formatRelativeTime(long timestampMs) {
            long diff = Math.max(0L, System.currentTimeMillis() - timestampMs);
            if (diff < 60_000L) {
                long secs = Math.max(1L, diff / 1000L);
                return secs + "s";
            }
            if (diff < 3_600_000L) {
                return (diff / 60_000L) + "m";
            }
            if (diff < 86_400_000L) {
                return (diff / 3_600_000L) + "h";
            }
            return olderFormat.format(new Date(timestampMs));
        }
    }

    private static final class RowHolder {
        final View root;
        final TextView authorView;
        final TextView handleView;
        final TextView badgeView;
        final TextView timeView;
        final TextView moreView;
        final TextView bodyView;

        RowHolder(
                View root,
                TextView authorView,
                TextView handleView,
                TextView badgeView,
                TextView timeView,
                TextView moreView,
                TextView bodyView
        ) {
            this.root = root;
            this.authorView = authorView;
            this.handleView = handleView;
            this.badgeView = badgeView;
            this.timeView = timeView;
            this.moreView = moreView;
            this.bodyView = bodyView;
        }
    }

    private static int dp(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private static final class ThemePalette {
        final int bgColor;
        final int primaryTextColor;
        final int secondaryTextColor;
        final int hintColor;
        final int surfaceColor;
        final int borderColor;
        final int accentColor;
        final int chipSelectedBg;

        private ThemePalette(
                int bgColor,
                int primaryTextColor,
                int secondaryTextColor,
                int hintColor,
                int surfaceColor,
                int borderColor,
                int accentColor,
                int chipSelectedBg
        ) {
            this.bgColor = bgColor;
            this.primaryTextColor = primaryTextColor;
            this.secondaryTextColor = secondaryTextColor;
            this.hintColor = hintColor;
            this.surfaceColor = surfaceColor;
            this.borderColor = borderColor;
            this.accentColor = accentColor;
            this.chipSelectedBg = chipSelectedBg;
        }

        static ThemePalette fromContext(Context context) {
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

            int surface = dark ? Color.argb(28, 255, 255, 255) : Color.argb(12, 0, 0, 0);
            int border = dark ? Color.argb(46, 255, 255, 255) : Color.argb(28, 0, 0, 0);
            int hint = Color.argb(140, Color.red(secondary), Color.green(secondary), Color.blue(secondary));
            int chipSelectedBg = Color.argb(dark ? 56 : 28, Color.red(accent), Color.green(accent), Color.blue(accent));

            return new ThemePalette(bg, primary, secondary, hint, surface, border, accent, chipSelectedBg);
        }

        private static int resolveColorSafe(Context context, String attr, int fallback) {
            try {
                return Utils.resolveColor(context, attr);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }
}
