package com.homesweetlove.hwpapp;

import android.content.Context;
import android.view.*;
import android.widget.LinearLayout;

/** Tool groups wrap instead of clipping on narrow windows or with enlarged text. */
final class ActionRow extends LinearLayout {
  private final int gap;

  ActionRow(Context context) {
    super(context);
    setOrientation(HORIZONTAL);
    gap = Math.round(6 * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int limit = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
    if (limit <= 0) limit = 10000;
    int x = 0, y = 0, line = 0, max = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      child.measure(
          MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
      if (x > 0 && x + w > limit) {
        y += line + gap;
        x = 0;
        line = 0;
      }
      x += w + gap;
      line = Math.max(line, h);
      max = Math.max(max, x - gap);
    }
    setMeasuredDimension(
        resolveSize(max + getPaddingLeft() + getPaddingRight(), widthSpec),
        resolveSize(y + line + getPaddingTop() + getPaddingBottom(), heightSpec));
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int limit = getWidth() - getPaddingRight(), x = getPaddingLeft(), y = getPaddingTop(), line = 0;
    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
      if (x > getPaddingLeft() && x + w > limit) {
        y += line + gap;
        x = getPaddingLeft();
        line = 0;
      }
      int left = rtl ? getWidth() - x - w : x;
      child.layout(left, y, left + w, y + h);
      x += w + gap;
      line = Math.max(line, h);
    }
  }
}
