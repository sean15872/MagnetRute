package at.andi.magnetrute;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.location.Location;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SurveyServiceTest {
    private Context context;
    private SurveyStore store;
    private String projectId;
    private ServiceController<SurveyService> controller;
    private SurveyService service;
    private final Sensor magnet=ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD);

    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("survey-service",Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sessions",Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("survey-v1.sqlite");
        delete(new File(context.getFilesDir(),"sessions"));
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION);
        store=new SurveyStore(context);projectId=UUID.randomUUID().toString();
        store.putProject(new JSONObject().put("id",projectId).put("name","Garten"));
        SurveyService.recover(context);
    }
    @After public void after() throws Exception {
        if(controller!=null) {
            HandlerThread thread=ReflectionHelpers.getField(service,"thread");
            controller.destroy();thread.join(3000);assertFalse("Recorder worker must stop",thread.isAlive());
        }
        if(store!=null) store.close();
    }
    private static void delete(File f) { if(f.isDirectory()) for(File c:f.listFiles()) delete(c);f.delete(); }
    private void start() {
        controller=Robolectric.buildService(SurveyService.class).create();service=controller.get();
        service.onStartCommand(new Intent(context,SurveyService.class).setAction("start").putExtra("projectId",projectId),0,1);
        drain();assertEquals("recording",SurveyService.snapshot().state);
    }
    private void drain() {
        HandlerThread thread=ReflectionHelpers.getField(service,"thread");
        Shadows.shadowOf(thread.getLooper()).idle();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private SensorEvent event(float x,float y,float z) {
        SensorEvent event=ReflectionHelpers.callConstructor(SensorEvent.class,ReflectionHelpers.ClassParameter.from(int.class,3));
        event.sensor=magnet;event.timestamp=SystemClock.elapsedRealtimeNanos();event.accuracy=3;
        event.values[0]=x;event.values[1]=y;event.values[2]=z;return event;
    }
    private void sample(float x,float y,float z) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110));
        service.onSensorChanged(event(x,y,z));
    }
    private Location location(double lat,double lon,float accuracy) {
        Location location=new Location("gps");location.setLatitude(lat);location.setLongitude(lon);location.setAccuracy(accuracy);
        location.setTime(System.currentTimeMillis());location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());return location;
    }

    @Test public void rawSamplesSurviveSettingsPauseAndResumeWithPersistentEvents() throws Exception {
        start();service.onAccuracyChanged(magnet,3);
        for(int i=0;i<22;i++) sample(3,4,12);
        String session=SurveyService.snapshot().sessionId;
        List<JSONObject> samples=store.samples(session);assertEquals(22,samples.size());
        JSONObject first=samples.get(0);assertEquals(13,first.getDouble("total"),.0001);
        assertEquals(3,first.getDouble("x"),0);assertEquals(4,first.getDouble("y"),0);assertEquals(12,first.getDouble("z"),0);
        assertTrue(first.isNull("uncalX"));assertTrue(first.isNull("fix"));
        assertEquals(13,samples.get(21).getDouble("reference"),.0001);
        SurveyService.sensitivity(context,10);drain();sample(3,4,12);
        assertEquals(13,store.samples(session).get(22).getDouble("total"),.0001);
        assertEquals(10,store.samples(session).get(22).getDouble("sensitivity"),0);
        SurveyService.pause(context);drain();assertEquals("paused",store.session(session).getString("status"));
        sample(30,40,120);assertEquals(23,store.samples(session).size());
        SurveyService.resume(context);drain();sample(3,4,12);
        assertEquals(session,SurveyService.snapshot().sessionId);
        assertEquals("recording",store.session(session).getString("status"));
        assertNotEquals(store.samples(session).get(22).getString("passId"),store.samples(session).get(23).getString("passId"));
        SurveyService.stop(context);drain();assertEquals("stopped",store.session(session).getString("status"));
        List<JSONObject> events=store.events(session);
        assertTrue(events.stream().anyMatch(e->"EMPFINDLICHKEIT".equals(e.optString("name"))));
        assertTrue(events.stream().anyMatch(e->"REFERENZ_AUTO".equals(e.optString("name"))));
        assertTrue(events.stream().anyMatch(e->"PAUSE".equals(e.optString("name"))));
        assertTrue(new File(context.getFilesDir(),"sessions/"+store.session(session).getString("legacyFolder")+"/messwerte.csv").isFile());
    }

    @Test public void rejectedAndStaleGpsRemainRawWithoutInventingSamplePosition() throws Exception {
        start();service.onAccuracyChanged(magnet,3);String session=SurveyService.snapshot().sessionId;
        service.onLocationChanged(location(47.1,14.1,900));sample(30,0,0);
        assertFalse(store.fixes(session).get(0).getBoolean("accepted"));
        assertEquals(900,store.fixes(session).get(0).getDouble("accuracy"),0);
        assertTrue(store.samples(session).get(0).isNull("fix"));assertNull(SurveyService.snapshot().fix);
        service.onLocationChanged(location(47.1,14.1,4));
        assertFalse(store.fixes(session).get(1).getBoolean("accepted")); // first stable-looking fix awaits confirmation
        service.onLocationChanged(location(47.1,14.1,4));sample(30,0,0);
        assertTrue(store.fixes(session).get(2).getBoolean("accepted"));
        assertEquals(47.1,store.samples(session).get(1).getJSONObject("fix").getDouble("lat"),0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));sample(30,0,0);
        assertTrue(store.samples(session).get(2).isNull("fix"));assertNull(SurveyService.snapshot().fix);
        assertTrue(SurveyService.snapshot().gpsStatus.contains("veraltet"));
        assertTrue(store.events(session).stream().anyMatch(e->"GPS_REJECTED".equals(e.optString("name"))));
        assertEquals(3,store.samples(session).size());
        service.onLocationChanged(location(47.1,14.1,4));sample(30,0,0);
        // A GPS outage breaks geometry, but is not an independent magnetic traversal.
        assertEquals(store.samples(session).get(1).getString("passId"),store.samples(session).get(3).getString("passId"));
        assertNotEquals(store.samples(session).get(1).getInt("segmentId"),store.samples(session).get(3).getInt("segmentId"));
    }

    @Test public void recoveryPausesInterruptedSessionAndDoesNotReuseOldGps() throws Exception {
        String session=store.createSession(projectId,"Vor Neustart");
        context.getSharedPreferences("survey-service",Context.MODE_PRIVATE).edit().putString("sessionId",session).commit();
        SurveyService.recover(context);
        assertEquals("recovered",SurveyService.snapshot().state);assertEquals("recovered",store.session(session).getString("status"));
        assertNull(SurveyService.snapshot().fix);assertEquals(0,store.samples(session).size());
        assertTrue(store.events(session).stream().anyMatch(e->"PROCESS_RECOVERED".equals(e.optString("name"))));
        SurveyService.sensitivity(context,7);SurveyService.configureAccuracy(context,8);
        assertEquals(7,store.session(session).getJSONObject("config").getDouble("sensitivity"),0);
        assertEquals(8,store.session(session).getJSONObject("config").getDouble("maxAccuracy"),0);
        SurveyService.stop(context);assertEquals("stopped",store.session(session).getString("status"));
    }

    @Test public void missingLocationPermissionCannotStartRecording() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        controller=Robolectric.buildService(SurveyService.class).create();service=controller.get();
        service.onStartCommand(new Intent(context,SurveyService.class).setAction("start").putExtra("projectId",projectId),0,1);
        drain();assertEquals("error",SurveyService.snapshot().state);assertEquals(0,store.sessions(projectId).size());
    }

    @Test public void explicitPassBoundaryChangesAggregationGroupWithoutGpsGapOrRawChanges() throws Exception {
        start();service.onAccuracyChanged(magnet,3);String session=SurveyService.snapshot().sessionId;
        service.onLocationChanged(location(47.1,14.1,4));
        service.onLocationChanged(location(47.1,14.1,4));sample(3,4,12);
        SurveyService.newPass(context);drain();sample(3,4,12);
        List<JSONObject> samples=store.samples(session);
        assertEquals(2,samples.size());assertNotEquals(samples.get(0).getString("passId"),samples.get(1).getString("passId"));
        assertEquals(13,samples.get(0).getDouble("total"),0);assertEquals(13,samples.get(1).getDouble("total"),0);
        assertEquals(samples.get(0).getJSONObject("fix").getLong("elapsedNs"),samples.get(1).getJSONObject("fix").getLong("elapsedNs"));
        assertEquals(2,store.fixes(session).size());
        assertTrue(store.events(session).stream().anyMatch(e->"NEUER_DURCHGANG".equals(e.optString("name"))));
    }
}
