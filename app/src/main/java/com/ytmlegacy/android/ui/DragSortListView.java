package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * A ListView whose rows can be dragged by a handle on their right edge (the way the music
 * players of the Android 2.x/4.x era did it). While a row is held, a snapshot of it follows the
 * finger, the list shows the gap where it would land, and near the top or bottom edge the list
 * scrolls by itself, faster the closer the finger is to the edge.
 */
public class DragSortListView extends ListView {

    /** Called when a row is dropped somewhere else. */
    public interface DropListener {
        void onDrop(int from, int to);
    }

    /** The adapter shows the list as if the row at {@code from} already sat at {@code to}. */
    public interface DragAdapter {
        void setDrag(int from, int to);
    }

    private DropListener listener;
    private final int handleWidth;
    private final int maxScrollStep;
    private final Paint floatPaint = new Paint();
    private final Paint floatBack = new Paint();
    private final Paint accent = new Paint();
    private final Paint shadow = new Paint();
    private int shadowSize;
    private boolean dragging;
    private int from = -1;
    private int to = -1;
    private Bitmap snapshot;
    private float touchY;
    private int grabOffset;
    private int itemHeight;

    public DragSortListView(Context context) {
        this(context, null);
    }

    public DragSortListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        handleWidth = (int) (56 * d);
        maxScrollStep = (int) (14 * d);
        floatBack.setColor(0xFF1C2A35);
        accent.setColor(0xFF33B5E5);
        shadowSize = (int) (8 * d);
    }

    public void setDropListener(DropListener l) {
        listener = l;
    }

    public boolean isDragging() {
        return dragging;
    }

    private DragAdapter dragAdapter() {
        ListAdapter a = getAdapter();
        return a instanceof DragAdapter ? (DragAdapter) a : null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if ((ev.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN && dragAdapter() != null
                && ev.getX() >= getWidth() - handleWidth) {
            int pos = pointToPosition((int) ev.getX(), (int) ev.getY());
            if (pos != INVALID_POSITION && pos < getAdapter().getCount() && startDrag(pos, ev.getY())) return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) {
            // A drag may also begin with a touch the list received directly.
            if ((ev.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN && dragAdapter() != null
                    && ev.getX() >= getWidth() - handleWidth) {
                int pos = pointToPosition((int) ev.getX(), (int) ev.getY());
                if (pos != INVALID_POSITION && pos < getAdapter().getCount() && startDrag(pos, ev.getY())) return true;
            }
            return super.onTouchEvent(ev);
        }
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE:
                touchY = ev.getY();
                updateTarget();
                startAutoScroll();
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                stopDrag(true);
                break;
            case MotionEvent.ACTION_CANCEL:
                stopDrag(false);
                break;
            default:
                break;
        }
        return true;
    }

    private boolean startDrag(int position, float y) {
        View row = getChildAt(position - getFirstVisiblePosition());
        if (row == null || row.getWidth() <= 0 || row.getHeight() <= 0) return false;
        try {
            snapshot = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
            row.draw(new Canvas(snapshot));
        } catch (OutOfMemoryError e) {
            snapshot = null;
        }
        itemHeight = row.getHeight();
        grabOffset = (int) y - row.getTop();
        touchY = y;
        from = to = position;
        dragging = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        DragAdapter a = dragAdapter();
        if (a != null) a.setDrag(from, to);
        invalidate();
        return true;
    }

    /** The row under the middle of the floating snapshot becomes the drop target. */
    private void updateTarget() {
        int count = getAdapter() == null ? 0 : getAdapter().getCount();
        if (count == 0) return;
        int center = (int) (touchY - grabOffset + itemHeight / 2f);
        center = Math.max(0, Math.min(getHeight() - 1, center));
        int p = rowAt(center);
        if (p < 0) return;
        p = Math.max(0, Math.min(count - 1, p));
        if (p != to) {
            to = p;
            DragAdapter a = dragAdapter();
            if (a != null) a.setDrag(from, to);
        }
    }

    /**
     * The row at a height, counting the hidden gap row too (pointToPosition skips invisible
     * children, which made the target jump to the end while the finger was over the gap).
     */
    private int rowAt(int y) {
        int n = getChildCount();
        if (n == 0) return -1;
        int first = getFirstVisiblePosition();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            int bottom = i + 1 < n ? getChildAt(i + 1).getTop() : c.getBottom();
            if (y >= c.getTop() && y < bottom) return first + i;
        }
        return y < getChildAt(0).getTop() ? first : first + n - 1;
    }

    /** Pixels to scroll per frame for the finger's distance to an edge (0 outside the edge zones). */
    private int scrollStep() {
        int zone = Math.max(itemHeight, getHeight() / 6);
        if (touchY < zone) return -(int) (maxScrollStep * (zone - touchY) / zone) - 1;
        if (touchY > getHeight() - zone) return (int) (maxScrollStep * (touchY - (getHeight() - zone)) / zone) + 1;
        return 0;
    }

    private boolean scrolling;

    private void startAutoScroll() {
        if (scrolling || scrollStep() == 0) return;
        scrolling = true;
        post(autoScroll);
    }

    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            int step = dragging ? scrollStep() : 0;
            if (step == 0) {
                scrolling = false;
                return;
            }
            View first = getChildAt(0);
            if (first != null) {
                int firstPos = getFirstVisiblePosition();
                boolean atTop = firstPos == 0 && first.getTop() >= getPaddingTop();
                View last = getChildAt(getChildCount() - 1);
                boolean atBottom = last != null && getLastVisiblePosition() == getAdapter().getCount() - 1
                        && last.getBottom() <= getHeight() - getPaddingBottom();
                if ((step < 0 && !atTop) || (step > 0 && !atBottom)) {
                    // Through the list's own scroller: survives the relayouts the drag causes.
                    smoothScrollBy(step, 20);
                }
            }
            updateTarget();
            invalidate();
            postDelayed(this, 16);
        }
    };

    private void stopDrag(boolean drop) {
        dragging = false;
        scrolling = false;
        removeCallbacks(autoScroll);
        int f = from;
        int t = to;
        from = to = -1;
        DragAdapter a = dragAdapter();
        if (a != null) a.setDrag(-1, -1);
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
        invalidate();
        if (drop && f >= 0 && t >= 0 && f != t && listener != null) listener.onDrop(f, t);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!dragging) return;
        float top = Math.max(-itemHeight / 2f, Math.min(getHeight() - itemHeight / 2f, touchY - grabOffset));
        // Opaque, lifted row: nothing of the rows below shows through it.
        shadow.setShader(new android.graphics.LinearGradient(0, top - shadowSize, 0, top, 0x00000000, 0x80000000,
                android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(0, top - shadowSize, getWidth(), top, shadow);
        shadow.setShader(new android.graphics.LinearGradient(0, top + itemHeight, 0, top + itemHeight + shadowSize,
                0x80000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(0, top + itemHeight, getWidth(), top + itemHeight + shadowSize, shadow);
        canvas.drawRect(0, top, getWidth(), top + itemHeight, floatBack);
        if (snapshot != null) canvas.drawBitmap(snapshot, 0, top, floatPaint);
        canvas.drawRect(0, top, Math.max(3, shadowSize / 2), top + itemHeight, accent);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (dragging) stopDrag(false);
        super.onDetachedFromWindow();
    }
}
