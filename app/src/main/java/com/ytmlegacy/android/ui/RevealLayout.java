package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

/**
 * A panel that unrolls downwards: its drawing is clipped to a growing height, with a soft
 * shadow under the moving edge. The children are laid out once at full size, so each frame
 * only redraws (light enough for Android 2.3 phones).
 */
public class RevealLayout extends FrameLayout {

    private static final int OPEN_MS = 260;
    private static final int CLOSE_MS = 200;

    private float progress = 1f;
    private float fromProgress;
    private float toProgress;
    private long startTime;
    private int duration;
    private Interpolator interpolator;
    private boolean animating;
    private final Paint shadow = new Paint();
    private final int shadowHeight;
    private Runnable afterClose;

    public RevealLayout(Context context) {
        this(context, null);
    }

    public RevealLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        shadowHeight = (int) (10 * context.getResources().getDisplayMetrics().density);
        setWillNotDraw(false);
    }

    /** Open or opening (not closing). */
    public boolean isOpen() {
        return getVisibility() == VISIBLE && (!animating || toProgress > 0f);
    }

    /** Hides at once (when the whole player screen closes). */
    public void hideNow() {
        animating = false;
        progress = 0f;
        afterClose = null;
        setVisibility(GONE);
    }

    public void open() {
        if (getVisibility() != VISIBLE) progress = 0f;
        setVisibility(VISIBLE);
        animate(1f, OPEN_MS, new DecelerateInterpolator(1.5f));
    }

    /** Rolls up, then hides; {@code then} runs once it is gone (may be null). */
    public void close(Runnable then) {
        if (getVisibility() != VISIBLE) {
            if (then != null) then.run();
            return;
        }
        afterClose = then;
        animate(0f, CLOSE_MS, new AccelerateInterpolator(1.2f));
    }

    private void animate(float target, int ms, Interpolator i) {
        fromProgress = progress;
        toProgress = target;
        duration = Math.max(1, (int) (ms * Math.abs(target - progress)));
        interpolator = i;
        startTime = -1; // set by the first frame drawn, so no part of the motion is skipped
        animating = true;
        invalidate();
    }

    @Override
    public void draw(Canvas canvas) {
        if (animating) {
            if (startTime < 0) startTime = AnimationUtils.currentAnimationTimeMillis();
            float t = Math.min(1f, (AnimationUtils.currentAnimationTimeMillis() - startTime) / (float) duration);
            progress = fromProgress + (toProgress - fromProgress) * interpolator.getInterpolation(t);
            if (t >= 1f) {
                animating = false;
                progress = toProgress;
                if (progress <= 0f) {
                    setVisibility(GONE);
                    Runnable r = afterClose;
                    afterClose = null;
                    if (r != null) post(r);
                    return;
                }
            }
        }
        int edge = (int) (getHeight() * progress);
        if (progress >= 1f) {
            super.draw(canvas);
        } else {
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), edge);
            super.draw(canvas);
            canvas.restore();
            // Shadow under the rolling edge (drawn over the screen below the panel).
            shadow.setShader(new LinearGradient(0, edge, 0, edge + shadowHeight, 0x66000000, 0x00000000,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, edge, getWidth(), edge + shadowHeight, shadow);
        }
        if (animating) invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return animating || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return true; // touches on the panel never reach the player below
    }
}
