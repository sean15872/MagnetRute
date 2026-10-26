package at.andi.magnetrute;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.MotionEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** Real local raster pixels and the same coordinate transform used by GPS and annotations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OfflineMapViewTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final double lat=47.1457,lon=14.0328;

    private JSONObject project(String id) throws Exception {
        return new JSONObject().put("id",id).put("latitude",lat).put("longitude",lon).put("zoom",18)
            .put("source",OfflineTiles.BASEMAP_ORTHO).put("attribution","Datenquelle: basemap.at");
    }
    private OfflineMapView view(AtomicInteger requests) {
        Context context=RuntimeEnvironment.getApplication();
        OfflineMapView map=new OfflineMapView(context,(s,z,x,y) -> {
            requests.incrementAndGet();Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);b.eraseColor(Color.GREEN);return b;
        });
        map.layout(0,0,512,512);return map;
    }
    private static Bitmap draw(OfflineMapView map) {
        Bitmap result=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);map.draw(new Canvas(result));return result;
    }
    private static void idleTiles(OfflineMapView map) throws Exception {
        long limit=System.nanoTime()+8_000_000_000L;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Set<?> pending=ReflectionHelpers.getField(map,"pending");
            synchronized(pending) { if(pending.isEmpty()) return; }
            Thread.sleep(10);
        } while(System.nanoTime()<limit);
        fail("Map tile worker did not finish");
    }
    private byte[] greenTile() {
        Bitmap tile=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);tile.eraseColor(Color.GREEN);
        ByteArrayOutputStream out=new ByteArrayOutputStream();tile.compress(Bitmap.CompressFormat.PNG,100,out);tile.recycle();return out.toByteArray();
    }
    private File offlinePackage() throws Exception {
        JSONArray ring=new JSONArray().put(new JSONArray().put(lon-.004).put(lat-.004))
            .put(new JSONArray().put(lon+.004).put(lat-.004)).put(new JSONArray().put(lon+.004).put(lat+.004))
            .put(new JSONArray().put(lon-.004).put(lat+.004));
        File file=new File(temporary.getRoot(),"map.mbtiles");byte[] tile=greenTile();
        OfflineTiles.download(file,OfflineTiles.estimate(ring,0,18,18,OfflineTiles.BASEMAP_ORTHO),null,()->false,url->tile);
        return file;
    }

    @Test public void screenCoordinatesRemainGeoreferencedAfterZoomRotationAndPan() throws Exception {
        OfflineMapView map=view(new AtomicInteger());map.setProject(project("projection"),null,true);
        for(float rotation:new float[]{0,37,-112,180}) {
            map.rotateBy(rotation);
            for(double[] coordinate:new double[][]{{lat,lon},{lat+.0007,lon+.0011},{lat-.001,lon-.0006}}) {
                float[] screen=map.screenPoint(coordinate[0],coordinate[1]);double[] result=map.coordinateAt(screen[0],screen[1]);
                assertEquals(coordinate[0],result[0],1e-8);assertEquals(coordinate[1],result[1],1e-8);
            }
        }
        map.setZoom(16);map.center(lat+.002,lon-.003);
        double[] center=map.coordinateAt(256,256);assertEquals(lat+.002,center[0],1e-10);assertEquals(lon-.003,center[1],1e-10);
    }

    @Test public void offlineRasterAndMissingTilesNeverCallPreviewNetwork() throws Exception {
        AtomicInteger calls=new AtomicInteger();OfflineMapView map=view(calls);map.setProject(project("local"),offlinePackage(),true);
        Bitmap first=draw(map);first.recycle();idleTiles(map);Bitmap loaded=draw(map);
        assertEquals(Color.GREEN,loaded.getPixel(256,256));assertEquals(0,calls.get());loaded.recycle();
        map.center(lat+.5,lon+.5);draw(map).recycle();idleTiles(map);Bitmap missing=draw(map);
        assertNotEquals(Color.GREEN,missing.getPixel(256,256));assertEquals(0,calls.get());missing.recycle();
        map.setZoom(19);draw(map).recycle();idleTiles(map);assertEquals(0,calls.get());
    }

    @Test public void manualZoomUpTo22PreservesGeoreferencingWithoutNewProviderRequests() throws Exception {
        AtomicInteger calls=new AtomicInteger();OfflineMapView map=view(calls);
        map.setProject(project("overzoom"),offlinePackage(),true);
        map.setZoom(22);
        assertEquals(22,map.currentZoom());
        float[] point=map.screenPoint(lat+.00001,lon+.00001);
        double[] recovered=map.coordinateAt(point[0],point[1]);
        assertEquals(lat+.00001,recovered[0],1e-8);assertEquals(lon+.00001,recovered[1],1e-8);
        draw(map).recycle();idleTiles(map);
        assertEquals("Digitale Vergrößerung darf offline keine neuen Online-Kacheln abrufen",0,calls.get());
    }

    @Test public void previewIsExplicitAndOfflineSwitchRemovesRemoteFallback() throws Exception {
        AtomicInteger calls=new AtomicInteger();OfflineMapView map=view(calls);map.setProject(project("preparation"),null,false);
        draw(map).recycle();idleTiles(map);assertTrue(calls.get()>0);
        int previous=calls.get();map.setProject(project("preparation"),null,true);
        draw(map).recycle();idleTiles(map);assertEquals(previous,calls.get());
        Bitmap rendered=draw(map);assertNotEquals(Color.GREEN,rendered.getPixel(256,256));rendered.recycle();
    }

    private JSONObject fix(double longitude,long stamp,boolean accepted,double accuracy,boolean gap) throws Exception {
        return new JSONObject().put("lat",lat).put("lon",longitude).put("elapsedNs",stamp)
            .put("accepted",accepted).put("accuracy",accuracy).put("breakTrack",gap);
    }
    @Test public void rejectedGpsFixBreaksPathRatherThanDrawingKilometreBridges() throws Exception {
        OfflineMapView map=view(new AtomicInteger());map.setProject(project("track"),null,true);map.setBaseVisible(false);
        map.setLayers(false,false,false,true,false);
        // About 38 metres over three seconds is within the walking filter's tolerance.
        JSONObject a=fix(lon-.00025,1_000_000_000L,true,3,true),b=fix(lon+.00025,4_000_000_000L,true,3,false);
        map.setTrack(Arrays.asList(a,b));Bitmap joined=draw(map);assertEquals(0xff73e6ce,joined.getPixel(256,256));joined.recycle();
        JSONObject bad=fix(lon+3,1_500_000_000L,false,1000,true);map.setTrack(Arrays.asList(a,bad,b));
        Bitmap broken=draw(map);assertEquals(0xff17243a,broken.getPixel(256,256));broken.recycle();
        bad.put("accepted",true);map.setTrack(Arrays.asList(a,bad,b));Bitmap poor=draw(map);
        assertEquals(0xff17243a,poor.getPixel(256,256));poor.recycle();
        bad.put("accuracy",3);map.setTrack(Arrays.asList(a,bad,b));Bitmap jump=draw(map);
        assertEquals(0xff17243a,jump.getPixel(256,256));jump.recycle();
    }

    @Test public void heatmapUsesMeasuredCellsAndLeavesUnmeasuredAreaEmpty() throws Exception {
        OfflineMapView map=view(new AtomicInteger());map.setProject(project("heat"),null,true);map.setBaseVisible(false);
        map.setLayers(false,false,false,false,true);
        JSONObject fix=new JSONObject().put("lat",lat).put("lon",lon).put("accuracy",2).put("accepted",true);
        JSONObject sample=new JSONObject().put("fix",fix).put("total",50).put("delta",5).put("sensorAccuracy",3)
            .put("timestampNs",1_000_000_000L).put("passId","walk-1");
        map.setSamples(Collections.singletonList(sample),"delta",1,5,10);
        List<FieldGrid.Cell> cells=FieldGrid.aggregate(Collections.singletonList(new FieldGrid.Sample(lat,lon,2,5,"walk-1",1_000_000_000L,false)),10);
        FieldGrid.Cell cell=cells.get(0);float[] center=map.screenPoint(cell.lat,cell.lon);Bitmap rendered=draw(map);
        assertEquals(0xffed3d30,rendered.getPixel(Math.round(center[0]),Math.round(center[1])));
        assertEquals(0xff17243a,rendered.getPixel(50,50));rendered.recycle();
        sample.put("calibrationJump",true);map.setSamples(Collections.singletonList(sample),"delta",1,5,10);Bitmap uncertain=draw(map);
        assertEquals(0xff17243a,uncertain.getPixel(Math.round(center[0]),Math.round(center[1])));uncertain.recycle();
    }

    @Test public void markerLayersAndEditingHandlesUseSameRotatedCoordinates() throws Exception {
        OfflineMapView map=view(new AtomicInteger());map.setProject(project("features"),null,true);map.setBaseVisible(false);map.rotateBy(45);
        JSONObject point=new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(lon+.0004).put(lat+.0003));
        JSONObject feature=new JSONObject().put("id","tree").put("geometry",point).put("name","").put("color","#ff0000");
        map.setFeatures(Collections.singletonList(feature));float[] location=map.screenPoint(lat+.0003,lon+.0004);
        Bitmap markers=draw(map);assertEquals(Color.RED,markers.getPixel(Math.round(location[0]),Math.round(location[1])));markers.recycle();
        map.setLayers(false,false,false,false,false);Bitmap hidden=draw(map);assertEquals(0xff17243a,hidden.getPixel(Math.round(location[0]),Math.round(location[1])));hidden.recycle();
        AtomicInteger edits=new AtomicInteger();final double[] edited={0,0};
        map.setListener(new OfflineMapView.Listener() {
            @Override public void tap(double a,double b) { }
            @Override public void longPress(double a,double b) { }
            @Override public void viewport(double a,double b,int z,float r) { }
            @Override public void pointMoved(int index,double a,double b) { edits.incrementAndGet();edited[0]=a;edited[1]=b; }
        });
        map.setEditing(Collections.singletonList(new double[]{lon,lat}),false);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,256,256,0),move=MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,300,280,0),up=MotionEvent.obtain(0,20,MotionEvent.ACTION_UP,300,280,0);
        map.onTouchEvent(down);map.onTouchEvent(move);map.onTouchEvent(up);down.recycle();move.recycle();up.recycle();
        double[] expected=map.coordinateAt(300,280);assertTrue(edits.get()>0);assertEquals(expected[0],edited[0],1e-10);assertEquals(expected[1],edited[1],1e-10);
    }
}
