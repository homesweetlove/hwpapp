package com.homesweetlove.hwpapp;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** All coordinates use the rendered page's pixel space; screen resizing never moves a mark. */
final class PageCanvas extends View {
    static final class Mark {
        final Path path;
        final String text;
        final float x, y;
        final Bitmap image;
        final float width, height;
        Mark(Path path) { this.path = path; text = null; x = y = 0; image = null; width = height = 0; }
        Mark(String text, float x, float y) { this.text = text; this.x = x; this.y = y; path = null; image = null; width = height = 0; }
        Mark(Bitmap image, float x, float y, float width, float height) { this.image = image; this.x = x; this.y = y; this.width = width; this.height = height; path = null; text = null; }
    }
    Bitmap pendingImage;
    float signatureWidth = .25f;
    Bitmap page;
    final List<Mark> marks;
    final Runnable changed;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF bounds = new RectF();
    Path current;
    boolean ink;
    String pendingText;
    PageCanvas(Context context, Bitmap page, List<Mark> marks, Runnable changed) {
        super(context); this.page = page; this.marks = marks; this.changed = changed;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * page.getHeight() / (float) page.getWidth()));
    }
    protected void onDraw(Canvas canvas) {
        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(page, null, bounds, null);
        canvas.save(); canvas.scale(getWidth() / (float) page.getWidth(), getHeight() / (float) page.getHeight());
        drawMarks(canvas); canvas.restore();
    }
    private void drawMarks(Canvas canvas) {
        paint.setColor(Color.rgb(35, 82, 145));
        paint.setStrokeWidth(Math.max(2f, page.getWidth() / 350f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        for (Mark mark : marks) {
            if (mark.image != null) { canvas.drawBitmap(mark.image, null, new RectF(mark.x, mark.y, mark.x + mark.width, mark.y + mark.height), null); }
            else if (mark.path != null) { paint.setStyle(Paint.Style.STROKE); canvas.drawPath(mark.path, paint); }
            else { paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(page.getWidth() / 35f); canvas.drawText(mark.text, mark.x, mark.y, paint); }
        }
        if (current != null) { paint.setStyle(Paint.Style.STROKE); canvas.drawPath(current, paint); }
    }
    static Bitmap renderLayer(Bitmap page, List<Mark> marks) {
        Bitmap layer = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layer); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(35, 82, 145)); paint.setStrokeWidth(Math.max(2f, page.getWidth() / 350f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        for (Mark mark : marks) {
            if (mark.image != null) { canvas.drawBitmap(mark.image, null, new RectF(mark.x, mark.y, mark.x + mark.width, mark.y + mark.height), null); }
            else if (mark.path != null) { paint.setStyle(Paint.Style.STROKE); canvas.drawPath(mark.path, paint); }
            else { paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(page.getWidth() / 35f); canvas.drawText(mark.text, mark.x, mark.y, paint); }
        }
        return layer;
    }
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (!ink && pendingText == null && pendingImage == null) return super.onTouchEvent(event);
        float x = Math.max(0, Math.min(page.getWidth(), event.getX() * page.getWidth() / getWidth()));
        float y = Math.max(0, Math.min(page.getHeight(), event.getY() * page.getHeight() / getHeight()));
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            if (pendingImage != null) {
                float width = page.getWidth() * signatureWidth, height = width * pendingImage.getHeight() / pendingImage.getWidth();
                if (height > page.getHeight()) { width *= page.getHeight() / height; height = page.getHeight(); }
                float left = Math.max(0, Math.min(page.getWidth() - width, x)), top = Math.max(0, Math.min(page.getHeight() - height, y));
                marks.add(new Mark(pendingImage, left, top, width, height)); pendingImage = null; changed.run(); invalidate(); return true;
            }
            if (pendingText != null) {
                marks.add(new Mark(pendingText, x, y)); pendingText = null; changed.run(); invalidate();
                return true;
            }
            current = new Path(); current.moveTo(x, y); current.lineTo(x + .1f, y + .1f);
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && current != null) current.lineTo(x, y);
        else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (current != null) { marks.add(new Mark(current)); current = null; changed.run(); }
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { current = null; getParent().requestDisallowInterceptTouchEvent(false); }
        invalidate(); return true;
    }
    public boolean performClick() { super.performClick(); return true; }
}
