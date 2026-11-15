/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.gallery;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Pref;

/**
 * UI helpers for legacy media gallery enhancements (page counter and stitch entry point).
 */
@SuppressWarnings("unused")
public final class GalleryHelper {
    private static final String COUNTER_TAG = "piko_gallery_counter";

    private GalleryHelper() {
    }

    /**
     * Updates or hides a centered pill counter ("1 / N") in the gallery {@link Toolbar}.
     *
     * @param toolbar    the gallery chrome toolbar (nullable)
     * @param position   0-based current pager index
     * @param totalCount total item count in the gallery adapter
     */
    public static void updatePageCounter(@Nullable Toolbar toolbar, int position, int totalCount) {
        if (toolbar == null) {
            return;
        }
        try {
            View existing = toolbar.findViewWithTag(COUNTER_TAG);
            TextView badge = existing instanceof TextView ? (TextView) existing : null;

            if (!Pref.galleryPageCounter() || totalCount <= 1 || position < 0 || position >= totalCount) {
                if (badge != null) {
                    badge.setVisibility(View.GONE);
                }
                return;
            }

            if (badge == null) {
                badge = createCounterBadge(toolbar.getContext());
                Toolbar.LayoutParams params = new Toolbar.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                );
                toolbar.addView(badge, params);
            }

            badge.setText((position + 1) + " / " + totalCount);
            badge.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static TextView createCounterBadge(Context context) {
        TextView badge = new TextView(context);
        badge.setTag(COUNTER_TAG);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setIncludeFontPadding(false);

        int padH = dpToPx(context, 10f);
        int padV = dpToPx(context, 4f);
        badge.setPadding(padH, padV, padH, padV);

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(dpToPx(context, 14f));
        badge.setBackground(pill);
        return badge;
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        ));
    }
}
