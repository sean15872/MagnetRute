package at.andi.magnetrute;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.widget.Switch;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** MagnetRute: bewusst Messgeraet, keine automatische Aussage ueber Objekte. */
public final class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int ZIP_SAVE_REQUEST = 2001;
    private static final int LOCATION_PERMISSION_REQUEST = 2002;
    private static final int BACK = 0xff0a1020;
    private static final int PANEL = 0xff151e31;
    private static final int TEXT = 0xffeaf3ff;
    private static final int SUB = 0xffa5b5cc;
    private static final int MINT = 0xff63e0cf;
    private static final String[] MODE_NAMES = {"Gesamtfeld |B|", "Vertikal zur Schwerkraft", "Horizontal zur Schwerkraft"};

    private SensorManager manager;
    private Sensor magnet, magnetUncal, gravitySensor, accelerometer;
    private MeasurementLogger recorder;
    private RodView rods;
    private GraphView graph;
    private TextView fieldView, deltaView, axesView, statusView, recView, sensitivityText, tipView, locationView;
    private Button startButton, exportButton, modeButton;
    private Spinner spinner;
    private SeekBar sensitivityBar;
    private Switch locationSwitch;
    private LocationManager locationManager;
    private Location lastLocation;
    private boolean locationRequested = false, locationRegistered = false, isForeground = false;


    private int mode = 0, accuracy = -1;
    private double sensitivity = 3.0;
    private final float[] gravity = new float[3];
    private final float[] acceleration = new float[3];
    private boolean hasGravity = false, hasAccel = false;
    private long gravityTime = 0, accelerationTime = 0, lastUpdateTime = 0;
    private final double[] filtered = {Double.NaN, Double.NaN, Double.NaN};
    private final double[] baseline = {Double.NaN, Double.NaN, Double.NaN};
    private final double[][] recent = new double[3][30];
    private int recNext = 0, recCount = 0;
    private long measuredCount = 0, crossingStart = 0;
    private boolean crossing = false;
    private String latestMagInfo = "Noch keine Messung";
    private boolean sensorsRegistered = false;
    private final float[] lastUncalibrated = {Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN};
    private long uncalTimestampNs = 0;

    private int dp(float px) { return Math.round(px * getResources().getDisplayMetrics().density); }
    private static String fmt(double v) { return Double.isFinite(v) ? String.format(Locale.GERMANY, "%.2f", v) : "—"; }
    private GradientDrawable bg(int color, int corner) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(corner)); return d;
    }
    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(BACK); b.setTextSize(13); b.setAllCaps(false);
        b.setPadding(dp(8),0,dp(8),0); b.setBackground(bg(color,12));
        b.setMinHeight(dp(45)); return b;
    }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL); return r;
    }
    private LinearLayout.LayoutParams rowWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(48),1);
        p.setMargins(dp(3),dp(5),dp(3),dp(5)); return p;
    }
    private void add(LinearLayout parent, View view, int marginBottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(marginBottom);parent.addView(view,p);
    }
    private LinearLayout section() {
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(13),dp(12),dp(13),dp(12));l.setBackground(bg(PANEL,16));return l;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        manager=(SensorManager)getSystemService(SENSOR_SERVICE);
        locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        magnet=manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        magnetUncal=manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        gravitySensor=manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelerometer=manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        recorder=new MeasurementLogger(this);
        if(magnet!=null) recorder.setSensorDescription(sensorInfo());
        createUi();
        if(magnet==null) {
            statusView.setText("Kein Magnetfeldsensor gefunden. Dieses Handy kann nicht messen.");
            startButton.setEnabled(false);
        } else {
            statusView.setText("Sensor bereit • " + magnet.getName());
        }
        exportButton.setEnabled(recorder.getLastFolder()!=null);
    }
    private String sensorInfo() {
        if (magnet == null) return "Kein Magnetometer vorhanden";
        return "Name=" + magnet.getName() + "; Hersteller=" + magnet.getVendor() +
            "; Version=" + magnet.getVersion() + "; Aufloesung=" + magnet.getResolution() +
            " uT; Max=" + magnet.getMaximumRange() + " uT; GravSensor=" +
            (gravitySensor!=null?gravitySensor.getName():"kein") +
            "; RohMagnetometer=" + (magnetUncal!=null?magnetUncal.getName():"kein") +
            "; Accelerometer=" + (accelerometer!=null?accelerometer.getName():"kein");
    }
    private void createUi() {
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BACK);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(14),dp(14),dp(30));sv.addView(root);
        TextView title=label("MagnetRute",26,TEXT,true);add(root,title,1);
        TextView subtitle=label("v0.4.0  •  Virtuelle Wünschelrute & Magnetfeld-Labor",12,SUB,false);
        add(root,subtitle,12);
        LinearLayout navigation=row();
        Button mapButton=button("Karte",MINT);
        mapButton.setOnClickListener(v -> startActivity(new Intent(this,SurveyActivity.class)));
        navigation.addView(mapButton,rowWeight());
        Button surveyButton=button("Begehungen / Einstellungen",0xffc4d3e5);
        surveyButton.setOnClickListener(v -> startActivity(new Intent(this,SurveyActivity.class).putExtra("screen","Aufzeichnungen")));
        navigation.addView(surveyButton,rowWeight());add(root,navigation,8);
        Button surveyRecording=button("Begehung auf Karte starten / pausieren",MINT);
        surveyRecording.setOnClickListener(v -> toggleSurveyRecording());add(root,surveyRecording,8);
        Button plans=button("Grundriss-Scanner öffnen",MINT);
        plans.setOnClickListener(v -> startActivity(new Intent(this,PlanActivity.class)));
        add(root,plans,12);

        statusView=label("Sensor wird gesucht ...",12,MINT,false);
        add(root,statusView,10);

        rods=new RodView(this);add(root,rods,13);

        LinearLayout reading=section();
        TextView readingHeader=label("MAGNETFELD",12,SUB,true); add(reading,readingHeader,7);
        fieldView=label("|B|: — µT",25,TEXT,true);add(reading,fieldView,3);
        deltaView=label("Δ Referenz: — µT",21,MINT,true);add(reading,deltaView,7);
        axesView=label("X —   Y —   Z — µT",13,SUB,false);add(reading,axesView,4);
        recView=label("Keine Aufzeichnung",12,SUB,false);add(reading,recView,0);
        add(root,reading,13);

        LinearLayout settings=section();
        add(settings,label("MESSMODUS",12,SUB,true),4);
        spinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,MODE_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0);
        add(settings,spinner,10);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View v,int position,long id) {
                if(mode!=position){mode=position;recorder.event("MODUS","mode="+mode+"; "+MODE_NAMES[mode]);refreshReferenceHint();}
            }
        });
        sensitivityText=label("Empfindlichkeit: 3,0 µT für vollen Ausschlag",13,TEXT,false);
        add(settings,sensitivityText,3);
        sensitivityBar=new SeekBar(this);sensitivityBar.setMax(195);
        sensitivityBar.setProgress(25);add(settings,sensitivityBar,5);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int v,boolean fromUser) {
                sensitivity=.5+v*.1;
                if(fromUser) SurveyService.sensitivity(MainActivity.this,sensitivity);
                sensitivityText.setText("Empfindlichkeit: " + fmt(sensitivity) + " µT für vollen Ausschlag");
                graph.setThreshold(sensitivity);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) {
                recorder.event("EMPFINDLICHKEIT", "uT="+sensitivity);
            }
        });
        Button reference=button("Neue Referenz setzen",MINT);
        reference.setOnClickListener(v -> newReference(true));
        add(settings,reference,5);
        tipView=label("Referenz: startet automatisch nach ~2 s • jederzeit neu setzen",11,SUB,false);
        add(settings,tipView,0);
        add(root,settings,13);

        LinearLayout locationPanel=section();
        add(locationPanel,label("STANDORT / KARTIERUNG",12,SUB,true),6);
        locationSwitch=new Switch(this);
        locationSwitch.setText("GPS im Messlabor-Log mit aufzeichnen");
        locationSwitch.setTextColor(TEXT);
        locationSwitch.setTextSize(14);
        locationSwitch.setChecked(false); // Bewusste Freigabe bei jedem App-Start.
        add(locationPanel,locationSwitch,5);
        locationView=label("Messlabor-GPS ist AUS; Begehung wird separat gesteuert.",12,SUB,false);
        add(locationPanel,locationView,7);
        add(locationPanel,label("Bei aktivierter Messung werden Koordinaten, Genauigkeit, Quelle und Zeit erfasst. " +
            "Markierungen werden für spätere Karten als GeoJSON exportiert. " +
            "In Gebäuden ist GPS häufig mehrere Meter ungenau.",11,SUB,false),0);
        locationSwitch.setOnCheckedChangeListener((v,checked)-> {
            locationRequested=checked;
            if (checked && !hasLocationPermission()) {
                locationView.setText("Standortberechtigung wird angefragt ...");
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERMISSION_REQUEST);
            } else if (checked) {
                startLocationIfForeground();
            } else {
                stopLocation();
                lastLocation=null;
                recorder.setLocationTrackingEnabled(false);
                locationView.setText("Messlabor-GPS ist AUS; Begehung wird separat gesteuert.");
            }
        });
        add(root,locationPanel,13);

        LinearLayout panel=section();
        add(panel,label("LIVE-VERLAUF (LETZTE 60 SEKUNDEN)",12,SUB,true),7);
        graph=new GraphView(this);graph.setThreshold(sensitivity);add(panel,graph,10);
        modeButton=button("Kurve: Abweichung  →  X/Y/Z",0xffc4d3e5);
        modeButton.setOnClickListener(v -> {
            graph.toggleMode();
            modeButton.setText(graph.isAxesMode()?"Kurve: X/Y/Z  →  Abweichung":"Kurve: Abweichung  →  X/Y/Z");
        });
        add(panel,modeButton,0);add(root,panel,13);

        LinearLayout actions=section();
        add(actions,label("MESSPROTOKOLL",12,SUB,true),7);
        LinearLayout buttons=row();
        startButton=button("● Aufzeichnen",MINT);
        startButton.setOnClickListener(v -> toggleRecording());
        buttons.addView(startButton,rowWeight());
        Button marker=button("Markierung",0xffa5b6db);
        marker.setOnClickListener(v -> mark());
        buttons.addView(marker,rowWeight());
        add(actions,buttons,4);
        exportButton=button("Log teilen  (ZIP + CSV + Diagnose)",0xffefc37d);
        exportButton.setOnClickListener(v -> exportLog());
        add(actions,exportButton,7);
        Button saveZip=button("Log als ZIP speichern",0xffc0cce1);
        saveZip.setOnClickListener(v -> saveLog());
        add(actions,saveZip,7);
        TextView note=label("Die CSV enthält Rohachsen, Gesamtfeld, Vertikal-/Horizontalanteil, " +
            "Referenz, Bewegung, Ausschlag, Zeitstempel und Sensorgenauigkeit. " +
            "Ereignisse/Markierungen und GPS-Fixes werden separat erfasst. " +
            "Bei freigegebenem Standort enthält das ZIP auch GeoJSON-Kartenpunkte.",11,SUB,false);
        add(actions,note,0);add(root,actions,13);

        LinearLayout hints=section();
        add(hints,label("HINWEISE FÜR VERGLEICHBARE TESTS",12,SUB,true),8);
        add(hints,label("Halte das Handy möglichst ruhig und weit weg von Magneten, " +
            "Schlüsseln und Lautsprechern. Setze eine Referenz mit Abstand zum Kamin. " +
            "Gehe dann langsam auf ihn zu und markiere die Stellen im Log. " +
            "Wiederhole den Versuch auf demselben Weg.",12,TEXT,false),8);
        add(hints,label("|B| ist von der Handy-Drehung weitgehend unabhängig. " +
            "X/Y/Z drehen sich mit dem Handy und sind KEINE festen Raumachsen. " +
            "Vertikal und Horizontal werden relativ zur Schwerkraft berechnet; " +
            "starke Bewegung kann sie verfälschen. " +
            "Magnetische Änderungen beweisen weder eine Wasserader noch ein unterirdisches Objekt.",11,SUB,false),0);
        add(root,hints,0);
        setContentView(sv);
    }

    private void refreshReferenceHint() {
        boolean ready=Double.isFinite(baseline[mode]);
        tipView.setText(ready ? "Referenz ("+MODE_NAMES[mode]+"): "+fmt(baseline[mode])+" µT" :
            "Noch keine gültige Referenz für diesen Messmodus");
    }
    private void newReference(boolean byUser) {
        if (recCount<1) {
            Toast.makeText(this,"Noch keine Sensordaten vorhanden",Toast.LENGTH_SHORT).show();return;
        }
        for(int k=0;k<3;k++) {
            double sum=0;int num=0;
            for(int j=0;j<recCount;j++) {
                double v=recent[k][j];
                if(Double.isFinite(v)){sum+=v;num++;}
            }
            if(num>0) baseline[k]=sum/num;
        }
        refreshReferenceHint();
        recorder.event(byUser?"REFERENZ_MANUELL":"REFERENZ_AUTO",
            "total_uT="+fmt(baseline[0])+"; vertical_uT="+fmt(baseline[1])+
            "; horizontal_uT="+fmt(baseline[2]));
        if(byUser) SurveyService.reference(this);
        if(byUser) Toast.makeText(this,"Referenz gesetzt (Mittelwert der letzten 3 s)",Toast.LENGTH_SHORT).show();
    }
    private void toggleSurveyRecording() {
        // The offline project recorder is independent of the displayed Activity.
        String projectId=getSharedPreferences("survey-ui",MODE_PRIVATE).getString("projectId","");
        if(!projectId.isEmpty()) {
            SurveyService.recover(this);
            if("recording".equals(SurveyService.snapshot().state)) SurveyService.pause(this);
            else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                if(("paused".equals(SurveyService.snapshot().state)||"recovered".equals(SurveyService.snapshot().state)) && projectId.equals(SurveyService.snapshot().projectId)) SurveyService.resume(this);
                else SurveyService.start(this,projectId);
            } else {
                startActivity(new Intent(this,SurveyActivity.class));
                Toast.makeText(this,"Standort für die Begehung auf der Karte freigeben.",Toast.LENGTH_LONG).show();return;
            }
            Toast.makeText(this,"Begehung wird unabhängig von der Ansicht gespeichert; Stopp / Export auf der Karte.",Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this,SurveyActivity.class));
        Toast.makeText(this,"Zuerst auf der Karte ein Grundstück auswählen.",Toast.LENGTH_LONG).show();
    }
    private void toggleRecording() {
        if(recorder.isRecording()) {
            recorder.stop();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            startButton.setText("● Aufzeichnen");
            exportButton.setEnabled(true);
            recView.setText("Aufzeichnung gestoppt • "+recorder.getCount()+" Messpunkte");
            return;
        }
        try {
            recorder.start(mode,sensitivity,sensorInfo());
            recorder.setLocationTrackingEnabled(locationRequested && hasLocationPermission() && locationRegistered);
            if (locationRequested && lastLocation != null &&
                    SystemClock.elapsedRealtimeNanos()-lastLocation.getElapsedRealtimeNanos()<=5_000_000_000L)
                recorder.onLocation(lastLocation);
            recorder.event("REFERENZ_START", "total="+fmt(baseline[0])+"; vertical="+
                fmt(baseline[1])+"; horizontal="+fmt(baseline[2]));
            startButton.setText("■ Aufnahme stoppen");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            recView.setText("● Log läuft • 0 Messpunkte");
            exportButton.setEnabled(true);
        } catch(IOException e) {
            Toast.makeText(this,"Logstart fehlgeschlagen: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    private void mark() {
        if(!recorder.isRecording()) {
            Toast.makeText(this,"Zuerst Aufzeichnung starten",Toast.LENGTH_SHORT).show();return;
        }
        EditText t=new EditText(this);
        t.setSingleLine(true);t.setHint("z.B. 1 m vor Kamin / direkt davor");
        t.setPadding(dp(15),dp(10),dp(15),dp(10));
        new AlertDialog.Builder(this).setTitle("Messpunkt markieren")
            .setMessage("Schreibe einen Kommentar für die spätere Log-Analyse.")
            .setView(t).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Speichern",(dialog,which)-> {
                String label=t.getText().toString().trim();
                recorder.event("MARKIERUNG",label.length()>0?label:"Markierung ohne Text");
                Toast.makeText(this,"Markierung gespeichert",Toast.LENGTH_SHORT).show();
            }).show();
    }
    private void saveLog() {
        try {
            recorder.export();
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
            intent.putExtra(Intent.EXTRA_TITLE,"MagnetRute_v0.4_Log_"+stamp+".zip");
            startActivityForResult(intent,ZIP_SAVE_REQUEST);
        } catch(Exception e) {
            Toast.makeText(this,"Speichern: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=ZIP_SAVE_REQUEST || resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            File z=new File(getCacheDir(),"MagnetRute_Log.zip");
            try (FileInputStream in=new FileInputStream(z);
                 OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IOException("Keine Schreibberechtigung");
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1) out.write(b,0,n);
            }
            Toast.makeText(this,"ZIP gespeichert. Jetzt hier im Chat anhängen.",Toast.LENGTH_LONG).show();
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("Speichern fehlgeschlagen")
                .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK",null).show();
        }
    }
    private void exportLog() {
        try {
            recorder.export();
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM,LogExportProvider.URI);
            send.putExtra(Intent.EXTRA_SUBJECT,"MagnetRute v0.4 Messlog");
            send.setClipData(ClipData.newRawUri("MagnetRute_Log.zip",LogExportProvider.URI));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send,"Log teilen (z.B. ChatGPT, Drive, Mail)"));
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("Logexport fehlgeschlagen")
                .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK",null).show();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        isForeground=true;
        startLocationIfForeground();
        if(magnet!=null && !sensorsRegistered) {
            manager.registerListener(this,magnet,SensorManager.SENSOR_DELAY_GAME);
            if(gravitySensor!=null) manager.registerListener(this,gravitySensor,SensorManager.SENSOR_DELAY_GAME);
            if(magnetUncal!=null) manager.registerListener(this,magnetUncal,SensorManager.SENSOR_DELAY_GAME);
            if(accelerometer!=null) manager.registerListener(this,accelerometer,SensorManager.SENSOR_DELAY_GAME);
            sensorsRegistered=true;
        }
        if(recorder!=null && recorder.isRecording()) recorder.event("APP_RESUME","Messwerte nach Rueckkehr zur App");
    }
    @Override protected void onPause() {
        if(recorder!=null && recorder.isRecording()) {
            recorder.event("APP_PAUSE","Magnet- und GPS-Logging pausieren im Hintergrund");
            recorder.flush();
        }
        isForeground=false;
        stopLocation();
        manager.unregisterListener(this);sensorsRegistered=false;
        super.onPause();
    }
    @Override protected void onDestroy() {
        stopLocation();
        if(recorder!=null) recorder.stop();
        super.onDestroy();
    }
    @Override public void onAccuracyChanged(Sensor sensor,int newAccuracy) {
        if(magnet!=null && sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD && accuracy!=newAccuracy) {
            accuracy=newAccuracy;
            recorder.event("SENSOR_ACCURACY","accuracy="+accuracy+" (0=unreliable .. 3=high)");
        }
    }
    @Override public void onSensorChanged(SensorEvent e) {
        long now=SystemClock.elapsedRealtime();
        switch(e.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                if(e.values.length >= 6) {
                    System.arraycopy(e.values,0,lastUncalibrated,0,6);
                    uncalTimestampNs=e.timestamp;
                }
                return;
            case Sensor.TYPE_GRAVITY:
                System.arraycopy(e.values,0,gravity,0,3);
                hasGravity=true;gravityTime=now;return;
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(e.values,0,acceleration,0,3);
                hasAccel=true;accelerationTime=now;
                if(gravitySensor==null) {
                    if(!hasGravity) System.arraycopy(e.values,0,gravity,0,3);
                    else for(int i=0;i<3;i++) gravity[i]=0.95f*gravity[i]+0.05f*e.values[i];
                    hasGravity=true;gravityTime=now;
                }
                return;
            case Sensor.TYPE_MAGNETIC_FIELD:
                if(now-lastUpdateTime<100) return;
                lastUpdateTime=now;
                processMag(e,now);return;
            default: return;
        }
    }
    private void processMag(SensorEvent event,long now) {
        float x=event.values[0], y=event.values[1], z=event.values[2];
        boolean freshGravity=hasGravity && now-gravityTime<=1000;
        SensorMath.Components c=SensorMath.components(x,y,z,gravity[0],gravity[1],gravity[2],freshGravity);
        double[] values={c.total,c.vertical,c.horizontal};
        for(int k=0;k<3;k++) {
            if(Double.isFinite(values[k])) {
                if(!Double.isFinite(filtered[k])) filtered[k]=values[k];
                else filtered[k]=.23*values[k]+.77*filtered[k];
            } else filtered[k]=Double.NaN;
            recent[k][recNext]=filtered[k];
        }
        recNext=(recNext+1)%30;
        recCount=Math.min(30,recCount+1);
        measuredCount++;
        if(!Double.isFinite(baseline[0]) && recCount>=20) newReference(false);
        if(freshGravity && !Double.isFinite(baseline[1]) && recCount>=20) newReference(false);
        double signal=filtered[mode];
        boolean ready=Double.isFinite(signal) && Double.isFinite(baseline[mode]);
        double delta=ready?signal-baseline[mode]:Double.NaN;
        double progress=ready?SensorMath.clamp(Math.abs(delta)/sensitivity,0,1):0;
        double motion=Double.NaN;
        if(hasAccel && freshGravity && now-accelerationTime<1000) {
            double dx=acceleration[0]-gravity[0],dy=acceleration[1]-gravity[1],dz=acceleration[2]-gravity[2];
            motion=Math.sqrt(dx*dx+dy*dy+dz*dz);
        }
        if(ready && progress>.8) {
            if(crossingStart==0) crossingStart=now;
            if(!crossing && now-crossingStart>=300) {
                crossing=true;recorder.event("AUSSCHLAG_BEGIN","mode="+mode+"; delta_uT="+fmt(delta));
            }
        } else if(progress<.6 || !ready) {
            crossingStart=0;
            if(crossing) {crossing=false;recorder.event("AUSSCHLAG_ENDE","mode="+mode+"; delta_uT="+fmt(delta));}
        }
        rods.setState(progress,ready,Double.isFinite(signal));
        graph.add(delta,x,y,z,c.total);
        fieldView.setText("|B|: "+fmt(c.total)+" µT");
        deltaView.setText("Δ Referenz: "+(ready?(delta>=0?"+":"")+fmt(delta):"—")+" µT");
        deltaView.setTextColor(progress>.75?0xffffc27c:MINT);
        axesView.setText("X: "+fmt(x)+"   Y: "+fmt(y)+"   Z: "+fmt(z)+" µT\n"+
            "Vertikal: "+fmt(c.vertical)+"   Horizontal: "+fmt(c.horizontal)+" µT");
        String quality=(accuracy==3?"hoch":accuracy==2?"mittel":accuracy==1?"niedrig":accuracy==0?"UNZUVERLÄSSIG":"unbekannt");
        boolean moving=Double.isFinite(motion) && motion>1.5;
        statusView.setText("Sensorqualität: "+quality+(moving?"  •  Handy bewegt sich":"")+
            ((!freshGravity && mode!=0)?"  •  Schwerkraft fehlt":""));
        statusView.setTextColor(accuracy==0 || moving?0xffffbb7b:MINT);
        updateLocationUi();
        if(recorder.isRecording()) {
            recorder.sample(event.timestamp,x,y,z,c,gravity,signal,baseline[mode],delta,sensitivity,mode,accuracy,motion,progress,lastUncalibrated,uncalTimestampNs);
            recView.setText("● AUFZEICHNUNG • "+recorder.getCount()+" Messpunkte (~10/s)");
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void startLocationIfForeground() {
        if (!isForeground || !locationRequested || locationRegistered || !hasLocationPermission()) return;
        if (locationManager == null) {
            locationView.setText("Kein Android-Standortdienst verfügbar.");
            return;
        }
        boolean any = false;
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f,
                    this, Looper.getMainLooper());
                any=true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f,
                    this, Looper.getMainLooper());
                any=true;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            android.util.Log.w("MagnetRute", "Standortdienst: " + e.getMessage());
        }
        locationRegistered=any;
        recorder.setLocationTrackingEnabled(any);
        locationView.setText(any ? "Standort aktiv • warte auf neue GPS-/Netzwerk-Position ..." :
            "Kein Standortanbieter aktiv. Bitte Standort am Handy einschalten.");
    }
    private void stopLocation() {
        if (locationManager!=null) {
            try { locationManager.removeUpdates(this); }
            catch (SecurityException ignored) { }
        }
        locationRegistered=false;
        if (recorder!=null) recorder.setLocationTrackingEnabled(false);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode != LOCATION_PERMISSION_REQUEST) return;
        if(hasLocationPermission() && locationRequested) {
            startLocationIfForeground();
        } else {
            locationRequested=false;
            locationSwitch.setChecked(false);
            locationView.setText("Standort nicht freigegeben. Magnetmessung läuft ohne GPS.");
            recorder.event("GPS_VERWEIGERT","Berechtigung nicht erteilt");
        }
    }
    @Override public void onLocationChanged(Location location) {
        if(!locationRequested || !isForeground || location == null) return;
        lastLocation=new Location(location);
        recorder.onLocation(lastLocation);
        updateLocationUi();
    }
    @Override public void onProviderEnabled(String provider) { startLocationIfForeground(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { updateLocationUi(); }
    @Override public void onProviderDisabled(String provider) { updateLocationUi(); }
    private void updateLocationUi() {
        if(locationView==null || !locationRequested) return;
        if(lastLocation==null) {
            if(locationRegistered) locationView.setText("Standort aktiv • warte auf ersten Fix ...");
            return;
        }
        long ageMs=Math.max(0,(SystemClock.elapsedRealtimeNanos()-lastLocation.getElapsedRealtimeNanos())/1_000_000L);
        String permission=(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            ? "genau freigegeben" : "nur ungefähr";
        if(ageMs>5000) {
            locationView.setText("Standort-Fix veraltet ("+(ageMs/1000)+" s) • warte auf neuen Fix • "+permission);
        } else {
            String coords=String.format(Locale.US,"%.6f, %.6f",lastLocation.getLatitude(),lastLocation.getLongitude());
            locationView.setText(coords+"  •  ±"+(lastLocation.hasAccuracy()?Math.round(lastLocation.getAccuracy())+" m":"? m")+
                "  •  "+lastLocation.getProvider()+"  •  "+permission);
        }
    }
}
