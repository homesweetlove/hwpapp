package com.homesweetlove.hwpapp;

import android.animation.*;
import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.animation.*;

/** Small, macOS-flavoured motion helpers. All of them settle instantly when animations are off. */
final class Motion {
  /** Fast start, long soft landing — close to the curve macOS uses for sheets and windows. */
  static final Interpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.25f, 1f);

  private static final Interpolator SPRING = new OvershootInterpolator(2.2f);

  private Motion() {}

  static boolean enabled() {
    return ValueAnimator.areAnimatorsEnabled();
  }

  /** Children rise and fade in one after another, like a window settling into place. */
  static void stagger(ViewGroup group, float distance) {
    int animated = 0;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child.getVisibility() != View.VISIBLE) continue;
      child.animate().cancel();
      if (!enabled() || animated >= 14) {
        child.setAlpha(1);
        child.setTranslationY(0);
        continue;
      }
      child.setAlpha(0);
      child.setTranslationY(distance);
      child
          .animate()
          .alpha(1)
          .translationY(0)
          .setStartDelay(animated * 28L)
          .setDuration(340)
          .setInterpolator(EASE)
          .start();
      animated++;
    }
  }

  /** A quick refresh pulse for content that changed in place (next rows, another sheet). */
  static void refresh(View view) {
    view.animate().cancel();
    if (!enabled()) {
      view.setAlpha(1);
      return;
    }
    view.setAlpha(0.35f);
    view.animate().alpha(1).setStartDelay(0).setDuration(180).setInterpolator(EASE).start();
  }

  /** Page turns slide in from the side they came from. */
  static void slide(View view, int direction, float distance) {
    view.animate().cancel();
    if (!enabled() || direction == 0) {
      view.setAlpha(1);
      view.setTranslationX(0);
      return;
    }
    view.setAlpha(0);
    view.setTranslationX(direction * distance);
    view.animate().alpha(1).translationX(0).setDuration(300).setInterpolator(EASE).start();
  }

  static void fadeIn(View view, long delay) {
    view.animate().cancel();
    if (!enabled()) {
      view.setAlpha(1);
      return;
    }
    view.setAlpha(0);
    view.animate().alpha(1).setStartDelay(delay).setDuration(260).setInterpolator(EASE).start();
  }

  /** Pressed controls sink a little and spring back, like clicking a macOS toolbar button. */
  @SuppressLint("ClickableViewAccessibility")
  static void press(View view, float depth) {
    view.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              if (v.isEnabled())
                v.animate()
                    .scaleX(depth)
                    .scaleY(depth)
                    .setStartDelay(0)
                    .setDuration(90)
                    .setInterpolator(EASE)
                    .start();
              break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              v.animate()
                  .scaleX(1)
                  .scaleY(1)
                  .setStartDelay(0)
                  .setDuration(260)
                  .setInterpolator(SPRING)
                  .start();
              break;
            default:
              break;
          }
          return false;
        });
  }

  /** The Dock bounce: a short hop when an item is chosen. */
  static void bounce(View view) {
    if (!enabled()) return;
    view.animate()
        .translationY(-view.getResources().getDisplayMetrics().density * 6)
        .scaleX(1)
        .scaleY(1)
        .setStartDelay(0)
        .setDuration(120)
        .setInterpolator(new DecelerateInterpolator())
        .withEndAction(
            () ->
                view.animate()
                    .translationY(0)
                    .setDuration(320)
                    .setInterpolator(new BounceInterpolator())
                    .start())
        .start();
  }

  /** A soft accent glow that fades away — marks what was just edited or found. */
  static void flash(View view, int color, float radius) {
    if (!enabled()) return;
    GradientDrawable glow = new GradientDrawable();
    glow.setColor(color);
    glow.setCornerRadius(radius);
    view.setForeground(glow);
    ObjectAnimator fade = ObjectAnimator.ofInt(glow, "alpha", 255, 0);
    fade.setStartDelay(450);
    fade.setDuration(1200);
    fade.setInterpolator(EASE);
    fade.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (view.getForeground() == glow) view.setForeground(null);
          }
        });
    fade.start();
  }
}
