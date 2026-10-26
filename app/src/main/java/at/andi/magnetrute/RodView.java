package at.andi.magnetrute;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** Animierte, rein virtuelle Wuenschelruten – Ausschlag nach gemessener Abweichung. */
public final class RodView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0f;
    private float target = 0f;
    private boolean hasReference;
    private boolean valid = true;

    public RodView(Context c) { super(c); }
    public void setState(double f, boolean ref, boolean validValue) {
        target = (float) SensorMath.clamp(f, 0, 1);
        hasReference = ref;
        valid = validValue;
        progress += (target - progress) * 0.23f;
        if (Math.abs(target - progress) < .002f) progress = target;
        invalidate();
    }
    @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (205 * getResources().getDisplayMetrics().density), MeasureSpec.EXACTLY));
    }
    private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float sw) {
        p.setColor(color); p.setStrokeWidth(sw); p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND); p.setShader(null);
        c.drawLine(x1, y1, x2, y2, p);
        p.setStyle(Paint.Style.FILL);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        p.setShader(new LinearGradient(0,0,0,h, 0xff17243a, 0xff0b1323, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(1*d, 1*d, w-1*d, h-1*d, 20*d, 20*d, p);
        p.setShader(null);
        float y = h * .80f;
        float lX = w * .29f, rX = w * .71f;
        float len = h * .60f;
        float angle = (float) (Math.toRadians(67) * progress);
        float shift = (float) Math.sin(angle) * len;
        float rise = (float) Math.cos(angle) * len;
        int rodColor = !hasReference || !valid ? 0xff8293a9 : (progress > .72f ? 0xffffbe68 : 0xff67e7d4);
        // Unterlegter Schatten und symmetrisch laufende Ruten.
        line(canvas, lX+1*d, y+3*d, lX+shift+1*d, y-rise+3*d, 0x77000000, 9*d);
        line(canvas, rX+1*d, y+3*d, rX-shift+1*d, y-rise+3*d, 0x77000000, 9*d);
        line(canvas, lX, y, lX+shift, y-rise, rodColor, 5*d);
        line(canvas, rX, y, rX-shift, y-rise, rodColor, 5*d);
        p.setColor(0xffc6d6ec);
        canvas.drawCircle(lX, y, 10*d, p); canvas.drawCircle(rX, y, 10*d, p);
        p.setColor(0xff15243b);
        canvas.drawCircle(lX, y, 5*d, p); canvas.drawCircle(rX, y, 5*d, p);
        p.setTextAlign(Paint.Align.CENTER); p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        p.setTextSize(14*d); p.setColor(0xffa4b7d0);
        String title = !hasReference ? "Referenz wird vorbereitet" : !valid ? "Messung nicht verfuegbar" :
                (progress > .75f ? "DEUTLICHER AUSSCHLAG" : "Ruten reagieren auf ΔB");
        canvas.drawText(title, w/2, 25*d, p);
        p.setTextSize(11*d); p.setColor(0xff8197b6);
        canvas.drawText("Symbolische Anzeige • keine Objekterkennung", w/2, h-9*d, p);
    }
}
