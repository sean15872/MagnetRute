package at.andi.magnetrute;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

/** Touch coordinates always map back to image pixels, independently of display size. */
public final class PlanEditorView extends View {
    public enum Mode { CORNERS, CROP, SCALE, PREVIEW }
    private final Bitmap image;
    private final Mode mode;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double[] points;
    private float factor=1,offsetX,offsetY;
    private int handle;

    public PlanEditorView(Context context,Bitmap bitmap,Mode selectionMode,double[] restored) {
        super(context); image=bitmap; mode=selectionMode;
        double w=bitmap.getWidth()-1,h=bitmap.getHeight()-1;
        points=restored!=null?restored.clone():new double[]{w*.05,h*.05,w*.95,h*.05,w*.95,h*.95,w*.05,h*.95};
        if(restored==null && mode==Mode.CROP) { points[0]=0;points[1]=0;points[2]=w;points[3]=h; }
        if(restored==null && mode==Mode.SCALE) { points[0]=w*.25;points[1]=h*.5;points[2]=w*.75;points[3]=h*.5; }
        setContentDescription(mode==Mode.CORNERS?"Vier Planecken ziehen: oben links, oben rechts, unten rechts, unten links":
            mode==Mode.CROP?"Ausschnitt durch Ziehen eines Rechtecks wählen":
            mode==Mode.SCALE?"Zwei Endpunkte der bekannten Strecke ziehen":"Gespeicherter Grundriss");
        setFocusable(true);
    }
    public double[] points() { return points.clone(); }
    public Rect crop() {
        return new Rect((int)Math.floor(Math.min(points[0],points[2])),(int)Math.floor(Math.min(points[1],points[3])),
            Math.min(image.getWidth(),(int)Math.ceil(Math.max(points[0],points[2]))+1),
            Math.min(image.getHeight(),(int)Math.ceil(Math.max(points[1],points[3]))+1));
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        factor=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
        offsetX=(getWidth()-image.getWidth()*factor)/2;offsetY=(getHeight()-image.getHeight()*factor)/2;
        c.drawColor(0xff0a1020);
        c.save(); c.translate(offsetX,offsetY); c.scale(factor,factor);
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);paint.setFilterBitmap(true);
        c.drawBitmap(image,0,0,paint);
        paint.setColor(0xff00a898);paint.setStrokeWidth(3/factor);paint.setStyle(Paint.Style.STROKE);
        if(mode==Mode.CORNERS) {
            for(int i=0;i<4;i++) { int j=(i+1)%4; c.drawLine((float)points[i*2],(float)points[i*2+1],(float)points[j*2],(float)points[j*2+1],paint); }
        } else if(mode==Mode.CROP) c.drawRect(crop(),paint);
        else if(mode==Mode.SCALE) c.drawLine((float)points[0],(float)points[1],(float)points[2],(float)points[3],paint);
        int count=mode==Mode.CORNERS?4:mode==Mode.PREVIEW?0:2;
        for(int i=0;i<count;i++) {
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xff004f49);
            c.drawCircle((float)points[i*2],(float)points[i*2+1],12/factor,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(14/factor);paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(i+1),(float)points[i*2],(float)points[i*2+1]+5/factor,paint);
        }
        c.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(mode==Mode.PREVIEW || !isEnabled()) return false;
        double x=Math.max(0,Math.min(image.getWidth()-1,(e.getX()-offsetX)/factor));
        double y=Math.max(0,Math.min(image.getHeight()-1,(e.getY()-offsetY)/factor));
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            handle=0; double best=Double.MAX_VALUE;
            int count=mode==Mode.CORNERS?4:2;
            for(int i=0;i<count;i++) {
                double d=Math.hypot(x-points[i*2],y-points[i*2+1]);
                if(d<best) { best=d;handle=i; }
            }
            // Cropping may begin anywhere: drag a new rectangle, or adjust a nearby handle.
            if(mode==Mode.CROP && best*factor>30*getResources().getDisplayMetrics().density) {
                points[0]=x;points[1]=y;handle=1;
            }
        } else if(e.getActionMasked()!=MotionEvent.ACTION_MOVE && e.getActionMasked()!=MotionEvent.ACTION_UP) return true;
        points[handle*2]=x;points[handle*2+1]=y;invalidate();
        if(e.getActionMasked()==MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { super.performClick();return true; }
}
