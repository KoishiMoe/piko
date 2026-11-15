/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.nativeFeatures.search;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.Utils;

/**
 * Interactive bottom sheet for building advanced per-author search queries.
 */
public final class QuickSearchSheet {

    private static final int ANIM_DURATION_MS = 220;
    private static final ExecutorService LOOKUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private QuickSearchSheet() {
    }

    public static void show(Context context, long userId, String cleanHandle) {
        ThemePalette palette = ThemePalette.fromContext(context);

        SheetBottomDialog.DraggableLinearLayout mainLayout =
                SheetBottomDialog.createMainLayout(context, palette.sheetBgColor);

        int padH = dp(context, 16);
        int padB = dp(context, 18);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int maxScrollHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.78f);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padH, dp(context, 10), padH, padB);

        // Header row: title + @handle badge
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(context, 12));
        headerRow.setLayoutParams(headerParams);

        TextView titleView = new TextView(context);
        titleView.setText(StringRef.str("piko_title_quick_search"));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTextColor(palette.primaryTextColor);

        TextView handleView = new TextView(context);
        handleView.setText("@" + cleanHandle);
        handleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        handleView.setTextColor(palette.secondaryTextColor);
        handleView.setSingleLine(true);
        handleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        handleParams.setMarginStart(dp(context, 8));
        handleView.setLayoutParams(handleParams);

        headerRow.addView(titleView);
        headerRow.addView(handleView);
        content.addView(headerRow);

        // Custom keyword input
        EditText keywordInput = new EditText(context);
        keywordInput.setHint(StringRef.str("piko_quick_search_input_hint"));
        keywordInput.setHintTextColor(palette.hintColor);
        keywordInput.setTextColor(palette.primaryTextColor);
        keywordInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keywordInput.setSingleLine(true);
        keywordInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        keywordInput.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(palette.surfaceColor);
        inputBg.setCornerRadius(dp(context, 10));
        inputBg.setStroke(dp(context, 1), palette.borderColor);
        keywordInput.setBackground(inputBg);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, 0, 0, dp(context, 14));
        keywordInput.setLayoutParams(inputParams);
        content.addView(keywordInput);

        // Collect preset keyword chip states
        List<ChipToggle> presetChips = new ArrayList<>();
        String kwEn = Pref.quickSearchKeywordsEn();
        String kwJa = Pref.quickSearchKeywordsJa();
        String kwZh = Pref.quickSearchKeywordsZh();
        String kwKo = Pref.quickSearchKeywordsKo();

        if (!isBlank(kwEn)) {
            presetChips.add(new ChipToggle(StringRef.str("piko_quick_search_lang_en"), kwEn, false));
        }
        if (!isBlank(kwJa)) {
            presetChips.add(new ChipToggle(StringRef.str("piko_quick_search_lang_ja"), kwJa, false));
        }
        if (!isBlank(kwZh)) {
            presetChips.add(new ChipToggle(StringRef.str("piko_quick_search_lang_zh"), kwZh, false));
        }
        if (!isBlank(kwKo)) {
            presetChips.add(new ChipToggle(StringRef.str("piko_quick_search_lang_ko"), kwKo, false));
        }

        List<String> customChipTexts = QuickSearchHelper.parseCustomChips(Pref.quickSearchCustomChips());
        List<ChipToggle> customChips = new ArrayList<>();
        for (String chipText : customChipTexts) {
            customChips.add(new ChipToggle(chipText, chipText, false));
        }

        ChipToggle allPresetsChip = presetChips.size() > 1
                ? new ChipToggle(StringRef.str("piko_quick_search_chip_all_presets"), "", false)
                : null;

        if (!presetChips.isEmpty() || !customChips.isEmpty()) {
            content.addView(buildSectionLabel(context, StringRef.str("piko_quick_search_keywords_section"), palette));

            FlowLayout keywordsFlow = new FlowLayout(context, dp(context, 8), dp(context, 8));
            LinearLayout.LayoutParams flowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flowParams.setMargins(0, 0, 0, dp(context, 14));
            keywordsFlow.setLayoutParams(flowParams);

            Runnable syncMasterChip = () -> {
                if (allPresetsChip == null) return;
                boolean allSelected = true;
                for (ChipToggle chip : presetChips) {
                    if (!chip.selected) {
                        allSelected = false;
                        break;
                    }
                }
                allPresetsChip.setSelected(allSelected, palette);
            };

            if (allPresetsChip != null) {
                allPresetsChip.createView(context, palette, v -> {
                    boolean target = !allPresetsChip.selected;
                    allPresetsChip.setSelected(target, palette);
                    for (ChipToggle chip : presetChips) {
                        chip.setSelected(target, palette);
                    }
                });
                keywordsFlow.addView(allPresetsChip.view);
            }

            for (ChipToggle chip : presetChips) {
                chip.createView(context, palette, v -> {
                    chip.setSelected(!chip.selected, palette);
                    syncMasterChip.run();
                });
                keywordsFlow.addView(chip.view);
            }

            for (ChipToggle chip : customChips) {
                chip.createView(context, palette, v -> chip.setSelected(!chip.selected, palette));
                keywordsFlow.addView(chip.view);
            }

            content.addView(keywordsFlow);
        }

        // Filters (AND) section
        content.addView(buildSectionLabel(context, StringRef.str("piko_quick_search_filters_section"), palette));

        FlowLayout filtersFlow = new FlowLayout(context, dp(context, 8), dp(context, 8));
        LinearLayout.LayoutParams filtersParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filtersParams.setMargins(0, 0, 0, dp(context, 16));
        filtersFlow.setLayoutParams(filtersParams);

        long[] resolvedCreatedAtHolder = new long[]{UserCreationLookup.lookupCreatedAt(userId, cleanHandle)};
        int initialYear = UserCreationLookup.formatYearUtc(resolvedCreatedAtHolder[0]);
        String earlyLabel = initialYear > 0
                ? StringRef.str("piko_quick_search_filter_early_year", String.valueOf(initialYear))
                : StringRef.str("piko_quick_search_filter_early");

        ChipToggle earlyChip = new ChipToggle(earlyLabel, "early", false);
        ChipToggle mediaChip = new ChipToggle(StringRef.str("piko_quick_search_filter_media"), "media", true);
        ChipToggle noRepostsChip = new ChipToggle(StringRef.str("piko_quick_search_filter_no_reposts"), "no_rt", true);

        earlyChip.createView(context, palette, v -> earlyChip.setSelected(!earlyChip.selected, palette));
        mediaChip.createView(context, palette, v -> mediaChip.setSelected(!mediaChip.selected, palette));
        noRepostsChip.createView(context, palette, v -> noRepostsChip.setSelected(!noRepostsChip.selected, palette));

        filtersFlow.addView(earlyChip.view);
        filtersFlow.addView(mediaChip.view);
        filtersFlow.addView(noRepostsChip.view);
        content.addView(filtersFlow);

        // Search CTA button
        Button searchButton = new Button(context);
        searchButton.setText(StringRef.str("piko_quick_search_action_search"));
        searchButton.setAllCaps(false);
        searchButton.setTypeface(Typeface.DEFAULT_BOLD);
        searchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchButton.setTextColor(Color.WHITE);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(palette.accentColor);
        btnBg.setCornerRadius(dp(context, 22));
        searchButton.setBackground(btnBg);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 44));
        searchButton.setLayoutParams(btnParams);
        content.addView(searchButton);

        scrollView.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollView.setLayoutParams(scrollParams);
        mainLayout.addView(scrollView);

        // Cap scroll height when taller than ~78% viewport height
        scrollView.post(() -> {
            if (scrollView.getHeight() > maxScrollHeight) {
                ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
                lp.height = maxScrollHeight;
                scrollView.setLayoutParams(lp);
            }
        });

        SheetBottomDialog.SlideDialog dialog =
                SheetBottomDialog.createSlideDialog(context, mainLayout, ANIM_DURATION_MS);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            );
        }

        Runnable executeSearch = () -> {
            List<String> selectedKeywordGroups = new ArrayList<>();
            for (ChipToggle chip : presetChips) {
                if (chip.selected) {
                    selectedKeywordGroups.add(chip.payload);
                }
            }
            List<String> selectedCustom = new ArrayList<>();
            for (ChipToggle chip : customChips) {
                if (chip.selected) {
                    selectedCustom.add(chip.payload);
                }
            }

            String query = QuickSearchHelper.buildSearchQuery(
                    cleanHandle,
                    keywordInput.getText() != null ? keywordInput.getText().toString() : "",
                    selectedKeywordGroups,
                    selectedCustom,
                    earlyChip.selected,
                    resolvedCreatedAtHolder[0],
                    mediaChip.selected,
                    noRepostsChip.selected
            );

            if (query.isEmpty()) {
                QuickSearchHelper.showToast(context, StringRef.str("piko_quick_search_empty_query"));
                return;
            }
            dialog.dismiss();
            QuickSearchHelper.launchInAppSearch(context, query);
        };

        searchButton.setOnClickListener(v -> executeSearch.run());
        keywordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch.run();
                return true;
            }
            return false;
        });

        // If creation date wasn't already in memory cache, query DB off main thread and refresh chip label
        if (resolvedCreatedAtHolder[0] <= 0L) {
            LOOKUP_EXECUTOR.execute(() -> {
                long ts = UserCreationLookup.lookupCreatedAt(userId, cleanHandle);
                int year = UserCreationLookup.formatYearUtc(ts);
                if (ts > 0L && year > 0) {
                    MAIN_HANDLER.post(() -> {
                        resolvedCreatedAtHolder[0] = ts;
                        if (earlyChip.view != null && dialog.isShowing()) {
                            earlyChip.view.setText(StringRef.str(
                                    "piko_quick_search_filter_early_year",
                                    String.valueOf(year)
                            ));
                        }
                    });
                }
            });
        }

        dialog.show();
    }

    private static TextView buildSectionLabel(Context context, String label, ThemePalette palette) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(palette.secondaryTextColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 8));
        tv.setLayoutParams(params);
        return tv;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int dp(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    private static final class ChipToggle {
        final String label;
        final String payload;
        boolean selected;
        TextView view;

        ChipToggle(String label, String payload, boolean initialSelected) {
            this.label = label;
            this.payload = payload;
            this.selected = initialSelected;
        }

        void createView(Context context, ThemePalette palette, View.OnClickListener listener) {
            TextView chip = new TextView(context);
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(listener);
            this.view = chip;
            applyStyle(context, palette);
        }

        void setSelected(boolean selected, ThemePalette palette) {
            this.selected = selected;
            if (view != null) {
                applyStyle(view.getContext(), palette);
            }
        }

        private void applyStyle(Context context, ThemePalette palette) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(context, 16));
            if (selected) {
                bg.setColor(palette.selectedChipBg);
                bg.setStroke(dp(context, 1), palette.accentColor);
                view.setTextColor(palette.selectedChipText);
                view.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                bg.setColor(palette.surfaceColor);
                bg.setStroke(dp(context, 1), palette.borderColor);
                view.setTextColor(palette.primaryTextColor);
                view.setTypeface(Typeface.DEFAULT);
            }
            view.setBackground(bg);
        }
    }

    private static final class ThemePalette {
        final int sheetBgColor;
        final int primaryTextColor;
        final int secondaryTextColor;
        final int hintColor;
        final int surfaceColor;
        final int borderColor;
        final int accentColor;
        final int selectedChipBg;
        final int selectedChipText;

        private ThemePalette(
                int sheetBgColor,
                int primaryTextColor,
                int secondaryTextColor,
                int hintColor,
                int surfaceColor,
                int borderColor,
                int accentColor,
                int selectedChipBg,
                int selectedChipText
        ) {
            this.sheetBgColor = sheetBgColor;
            this.primaryTextColor = primaryTextColor;
            this.secondaryTextColor = secondaryTextColor;
            this.hintColor = hintColor;
            this.surfaceColor = surfaceColor;
            this.borderColor = borderColor;
            this.accentColor = accentColor;
            this.selectedChipBg = selectedChipBg;
            this.selectedChipText = selectedChipText;
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

            int surface = dark ? Color.argb(36, 255, 255, 255) : Color.argb(14, 0, 0, 0);
            int border = dark ? Color.argb(64, 255, 255, 255) : Color.argb(38, 0, 0, 0);
            int hint = Color.argb(140, Color.red(secondary), Color.green(secondary), Color.blue(secondary));
            int chipSelectedBg = Color.argb(dark ? 56 : 28, Color.red(accent), Color.green(accent), Color.blue(accent));

            return new ThemePalette(bg, primary, secondary, hint, surface, border, accent, chipSelectedBg, accent);
        }

        private static int resolveColorSafe(Context context, String attr, int fallback) {
            try {
                return Utils.resolveColor(context, attr);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    /** Simple wrapping horizontal flow layout for toggle chips. */
    private static final class FlowLayout extends ViewGroup {
        private final int hSpacing;
        private final int vSpacing;

        FlowLayout(Context context, int hSpacing, int vSpacing) {
            super(context);
            this.hSpacing = hSpacing;
            this.vSpacing = vSpacing;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? Integer.MAX_VALUE
                    : MeasureSpec.getSize(widthMeasureSpec);
            int widthUsed = 0;
            int heightUsed = 0;
            int lineWidth = 0;
            int lineHeight = 0;

            int childCount = getChildCount();
            int availWidth = Math.max(0, maxWidth - getPaddingLeft() - getPaddingRight());
            int childWidthSpec = maxWidth == Integer.MAX_VALUE
                    ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    : MeasureSpec.makeMeasureSpec(availWidth, MeasureSpec.AT_MOST);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(childWidthSpec, childHeightSpec);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();

                int nextLineWidth = lineWidth == 0 ? cw : lineWidth + hSpacing + cw;
                if (lineWidth > 0 && nextLineWidth > availWidth) {
                    widthUsed = Math.max(widthUsed, lineWidth);
                    heightUsed += lineHeight + vSpacing;
                    lineWidth = cw;
                    lineHeight = ch;
                } else {
                    lineWidth = nextLineWidth;
                    lineHeight = Math.max(lineHeight, ch);
                }
            }
            widthUsed = Math.max(widthUsed, lineWidth);
            heightUsed += lineHeight;

            int finalW = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                    ? maxWidth
                    : resolveSize(widthUsed + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
            int finalH = resolveSize(heightUsed + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
            setMeasuredDimension(finalW, finalH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int availWidth = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int lineHeight = 0;

            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();

                if (x > getPaddingLeft() && (x - getPaddingLeft() + cw) > availWidth) {
                    x = getPaddingLeft();
                    y += lineHeight + vSpacing;
                    lineHeight = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hSpacing;
                lineHeight = Math.max(lineHeight, ch);
            }
        }
    }
}
