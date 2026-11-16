/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.settings.widgets;

import android.content.Context;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatImageButton;

import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.ActivityHook;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Quick-access button inside the navigation drawer footer to open Timeline History.
 */
public class PikoHistoryButton extends AppCompatImageButton implements View.OnClickListener {

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
        applyThemeTint();
        updateVisibility();
        setOnClickListener(this);
    }

    private void applyThemeTint() {
        try {
            int clr = Utils.resolveColor(getContext(), "coreColorPrimaryText");
            if (clr != 0) {
                setColorFilter(clr, PorterDuff.Mode.SRC_IN);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateVisibility();
    }

    private void updateVisibility() {
        boolean visible = SettingsStatus.timelineHistory && Pref.showTimelineHistoryButton();
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onClick(View view) {
        ActivityHook.startActivity(Settings.TIMELINE_HISTORY_VIEW);
    }
}
