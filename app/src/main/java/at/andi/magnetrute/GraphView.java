package at.andi.magnetrute;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import java.util.Locale;

/** Einfache Ringpuffer-Livekurve: Delta oder Magnetometer X/Y/Z/|B|. */
public final class GraphView extends View {
    private static final int N = 600; // 60 Sekunden bei 10 Hz
    private final double[][] data = new double[5][N]; // delta, x, y, z, total
    private int count = 0, next = 0;
    private boolean axesMode = false;
    private double threshold = 3;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GraphView(Context ctx) { super(ctx); }
    public boolean isAxesMode() { return axesMode; }
    public void toggleMode() { axesMode = !axesMode; invalidate(); }
    public void setThreshold(double t) { threshold = t; invalidate(); }
    public void add(double delta, double x, double y, double z, double mag) {
        data[0][next] = delta;
        data[1][next] = x;
        data[2][next] = y;
        data[3][next] = z;
        data[4][next] = mag;
        next = (next+1)%N;
        if(count<N) count++;
        invalidate();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int hd = (int) (230*getResources().getDisplayMetrics().density);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(hd, MeasureSpec.EXACTLY));
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        p.setColor(0xff111c30);
        c.drawRoundRect(0,0,w,h,14*d,14*d,p);
        float left=50*d, right=w-15*d, top=33*d, bottom=h-29*d;
        p.setTextSize(12*d); p.setTypeface(Typeface.DEFAULT_BOLD); p.setColor(0xffd0e5ff);
        c.drawText(axesMode ? "Sensorachsen (geraetefest)" : "Abweichung zur Referenz", 13*d, 20*d,p);
        double min = axesMode ? Double.POSITIVE_INFINITY : -threshold*1.2;
        double max = axesMode ? Double.NEGATIVE_INFINITY : threshold*1.2;
        int visible = Math.min(N, count);
        for (int n=0;n<visible;n++) {
            int idx=(next-visible+n+N)%N;
            for (int k=(axesMode?1:0); k<(axesMode?5:1); k++) {
                double v=data[k][idx];
                if (!Double.isFinite(v)) continue;
                min=Math.min(min,v); max=Math.max(max,v);
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {min=-10;max=10;}
        if (max-min<1) {double mid=(max+min)*.5; min=mid-1; max=mid+1;}
        double pad=(max-min)*.08; max+=pad; min-=pad;
        p.setStrokeWidth(d); p.setTextSize(10*d); p.setTypeface(Typeface.DEFAULT);
        for (int i=0;i<=4;i++) {
            float y=top+(bottom-top)*i/4f;
            p.setColor(0xff26364b);
            c.drawLine(left,y,right,y,p);
            p.setColor(0xff9fb1c8);
            String v=String.format(Locale.US,"%.1f",max-(max-min)*i/4.0);
            c.drawText(v,4*d,y+3*d,p);
        }
        if (!axesMode) {
            float mid=toY(0,min,max,top,bottom);
            p.setColor(0xff65738c);p.setStrokeWidth(1.5f*d);c.drawLine(left,mid,right,mid,p);
            float up=toY(threshold,min,max,top,bottom), dn=toY(-threshold,min,max,top,bottom);
            p.setColor(0xff765840);p.setStrokeWidth(d);c.drawLine(left,up,right,up,p);c.drawLine(left,dn,right,dn,p);
        }
        int[] colors={0xff72e5d7,0xffec7b86,0xff74bef2,0xffffc170,0xffffffff};
        for (int k=axesMode?1:0;k<(axesMode?5:1);k++) {
            Path path=new Path(); boolean started=false;
            for(int n=0;n<visible;n++) {
                int idx=(next-visible+n+N)%N;
                double v=data[k][idx]; if (!Double.isFinite(v)) {started=false;continue;}
                float x=right-(visible-1-n)*(right-left)/(N-1);
                float y=toY(v,min,max,top,bottom);
                if(!started){path.moveTo(x,y);started=true;}else path.lineTo(x,y);
            }
            p.setStyle(Paint.Style.STROKE);p.setColor(colors[k]);p.setStrokeWidth((k==0?2.5f:1.4f)*d);
            c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
        }
        p.setColor(0xff94a8be);p.setTextSize(10*d);
        c.drawText("-60 s",left,bottom+16*d,p); c.drawText("jetzt",right-26*d,bottom+16*d,p);
        if(axesMode) {
            p.setTextSize(10*d);
            String[] labels={"X", "Y", "Z", "|B|"};
            for(int i=0;i<4;i++) {
                float x=left+i*(right-left)/4f;
                p.setColor(colors[i+1]);c.drawText(labels[i],x,top-6*d,p);
            }
        }
    }
    private static float toY(double val, double min, double max, float top,float bottom) {
        return (float)(bottom - (val-min)/(max-min)*(bottom-top));
    }
}
