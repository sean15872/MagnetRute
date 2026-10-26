package at.andi.magnetrute;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Foreground, explicitly started recorder. Cartography never rewrites the original sensor data. */
public final class SurveyService extends Service implements SensorEventListener, LocationListener {
    private static final String CHANNEL="magnetrute-survey";
    private static final String PREFS="survey-service";
    private static final String START="start",RESUME="resume",PAUSE="pause",STOP="stop",REFERENCE="reference",
        SENSITIVITY="sensitivity",ACCURACY="accuracy",PASS="pass";
    private static final int NOTIFICATION_ID=4001;
    private static volatile SurveyService instance;
    private static volatile Snapshot current=new Snapshot(null,null,"idle",null,null,"Warte auf GPS",3,10);

    /** A fresh immutable container; JSONObject samples are published once, never modified afterwards. */
    public static final class Snapshot {
        public final String sessionId,projectId,state,gpsStatus;
        public final JSONObject sample;
        public final GpsQuality.Fix fix;
        public final double sensitivity,maxAccuracy;
        Snapshot(String sessionId,String projectId,String state,JSONObject sample,GpsQuality.Fix fix,
                 String gpsStatus,double sensitivity,double maxAccuracy) {
            this.sessionId=sessionId;this.projectId=projectId;this.state=state;this.sample=sample;this.fix=fix;
            this.gpsStatus=gpsStatus;this.sensitivity=sensitivity;this.maxAccuracy=maxAccuracy;
        }
    }

    private SurveyStore store;
    private MeasurementLogger legacy;
    private SensorManager sensors;
    private LocationManager locations;
    private HandlerThread thread;
    private Handler worker;
    private final GpsQuality gps=new GpsQuality();
    private final CalibrationGuard calibration=new CalibrationGuard();
    private Sensor magnet,uncalSensor,gravitySensor,accelSensor;
    private final float[] gravity=new float[3],acceleration=new float[3],uncal=new float[6];
    private final double[] recent=new double[30];
    private int recentCount,recentNext,sensorAccuracy=-1,segment;
    private long gravityNs,accelNs,uncalNs,lastSampleNs;
    private boolean registered,locationRegistered,usablePosition;
    private String sessionId,projectId,state="idle",gpsStatus="Warte auf GPS";
    private String runId=UUID.randomUUID().toString();
    private JSONObject sample;
    private double sensitivity=3,maxAccuracy=10,reference=Double.NaN,filtered=Double.NaN;

    public static Snapshot snapshot() {
        Snapshot value=current;
        if(value.fix!=null && (SystemClock.elapsedRealtimeNanos()<value.fix.elapsedNs
                || SystemClock.elapsedRealtimeNanos()-value.fix.elapsedNs>GpsQuality.MAX_AGE_NS))
            return new Snapshot(value.sessionId,value.projectId,value.state,value.sample,null,
                "Warte auf GPS · letzter brauchbarer Fix ist veraltet",value.sensitivity,value.maxAccuracy);
        return value;
    }

    /** Called on UI entry; never starts background tracking or requests a permission. */
    public static synchronized void recover(Context context) {
        if(instance!=null) return;
        SharedPreferences prefs=context.getSharedPreferences(PREFS,MODE_PRIVATE);
        String id=prefs.getString("sessionId",null);
        double sensitivity=prefs.getFloat("sensitivity",3),accuracy=prefs.getFloat("maxAccuracy",10);
        if(id==null) {
            current=new Snapshot(null,null,"idle",null,null,"Warte auf GPS",sensitivity,accuracy);return;
        }
        try(SurveyStore saved=new SurveyStore(context)) {
            JSONObject session=saved.session(id);
            if(session==null) { prefs.edit().remove("sessionId").apply();return; }
            String status=session.optString("status","recovered");
            if("recording".equals(status)) {
                status="recovered";session.put("status",status);saved.updateSession(session);
                saved.addEvent(id,"PROCESS_RECOVERED","Aufzeichnung nach Prozessende unterbrochen; explizit fortsetzen",SystemClock.elapsedRealtimeNanos());
            }
            current=new Snapshot(id,session.optString("projectId"),status,null,null,
                "Warte auf GPS · keine alte Position übernommen",sensitivity,accuracy);
        } catch(Exception e) {
            current=new Snapshot(null,null,"error",null,null,"Aufzeichnung nicht lesbar: "+e.getMessage(),sensitivity,accuracy);
        }
    }

    public static void start(Context context,String projectId) { send(context,START,projectId,null,Double.NaN,true); }
    public static void pause(Context context) { send(context,PAUSE,null,null,Double.NaN,false); }
    public static void resume(Context context) { send(context,RESUME,null,null,Double.NaN,true); }
    public static void resume(Context context,String sessionId) { send(context,RESUME,null,sessionId,Double.NaN,true); }
    public static void stop(Context context) { send(context,STOP,null,null,Double.NaN,false); }
    public static void reference(Context context) { send(context,REFERENCE,null,null,Double.NaN,false); }
    /** Explicit independent traversal boundary; it does not invent a GPS gap or alter raw values. */
    public static void newPass(Context context) { send(context,PASS,null,null,Double.NaN,false); }
    public static void sensitivity(Context context,double value) {
        if(!Double.isFinite(value) || value<.5 || value>100) throw new IllegalArgumentException("Empfindlichkeit: 0,5–100 µT");
        context.getSharedPreferences(PREFS,MODE_PRIVATE).edit().putFloat("sensitivity",(float)value).apply();
        if(instance==null) { recover(context);saveDormantSetting(context,"sensitivity",value,"EMPFINDLICHKEIT");return; }send(context,SENSITIVITY,null,null,value,false);
    }
    public static void configureAccuracy(Context context,double value) {
        if(!Double.isFinite(value) || value<1 || value>1000) throw new IllegalArgumentException("GPS-Grenze: 1–1000 m");
        context.getSharedPreferences(PREFS,MODE_PRIVATE).edit().putFloat("maxAccuracy",(float)value).apply();
        if(instance==null) { recover(context);saveDormantSetting(context,"maxAccuracy",value,"GPS_FILTER");return; }send(context,ACCURACY,null,null,value,false);
    }
    private static void saveDormantSetting(Context context,String key,double value,String event) {
        String id=current.sessionId;if(id==null)return;
        try(SurveyStore db=new SurveyStore(context)) {
            JSONObject row=db.session(id);if(row==null||!("paused".equals(row.optString("status"))||"recovered".equals(row.optString("status"))))return;
            JSONObject config=row.optJSONObject("config");if(config==null)config=new JSONObject();config.put(key,value);row.put("config",config);db.updateSession(row);
            db.addEvent(id,event,key+"="+value+"; vor Fortsetzung geändert",SystemClock.elapsedRealtimeNanos());
        }catch(Exception e){throw new IllegalStateException("Einstellung konnte nicht für die Fortsetzung gespeichert werden",e);}
    }
    private static void send(Context context,String action,String project,String session,double value,boolean foreground) {
        Intent intent=new Intent(context,SurveyService.class).setAction(action);
        if(project!=null) intent.putExtra("projectId",project);
        if(session!=null) intent.putExtra("sessionId",session);
        if(Double.isFinite(value)) intent.putExtra("value",value);
        SurveyService running=instance;
        if(running!=null) { running.worker.post(() -> running.command(intent));return; }
        if(foreground) context.startForegroundService(intent);
        else if(STOP.equals(action) || PAUSE.equals(action)) {
            recover(context);
            Snapshot saved=current;
            if(saved.sessionId==null) return;
            try(SurveyStore db=new SurveyStore(context)) {
                JSONObject row=db.session(saved.sessionId);
                if(row!=null) {
                    row.put("status",STOP.equals(action)?"stopped":"paused");
                    if(STOP.equals(action)) row.put("endUnixMs",System.currentTimeMillis());
                    db.updateSession(row);db.addEvent(saved.sessionId,STOP.equals(action)?"STOP":"PAUSE",
                        "Ohne laufenden Prozess gespeichert",SystemClock.elapsedRealtimeNanos());
                }
                current=new Snapshot(saved.sessionId,saved.projectId,STOP.equals(action)?"stopped":"paused",null,null,
                    "Standortaufnahme pausiert",saved.sensitivity,saved.maxAccuracy);
            } catch(Exception e) { throw new IllegalStateException("Aufzeichnung konnte nicht geändert werden",e); }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        recover(this);
        store=new SurveyStore(this);legacy=new MeasurementLogger(this);
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(sensors!=null) {
            magnet=sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            uncalSensor=sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
            gravitySensor=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            accelSensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        Arrays.fill(uncal,Float.NaN);
        Snapshot previous=current;
        sessionId=previous.sessionId;projectId=previous.projectId;state=previous.state;
        sensitivity=previous.sensitivity;maxAccuracy=previous.maxAccuracy;
        thread=new HandlerThread("MagnetRute-Aufzeichnung");thread.start();worker=new Handler(thread.getLooper());
        instance=this;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null) manager.createNotificationChannel(new NotificationChannel(CHANNEL,"MagnetRute Messung",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null) { stopSelf();return START_NOT_STICKY; }
        if(START.equals(intent.getAction()) || RESUME.equals(intent.getAction())) {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                state="error";gpsStatus="Genauen Standort erlauben, bevor die Begehung startet.";publish();stopSelf();return START_NOT_STICKY;
            }
            try { startForeground(NOTIFICATION_ID,notification("Messung wird vorbereitet …")); }
            catch(SecurityException e) { state="error";gpsStatus="Standortdienst nicht erlaubt: "+e.getMessage();publish();stopSelf();return START_NOT_STICKY; }
        }
        worker.post(() -> command(intent));return START_NOT_STICKY;
    }
    private void command(Intent intent) {
        try {
            String action=intent.getAction();
            if(START.equals(action)) {
                String wanted=intent.getStringExtra("projectId");
                if(wanted==null || store.project(wanted)==null) throw new IllegalArgumentException("Zuerst eine gespeicherte Messfläche öffnen.");
                if("recording".equals(state)) {
                    if(!wanted.equals(projectId)) event("START_REJECTED","Andere Begehung läuft noch");
                    return;
                }
                if(sessionId!=null && ("paused".equals(state) || "recovered".equals(state))) endSession();
                projectId=wanted;sessionId=store.createSession(wanted,new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).format(new Date()));
                reference=Double.NaN;filtered=Double.NaN;recentCount=recentNext=0;segment=0;sample=null;
                begin(false);
            } else if(RESUME.equals(action)) {
                String wanted=intent.getStringExtra("sessionId");
                if(wanted!=null) {
                    if("recording".equals(state) && !wanted.equals(sessionId)) throw new IllegalStateException("Zuerst laufende Begehung stoppen.");
                    sessionId=wanted;
                }
                if("recording".equals(state)) return;
                JSONObject row=sessionId==null?null:store.session(sessionId);
                if(row==null) throw new IllegalArgumentException("Keine fortsetzbare Begehung vorhanden.");
                projectId=row.getString("projectId");
                JSONObject config=row.optJSONObject("config");
                if(config!=null) {
                    reference=config.optDouble("reference",Double.NaN);
                    sensitivity=config.optDouble("sensitivity",sensitivity);
                    maxAccuracy=config.optDouble("maxAccuracy",maxAccuracy);
                }
                begin(true);
            } else if(PAUSE.equals(action)) {
                if(!"recording".equals(state)) return;
                event("PAUSE","Begehung pausiert; nächster Fix beginnt ein neues Segment");
                unregister();legacy.flush();state="paused";gpsStatus="Messung pausiert · kein aktueller Standort";saveState();publish();updateNotification();
            } else if(STOP.equals(action)) {
                endSession();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            } else if(REFERENCE.equals(action)) {
                resetReference("REFERENZ_MANUELL");
            } else if(PASS.equals(action)) {
                if(!"recording".equals(state)) return;
                runId=UUID.randomUUID().toString();event("NEUER_DURCHGANG","Durchgang vom Benutzer getrennt; Rohdaten und Referenz unverändert");publish();
            } else if(SENSITIVITY.equals(action)) {
                sensitivity=intent.getDoubleExtra("value",sensitivity);event("EMPFINDLICHKEIT","uT="+sensitivity);saveState();publish();
            } else if(ACCURACY.equals(action)) {
                maxAccuracy=intent.getDoubleExtra("value",maxAccuracy);gps.reset();usablePosition=false;segment++;
                event("GPS_FILTER","max_accuracy_m="+maxAccuracy);saveState();publish();
            }
        } catch(Exception e) {
            android.util.Log.e("MagnetRute","Survey command",e);
            unregister();legacy.stop();state="error";gpsStatus=e.getMessage()==null?"Aufzeichnungsfehler":e.getMessage();
            try { saveState(); } catch(Exception ignored) { }
            publish();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    private void begin(boolean resumed) throws Exception {
        state="recording";gps.reset();calibration.reset();usablePosition=false;lastSampleNs=0;gravityNs=accelNs=uncalNs=0;
        runId=UUID.randomUUID().toString();segment++;gpsStatus="Warte auf GPS · Genauigkeitsgrenze ±"+maxAccuracy+" m";
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("sessionId",sessionId).apply();
        if(!legacy.isRecording()) {
            legacy.start(0,sensitivity,magnet==null?"Kein Magnetometer":magnet.getName()+" / "+magnet.getVendor());
            File folder=legacy.getSessionFolder();JSONObject row=store.session(sessionId);
            if(folder!=null && row!=null) { row.put("legacyFolder",folder.getName());store.updateSession(row); }
        }
        legacy.setLocationTrackingEnabled(true);
        event(resumed?"RESUME":"START",resumed?"Neues monotones Segment; alte GPS-Position verworfen":"Offline-Begehung gestartet");
        register();saveState();publish();updateNotification();
    }
    private void register() {
        if(!registered && sensors!=null) {
            for(Sensor sensor:new Sensor[]{magnet,uncalSensor,gravitySensor,accelSensor})
                if(sensor!=null) sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,worker);
            registered=true;
        }
        if(locations!=null && !locationRegistered) {
            try {
                if(locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                    locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0,this,thread.getLooper());locationRegistered=true;
                    if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER))
                        gpsStatus="GPS ist ausgeschaltet · Messwerte werden ohne Position gespeichert";
                } else gpsStatus="Kein GPS-Anbieter · Messwerte werden ohne Position gespeichert";
                // Network fixes remain raw provenance, and must pass exactly the same quality filter.
                JSONObject activeProject=store.project(projectId);
                if(activeProject!=null&&!activeProject.optBoolean("offlineReady")&&locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000L,0,this,thread.getLooper());locationRegistered=true;
                }
            } catch(SecurityException | IllegalArgumentException e) {
                gpsStatus="Standort nicht verfügbar: "+e.getMessage();event("GPS_UNAVAILABLE",gpsStatus);
            }
        }
    }
    private void unregister() {
        if(sensors!=null) sensors.unregisterListener(this);registered=false;
        if(locations!=null) try { locations.removeUpdates(this); } catch(SecurityException ignored) { }
        locationRegistered=false;gps.reset();usablePosition=false;
    }
    private void endSession() throws Exception {
        if(sessionId==null) { state="stopped";publish();return; }
        event("STOP","Begehung beendet");unregister();legacy.stop();state="stopped";gpsStatus="Begehung beendet";saveState();publish();
    }
    private void saveState() throws JSONException {
        if(sessionId==null) return;
        JSONObject row=store.session(sessionId);if(row==null) return;
        row.put("status","error".equals(state)?"paused":state);
        if("error".equals(state)) row.put("lastError",gpsStatus);
        if("stopped".equals(state)) row.put("endUnixMs",System.currentTimeMillis());
        row.put("config",json("sensitivity",sensitivity,"maxAccuracy",maxAccuracy,"reference",reference,
            "mode",0,"monotonicSegment",segment));store.updateSession(row);
    }
    private void event(String name,String details) {
        if(sessionId!=null) store.addEvent(sessionId,name,details,SystemClock.elapsedRealtimeNanos());
        legacy.event(name,details);
    }
    private void resetReference(String name) throws JSONException {
        if(recentCount==0) { event("REFERENZ_FEHLT","Noch keine gültigen Magnetwerte");return; }
        double sum=0;for(int i=0;i<recentCount;i++) sum+=recent[i];reference=sum/recentCount;
        event(name,"total_uT="+reference);saveState();publish();
    }
    @Override public void onSensorChanged(SensorEvent e) {
        if(!"recording".equals(state)) return;
        int type=e.sensor.getType();
        if(type==Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            if(e.values.length>=6) { System.arraycopy(e.values,0,uncal,0,6);uncalNs=e.timestamp; }return;
        }
        if(type==Sensor.TYPE_GRAVITY) { System.arraycopy(e.values,0,gravity,0,3);gravityNs=e.timestamp;return; }
        if(type==Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(e.values,0,acceleration,0,3);accelNs=e.timestamp;
            if(gravitySensor==null) {
                for(int i=0;i<3;i++) gravity[i]=gravityNs==0?e.values[i]:.95f*gravity[i]+.05f*e.values[i];gravityNs=e.timestamp;
            }return;
        }
        if(type!=Sensor.TYPE_MAGNETIC_FIELD || e.values.length<3 || e.timestamp-lastSampleNs<100_000_000L) return;
        lastSampleNs=e.timestamp;
        try {
            float x=e.values[0],y=e.values[1],z=e.values[2];
            if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) { event("SENSOR_INVALID","Nicht endlicher Rohwert");return; }
            boolean freshGravity=gravityNs>0 && e.timestamp>=gravityNs && e.timestamp-gravityNs<=1_000_000_000L;
            SensorMath.Components c=SensorMath.components(x,y,z,gravity[0],gravity[1],gravity[2],freshGravity);
            filtered=Double.isFinite(filtered)?.23*c.total+.77*filtered:c.total;
            recent[recentNext]=c.total;recentNext=(recentNext+1)%recent.length;recentCount=Math.min(recent.length,recentCount+1);
            if(!Double.isFinite(reference) && recentCount>=20) resetReference("REFERENZ_AUTO");
            double delta=Double.isFinite(reference)?c.total-reference:Double.NaN;
            boolean freshUncal=uncalNs>0 && e.timestamp>=uncalNs && e.timestamp-uncalNs<=1_000_000_000L;
            CalibrationGuard.State quality=calibration.observe(e.timestamp,sensorAccuracy,
                freshUncal?uncal[3]:Double.NaN,freshUncal?uncal[4]:Double.NaN,freshUncal?uncal[5]:Double.NaN);
            if(quality.jump || quality.statusChanged) event("SENSOR_RECALIBRATION",quality.reason+"; uncertain_until_ns="+quality.untilNs);
            double motion=Double.NaN;
            if(freshGravity && accelNs>0 && e.timestamp>=accelNs && e.timestamp-accelNs<1_000_000_000L) {
                double dx=acceleration[0]-gravity[0],dy=acceleration[1]-gravity[1],dz=acceleration[2]-gravity[2];motion=Math.sqrt(dx*dx+dy*dy+dz*dz);
            }
            GpsQuality.Fix fix=usablePosition?gps.current(e.timestamp):null;
            sample=json("timestampNs",e.timestamp,"unixMs",System.currentTimeMillis(),"x",x,"y",y,"z",z,
                "total",c.total,"vertical",c.vertical,"horizontal",c.horizontal,"gravityX",freshGravity?gravity[0]:Double.NaN,
                "gravityY",freshGravity?gravity[1]:Double.NaN,"gravityZ",freshGravity?gravity[2]:Double.NaN,
                "filtered",filtered,"reference",reference,"delta",delta,"sensitivity",sensitivity,"mode",0,
                "sensorAccuracy",sensorAccuracy,"motion",motion,"calibrationJump",quality.jump,
                "uncertain",quality.uncertain,"calibrationReason",quality.reason,"passId",sessionId+"-"+runId,"segmentId",segment,
                "uncalX",freshUncal?uncal[0]:Double.NaN,"uncalY",freshUncal?uncal[1]:Double.NaN,"uncalZ",freshUncal?uncal[2]:Double.NaN,
                "biasX",freshUncal?uncal[3]:Double.NaN,"biasY",freshUncal?uncal[4]:Double.NaN,"biasZ",freshUncal?uncal[5]:Double.NaN,
                "uncalTimestampNs",freshUncal?uncalNs:JSONObject.NULL,"fix",fix==null?JSONObject.NULL:fixJson(fix));
            store.addSample(sessionId,sample);
            float[] raw=freshUncal?uncal:new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN};
            legacy.sample(e.timestamp,x,y,z,c,gravity,filtered,reference,delta,sensitivity,0,sensorAccuracy,motion,
                Double.isFinite(delta)?SensorMath.clamp(Math.abs(delta)/sensitivity,0,1):0,raw,freshUncal?uncalNs:0);
            publish();
        } catch(Exception ex) { failRecording(ex); }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {
        if(sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD && sensorAccuracy!=accuracy) {
            sensorAccuracy=accuracy;if(sessionId!=null) event("SENSOR_ACCURACY","accuracy="+accuracy);publish();
        }
    }
    @Override public void onLocationChanged(Location location) {
        if(!"recording".equals(state) || location==null) return;
        try {
            GpsQuality.Fix fix=new GpsQuality.Fix(location.getLatitude(),location.getLongitude(),
                location.hasAccuracy()?location.getAccuracy():Double.NaN,location.getElapsedRealtimeNanos(),location.getTime(),
                location.getProvider(),location.hasSpeed()?location.getSpeed():Double.NaN);
            GpsQuality.Decision decision=gps.inspect(fix,SystemClock.elapsedRealtimeNanos(),maxAccuracy);
            JSONObject original=fixJson(fix);
            original.put("accepted",decision.accepted).put("reason",decision.reason).put("breakTrack",decision.breakTrack)
                .put("isMock",location.isFromMockProvider()).put("altitude",location.hasAltitude()?location.getAltitude():JSONObject.NULL)
                .put("bearing",location.hasBearing()?location.getBearing():JSONObject.NULL);
            store.addFix(sessionId,original);
            usablePosition=decision.accepted;
            if(decision.breakTrack) segment++;
            gpsStatus=decision.accepted?"GPS ±"+Math.round(fix.accuracy)+" m · "+fix.provider:
                "Warte auf brauchbares GPS · verworfen: "+decision.reason+" (±"+fix.accuracy+" m)";
            // Raw location fixes are retained in standorte.csv; only accepted ones may annotate measurements.
            legacy.setLocationTrackingEnabled(true);
            legacy.onLocation(location);
            legacy.setLocationTrackingEnabled(decision.accepted);
            if(!decision.accepted) event("GPS_REJECTED",decision.reason+"; provider="+fix.provider+"; accuracy_m="+fix.accuracy);
            else if(!legacy.isRecording()) event("GPS_ACCEPTED",decision.reason);
            publish();
        } catch(Exception e) { failRecording(e); }
    }
    private static JSONObject fixJson(GpsQuality.Fix fix) {
        return json("lat",fix.lat,"lon",fix.lon,"accuracy",fix.accuracy,"elapsedNs",fix.elapsedNs,"unixMs",fix.unixMs,
            "provider",fix.provider,"speed",fix.speed);
    }
    @Override public void onProviderEnabled(String provider) { if("recording".equals(state)) { gpsStatus="GPS aktiviert · warte auf neuen Fix";publish(); } }
    @Override public void onProviderDisabled(String provider) { usablePosition=false;gps.reset();segment++;gpsStatus="GPS ausgefallen · Sensorwerte werden weiter gespeichert";event("GPS_DISABLED",provider);publish(); }
    @Override public void onStatusChanged(String provider,int status,Bundle extras) { }
    private void failRecording(Exception e) {
        android.util.Log.e("MagnetRute","Survey storage failure",e);unregister();legacy.stop();state="error";
        gpsStatus="Aufzeichnung angehalten: "+e.getMessage();
        try { saveState(); } catch(Exception ignored) { }
        publish();updateNotification();
    }
    private void publish() {
        GpsQuality.Fix position=usablePosition?gps.current(SystemClock.elapsedRealtimeNanos()):null;
        String status=usablePosition && position==null?"Warte auf GPS · letzter brauchbarer Fix ist veraltet":gpsStatus;
        current=new Snapshot(sessionId,projectId,state,sample,position,
            magnet==null?status+" · kein Magnetometer vorhanden":status,sensitivity,maxAccuracy);
    }
    private Notification notification(String message) {
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open=launch==null?null:PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SurveyService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MagnetRute · Begehung").setContentText(message).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"Stopp",stop).build());
        if(open!=null) builder.setContentIntent(open);return builder.build();
    }
    private void updateNotification() {
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null) manager.notify(NOTIFICATION_ID,notification("recording".equals(state)?"Messung läuft · GPS und Rohdaten werden lokal gespeichert":
            "paused".equals(state)?"Messung pausiert":gpsStatus));
    }
    private static JSONObject json(Object... pairs) {
        JSONObject result=new JSONObject();
        try {
            for(int i=0;i<pairs.length;i+=2) {
                Object value=pairs[i+1];
                if(value instanceof Double && !Double.isFinite((Double)value)) value=JSONObject.NULL;
                if(value instanceof Float && !Float.isFinite((Float)value)) value=JSONObject.NULL;
                result.put((String)pairs[i],value==null?JSONObject.NULL:value);
            }
            return result;
        } catch(JSONException e) { throw new IllegalStateException(e); }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if(instance==this) instance=null;
        if(worker!=null) worker.post(() -> {
            unregister();legacy.stop();
            if("recording".equals(state)) {
                state="recovered";try { saveState(); } catch(Exception ignored) { }
            }
            publish();store.close();thread.quitSafely();
        });
        super.onDestroy();
    }
}
