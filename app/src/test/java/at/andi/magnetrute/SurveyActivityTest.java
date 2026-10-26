package at.andi.magnetrute;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SurveyActivityTest {
    private Context context;private SurveyStore store;private String pid;
    private ActivityController<SurveyActivity> controller;private SurveyActivity activity;
    private static JSONObject object(Object... pairs)throws Exception {JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("survey-v1.sqlite");context.getSharedPreferences("survey-ui",0).edit().clear().commit();context.getSharedPreferences("survey-service",0).edit().clear().commit();
        store=new SurveyStore(context);pid=UUID.randomUUID().toString();store.putProject(object("id",pid,"name","Testwiese","latitude",47.15,"longitude",14.13,"zoom",17,"source",OfflineTiles.BASEMAP_ORTHO,"offlineReady",false));
        context.getSharedPreferences("survey-ui",0).edit().putString("projectId",pid).commit();
        controller=Robolectric.buildActivity(SurveyActivity.class).create().start().resume().visible();activity=controller.get();idle();
    }
    @After public void teardown(){if(controller!=null)controller.pause().stop().destroy();store.close();context.deleteDatabase("survey-v1.sqlite");}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static <T extends View>T find(View root,Class<T> type,String prefix){
        if(type.isInstance(root)&&(!(root instanceof Button)||((Button)root).getText().toString().startsWith(prefix)))return type.cast(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type,prefix);if(found!=null)return found;}return null;
    }
    private void click(String name){Button b=find(activity.getWindow().getDecorView(),Button.class,name);assertNotNull(name,b);b.performClick();idle();}
    private OfflineMapView map(){return find(activity.getWindow().getDecorView(),OfflineMapView.class,"");}
    private OfflineMapView.Listener listener(){return ReflectionHelpers.getField(map(),"listener");}
    private static List<EditText> edits(View root){List<EditText> result=new ArrayList<>();if(root instanceof EditText)result.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(edits(((ViewGroup)root).getChildAt(i)));return result;}
    private void await()throws Exception {long until=System.nanoTime()+10_000_000_000L;do{idle();if(!ReflectionHelpers.<Boolean>getField(activity,"busy"))return;Thread.sleep(10);}while(System.nanoTime()<until);fail("UI job did not finish");}

    @Test public void coldStartNeverEnablesNetworkAndPolygonPersistsAfterRestart()throws Exception {
        assertTrue(ReflectionHelpers.<Boolean>getField(map(),"offlineActive"));
        click("Polygon");listener().tap(47.15,14.13);listener().tap(47.15,14.131);listener().tap(47.151,14.131);click("Abschließen");
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();edits(dialog.getWindow().getDecorView()).get(0).setText("Garten Nord");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
        assertEquals("Garten Nord",store.project(pid).getString("name"));assertEquals(4,store.project(pid).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).length());
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(SurveyActivity.class).create().start().resume().visible();activity=controller.get();idle();
        assertTrue(ReflectionHelpers.<Boolean>getField(map(),"offlineActive"));assertEquals("Garten Nord",ReflectionHelpers.<JSONObject>getField(activity,"project").getString("name"));
        String session=store.createSession(pid,"Einstellungsprüfung");store.addSample(session,object("total",13));
        context.getSharedPreferences("survey-service",0).edit().putString("sessionId",session).commit();SurveyService.recover(context);
        click("Einstellungen");edits(activity.getWindow().getDecorView()).get(6).setText("4,5");click("Einstellungen speichern");
        JSONObject change=store.events(session).stream().filter(e->"KARTEN_EINSTELLUNGEN".equals(e.optString("name"))).findFirst().get();
        assertEquals(4.5,new JSONObject(change.getString("details")).getDouble("heatThreshold"),0);assertTrue(change.getLong("timestampNs")>=0);
        assertEquals(13,store.samples(session).get(0).getDouble("total"),0);
        // Selecting a map project must not turn the original GPS-optional lab into a GPS requirement.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION);
        android.hardware.Sensor sensor=org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
        Shadows.shadowOf((android.hardware.SensorManager)context.getSystemService(Context.SENSOR_SERVICE)).addSensor(sensor);
        ActivityController<MainActivity> lab=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        try {Button record=find(lab.get().getWindow().getDecorView(),Button.class,"● Aufzeichnen");assertNotNull(record);record.performClick();idle();
            assertTrue(ReflectionHelpers.<MeasurementLogger>getField(lab.get(),"recorder").isRecording());
            assertFalse(ReflectionHelpers.<Boolean>getField(lab.get(),"locationRequested"));
        }finally{lab.pause().stop().destroy();}
    }
    @Test public void manuallyCorrectedMarkerPreservesOriginalGpsAndZipImportCreatesCopy()throws Exception {
        String fid=UUID.randomUUID().toString();JSONObject original=object("lat",47.15,"lon",14.13,"accuracy",4,"provider","gps");
        store.putFeature(object("id",fid,"projectId",pid,"name","Eiche","type","Baum","originalGps",original,"geometry",object("type","Point","coordinates",new JSONArray("[14.13,47.15]"))));
        listener().longPress(47.15,14.13);idle();AlertDialog menu=ShadowAlertDialog.getLatestAlertDialog();menu.getListView().performItemClick(null,1,1);idle();
        listener().pointMoved(0,47.1502,14.1302);click("Abschließen");
        JSONObject changed=store.features(pid).get(0);assertEquals(47.1502,changed.getJSONObject("geometry").getJSONArray("coordinates").getDouble(1),1e-10);assertEquals(47.15,changed.getJSONObject("originalGps").getDouble("lat"),1e-10);
        File backup=new File(context.getCacheDir(),"ui-project.zip");activity.onActivityResult(4101,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(backup)));await();assertTrue(backup.length()>0);
        activity.onActivityResult(4102,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(backup)));await();assertEquals(2,store.projects().size());assertNotEquals(pid,ReflectionHelpers.<JSONObject>getField(activity,"project").getString("id"));assertEquals(1,store.features(pid).size());
    }
    @Test public void missingOfflinePackageIsNeverPresentedAsReadyAfterValidation()throws Exception {
        JSONObject p=store.project(pid);p.put("offlineReady",true);p.put("mapPath",UUID.randomUUID()+".mbtiles");store.putProject(p);
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(SurveyActivity.class).create().start().resume().visible();activity=controller.get();
        long until=System.nanoTime()+10_000_000_000L;while(store.project(pid).optBoolean("offlineReady")&&System.nanoTime()<until){idle();Thread.sleep(10);}idle();
        assertFalse(store.project(pid).optBoolean("offlineReady"));assertTrue(ReflectionHelpers.<Boolean>getField(map(),"offlineActive"));
    }
}
