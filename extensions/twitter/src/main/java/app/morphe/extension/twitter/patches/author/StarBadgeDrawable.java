/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.author;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Vector-drawn golden star badge scaled dynamically to match inline header badge bounds.
 */
final class StarBadgeDrawable extends Drawable {

    private static final int GOLD_COLOR = 0xFFF4B400;
    // Larger than normal single-line font heights so com.twitter.ui.widget.f scales it to 0.8x line height.
    private static final int INTRINSIC_SIZE = 128;
    private static final float OUTER_RADIUS_RATIO = 0.48f;
    private static final float INNER_RADIUS_RATIO = 0.43f;
    // Shift down slightly so the upright 5-point star bounding box (-1.0..+0.809) is optically centered.
    private static final float VERTICAL_CENTER_BIAS = 0.095f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();

    StarBadgeDrawable() {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GOLD_COLOR);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuildPath(bounds);
    }

    private void rebuildPath(Rect bounds) {
        starPath.reset();
        int width = bounds.width();
        int height = bounds.height();
        if (width <= 0 || height <= 0) {
            return;
        }

        float size = Math.min(width, height);
        float outerR = size * OUTER_RADIUS_RATIO;
        float innerR = outerR * INNER_RADIUS_RATIO;
        float cx = bounds.left + width * 0.5f;
        float cy = bounds.top + height * 0.5f + outerR * VERTICAL_CENTER_BIAS;

        double step = Math.PI / 5.0;
        double startAngle = -Math.PI / 2.0;
        for (int i = 0; i < 10; i++) {
            double r = (i % 2 == 0) ? outerR : innerR;
            double angle = startAngle + i * step;
            float x = (float) (cx + Math.cos(angle) * r);
            float y = (float) (cy + Math.sin(angle) * r);
            if (i == 0) {
                starPath.moveTo(x, y);
            } else {
                starPath.lineTo(x, y);
            }
        }
        starPath.close();
    }

    @Override
    public void draw(Canvas canvas) {
        if (starPath.isEmpty()) {
            rebuildPath(getBounds());
        }
        if (!starPath.isEmpty()) {
            canvas.drawPath(starPath, paint);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return INTRINSIC_SIZE;
    }

    @Override
    public int getIntrinsicHeight() {
        return INTRINSIC_SIZE;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
