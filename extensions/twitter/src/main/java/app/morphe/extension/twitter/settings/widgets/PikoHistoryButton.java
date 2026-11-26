/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.settings.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.appcompat.widget.AppCompatButton;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.settings.ActivityHook;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Quick-access button inside the navigation drawer footer to open Timeline History.
 */
public class PikoHistoryButton extends AppCompatButton implements View.OnClickListener {

    public PikoHistoryButton(Context context) {
        super(context);
        init();
    }

    public PikoHistoryButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PikoHistoryButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnClickListener(this);
        float elev = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 6f, getResources().getDisplayMetrics());
        setElevation(elev);
        setTranslationZ(elev);
        updateVisibility();
    }

    private final Runnable bringToFrontRunnable = () -> {
        if (!isAttachedToWindow() || getVisibility() != View.VISIBLE) {
            return;
        }
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            int count = group.getChildCount();
            if (count > 0 && group.getChildAt(count - 1) != this) {
                bringToFront();
            }
        }
    };

    private void scheduleBringToFront() {
        removeCallbacks(bringToFrontRunnable);
        post(bringToFrontRunnable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateVisibility();
        if (getVisibility() == View.VISIBLE) {
            // Defer reordering until after ViewGroup.dispatchAttachedToWindow finishes iterating
            // over its children; calling bringToFront() synchronously mutates mChildren mid-loop
            // and skips dispatchAttachedToWindow on subsequent siblings (e.g. drawer ComposeView).
            scheduleBringToFront();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateVisibility();
        if (visibility == View.VISIBLE && getVisibility() == View.VISIBLE) {
            scheduleBringToFront();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(bringToFrontRunnable);
        super.onDetachedFromWindow();
    }

    private void updateVisibility() {
        boolean visible = SettingsStatus.timelineHistory && Pref.showTimelineHistoryButton();
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onClick(View view) {
        try {
            ActivityHook.startActivity(Settings.TIMELINE_HISTORY_VIEW);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }
}
