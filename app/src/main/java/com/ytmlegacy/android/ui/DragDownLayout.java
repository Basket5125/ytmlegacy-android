package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Scroller;

/**
 * Holds the Now Playing screen. It slides up to open, and closes by sliding down: with the
 * collapse button, the back key, or by dragging it down with a finger. Written with Scroller
 * and layout offsets, so it runs the same on Android 2.3 (no property animations there).
 */
public class DragDownLayout extends FrameLayout {

    public interface Listener {
        void onOpened();

        void onClosed();
    }

    /** Decides whether a drag starting at y (in this layout) may move the panel. */
    public interface DragArea {
        boolean canDragFrom(float x, float y);
    }

    private int offset;
    private Scroller scroller;
    private boolean closing;
    private Listener listener;
    private DragArea dragArea;
    private final int touchSlop;
    private final int minFling;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean maybeDrag;
    private VelocityTracker velocity;
    private final Paint scrim = new Paint();

    public DragDownLayout(Context context) {
        this(context, null);
    }

    public DragDownLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        setWillNotDraw(false);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDragArea(DragArea area) {
        dragArea = area;
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE && !closing;
    }

    public void open() {
        if (getVisibility() == VISIBLE && !closing) return;
        closing = false;
        setVisibility(VISIBLE);
        int h = h();
        if (offset == 0 || getHeight() == 0) offset = h;
        animateTo(0, new DecelerateInterpolator(1.6f), 320);
        if (listener != null) listener.onOpened();
    }

    public void close() {
        if (getVisibility() != VISIBLE || closing) return;
        closing = true;
        animateTo(h(), new AccelerateInterpolator(1.2f), 260);
    }

    /** Shows or hides at once, without animation. */
    public void setOpenNow(boolean open) {
        scroller = null;
        closing = false;
        offset = 0;
        setVisibility(open ? VISIBLE : GONE);
        requestLayout();
    }

    private void animateTo(int target, android.view.animation.Interpolator interpolator, int ms) {
        scroller = new Scroller(getContext(), interpolator);
        int distance = Math.abs(target - offset);
        int h = Math.max(1, h());
        int duration = Math.max(120, ms * distance / h);
        scroller.startScroll(0, offset, 0, target - offset, duration);
        post(step);
    }

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            Scroller s = scroller;
            if (s == null) return;
            boolean more = s.computeScrollOffset();
            setOffset(s.getCurrY());
            if (more) {
                post(this);
            } else {
                scroller = null;
                if (closing) {
                    closing = false;
                    setVisibility(GONE);
                    offset = 0;
                    if (listener != null) listener.onClosed();
                }
            }
        }
    };

    /** Own height, or the parent's before the first layout (the panel fills its parent). */
    private int h() {
        if (getHeight() > 0) return getHeight();
        View parent = (View) getParent();
        return parent == null ? 0 : parent.getHeight();
    }

    private void setOffset(int value) {
        int h = Math.max(0, h());
        value = Math.max(0, Math.min(h, value));
        if (value == offset) return;
        int delta = value - offset;
        offset = value;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).offsetTopAndBottom(delta);
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Layout passes (text updates…) would undo the offset: apply it again.
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).offsetTopAndBottom(offset);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (scroller != null && closing) {
            scroller = null;
            closing = false;
            setVisibility(GONE);
            offset = 0;
            if (listener != null) listener.onClosed();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (offset > 0 && h() > 0) {
            float open = 1f - offset / (float) h();
            scrim.setColor(((int) (open * 150) << 24));
            canvas.drawRect(0, 0, getWidth(), offset, scrim);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = lastY = ev.getY();
                dragging = false;
                maybeDrag = scroller == null && (dragArea == null || dragArea.canDragFrom(downX, downY));
                break;
            case MotionEvent.ACTION_MOVE:
                if (!maybeDrag) break;
                float dy = ev.getY() - downY;
                float dx = ev.getX() - downX;
                if (dy > touchSlop && dy > Math.abs(dx) * 1.5f) {
                    dragging = true;
                    lastY = ev.getY();
                    startVelocity(ev);
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    private void startVelocity(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.clear();
        velocity.addMovement(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) {
            // Taps on the panel's empty parts are swallowed; the main UI below stays inert.
            if ((ev.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = lastY = ev.getY();
                maybeDrag = scroller == null && (dragArea == null || dragArea.canDragFrom(downX, downY));
            } else if ((ev.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE && maybeDrag) {
                float dy = ev.getY() - downY;
                if (dy > touchSlop && dy > Math.abs(ev.getX() - downX) * 1.5f) {
                    dragging = true;
                    lastY = ev.getY();
                    startVelocity(ev);
                }
            }
            return true;
        }
        if (velocity != null) velocity.addMovement(ev);
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE:
                setOffset(offset + (int) (ev.getY() - lastY));
                lastY = ev.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                float v = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    v = velocity.getYVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                if (v > minFling || (offset > h() / 4 && v > -minFling)) {
                    close();
                } else {
                    animateTo(0, new DecelerateInterpolator(), 200);
                }
                break;
            default:
                break;
        }
        return true;
    }

    /** Lets a child (e.g. the queue list) keep vertical drags while it scrolls. */
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        if (disallow) maybeDrag = false;
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    public static boolean contains(View v, View ancestor, float x, float y) {
        int[] a = new int[2];
        int[] b = new int[2];
        v.getLocationOnScreen(a);
        ancestor.getLocationOnScreen(b);
        float left = a[0] - b[0];
        float top = a[1] - b[1];
        return x >= left && x < left + v.getWidth() && y >= top && y < top + v.getHeight();
    }
}
