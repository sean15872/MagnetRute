package at.andi.magnetrute;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Raster map renderer with one WGS84/Web-Mercator transform for tiles, GPS and annotations.
 * The offline flag is a network boundary: missing local tiles never cause HTTP requests.
 */
public final class OfflineMapView extends View {
    public interface Listener {
        void tap(double latitude,double longitude);
        void longPress(double latitude,double longitude);
        void viewport(double latitude,double longitude,int zoom,float rotation);
        default void pointMoved(int index,double latitude,double longitude) { }
    }

    private static final ExecutorService TILE_WORKERS=Executors.newFixedThreadPool(2,r -> {
        Thread t=new Thread(r,"offline-map-tiles");t.setDaemon(true);return t;
    });
    private static final int PREVIEW_BYTES=64*1024*1024;
    interface PreviewSource { Bitmap load(String source,int zoom,int x,int y) throws IOException; }
    private final PreviewSource previewSource;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object tileLock=new Object();
    private final Object networkLock=new Object();
    private final Set<HttpURLConnection> connections=new HashSet<>();
    private final Set<String> pending=new HashSet<>();
    private final LruCache<String,Bitmap> tileCache=new LruCache<String,Bitmap>(PREVIEW_BYTES) {
        @Override protected int sizeOf(String key,Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Set<String> missing=new HashSet<>();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scales;
    private volatile boolean offlineActive=true;
    private volatile int generation;
    private OfflineTiles localTiles;
    private File mapFile;
    private JSONObject project=new JSONObject();
    private String source="basemap-ortho",attribution="Datenquelle: basemap.at";
    private double latitude=47.1457,longitude=14.0328;
    private int zoom=18,minZoom=0,maxZoom=20;
    private static final int MAX_VISUAL_ZOOM=22;
    private float rotation;
    private float pinchAngle;
    private boolean rotating,follow=true,showBase=true;
    private Listener listener;
    private List<JSONObject> features=Collections.emptyList(),track=Collections.emptyList(),samples=Collections.emptyList();
    private List<FieldGrid.Cell> cells=Collections.emptyList();
    private List<double[]> editing=Collections.emptyList();
    private boolean editingClosed,polygons=true,markers=true,lines=true,trackLayer=true,heatmap=true;
    private String displayMode="off";
    private double opacity=.55,scale=5,cellMeters=10,smoothingMeters,threshold;
    private GpsQuality.Fix location;
    private int draggedPoint=-1;
    private boolean moved;
    private float downX,downY;
    private double downWorldX,downWorldY;

    public OfflineMapView(Context context) {
        this(context,null);
    }
    OfflineMapView(Context context,PreviewSource testPreviewSource) {
        super(context);
        previewSource=testPreviewSource;
        setContentDescription("Offline-Karte. Verschieben mit einem Finger; zwei Finger zoomen und drehen. Antippen setzt einen Punkt.");
        setFocusable(true);
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if(!moved && listener!=null) { double[] p=coordinateAt(e.getX(),e.getY());listener.tap(p[0],p[1]); }
                performClick();return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if(!moved && draggedPoint<0 && listener!=null) {
                    double[] p=coordinateAt(e.getX(),e.getY());listener.longPress(p[0],p[1]);
                }
            }
        });
        scales=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float accumulated=1;
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { accumulated=1;return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                accumulated*=d.getScaleFactor();
                if(accumulated>1.35f) { changeZoom(zoom+1,d.getFocusX(),d.getFocusY());accumulated=1; }
                else if(accumulated<.74f) { changeZoom(zoom-1,d.getFocusX(),d.getFocusY());accumulated=1; }
                return true;
            }
        });
    }

    public void setListener(Listener value) { listener=value; }

    public void setProject(JSONObject value,File file,boolean offline) {
        if(value==null) value=new JSONObject();
        String nextSource=value.optString("source","basemap-ortho");
        boolean sameFile=mapFile==null?file==null:mapFile.equals(file);
        boolean changed=!sameFile || offlineActive!=offline || !source.equals(nextSource)
            || !project.optString("id").equals(value.optString("id"));
        if(changed) {
            generation++;
            synchronized(networkLock) {
                offlineActive=offline;
                // An already queued online preview cannot keep fetching after an offline switch.
                for(HttpURLConnection connection:connections) connection.disconnect();
                connections.clear();
            }
            synchronized(tileLock) {
                if(localTiles!=null) localTiles.close();localTiles=null;
                mapFile=file;
                if(file!=null && file.isFile()) {
                    try { localTiles=new OfflineTiles(file); } catch(Exception ignored) { localTiles=null; }
                }
            }
            tileCache.evictAll();synchronized(missing) { missing.clear(); }
            synchronized(pending) { pending.clear(); }
            latitude=validLatitude(value.optDouble("latitude",latitude));
            longitude=validLongitude(value.optDouble("longitude",longitude));
            zoom=Math.max(0,Math.min(MAX_VISUAL_ZOOM,value.optInt("zoom",zoom)));
            rotation=normalizeRotation((float)value.optDouble("rotation",0));
        }
        project=value;source=nextSource;offlineActive=offline;
        attribution=value.optString("attribution",source.equals("gis-stmk")?
            "Datenquelle: CC-BY-4.0: Land Steiermark - data.steiermark.gv.at":"Datenquelle: basemap.at");
        minZoom=0;maxZoom=20;
        synchronized(tileLock) {
            if(localTiles==null && mapFile!=null && mapFile.isFile()) {
                try { localTiles=new OfflineTiles(mapFile); } catch(Exception ignored) { }
            }
            if(localTiles!=null) {
                try {
                    JSONObject metadata=localTiles.metadata();
                    minZoom=metadata.optInt("minZoom",metadata.optInt("minzoom",0));
                    maxZoom=metadata.optInt("maxZoom",metadata.optInt("maxzoom",20));
                    if(attribution.isEmpty()) attribution=metadata.optString("attribution",attribution);
                } catch(Exception ignored) { }
            }
        }
        invalidate();
    }

    public void setFeatures(List<JSONObject> value) { features=value==null?Collections.emptyList():new ArrayList<>(value);invalidate(); }
    public void setTrack(List<JSONObject> value) { track=value==null?Collections.emptyList():new ArrayList<>(value);invalidate(); }
    public void setSamples(List<JSONObject> value,String mode,double alpha,double range,double gridMeters) {
        samples=value==null?Collections.emptyList():new ArrayList<>(value);displayMode=mode==null?"off":mode;
        opacity=Double.isFinite(alpha)?Math.max(0,Math.min(1,alpha)):.55;
        scale=Double.isFinite(range)&&range>0?range:5;
        cellMeters=Double.isFinite(gridMeters)?Math.max(1,Math.min(100000,gridMeters)):10;
        rebuildCells();invalidate();
    }
    public void setSmoothingMeters(double value) { smoothingMeters=Double.isFinite(value)?Math.max(0,Math.min(1000,value)):0;rebuildCells();invalidate(); }
    public void setThreshold(double value) { threshold=Double.isFinite(value)?Math.max(0,value):0;invalidate(); }
    public List<FieldGrid.Cell> displayedCells() { return cells; }
    /** Explicit retry after a connectivity change or a completed package update. */
    public void refreshTiles() {
        generation++;tileCache.evictAll();synchronized(missing) { missing.clear(); }
        synchronized(pending) { pending.clear(); }invalidate();
    }
    public void setBaseVisible(boolean visible) { showBase=visible;invalidate(); }
    public void setLocation(GpsQuality.Fix fix) {
        // Never advertise a last historical fix as a live GPS position.
        long now=SystemClock.elapsedRealtimeNanos();
        location=fix!=null && GeoMath.valid(fix.lat,fix.lon) && fix.accuracy>0
            && Double.isFinite(fix.accuracy) && fix.elapsedNs>0 && fix.elapsedNs<=now
            && now-fix.elapsedNs<=GpsQuality.MAX_AGE_NS?fix:null;
        if(follow && location!=null) center(location.lat,location.lon);else invalidate();
    }
    public void setFollow(boolean value) { follow=value;if(value && location!=null) center(location.lat,location.lon); }
    public boolean isFollowing() { return follow; }
    public void center(double lat,double lon) {
        if(!GeoMath.valid(lat,lon)) return;
        latitude=validLatitude(lat);longitude=lon;notifyViewport();invalidate();
    }
    public void setZoom(int value) { changeZoom(value,getWidth()/2f,getHeight()/2f); }
    public double currentLatitude() { return latitude; }
    public double currentLongitude() { return longitude; }
    public int currentZoom() { return zoom; }
    public float currentRotation() { return rotation; }
    public void rotateBy(float value) { if(Float.isFinite(value)) { rotation=normalizeRotation(rotation+value);notifyViewport();invalidate(); } }
    public void setLayers(boolean polygonLayer,boolean markerLayer,boolean lineLayer,boolean pathLayer,boolean heatLayer) {
        polygons=polygonLayer;markers=markerLayer;lines=lineLayer;trackLayer=pathLayer;heatmap=heatLayer;invalidate();
    }
    public void setEditing(List<double[]> coordinatesLonLat,boolean closed) {
        List<double[]> copy=new ArrayList<>();
        if(coordinatesLonLat!=null) for(double[] p:coordinatesLonLat)
            if(p!=null && p.length>=2 && GeoMath.valid(p[1],p[0])) copy.add(new double[]{p[0],p[1]});
        editing=copy;editingClosed=closed;invalidate();
    }

    /** Return order is latitude,longitude, including map rotation. */
    public double[] coordinateAt(float x,float y) {
        double[] origin=GeoMath.world(latitude,longitude,zoom);
        double radians=Math.toRadians(-rotation),dx=x-getWidth()/2.0,dy=y-getHeight()/2.0;
        return GeoMath.coordinate(origin[0]+dx*Math.cos(radians)-dy*Math.sin(radians),
            origin[1]+dx*Math.sin(radians)+dy*Math.cos(radians),zoom);
    }
    /** Draw-space screen point; public for precise feature editing and projection tests. */
    public float[] screenPoint(double lat,double lon) {
        double[] origin=GeoMath.world(latitude,longitude,zoom),point=GeoMath.world(lat,lon,zoom);
        double dx=wrappedDx(point[0]-origin[0]),dy=point[1]-origin[1],radians=Math.toRadians(rotation);
        return new float[]{(float)(getWidth()/2.0+dx*Math.cos(radians)-dy*Math.sin(radians)),
            (float)(getHeight()/2.0+dx*Math.sin(radians)+dy*Math.cos(radians))};
    }

    private void rebuildCells() {
        if("off".equals(displayMode) || "track".equals(displayMode)) { cells=Collections.emptyList();return; }
        List<FieldGrid.Sample> values=new ArrayList<>();
        for(JSONObject sample:samples) {
            JSONObject fix=sample.optJSONObject("fix");if(fix==null) continue;
            double value=sampleValue(sample);
            boolean uncertain=sample.optBoolean("calibrationJump",false) || sample.optBoolean("uncertain",false)
                || !fix.optBoolean("accepted",true) || (sample.has("sensorAccuracy") && sample.optInt("sensorAccuracy")<2);
            values.add(new FieldGrid.Sample(fix.optDouble("lat",Double.NaN),fix.optDouble("lon",Double.NaN),
                fix.optDouble("accuracy",Double.NaN),value,sample.optString("passId",sample.optString("sessionId","pass")),
                sample.optLong("timestampNs",0),uncertain));
        }
        cells=FieldGrid.aggregate(values,cellMeters);
        if(smoothingMeters>0 && !cells.isEmpty()) cells=FieldGrid.smooth(cells,smoothingMeters);
    }
    private double sampleValue(JSONObject sample) {
        double value="absolute".equals(displayMode)?sample.optDouble("total",Double.NaN):sample.optDouble("delta",Double.NaN);
        return "abs-delta".equals(displayMode)?Math.abs(value):value;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);canvas.drawColor(0xff17243a);
        boolean absent=false;
        if(showBase) absent=drawTiles(canvas);
        if(polygons) drawGeometry(canvas,project.optJSONObject("geometry"),0xff73e6ce,"Grundstück",false);
        if(heatmap && !"track".equals(displayMode) && !"off".equals(displayMode)) drawCells(canvas);
        if(trackLayer) drawTrack(canvas);
        if(trackLayer && "track".equals(displayMode)) drawFieldTrack(canvas);
        for(JSONObject feature:features) {
            JSONObject geometry=feature.optJSONObject("geometry");if(geometry==null) continue;
            String type=geometry.optString("type");
            if(("Point".equals(type)&&!markers) || ("LineString".equals(type)&&!lines)
                || ("Polygon".equals(type)&&!polygons)) continue;
            int color=featureColor(feature);
            drawGeometry(canvas,geometry,color,feature.optString("name",feature.optString("type","")),
                "dashed".equals(feature.optString("style","")) || "dotted".equals(feature.optString("style","")));
        }
        drawEditing(canvas);drawLocation(canvas);drawAttribution(canvas,absent);
    }

    private boolean drawTiles(Canvas canvas) {
        double[] center=GeoMath.world(latitude,longitude,zoom);
        double radians=Math.toRadians(rotation);
        double extentX=(Math.abs(Math.cos(radians))*getWidth()+Math.abs(Math.sin(radians))*getHeight())/2;
        double extentY=(Math.abs(Math.sin(radians))*getWidth()+Math.abs(Math.cos(radians))*getHeight())/2;
        int firstX=(int)Math.floor((center[0]-extentX)/256),lastX=(int)Math.floor((center[0]+extentX)/256);
        int firstY=(int)Math.floor((center[1]-extentY)/256),lastY=(int)Math.floor((center[1]+extentY)/256),count=1<<zoom;
        boolean absent=false;
        canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.rotate(rotation);
        paint.setStyle(Paint.Style.FILL);paint.setFilterBitmap(true);
        for(int row=firstY;row<=lastY;row++) for(int column=firstX;column<=lastX;column++) {
            if(row<0 || row>=count) continue;
            int x=((column%count)+count)%count;
            // Digital magnification uses downloaded source tiles; it does not add detail.
            int nativeZoom=Math.min(zoom,maxZoom);
            int over=zoom-nativeZoom;
            int parentX=x>>over,parentY=row>>over;
            String key=generation+"/"+nativeZoom+"/"+parentX+"/"+parentY;
            Bitmap tile=tileCache.get(key);
            float left=(float)(column*256.0-center[0]),top=(float)(row*256.0-center[1]);
            if(tile!=null && !tile.isRecycled()) {
                int divisor=1<<over,offsetX=x&(divisor-1),offsetY=row&(divisor-1);
                int sx=(int)Math.floor(offsetX*256.0/divisor),sy=(int)Math.floor(offsetY*256.0/divisor);
                int ex=(int)Math.ceil((offsetX+1)*256.0/divisor),ey=(int)Math.ceil((offsetY+1)*256.0/divisor);
                canvas.drawBitmap(tile,new Rect(sx,sy,Math.min(256,Math.max(sx+1,ex)),Math.min(256,Math.max(sy+1,ey))),new RectF(left,top,left+256,top+256),paint);
            } else {
                absent=true;paint.setColor(0xff233149);canvas.drawRect(left,top,left+256,top+256,paint);
                paint.setColor(0xff34435b);canvas.drawRect(left,top,left+128,top+128,paint);canvas.drawRect(left+128,top+128,left+256,top+256,paint);
                requestTile(key,nativeZoom,parentX,parentY);
            }
        }
        canvas.restore();return absent;
    }

    private void requestTile(String key,int z,int x,int y) {
        synchronized(missing) { if(missing.contains(key)) return; }
        synchronized(pending) { if(pending.contains(key) || pending.size()>80) return;pending.add(key); }
        int requestGeneration=generation;
        boolean onlineAllowed=!offlineActive;
        String requestSource=source;
        TILE_WORKERS.execute(() -> {
            Bitmap bitmap=null;
            try {
                synchronized(tileLock) {
                    if(requestGeneration==generation && localTiles!=null) bitmap=localTiles.get(z,x,y);
                }
                if(bitmap==null && onlineAllowed && !offlineActive && requestGeneration==generation)
                    bitmap=previewSource==null?preview(requestSource,z,x,y,requestGeneration):previewSource.load(requestSource,z,x,y);
            } catch(Exception ignored) { }
            Bitmap loaded=bitmap;
            main.post(() -> {
                synchronized(pending) { pending.remove(key); }
                if(requestGeneration!=generation) { if(loaded!=null) loaded.recycle();return; }
                if(loaded!=null) tileCache.put(key,loaded);
                else synchronized(missing) { missing.add(key); }
                invalidate();
            });
        });
    }

    private Bitmap preview(String requestSource,int z,int x,int y,int requestGeneration) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(OfflineTiles.previewUrl(requestSource,z,x,y)).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","MagnetRute/0.4 (bounded map preparation)");
        synchronized(networkLock) {
            if(offlineActive || requestGeneration!=generation) { connection.disconnect();return null; }
            connections.add(connection);
        }
        try {
            if(connection.getResponseCode()!=200) throw new IOException("Kartenquelle nicht erreichbar");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) {
                    if(offlineActive || requestGeneration!=generation) return null;
                    if(bytes.size()+n>1024*1024) throw new IOException("Kartenkachel zu groß");bytes.write(buffer,0,n);
                }
            }
            byte[] data=bytes.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,bounds);
            if(bounds.outWidth!=256 || bounds.outHeight!=256) throw new IOException("Ungültige Kartenkachel");
            return BitmapFactory.decodeByteArray(data,0,data.length);
        } finally { synchronized(networkLock) { connections.remove(connection); }connection.disconnect(); }
    }

    private void drawGeometry(Canvas canvas,JSONObject geometry,int color,String name,boolean dashed) {
        if(geometry==null) return;String type=geometry.optString("type");JSONArray coordinates=geometry.optJSONArray("coordinates");
        if(coordinates==null) return;
        paint.setColor(color);paint.setStrokeWidth(dp(3));paint.setStyle(Paint.Style.STROKE);
        paint.setPathEffect(dashed?new android.graphics.DashPathEffect(new float[]{dp(8),dp(5)},0):null);
        if("Point".equals(type)) {
            if(coordinates.length()<2) return;double lon=coordinates.optDouble(0,Double.NaN),lat=coordinates.optDouble(1,Double.NaN);
            if(!GeoMath.valid(lat,lon)) return;float[] point=screenPoint(lat,lon);
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(point[0],point[1],dp(7),paint);
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawCircle(point[0],point[1],dp(7),paint);
            label(canvas,name,point[0]+dp(10),point[1]-dp(8),color);
        } else if("LineString".equals(type) || "Polygon".equals(type)) {
            JSONArray ring="Polygon".equals(type)?coordinates.optJSONArray(0):coordinates;
            if(ring==null) return;Path path=new Path();float[] first=null;
            for(int i=0;i<ring.length();i++) {
                JSONArray p=ring.optJSONArray(i);if(p==null || p.length()<2) continue;
                double lon=p.optDouble(0,Double.NaN),lat=p.optDouble(1,Double.NaN);if(!GeoMath.valid(lat,lon)) continue;
                float[] point=screenPoint(lat,lon);if(first==null) { first=point;path.moveTo(point[0],point[1]); }else path.lineTo(point[0],point[1]);
            }
            if(first==null) return;
            if("Polygon".equals(type)) { path.close();paint.setStyle(Paint.Style.FILL);paint.setColor(withAlpha(color,35));canvas.drawPath(path,paint);paint.setColor(color);paint.setStyle(Paint.Style.STROKE); }
            canvas.drawPath(path,paint);paint.setPathEffect(null);label(canvas,name,first[0]+dp(4),first[1]-dp(5),color);
        }
        paint.setPathEffect(null);
    }

    private void drawTrack(Canvas canvas) {
        JSONObject previous=null;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(0xff73e6ce);
        for(JSONObject fix:track) {
            if(!usableFix(fix)) { previous=null;continue; }
            if(previous!=null && !fix.optBoolean("breakTrack",true)
                && continuous(previous,fix)) {
                float[] a=screenPoint(previous.optDouble("lat"),previous.optDouble("lon")),b=screenPoint(fix.optDouble("lat"),fix.optDouble("lon"));
                canvas.drawLine(a[0],a[1],b[0],b[1],paint);
            }
            previous=fix;
        }
    }
    private boolean continuous(JSONObject previous,JSONObject current) {
        long before=previous.optLong("elapsedNs",previous.optLong("timestampNs",0)),after=current.optLong("elapsedNs",current.optLong("timestampNs",0));
        if(before>0 && (after<=before || after-before>GpsQuality.MAX_AGE_NS)) return false;
        if(before>0 && GeoMath.distance(previous.optDouble("lat"),previous.optDouble("lon"),current.optDouble("lat"),current.optDouble("lon"))>
                (after-before)/1_000_000_000.0*12+previous.optDouble("accuracy",0)+current.optDouble("accuracy",0)) return false;
        return !current.has("sessionId") || !previous.has("sessionId") || current.optString("sessionId").equals(previous.optString("sessionId"));
    }
    private static boolean usableFix(JSONObject fix) {
        return usableFix(fix,false);
    }
    private static boolean usableFix(JSONObject fix,boolean associatedSample) {
        return fix!=null && fix.optBoolean("accepted",associatedSample) && GeoMath.valid(fix.optDouble("lat",Double.NaN),fix.optDouble("lon",Double.NaN))
            && fix.optDouble("accuracy",Double.POSITIVE_INFINITY)>0 && fix.optDouble("accuracy",Double.POSITIVE_INFINITY)<=50;
    }
    private void drawFieldTrack(Canvas canvas) {
        JSONObject previous=null,previousFix=null;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(5));
        for(JSONObject sample:samples) {
            JSONObject fix=sample.optJSONObject("fix");
            if(!usableFix(fix,true) || sample.optBoolean("calibrationJump",false) || sample.optBoolean("uncertain",false)) { previous=null;previousFix=null;continue; }
            double value=sample.optDouble("delta",Double.NaN);
            if(previous!=null && previous.optString("passId").equals(sample.optString("passId"))
                && previous.optInt("segmentId")==sample.optInt("segmentId") && continuous(previousFix,fix)
                && !fix.optBoolean("breakTrack",false) && Double.isFinite(value)) {
                float[] a=screenPoint(previousFix.optDouble("lat"),previousFix.optDouble("lon")),b=screenPoint(fix.optDouble("lat"),fix.optDouble("lon"));
                paint.setColor(fieldColor(value));canvas.drawLine(a[0],a[1],b[0],b[1],paint);
            }
            previous=sample;previousFix=fix;
        }
    }
    private void drawCells(Canvas canvas) {
        double resolution=GeoMath.metersPerPixel(latitude,zoom);
        for(FieldGrid.Cell cell:cells) {
            if(Math.abs(cell.value)<threshold) continue;float[] point=screenPoint(cell.lat,cell.lon);
            float radius=(float)(cell.cellMeters/resolution/2);
            paint.setStyle(Paint.Style.FILL);paint.setColor(withAlpha(fieldColor(cell.value),(int)Math.round(255*opacity)));
            canvas.save();canvas.rotate(rotation,point[0],point[1]);canvas.drawRect(point[0]-radius,point[1]-radius,point[0]+radius,point[1]+radius,paint);canvas.restore();
            if(radius>dp(15)) label(canvas,cell.passes+"× / "+cell.count,point[0]-radius+dp(2),point[1]+dp(4),Color.WHITE);
        }
    }
    private int fieldColor(double value) {
        double fraction="absolute".equals(displayMode)||"abs-delta".equals(displayMode)?value/scale:(value/scale+1)/2;
        fraction=Math.max(0,Math.min(1,fraction));
        if(fraction<.5) return interpolate(0xff246bfe,0xffffe16a,fraction*2);
        return interpolate(0xffffe16a,0xffed3d30,(fraction-.5)*2);
    }
    private void drawEditing(Canvas canvas) {
        if(editing.isEmpty()) return;Path path=new Path();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(0xffffcf6b);
        for(int i=0;i<editing.size();i++) { double[] p=editing.get(i);float[] point=screenPoint(p[1],p[0]);if(i==0) path.moveTo(point[0],point[1]);else path.lineTo(point[0],point[1]); }
        if(editingClosed && editing.size()>2) path.close();canvas.drawPath(path,paint);
        for(int i=0;i<editing.size();i++) {
            double[] p=editing.get(i);float[] point=screenPoint(p[1],p[0]);paint.setStyle(Paint.Style.FILL);paint.setColor(0xffffcf6b);canvas.drawCircle(point[0],point[1],dp(10),paint);
            label(canvas,String.valueOf(i+1),point[0]-dp(3),point[1]+dp(4),0xff102035);
        }
    }
    private void drawLocation(Canvas canvas) {
        if(location==null) return;long now=SystemClock.elapsedRealtimeNanos();
        if(now<location.elapsedNs || now-location.elapsedNs>GpsQuality.MAX_AGE_NS) return;
        float[] point=screenPoint(location.lat,location.lon);float radius=(float)(location.accuracy/GeoMath.metersPerPixel(location.lat,zoom));
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x44278aff);canvas.drawCircle(point[0],point[1],radius,paint);
        paint.setColor(0xff278aff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));canvas.drawCircle(point[0],point[1],radius,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(point[0],point[1],dp(8),paint);paint.setColor(0xff1878e9);canvas.drawCircle(point[0],point[1],dp(6),paint);
    }
    private void drawAttribution(Canvas canvas,boolean absent) {
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xdd0a1020);canvas.drawRect(0,getHeight()-dp(40),getWidth(),getHeight(),paint);
        label(canvas,attribution.isEmpty()?"Datenquelle unbekannt":attribution,dp(5),getHeight()-dp(23),Color.WHITE);
        boolean available; synchronized(tileLock) { available=localTiles!=null; }
        String state=offlineActive?(!available?"Offline · Kartenpaket fehlt":zoom>maxZoom?"Offline · digital vergrößert (keine neuen Bilddetails)":zoom<minZoom?"Offline · Zoom nicht gespeichert":absent?"Offline · Kachel fehlt / außerhalb des Pakets":"Offline · lokale Karte"):
            "Online-Vorbereitung · noch keine Offline-Zusage";
        label(canvas,state,dp(5),getHeight()-dp(7),0xffb7cce7);
    }
    private void label(Canvas canvas,String value,float x,float y,int color) {
        paint.setStyle(Paint.Style.FILL);paint.setPathEffect(null);paint.setColor(color);paint.setTextSize(dp(11));paint.setTextAlign(Paint.Align.LEFT);
        if(value!=null && !value.isEmpty()) canvas.drawText(value,x,y,paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled()) return false;
        scales.onTouchEvent(event);
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
            downX=event.getX();downY=event.getY();moved=false;draggedPoint=-1;
            double[] origin=GeoMath.world(latitude,longitude,zoom);downWorldX=origin[0];downWorldY=origin[1];
            for(int i=0;i<editing.size();i++) { double[] p=editing.get(i);float[] point=screenPoint(p[1],p[0]);if(Math.hypot(point[0]-downX,point[1]-downY)<dp(26)) { draggedPoint=i;break; } }
        } else if(action==MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount()>=2) {
            moved=true;draggedPoint=-1;rotating=true;pinchAngle=angle(event);
        } else if(action==MotionEvent.ACTION_MOVE) {
            if(event.getPointerCount()>=2) {
                if(rotating) { float next=angle(event);rotation=normalizeRotation(rotation+normalizeRotation(next-pinchAngle));pinchAngle=next;notifyViewport();invalidate(); }
                moved=true;
            } else if(!scales.isInProgress()) {
                float dx=event.getX()-downX,dy=event.getY()-downY;
                if(Math.hypot(dx,dy)>dp(5)) moved=true;
                if(draggedPoint>=0) {
                    double[] point=coordinateAt(event.getX(),event.getY());editing.set(draggedPoint,new double[]{point[1],point[0]});
                    if(listener!=null) listener.pointMoved(draggedPoint,point[0],point[1]);invalidate();
                } else if(moved) {
                    follow=false;double radians=Math.toRadians(-rotation);
                    double[] center=GeoMath.coordinate(downWorldX-dx*Math.cos(radians)+dy*Math.sin(radians),
                        downWorldY-dx*Math.sin(radians)-dy*Math.cos(radians),zoom);
                    latitude=center[0];longitude=center[1];notifyViewport();invalidate();
                }
            }
        } else if(action==MotionEvent.ACTION_POINTER_UP) {
            rotating=false;
            int remaining=event.getActionIndex()==0?1:0;downX=event.getX(remaining);downY=event.getY(remaining);
            double[] origin=GeoMath.world(latitude,longitude,zoom);downWorldX=origin[0];downWorldY=origin[1];
        }
        if(draggedPoint<0) gestures.onTouchEvent(event);
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            if(draggedPoint>=0) performClick();draggedPoint=-1;rotating=false;
            if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick();return true; }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        synchronized(tileLock) {
            if(localTiles==null && mapFile!=null && mapFile.isFile()) {
                try { localTiles=new OfflineTiles(mapFile); } catch(IOException ignored) { }
            }
        }
    }
    @Override protected void onDetachedFromWindow() {
        generation++;
        synchronized(networkLock) { for(HttpURLConnection c:connections)c.disconnect();connections.clear(); }
        synchronized(tileLock) { if(localTiles!=null) localTiles.close();localTiles=null; }
        super.onDetachedFromWindow();
    }
    private void changeZoom(int requested,float focusX,float focusY) {
        int next=Math.max(0,Math.min(MAX_VISUAL_ZOOM,requested));if(next==zoom) return;
        double[] anchor=coordinateAt(focusX,focusY);zoom=next;
        double[] origin=GeoMath.world(anchor[0],anchor[1],zoom);double radians=Math.toRadians(-rotation);
        double dx=focusX-getWidth()/2.0,dy=focusY-getHeight()/2.0;
        double[] nextCenter=GeoMath.coordinate(origin[0]-dx*Math.cos(radians)+dy*Math.sin(radians),origin[1]-dx*Math.sin(radians)-dy*Math.cos(radians),zoom);
        latitude=nextCenter[0];longitude=nextCenter[1];notifyViewport();invalidate();
    }
    private void notifyViewport() { if(listener!=null) listener.viewport(latitude,longitude,zoom,rotation); }
    private double wrappedDx(double dx) { double width=Math.scalb(256.0,zoom);if(dx>width/2)dx-=width;if(dx< -width/2)dx+=width;return dx; }
    private float dp(float value) { return value*getResources().getDisplayMetrics().density; }
    private static double validLatitude(double value) { return Double.isFinite(value)?Math.max(-GeoMath.MAX_MERCATOR_LATITUDE,Math.min(GeoMath.MAX_MERCATOR_LATITUDE,value)):47.1457; }
    private static double validLongitude(double value) { return Double.isFinite(value)?Math.max(-180,Math.min(180,value)):14.0328; }
    private static float normalizeRotation(float value) { return ((value+180)%360+360)%360-180; }
    private static float angle(MotionEvent event) { return (float)Math.toDegrees(Math.atan2(event.getY(1)-event.getY(0),event.getX(1)-event.getX(0))); }
    private static int withAlpha(int color,int alpha) { return (color&0x00ffffff)|(Math.max(0,Math.min(255,alpha))<<24); }
    private static int interpolate(int a,int b,double t) { return Color.rgb((int)Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),(int)Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),(int)Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t)); }
    private static int featureColor(JSONObject feature) {
        String explicit=feature.optString("color",feature.optString("stylecolor",""));
        try { if(!explicit.isEmpty()) return Color.parseColor(explicit); } catch(IllegalArgumentException ignored) { }
        String kind=feature.optString("type","").toLowerCase(Locale.ROOT);
        if(kind.contains("strom")) return 0xffff7878;if(kind.contains("wasser")||kind.contains("drain")) return 0xff68bdff;
        if(kind.contains("baum")) return 0xff80e699;return 0xffffcf6b;
    }
}
