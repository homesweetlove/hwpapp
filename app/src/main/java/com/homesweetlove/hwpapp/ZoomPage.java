package com.homesweetlove.hwpapp;

import android.content.Context;
import android.view.*;
import android.widget.HorizontalScrollView;

/** Pinch changes the measured page size, keeping annotations in page coordinates. */
final class ZoomPage extends HorizontalScrollView {
  interface Changed {
    void accept(float value);
  }

  private final ScaleGestureDetector detector;
  private final Changed changed;
  private float zoom;
  private boolean scaling;

  ZoomPage(Context c, View page, float zoom, Changed changed) {
    super(c);
    this.zoom = zoom;
    this.changed = changed;
    setFillViewport(false);
    setClipToPadding(false);
    setHorizontalScrollBarEnabled(zoom > 1);
    addView(page, new LayoutParams(1, -2));
    detector =
        new ScaleGestureDetector(
            c,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScale(ScaleGestureDetector d) {
                setZoom(ZoomPage.this.zoom * d.getScaleFactor());
                return true;
              }
            });
  }

  void setZoom(float value) {
    zoom = Math.max(1, Math.min(3, value));
    setHorizontalScrollBarEnabled(zoom > 1);
    updatePage();
    changed.accept(zoom);
  }

  private void updatePage() {
    if (getWidth() > 0 && getChildCount() > 0) {
      View child = getChildAt(0);
      android.view.ViewGroup.LayoutParams p = child.getLayoutParams();
      int width = Math.max(1, Math.round(getWidth() * zoom));
      if (p.width != width) {
        p.width = width;
        child.setLayoutParams(p);
      }
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updatePage();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getPointerCount() > 1 || scaling) {
      if (!scaling) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
      }
      scaling = true;
      getParent().requestDisallowInterceptTouchEvent(true);
      detector.onTouchEvent(event);
      if (event.getActionMasked() == MotionEvent.ACTION_UP
          || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        scaling = false;
        getParent().requestDisallowInterceptTouchEvent(false);
      }
      return true;
    }
    detector.onTouchEvent(event);
    return super.dispatchTouchEvent(event);
  }
}
