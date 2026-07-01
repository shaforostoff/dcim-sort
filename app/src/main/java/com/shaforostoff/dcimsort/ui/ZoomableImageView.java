package com.shaforostoff.dcimsort.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.widget.ImageView;

/**
 * Matrix-based zoomable image view (pinch, drag-pan, double-tap zoom) with no third-party library.
 * Also supports a "hold to compare" gesture: pressing one finger still for >500ms fires a compare
 * callback; releasing reverts. Swapping the bitmap via {@link #setImageBitmapKeepMatrix} preserves
 * the current zoom/visible region (used to overlay the compressed version).
 * When at base (fit) scale a horizontal fling triggers {@link NavigationListener}.
 */
public class ZoomableImageView extends ImageView {

    public interface CompareListener {
        void onCompareStart();
        void onCompareEnd();
    }

    public interface NavigationListener {
        void onSwipePrev();
        void onSwipeNext();
    }

    private static final long LONG_PRESS_MS = 500;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private float baseScale = 1f, minScale = 1f, midScale = 2.5f, maxScale = 4f;
    private boolean needFit = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private float downX, downY;
    private int touchSlop;
    private int minFlingVelocity;
    private boolean comparing = false;
    private boolean compareEnabled = false;
    private CompareListener compareListener;
    private NavigationListener navListener;

    public ZoomableImageView(Context c) { super(c); init(c); }
    public ZoomableImageView(Context c, AttributeSet a) { super(c, a); init(c); }
    public ZoomableImageView(Context c, AttributeSet a, int s) { super(c, a, s); init(c); }

    private void init(Context c) {
        super.setScaleType(ScaleType.MATRIX);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity() * 3;
        scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
        gestureDetector = new GestureDetector(c, new GestureListener());
    }

    public void setCompareEnabled(boolean enabled) {
        this.compareEnabled = enabled;
    }

    public void setCompareListener(CompareListener l) {
        this.compareListener = l;
    }

    public void setNavigationListener(NavigationListener l) {
        this.navListener = l;
    }

    private boolean isAtBaseScale() {
        return currentScale() <= baseScale * 1.05f;
    }

    /** Sets a new image and refits to the view. */
    public void setImageFitted(Bitmap bmp) {
        needFit = true;
        super.setImageBitmap(bmp);
        if (getWidth() > 0 && getHeight() > 0) computeBaseFit();
    }

    /** Replaces the bitmap but keeps the current zoom/pan matrix (assumes same pixel dimensions). */
    public void setImageBitmapKeepMatrix(Bitmap bmp) {
        super.setImageBitmap(bmp);
        setImageMatrix(matrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (needFit && getDrawable() != null) computeBaseFit();
    }

    private void computeBaseFit() {
        Drawable d = getDrawable();
        if (d == null) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float vw = getWidth();
        float vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float scale = Math.min(vw / dw, vh / dh);
        baseScale = scale;
        minScale = scale;
        midScale = scale * 2.5f;
        maxScale = scale * 4f;
        matrix.reset();
        matrix.postScale(scale, scale);
        float dx = (vw - dw * scale) / 2f;
        float dy = (vh - dh * scale) / 2f;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
        needFit = false;
    }

    private float currentScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private RectF drawableRect() {
        Drawable d = getDrawable();
        RectF r = new RectF();
        if (d != null) {
            r.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            matrix.mapRect(r);
        }
        return r;
    }

    private void fixTranslation() {
        RectF r = drawableRect();
        float vw = getWidth();
        float vh = getHeight();
        float dx = 0, dy = 0;

        if (r.width() <= vw) {
            dx = (vw - r.width()) / 2f - r.left;
        } else {
            if (r.left > 0) dx = -r.left;
            else if (r.right < vw) dx = vw - r.right;
        }
        if (r.height() <= vh) {
            dy = (vh - r.height()) / 2f - r.top;
        } else {
            if (r.top > 0) dy = -r.top;
            else if (r.bottom < vh) dy = vh - r.bottom;
        }
        matrix.postTranslate(dx, dy);
    }

    // ---- Touch handling -----------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                scheduleLongPress();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelHoldTimer();
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    cancelHoldTimer();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelHoldTimer();
                endCompare();
                break;
            default:
                break;
        }
        return true;
    }

    private void scheduleLongPress() {
        if (!compareEnabled) return;
        cancelHoldTimer();
        longPressRunnable = () -> {
            if (compareEnabled && !comparing) {
                comparing = true;
                if (compareListener != null) compareListener.onCompareStart();
            }
        };
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
    }

    private void cancelHoldTimer() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void endCompare() {
        if (comparing) {
            comparing = false;
            if (compareListener != null) compareListener.onCompareEnd();
        }
    }

    // ---- Gesture listeners --------------------------------------------------

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            cancelHoldTimer();
            float factor = detector.getScaleFactor();
            float cur = currentScale();
            float target = cur * factor;
            if (target < minScale) factor = minScale / cur;
            else if (target > maxScale) factor = maxScale / cur;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            fixTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (scaleDetector.isInProgress()) return false;
            matrix.postTranslate(-dx, -dy);
            fixTranslation();
            setImageMatrix(matrix);
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (navListener != null && isAtBaseScale()
                    && Math.abs(velocityX) > Math.abs(velocityY)
                    && Math.abs(velocityX) > minFlingVelocity) {
                if (velocityX < 0) navListener.onSwipeNext();
                else navListener.onSwipePrev();
                return true;
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            cancelHoldTimer();
            float cur = currentScale();
            boolean nearMin = cur < minScale + (maxScale - minScale) * 0.1f;
            animateScaleTo(nearMin ? midScale : minScale, e.getX(), e.getY());
            return true;
        }
    }

    private void animateScaleTo(final float targetScale, final float px, final float py) {
        final float start = currentScale();
        ValueAnimator anim = ValueAnimator.ofFloat(start, targetScale);
        anim.setDuration(220);
        final float[] prev = {start};
        anim.addUpdateListener(a -> {
            float val = (float) a.getAnimatedValue();
            float factor = val / prev[0];
            prev[0] = val;
            matrix.postScale(factor, factor, px, py);
            fixTranslation();
            setImageMatrix(matrix);
        });
        anim.start();
    }
}
