package com.shaforostoff.dcimsort.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridView;

import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.List;

/**
 * Touch helper that implements long-press-to-start drag selection on a GridView.
 * Long-pressing an item toggles it and records the resulting state; dragging over
 * subsequent items brings them to the same state. Single taps fall through to the
 * GridView's own click listener unchanged.
 */
class DragSelectionHelper implements View.OnTouchListener {

    interface ImageSource {
        List<MediaImage> get();
    }

    interface ChangeCallback {
        void onChanged();
    }

    private static final int IDLE = 0, PENDING = 1, DRAGGING = 2;

    private final GridView grid;
    private final ImageSource source;
    private final ChangeCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private final long longPressMs;

    private int state = IDLE;
    private boolean dragTargetSelected;
    private int lastDraggedPos = GridView.INVALID_POSITION;
    private float downX, downY;
    private Runnable longPressRunnable;

    DragSelectionHelper(GridView grid, ImageSource source, ChangeCallback callback) {
        this.grid = grid;
        this.source = source;
        this.callback = callback;
        ViewConfiguration vc = ViewConfiguration.get(grid.getContext());
        touchSlop = vc.getScaledTouchSlop();
        longPressMs = ViewConfiguration.getLongPressTimeout();
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        int x = (int) e.getX();
        int y = (int) e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                int pos = grid.pointToPosition(x, y);
                if (pos != GridView.INVALID_POSITION) {
                    state = PENDING;
                    final int capturedPos = pos;
                    longPressRunnable = () -> {
                        List<MediaImage> images = source.get();
                        if (capturedPos >= images.size()) return;
                        String key = images.get(capturedPos).key();
                        SelectionStore.toggle(key);
                        dragTargetSelected = SelectionStore.isSelected(key);
                        lastDraggedPos = capturedPos;
                        state = DRAGGING;
                        callback.onChanged();
                    };
                    handler.postDelayed(longPressRunnable, longPressMs);
                }
                return false; // let GridView handle scroll + click detection
            }

            case MotionEvent.ACTION_MOVE: {
                if (state == PENDING) {
                    if (Math.hypot(x - downX, y - downY) > touchSlop) {
                        cancelLongPress();
                        state = IDLE;
                    }
                    return false;
                }
                if (state == DRAGGING) {
                    int pos = grid.pointToPosition(x, y);
                    if (pos != GridView.INVALID_POSITION && pos != lastDraggedPos) {
                        List<MediaImage> images = source.get();
                        if (pos < images.size()) {
                            String key = images.get(pos).key();
                            if (SelectionStore.isSelected(key) != dragTargetSelected) {
                                SelectionStore.toggle(key);
                                callback.onChanged();
                            }
                            lastDraggedPos = pos;
                        }
                    }
                    return true; // consume — prevent GridView scrolling while dragging
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancelLongPress();
                boolean wasDragging = state == DRAGGING;
                state = IDLE;
                return wasDragging; // consume UP so GridView doesn't fire a click after drag
            }
        }
        return false;
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }
}
