package at.andi.magnetrute;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline field workspace. Only an explicit preparation/download/search action may use the network. */
public final class SurveyActivity extends Activity implements LocationListener {
    private static final int GPS_PERMISSION=4100,EXPORT=4101,IMPORT=4102,LEGACY=4103,PHOTO=4104;
    private static final ExecutorService IO=Executors.newFixedThreadPool(3);
    private static volatile DownloadJob download;
    private static final String[] CATEGORIES={"Baum","Drainage","Brunnen","Wasserleitung","Stromleitung",
        "Wünschelruten-Markierung","Kreuzung","Auffälligkeit","Sonstiges"};
    private SurveyStore store;
    private SharedPreferences prefs;
    private JSONObject project;
    private OfflineMapView map;
    private LinearLayout root,content,controls;
    private TextView status,legend,stageHint;
    private Button recordButton,stopButton;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final GpsQuality locateQuality=new GpsQuality();
    private LocationManager locations;
    private boolean locating,pendingLocate,onlinePreparation,following=true,lineWalking,comparison;
    private boolean polygons=true,markers=true,lines=true,path=true,heat=false,base=true,busy;
    private String screen="Karte",editType,editingId,photoFeature,pendingPermission,jobStatus="";
    private final List<double[]> editPoints=new ArrayList<>();
    private final Set<String> selectedSessions=new HashSet<>();
    private String heatMode="off";
    private long lastRefresh;
    private List<JSONObject> cachedSamples=new ArrayList<>();

    private static final class DownloadJob {
        final String projectId;volatile boolean pause,running=true;volatile int done,total;volatile long bytes;volatile String error="";
        DownloadJob(String id) { projectId=id; }
    }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private static JSONObject obj(Object... values) {
        JSONObject o=new JSONObject();try { for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]); }
        catch(Exception e) { throw new IllegalArgumentException(e); }return o;
    }
    private static JSONArray coord(double lon,double lat) { try{return new JSONArray(new double[]{lon,lat});}catch(Exception e){throw new IllegalArgumentException("Ungültige Koordinate",e);} }
    private static String id() { return UUID.randomUUID().toString(); }
    private void put(JSONObject o,String key,Object value) { try{o.put(key,value);}catch(Exception e){throw new IllegalArgumentException(e);} }

    @Override public void onCreate(Bundle saved) {
        prefs=getSharedPreferences("survey-ui",MODE_PRIVATE);
        if(prefs.getBoolean("light",false))setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);store=new SurveyStore(this);
        locations=(LocationManager)getSystemService(LOCATION_SERVICE);SurveyService.recover(this);
        String selected=saved==null?prefs.getString("projectId",""):saved.getString("projectId","");
        if(!selected.isEmpty()) try { project=store.project(selected); } catch(Exception e) { error(e); }
        if(saved!=null) {
            screen=saved.getString("screen","Karte");editType=saved.getString("editType");editingId=saved.getString("editingId");photoFeature=saved.getString("photoFeature");
            try { JSONArray a=new JSONArray(saved.getString("editPoints","[]"));for(int i=0;i<a.length();i++)editPoints.add(new double[]{a.getJSONArray(i).getDouble(0),a.getJSONArray(i).getDouble(1)}); }catch(Exception ignored) { editPoints.clear();editType=null; }
        }
        // A cold start always uses local tiles; preparation must be explicitly re-enabled.
        heatMode=prefs.getString("heatMode","off");polygons=prefs.getBoolean("polygons",true);markers=prefs.getBoolean("markers",true);lines=prefs.getBoolean("lines",true);path=prefs.getBoolean("path",true);heat=prefs.getBoolean("heat",false);base=prefs.getBoolean("base",true);following=prefs.getBoolean("follow",true);
        if(!prefs.getBoolean("v042MapDefaults",false)){
            heatMode="off";heat=false;
            prefs.edit().putBoolean("v042MapDefaults",true).apply();
        }
        if(saved==null)screen=getIntent().getStringExtra("screen")==null?"Karte":getIntent().getStringExtra("screen");
        onlinePreparation=false;showScreen(screen);verifyPackage();
    }
    private TextView label(String value,int size) { TextView t=new TextView(this);t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);t.setText(value);t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());t.setTextColor(prefs.getBoolean("light",false)?0xff15243b:0xffeaf3ff);t.setTextSize(size);t.setPadding(dp(5),dp(3),dp(5),dp(3));return t; }
    private Button button(LinearLayout parent,String value,Runnable action) {
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(value);b.setTextSize(13);b.setTextColor(0xffeef6ff);
        b.setPadding(dp(10),0,dp(10),0);
        b.setMinHeight(dp(44));
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(0xff25334c);
        shape.setCornerRadius(dp(13));
        shape.setStroke(dp(1),0xff354962);
        b.setBackground(shape);
        b.setOnClickListener(v -> { if(!busy) try { action.run(); } catch(Exception e) { error(e); } });
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(3),dp(2),dp(3),dp(2));
        parent.addView(b,p);return b;
    }
    private LinearLayout row(LinearLayout parent) {
        HorizontalScrollView sv=new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout r=new LinearLayout(this);sv.addView(r);parent.addView(sv);return r;
    }
    private void roundedPanel(LinearLayout panel,int color,int radius) {
        GradientDrawable b=new GradientDrawable();
        b.setColor(color);b.setCornerRadius(dp(radius));panel.setBackground(b);
    }
    private void weighted(Button b,int weight) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),weight);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));b.setLayoutParams(p);
    }
    private void error(Exception e) { if(!isDestroyed())new AlertDialog.Builder(this).setTitle("Vorgang nicht möglich").setMessage(e.getMessage()==null?"Bitte Eingaben und Datei prüfen.":e.getMessage()).setPositiveButton("OK",null).show(); }
    private void info(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK",null).show(); }
    private String projectId() { return project==null?null:project.optString("id"); }
    private boolean requireProject() { if(project!=null)return true;info("Zuerst ein Grundstück öffnen oder eine neue Fläche vorbereiten.");return false; }
    private void saveProject() { if(project!=null) {
        JSONObject latest=store.project(projectId());
        if(latest!=null&&download!=null&&!download.running&&projectId().equals(download.projectId)&&!latest.optString("mapPath").equals(project.optString("mapPath"))) {
            for(String k:new String[]{"mapPath","offlineReady","source","attribution","mapInfo"})put(project,k,latest.opt(k));
            project.remove("pendingMap");project.remove("pendingPlan");
        }
        store.putProject(project);prefs.edit().putString("projectId",projectId()).apply();
    } }
    private void saveViewportState() {
        if(project==null)return;JSONObject latest=store.project(projectId());if(latest==null)return;
        for(String key:new String[]{"latitude","longitude","zoom","rotation"})if(project.has(key))put(latest,key,project.opt(key));
        store.putProject(latest);
    }
    private void verifyPackage() {
        if(project==null||!project.optBoolean("offlineReady"))return;
        final String pid=projectId();final File file=mapFile(project);jobStatus="Offline-Paket wird auf Vollständigkeit geprüft …";
        IO.execute(()->{boolean valid=OfflineTiles.validate(file);handler.post(()->{
            if(isDestroyed()||!pid.equals(projectId()))return;
            jobStatus=valid?"Offline-Paket vollständig geprüft":"Paket fehlt oder ist beschädigt. Keine Online-Ersatzkacheln; Offline speichern zum Reparieren wählen.";
            if(!valid){put(project,"offlineReady",false);saveProject();}
        });});
    }
    private File mapFile(JSONObject p) { return new File(new File(getFilesDir(),"offline-maps"),p.optString("mapPath","missing.mbtiles")); }

    private void showScreen(String value) {
        screen=value;map=null;recordButton=null;stopButton=null;legend=null;stageHint=null;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0a1423);
        LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(5),dp(3),dp(5),dp(3));tabs.setBackgroundColor(0xff121f32);
        Button lab=button(tabs,"Messen",()->startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        Button maps=button(tabs,"Karte",()->showScreen("Karte"));
        Button records=button(tabs,"Verlauf",()->showScreen("Aufzeichnungen"));
        Button settings=button(tabs,"Einstellungen",()->showScreen("Einstellungen"));
        weighted(lab,1);weighted(maps,1);weighted(records,1);weighted(settings,2);
        for(Button tab:new Button[]{lab,maps,records,settings}) {
            tab.setSingleLine(true);
            tab.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        Button active="Karte".equals(screen)?maps:"Aufzeichnungen".equals(screen)?records:"Einstellungen".equals(screen)?settings:lab;
        prominent(active,0xff266d86);
        root.addView(tabs);
        status=label("MagnetRute",12);
        if(!"Karte".equals(screen))root.addView(status);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if("Karte".equals(screen))showMap();else if("Aufzeichnungen".equals(screen))showRecords();else showSettings();
        lastRefresh=0;refresh();
    }
    private void prominent(Button b,int color) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(14));
        b.setBackground(shape);b.setTextColor(0xffffffff);b.setTextSize(15);b.setMinHeight(dp(48));
    }
    private String mapGuide() {
        if("Polygon".equals(editType))return "Grundstück: "+editPoints.size()+" Eckpunkte gesetzt. Auf die Karte tippen (mindestens 3), dann Fertig.";
        if("LineString".equals(editType))return "Linie: Eckpunkte auf der Karte setzen, danach Fertig wählen.";
        if("Point".equals(editType))return "Auf die Karte tippen, um die Markierung zu setzen.";
        if(project==null)return "1/3  Ort suchen oder Standort holen. Luftbild laden und Karte zoomen; danach Grundstück einzeichnen.";
        if(project.optJSONObject("geometry")==null)return "2/3  Grundstück auf der Karte durch Antippen der Ecken einzeichnen.";
        if(!project.optBoolean("offlineReady"))return "3/3  Fläche gespeichert. Jetzt den Luftbildausschnitt für die Offline-Nutzung herunterladen.";
        return "Offline bereit. Aufnahme starten, beim Gehen Markierungen setzen oder zur Heatmap wechseln.";
    }
    private void activateOnlinePreview() {
        if(project!=null&&project.optBoolean("offlineReady")) {
            info("Diese Fläche ist offline gespeichert. Für einen anderen Ort bitte unter Mehr ein neues Grundstück anlegen.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Luftbild online anzeigen?")
            .setMessage("Nur Kartenkacheln für die gewählte Ansicht werden vom Kartenanbieter geladen. Messwerte bleiben lokal. Später kannst du den Grundstücksausschnitt offline speichern.")
            .setNegativeButton("Abbrechen",null).setPositiveButton("Karte laden",(d,w)-> {
                onlinePreparation=true;
                if(map!=null) {
                    JSONObject shown=project==null?obj("latitude",map.currentLatitude(),"longitude",map.currentLongitude(),"zoom",map.currentZoom(),"rotation",map.currentRotation(),"source",OfflineTiles.BASEMAP_ORTHO):project;
                    map.setProject(shown,project==null?null:mapFile(project),false);
                    map.refreshTiles();
                }
                if(stageHint!=null)stageHint.setText(mapGuide());
                jobStatus="Online-Luftbild aktiv. Nach dem Einzeichnen Offline-Karte speichern.";
            }).show();
    }
    private void mapMenu() {
        String[] names={"Meine Grundstücke", "Neues Grundstück", "Kartenquelle auswählen",
            "Offline-Karte speichern", "Grundstück bearbeiten", "Markierung auf Karte setzen",
            "Drainage / Leitung einzeichnen", "Drainagelinie mitgehen", "Ansicht / Heatmap",
            "Ebenen ein-/ausblenden", "GPS-Nachführung umschalten", "Neuer Messdurchgang",
            "Referenz setzen", "Aufzeichnungen", "Einstellungen"};
        new AlertDialog.Builder(this).setTitle("Weitere Kartenfunktionen").setItems(names,(d,n)->{
            switch(n) {
                case 0: chooseProject();break;
                case 1: newProject();break;
                case 2: sourceDialog();break;
                case 3: downloadDialog();break;
                case 4: if(requireProject()){beginEdit("Polygon",projectId());showScreen("Karte");}break;
                case 5: if(requireProject()){editType="Point";editingId=null;editPoints.clear();showScreen("Karte");}break;
                case 6: if(requireProject()){beginEdit("LineString",null);showScreen("Karte");}break;
                case 7: walkingLine();break;
                case 8: viewDialog();break;
                case 9: layersDialog();break;
                case 10: if(map!=null){following=!map.isFollowing();map.setFollow(following);}break;
                case 11: SurveyService.newPass(this);break;
                case 12: SurveyService.reference(this);break;
                case 13: showScreen("Aufzeichnungen");break;
                case 14: showScreen("Einstellungen");break;
                default:break;
            }
        }).show();
    }
    private void showMap() {
        FrameLayout stage=new FrameLayout(this);
        content.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

        map=new OfflineMapView(this);
        JSONObject shown=project==null?
            obj("latitude",prefs.getFloat("browseLat",47.2f),"longitude",prefs.getFloat("browseLon",14.3f),
                "zoom",prefs.getInt("browseZoom",7),"source",OfflineTiles.BASEMAP_ORTHO):project;
        boolean offline=(project!=null&&project.optBoolean("offlineReady")) || !onlinePreparation;
        map.setProject(shown,project==null?null:mapFile(project),offline);
        map.setFollow(following);
        map.setListener(new OfflineMapView.Listener() {
            @Override public void tap(double lat,double lon) { mapTap(lat,lon); }
            @Override public void longPress(double lat,double lon) { selectFeature(lat,lon); }
            @Override public void pointMoved(int index,double lat,double lon) {
                if(index>=0&&index<editPoints.size()){editPoints.set(index,new double[]{lon,lat});refreshEditing();}
            }
            @Override public void viewport(double lat,double lon,int zoom,float rotation) {
                if(project!=null) {
                    put(project,"latitude",lat);put(project,"longitude",lon);
                    put(project,"zoom",zoom);put(project,"rotation",rotation);
                    handler.removeCallbacks(saveViewport);handler.postDelayed(saveViewport,500);
                } else prefs.edit().putFloat("browseLat",(float)lat).putFloat("browseLon",(float)lon).putInt("browseZoom",zoom).apply();
            }
        });
        stage.addView(map,new FrameLayout.LayoutParams(-1,-1));

        // Only three prominent map tools: search, GPS, additional actions.
        controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(5),dp(5),dp(5),dp(5));
        roundedPanel(controls,0xf10e1d31,18);
        Button search=button(controls,"⌕ Ort suchen",this::searchPlaces);
        Button gps=button(controls,"◎ GPS",this::locate);
        Button more=button(controls,"⋯ Mehr",this::mapMenu);
        weighted(search,2);weighted(gps,1);weighted(more,1);
        FrameLayout.LayoutParams bar=new FrameLayout.LayoutParams(-1,dp(62),Gravity.TOP);
        bar.setMargins(dp(10),dp(10),dp(10),0);stage.addView(controls,bar);

        // Controls float over the map instead of consuming half the map's height.
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(10),dp(9),dp(10),dp(9));
        roundedPanel(sheet,0xf40e1b2e,20);
        status.setTextSize(12);status.setMaxLines(2);sheet.addView(status);
        stageHint=label(mapGuide(),13);stageHint.setMaxLines(2);sheet.addView(stageHint);
        legend=label("",11);sheet.addView(legend);
        TextView attribution=label(project!=null&&OfflineTiles.GIS_STMK.equals(project.optString("source"))?
            "Luftbild: Land Steiermark · Karte & GPS können abweichen":
            "Luftbild: basemap.at · Karte & GPS können abweichen",10);
        sheet.addView(attribution);

        if(editType!=null) {
            LinearLayout editActions=new LinearLayout(this);
            Button done=button(editActions,"✓ Fertig",()->{finishEdit();if(editType==null)showScreen("Karte");});
            // Keep testable button name consistent with the existing UI tests.
            done.setText("Fertig");prominent(done,0xff19816b);
            Button undo=button(editActions,"↶ Zurück",()->{if(!editPoints.isEmpty()){editPoints.remove(editPoints.size()-1);refreshEditing();}});
            Button cancel=button(editActions,"Abbrechen",()->{editType=null;editingId=null;editPoints.clear();showScreen("Karte");});
            weighted(done,2);weighted(undo,1);weighted(cancel,1);
            sheet.addView(editActions);
        } else if(project==null || project.optJSONObject("geometry")==null) {
            Button begin=button(sheet,"+ Grundstück einzeichnen",()->{
                if(project==null)newProject();
                else {beginEdit("Polygon",projectId());showScreen("Karte");}
            });prominent(begin,0xff19816b);
            begin.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(48)));
        } else if(!project.optBoolean("offlineReady")) {
            Button save=button(sheet,"Offline-Karte herunterladen",this::downloadDialog);
            prominent(save,0xff19816b);
            save.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(48)));
        } else {
            LinearLayout views=new LinearLayout(this);
            Button baseButton=button(views,"Karte",()->{base=true;heatMode="off";heat=false;path=true;lastRefresh=0;showScreen("Karte");});
            Button trackButton=button(views,"Spur",()->{base=true;heatMode="track";heat=false;path=true;lastRefresh=0;showScreen("Karte");});
            Button heatButton=button(views,"Heatmap",()->{base=true;heatMode="delta";heat=true;path=true;lastRefresh=0;showScreen("Karte");});
            weighted(baseButton,1);weighted(trackButton,1);weighted(heatButton,1);
            prominent("delta".equals(heatMode)?heatButton:"track".equals(heatMode)?trackButton:baseButton,0xff226d83);
            sheet.addView(views);
            LinearLayout actions=new LinearLayout(this);
            recordButton=button(actions,"Aufnahme starten",this::toggleRecord);
            prominent(recordButton,0xff19816b);weighted(recordButton,2);
            stopButton=button(actions,"Stopp",()->{SurveyService.stop(this);handler.postDelayed(()->showScreen("Karte"),450);});
            prominent(stopButton,0xffaa3647);weighted(stopButton,1);
            Button mark=button(actions,"+ Marker",this::gpsMarker);
            prominent(mark,0xff2a6195);weighted(mark,2);
            sheet.addView(actions);
        }
        FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        bottom.setMargins(dp(9),0,dp(9),dp(12));
        stage.addView(sheet,bottom);
        refreshEditing();
    }
    private final Runnable saveViewport=() -> { if(!isDestroyed())try {saveViewportState();}catch(Exception e){error(e);} };
    private void chooseProject() {
        List<JSONObject> projects=store.projects();String[] names=new String[projects.size()];
        for(int i=0;i<names.length;i++)names[i]=projects.get(i).optString("name")+(projects.get(i).optBoolean("offlineReady")?" · offline":" · unvollständig");
        new AlertDialog.Builder(this).setTitle("Gespeicherte Grundstücke").setItems(names,(d,w) -> {
            project=projects.get(w);onlinePreparation=false;editType=null;editingId=null;editPoints.clear();selectedSessions.clear();saveProject();showScreen("Karte");verifyPackage();
        }).setNeutralButton("Projekt sichern / importieren",(d,w) -> showScreen("Aufzeichnungen")).setNegativeButton("Schließen",null).show();
    }
    private void newProject() {
        double lat=map==null?47.2:map.currentLatitude(),lon=map==null?14.3:map.currentLongitude();
        int zoom=map==null?7:map.currentZoom();
        project=obj("id",id(),"name","Neues Grundstück","latitude",lat,"longitude",lon,"zoom",zoom,"rotation",0,
            "source",OfflineTiles.BASEMAP_ORTHO,"offlineReady",false,"attribution","Datenquelle: basemap.at");
        onlinePreparation=true;selectedSessions.clear();saveProject();
        beginEdit("Polygon",projectId());showScreen("Karte");
    }
    private void beginEdit(String type,String existing) {
        if(!requireProject())return;
        if("Polygon".equals(type)&&project.optBoolean("offlineReady")){info("Eine gespeicherte Fläche kann bearbeitet werden; das vorhandene Kartenpaket deckt weiterhin nur den bisherigen Ausschnitt ab. Für neue Bereiche anschließend neu herunterladen.");}
        editType=type;editingId=existing;editPoints.clear();
        JSONObject g="Polygon".equals(type)?project.optJSONObject("geometry"):null;
        if(g!=null) try {JSONArray ring=g.getJSONArray("coordinates").getJSONArray(0);for(int i=0;i<ring.length()-1;i++)editPoints.add(new double[]{ring.getJSONArray(i).getDouble(0),ring.getJSONArray(i).getDouble(1)});}catch(Exception e){error(e);}
        jobStatus="Polygon".equals(type)?"Flächenecken antippen, danach Fertig wählen.":"Linienpunkte antippen, danach Fertig wählen.";refreshEditing();
    }
    private void refreshEditing() { if(map!=null)map.setEditing(editPoints,"Polygon".equals(editType));if(stageHint!=null)stageHint.setText(mapGuide()); }
    private void mapTap(double lat,double lon) {
        if(editType==null){selectFeature(lat,lon);return;}
        if("Point".equals(editType)){
            if(editingId!=null){editPoints.clear();editPoints.add(new double[]{lon,lat});refreshEditing();return;}
            featureDialog(obj("id",id(),"projectId",projectId(),"geometry",obj("type","Point","coordinates",coord(lon,lat)),"type","Sonstiges","originalGps",JSONObject.NULL));editType=null;return;
        }
        editPoints.add(new double[]{lon,lat});refreshEditing();
    }
    private void removePoint() {
        if(editPoints.isEmpty())return;
        String[] choices=new String[editPoints.size()];for(int i=0;i<choices.length;i++)choices[i]="Punkt "+(i+1);
        new AlertDialog.Builder(this).setTitle("Welchen Punkt löschen?").setItems(choices,(d,w) -> {editPoints.remove(w);refreshEditing();}).show();
    }
    private JSONArray editRing(boolean close) {
        JSONArray a=new JSONArray();for(double[] p:editPoints)a.put(coord(p[0],p[1]));if(close&&!editPoints.isEmpty())a.put(coord(editPoints.get(0)[0],editPoints.get(0)[1]));return a;
    }
    private void finishEdit() {
        if(editType==null)return;
        if("Polygon".equals(editType)) {
            if(editPoints.size()<3){info("Mindestens drei Flächenecken setzen.");return;}
            JSONObject geometry=obj("type","Polygon","coordinates",new JSONArray().put(editRing(true)));
            askText("Fläche benennen",project.optString("name"),name -> {put(project,"name",name);
                if(!geometry.toString().equals(String.valueOf(project.optJSONObject("geometry"))))put(project,"offlineReady",false);
                put(project,"geometry",geometry);saveProject();editType=null;editingId=null;editPoints.clear();refreshEditing();jobStatus="Fläche gespeichert. Jetzt das Luftbild offline herunterladen.";showScreen("Karte");});
        } else if("Point".equals(editType)&&editingId!=null&&!editPoints.isEmpty()) {
            JSONObject feature=findFeature(editingId);put(feature,"geometry",obj("type","Point","coordinates",coord(editPoints.get(0)[0],editPoints.get(0)[1])));
            put(feature,"correctedUnixMs",System.currentTimeMillis());store.putFeature(feature);editType=null;editingId=null;editPoints.clear();refreshEditing();lastRefresh=0;
        } else if("LineString".equals(editType)) {
            if(editPoints.size()<2){info("Mindestens zwei Linienpunkte setzen.");return;}
            JSONObject feature=editingId==null?obj("id",id(),"projectId",projectId(),"type","Drainage"):findFeature(editingId);
            put(feature,"geometry",obj("type","LineString","coordinates",editRing(false)));featureDialog(feature);
        }
    }
    private interface TextResult { void accept(String value); }
    private void askText(String title,String initial,TextResult done) {
        EditText edit=new EditText(this);edit.setText(initial);edit.setSingleLine();
        AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(edit).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();
        d.setOnShowListener(v -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {String s=edit.getText().toString().trim();if(s.isEmpty()||s.length()>120){edit.setError("1–120 Zeichen eingeben");return;}d.dismiss();done.accept(s);}));d.show();
    }
    private void sourceDialog() {
        if(!requireProject())return;
        new AlertDialog.Builder(this).setTitle("Kartenquelle für nächsten Download").setItems(new String[]{"basemap.at Orthofoto","basemap.at Standard","GIS Steiermark OGD (nur Steiermark)"},(d,w) -> {
            String source=new String[]{OfflineTiles.BASEMAP_ORTHO,OfflineTiles.BASEMAP_STANDARD,OfflineTiles.GIS_STMK}[w];
            put(project,"downloadSource",source);
            if(!project.optBoolean("offlineReady")){put(project,"source",source);onlinePreparation=true;}
            saveProject();showScreen("Karte");
            if(w==2)info("GIS Steiermark liefert außerhalb der Abdeckung gegebenenfalls weiße NoData-Flächen. Vor dem Speichern die Vorschau prüfen. basemap.at ist der österreichweite Standard.");
        }).setNeutralButton("Online-Vorbereitung fortsetzen",(d,w) -> {if(!project.optBoolean("offlineReady")){onlinePreparation=true;showScreen("Karte");}else info("Das aktive Offline-Paket bleibt offline. Für einen anderen Ort eine neue Fläche vorbereiten.");}).show();
    }
    private EditText number(LinearLayout parent,String hint,String value) {
        EditText e=new EditText(this);e.setSingleLine();e.setHint(hint);e.setText(value);e.setContentDescription(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);parent.addView(label(hint,12));parent.addView(e);return e;
    }
    private static double number(EditText edit) { return Double.parseDouble(edit.getText().toString().trim().replace(',','.')); }
    private void downloadDialog() {
        if(!requireProject())return;
        if(download!=null&&download.running){info("Ein Download läuft bereits. Bei Bedarf zuerst pausieren.");return;}
        JSONObject geometry=project.optJSONObject("geometry");if(geometry==null){info("Zuerst Polygon zeichnen und abschließen.");return;}
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        EditText buffer=number(fields,"Rand in Metern",project.optString("downloadBuffer","30"));
        EditText min=number(fields,"Kleinster Zoom (Übersicht)",project.optString("downloadMinZoom","14"));
        EditText max=number(fields,"Größter Zoom (Detail)",project.optString("downloadMaxZoom","20"));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Offline-Kartenpaket planen").setView(fields).setNegativeButton("Abbrechen",null).setPositiveButton("Berechnen",null).create();
        d.setOnShowListener(v -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            try {
                double b=number(buffer);int z0=(int)number(min),z1=(int)number(max);
                OfflineTiles.Plan plan=OfflineTiles.estimate(geometry.optJSONArray("coordinates").optJSONArray(0),b,z0,z1,project.optString("downloadSource",project.optString("source",OfflineTiles.BASEMAP_ORTHO)));
                d.dismiss();
                String detail=String.format(Locale.GERMANY,"%d Kacheln · Zoom %d–%d\nGeschätzt %.1f MiB (Größe variiert)\nAuflösung ca. %.2f m/Pixel; GPS bleibt typischerweise mehrere Meter ungenau.\nFrei: %.0f MiB · Limit: 250 MiB / 4000 Kacheln\n%s\nNach Bestätigung wird nur dieses begrenzte Paket geladen.",plan.tileCount,z0,z1,plan.estimatedBytes/1048576.0,plan.resolutionMeters,getFilesDir().getUsableSpace()/1048576.0,plan.attribution);
                new AlertDialog.Builder(this).setTitle("Kartenmaterial herunterladen?").setMessage(detail).setNegativeButton("Abbrechen",null)
                    .setPositiveButton("Herunterladen / fortsetzen",(a,k) -> {
                        put(project,"downloadBuffer",b);put(project,"downloadMinZoom",z0);put(project,"downloadMaxZoom",z1);startDownload(plan);
                    }).show();
            }catch(Exception e){error(e);}
        }));d.show();
    }
    private void startDownload(OfflineTiles.Plan plan) {
        String previous=project.optString("pendingPlan","");String filename=project.optString("pendingMap","");
        if(!plan.json().toString().equals(previous)||!filename.matches("[0-9a-f-]{36}\\.mbtiles"))filename=id()+".mbtiles";
        put(project,"pendingMap",filename);put(project,"pendingPlan",plan.json().toString());saveProject();
        File target=new File(new File(getFilesDir(),"offline-maps"),filename);String pid=projectId();
        DownloadJob job=new DownloadJob(pid);job.total=plan.tileCount;download=job;
        android.content.Context app=getApplicationContext();
        IO.execute(() -> {
            try {
                OfflineTiles.download(target,plan,(done,total,bytes) -> {job.done=done;job.total=total;job.bytes=bytes;},() -> job.pause);
                boolean ready=OfflineTiles.validate(target);
                if(ready)try(SurveyStore db=new SurveyStore(app)){
                    JSONObject p=db.project(pid);
                    if(p!=null){
                        OfflineTiles.Plan currentPlan=OfflineTiles.estimate(p.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0),p.optDouble("downloadBuffer",30),plan.minZoom,plan.maxZoom,plan.source);
                        if(!currentPlan.json().toString().equals(plan.json().toString()))throw new java.io.IOException("Fläche während des Downloads geändert; Offline speichern erneut wählen.");
                        String oldMap=p.optString("mapPath");p.put("mapPath",target.getName());p.put("offlineReady",true);p.put("source",plan.source);p.put("attribution",plan.attribution);
                        try(OfflineTiles local=new OfflineTiles(target)){p.put("mapInfo",local.metadata());}
                        p.remove("pendingMap");p.remove("pendingPlan");db.putProject(p);
                        if(!oldMap.isEmpty()&&!oldMap.equals(target.getName())){boolean used=false;for(JSONObject other:db.projects())used|=oldMap.equals(other.optString("mapPath"));if(!used)new File(target.getParentFile(),oldMap).delete();}
                    }
                }
                job.error=ready?"Offline bereit":"Pausiert · Offline speichern zum Fortsetzen";
            }catch(Exception e){job.error="Download unterbrochen: "+e.getMessage()+" · Offline speichern zum Fortsetzen";}
            finally {job.running=false;}
        });
    }

    private boolean fine() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    private void locate() {
        pendingLocate=true;
        if(!fine()){pendingPermission="locate";requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},GPS_PERMISSION);return;}
        if(locations==null||!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){info("Standort/GPS am Gerät einschalten. GNSS funktioniert auch ohne Internet; der erste Fix kann dauern.");return;}
        try {locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());locating=true;jobStatus="Warte auf GPS · keine alte Position wird übernommen";}
        catch(SecurityException e){locating=false;jobStatus="Standortberechtigung wurde entzogen.";error(e);}
    }
    @Override public void onLocationChanged(Location location) {
        GpsQuality.Fix fix=new GpsQuality.Fix(location.getLatitude(),location.getLongitude(),location.hasAccuracy()?location.getAccuracy():Double.NaN,
            location.getElapsedRealtimeNanos(),location.getTime(),location.getProvider(),location.hasSpeed()?location.getSpeed():Double.NaN);
        GpsQuality.Decision decision=locateQuality.inspect(fix,SystemClock.elapsedRealtimeNanos(),SurveyService.snapshot().maxAccuracy);
        jobStatus=decision.accepted?"GPS ±"+Math.round(fix.accuracy)+" m":"Warte auf brauchbares GPS · "+decision.reason;
        if(decision.accepted){if(map!=null){map.setLocation(fix);if(pendingLocate){map.center(fix.lat,fix.lon);if(project==null||project.optInt("zoom",7)<14)map.setZoom(17);pendingLocate=false;}}
            if(lineWalking){if(decision.breakTrack&&!editPoints.isEmpty()){persistWalkingDraft();editingId=null;editPoints.clear();jobStatus="GPS-Lücke: neuer Linienabschnitt; vorheriger Abschnitt bleibt gespeichert.";}appendWalking(fix);}}
    }
    @Override public void onProviderDisabled(String provider) { jobStatus="GPS ausgefallen · keine genaue Position verfügbar";locateQuality.reset(); }
    @Override public void onProviderEnabled(String provider) {jobStatus="Warte auf GPS";}
    @Override public void onStatusChanged(String provider,int value,Bundle extras) { }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);if(request!=GPS_PERMISSION)return;
        if(fine()){if("record".equals(pendingPermission))toggleRecord();else locate();}else info("Ohne genauen Standort bleiben Karte und manuelle Markierungen nutzbar. GPS-Begehung benötigt die Standortfreigabe.");pendingPermission=null;
    }
    private GpsQuality.Fix currentFix() {
        SurveyService.Snapshot s=SurveyService.snapshot();return s.fix!=null?s.fix:locateQuality.current(SystemClock.elapsedRealtimeNanos());
    }
    private void toggleRecord() {
        if(!requireProject())return;SurveyService.Snapshot s=SurveyService.snapshot();
        if("recording".equals(s.state)){SurveyService.pause(this);return;}
        if(!fine()){pendingPermission="record";requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},GPS_PERMISSION);return;}
        if(("paused".equals(s.state)||"recovered".equals(s.state))&&projectId().equals(s.projectId))SurveyService.resume(this);
        else SurveyService.start(this,projectId());
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4105);
        lastRefresh=0;
    }
    private JSONObject gpsJson(GpsQuality.Fix fix) { return obj("lat",fix.lat,"lon",fix.lon,"accuracy",fix.accuracy,"elapsedNs",fix.elapsedNs,"unixMs",fix.unixMs,"provider",fix.provider); }
    private void gpsMarker() {
        if(!requireProject())return;GpsQuality.Fix fix=currentFix();if(fix==null){info("Warte auf brauchbares, aktuelles GPS. Alternativ Punkt auf Karte wählen; es wird keine Position erfunden.");return;}
        featureDialog(obj("id",id(),"projectId",projectId(),"geometry",obj("type","Point","coordinates",coord(fix.lon,fix.lat)),"type","Sonstiges","originalGps",gpsJson(fix),"unixMs",System.currentTimeMillis()));
    }
    private JSONObject findFeature(String fid) { for(JSONObject f:store.features(projectId()))if(fid.equals(f.optString("id")))return f;throw new IllegalArgumentException("Markierung nicht gefunden."); }
    private void featureDialog(JSONObject feature) {
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        Spinner category=new Spinner(this);category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,CATEGORIES));
        for(int i=0;i<CATEGORIES.length;i++)if(CATEGORIES[i].equals(feature.optString("type")))category.setSelection(i);fields.addView(category);
        EditText name=new EditText(this);name.setSingleLine();name.setHint("Name");name.setText(feature.optString("name"));fields.addView(name);
        EditText note=new EditText(this);note.setHint("Notiz");note.setText(feature.optString("note"));fields.addView(note);
        Spinner style=new Spinner(this);String[] styles={"Türkis · durchgezogen","Orange · gestrichelt","Weiß · durchgezogen"};style.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,styles));style.setSelection(feature.optInt("styleIndex",0));fields.addView(style);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Markierung / Linie").setView(fields).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).setNeutralButton("Speichern + Foto",null).create();
        d.setOnShowListener(v -> {
            View.OnClickListener save=w -> {
                String n=name.getText().toString().trim();if(n.isEmpty())n=CATEGORIES[category.getSelectedItemPosition()];if(n.length()>120){name.setError("Maximal 120 Zeichen");return;}
                put(feature,"name",n);put(feature,"note",note.getText().toString());put(feature,"type",CATEGORIES[category.getSelectedItemPosition()]);put(feature,"styleIndex",style.getSelectedItemPosition());
                put(feature,"color",new String[]{"#63e0cf","#ffbf62","#eef7ff"}[style.getSelectedItemPosition()]);put(feature,"style",new String[]{"solid","dashed","solid"}[style.getSelectedItemPosition()]);put(feature,"draft",false);
                if(!feature.has("unixMs"))put(feature,"unixMs",System.currentTimeMillis());store.putFeature(feature);editType=null;editingId=null;lineWalking=false;editPoints.clear();refreshEditing();lastRefresh=0;d.dismiss();
                if(w==d.getButton(AlertDialog.BUTTON_NEUTRAL)){photoFeature=feature.optString("id");Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PHOTO);}
            };d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save);d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(save);
        });d.show();
    }
    private void selectFeature(double lat,double lon) {
        if(project==null)return;List<JSONObject> features=store.features(projectId());JSONObject best=null;double distance=Double.MAX_VALUE;
        for(JSONObject f:features){JSONObject g=f.optJSONObject("geometry");if(g==null)continue;JSONArray c=g.optJSONArray("coordinates");if(c==null)continue;
            if(!"Point".equals(g.optString("type")))c=c.optJSONArray(0);if(c==null)continue;
            double d=GeoMath.distance(lat,lon,c.optDouble(1),c.optDouble(0));if(d<distance){distance=d;best=f;}}
        if(best==null||distance>100){jobStatus="Keine Markierung in der Nähe. + Markierung oder Punkt auf Karte wählen.";return;}featureActions(best);
    }
    private void featureActions(JSONObject feature) {
        new AlertDialog.Builder(this).setTitle(feature.optString("name")).setItems(new String[]{"Name / Notiz / Stil","Geometrie verschieben / bearbeiten","Foto anzeigen","Löschen"},(d,w) -> {
            if(w==0)featureDialog(feature);
            else if(w==1){JSONObject g=feature.optJSONObject("geometry");editingId=feature.optString("id");editType=g.optString("type");editPoints.clear();
                if("Point".equals(editType)){JSONArray p=g.optJSONArray("coordinates");editPoints.add(new double[]{p.optDouble(0),p.optDouble(1)});jobStatus="Punktgriff ziehen; Fertig speichert die korrigierte Lage.";}
                else {JSONArray a=g.optJSONArray("coordinates");for(int i=0;i<a.length();i++){JSONArray p=a.optJSONArray(i);editPoints.add(new double[]{p.optDouble(0),p.optDouble(1)});}}refreshEditing();showScreen("Karte");}
            else if(w==2)showPhoto(feature);
            else new AlertDialog.Builder(this).setMessage("Markierung endgültig löschen?").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(a,k)->{store.deleteFeature(feature.optString("id"));lastRefresh=0;}).show();
        }).show();
    }
    private void showPhoto(JSONObject f) {
        String filename=f.optString("photoPath");if(filename.isEmpty()){info("Kein Foto hinterlegt.");return;}
        try {Bitmap b=PlanImages.decode(new File(new File(getFilesDir(),"survey-photos"),filename));ImageView image=new ImageView(this);image.setImageBitmap(b);image.setAdjustViewBounds(true);new AlertDialog.Builder(this).setView(image).setPositiveButton("Schließen",null).show();}catch(Exception e){error(e);}
    }
    private void walkingLine() {
        if(lineWalking){lineWalking=false;finishEdit();return;}
        if(!requireProject())return;lineWalking=true;editType="LineString";editingId=null;editPoints.clear();locate();jobStatus="Linie mitgehen: brauchbare GPS-Punkte werden übernommen. Erneut Linie mitgehen beendet.";
    }
    private void appendWalking(GpsQuality.Fix fix) {
        if(editPoints.isEmpty()||GeoMath.distance(editPoints.get(editPoints.size()-1)[1],editPoints.get(editPoints.size()-1)[0],fix.lat,fix.lon)>=2){editPoints.add(new double[]{fix.lon,fix.lat});persistWalkingDraft();refreshEditing();}
    }
    private void persistWalkingDraft() {
        if(!lineWalking||editPoints.size()<2||project==null)return;
        if(editingId==null)editingId=id();
        JSONObject draft=obj("id",editingId,"projectId",projectId(),"type","Drainage","name","Drainage (GPS-Entwurf)","note","Automatisch erfasste GPS-Linie; Verlauf und Genauigkeit prüfen.","geometry",obj("type","LineString","coordinates",editRing(false)),"unixMs",System.currentTimeMillis(),"draft",true);
        store.putFeature(draft);
    }
    private void searchPlaces() {
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        EditText query=new EditText(this);query.setSingleLine();query.setHint("z. B. Rinegg 69, 8853 Ranten");fields.addView(query);
        boolean savedOnly=project!=null&&project.optBoolean("offlineReady");
        new AlertDialog.Builder(this).setTitle("Ort oder Adresse finden").setView(fields)
            .setNegativeButton("Abbrechen",null)
            .setNeutralButton("Gespeicherte Orte",(d,w)->showPlaces(PlaceSearch.history(this)))
            .setPositiveButton(savedOnly?"Gespeicherte suchen":"Online suchen",(d,w)->{
                // Only this explicit action authorizes online preview/search for a new area.
                if(!savedOnly) {
                    onlinePreparation=true;
                    if(map!=null) {
                        JSONObject temporary=project==null?obj("latitude",map.currentLatitude(),"longitude",map.currentLongitude(),
                            "zoom",map.currentZoom(),"source",OfflineTiles.BASEMAP_ORTHO):project;
                        map.setProject(temporary,project==null?null:mapFile(project),false);
                    }
                }
                async(()->{
                    List<JSONObject> places=PlaceSearch.search(getApplicationContext(),query.getText().toString(),savedOnly);
                    return () -> showPlaces(places);
                });
            }).show();
    }
    private void showPlaces(List<JSONObject> places) {
        if(places.isEmpty()){info("Kein gespeicherter/passender Ort. Für die erste Adresssuche Neue Fläche (online) oder Online-Vorbereitung wählen; alternativ Karte manuell positionieren.");return;}
        String[] names=new String[places.size()];for(int i=0;i<names.length;i++)names[i]=places.get(i).optString("name")+"\n"+places.get(i).optString("attribution",places.get(i).optString("source"));
        new AlertDialog.Builder(this).setTitle("Ort auswählen und Lage prüfen").setItems(names,(d,w) -> {try {JSONObject p=places.get(w);PlaceSearch.remember(this,p);if(map!=null){following=false;map.setFollow(false);map.center(p.optDouble("latitude"),p.optDouble("longitude"));map.setZoom(17);}jobStatus="Adresse ist Suchergebnis, kein GPS-Fix. Lage prüfen. "+p.optString("attribution",p.optString("source"));}catch(Exception e){error(e);}}).show();
    }
    private void layersDialog() {
        boolean[] selected={polygons,markers,lines,path,heat,base};
        new AlertDialog.Builder(this).setTitle("Sichtbare Ebenen").setMultiChoiceItems(new String[]{"Grundstücksumrisse","Markierungen","Drainagen / Leitungen","Messspur","Heatmap","Orthofoto / Grundkarte"},selected,(d,w,b) -> selected[w]=b)
            .setPositiveButton("Anzeigen",(d,w)->{polygons=selected[0];markers=selected[1];lines=selected[2];path=selected[3];heat=selected[4];base=selected[5];lastRefresh=0;refresh();}).show();
    }
    private void viewDialog() {
        new AlertDialog.Builder(this).setTitle("Ansicht / Feldwerte").setItems(new String[]{"Orthofoto","Messspur nach Feldwert","Heatmap: absolut µT","Heatmap: Differenz zur Referenz µT","Heatmap: Betrag der Abweichung µT","Orthofoto + Heatmap"},(d,w)->{
            base=w==0||w==1||w==5;heat=w>=2;path=w==1;heatMode=w==1?"track":w==2?"absolute":w==4?"abs-delta":"delta";lastRefresh=0;refresh();
        }).show();
    }

    private void showRecords() {
        LinearLayout actions=row(content);button(actions,"Grundstücke",this::chooseProject);button(actions,"Projekt-ZIP sichern",this::exportProject);
        button(actions,"Projekt-ZIP importieren",() -> pickZip(IMPORT));button(actions,"v0.3-Messlog importieren",() -> {if(requireProject())pickZip(LEGACY);});
        button(actions,"Grundriss-Scanner",() -> startActivity(new Intent(this,PlanActivity.class)));
        if(!requireProject())return;
        button(actions,"Projekt umbenennen",() -> askText("Projektname",project.optString("name"),n->{put(project,"name",n);saveProject();showScreen("Aufzeichnungen");}));
        button(actions,"Projekt löschen",this::deleteProject);
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        list.addView(label(project.optString("name")+" · Begehungen auswählen für gemeinsame Anzeige / Vergleich. Ohne Auswahl wird die letzte Begehung angezeigt.",15));
        for(JSONObject s:store.sessions(projectId())){
            LinearLayout row=row(list);String sid=s.optString("id");boolean chosen=selectedSessions.contains(sid);
            button(row,(chosen?"☑ ":"☐ ")+s.optString("name")+" · "+s.optString("status"),()->{if(chosen)selectedSessions.remove(sid);else selectedSessions.add(sid);showScreen("Aufzeichnungen");});
            button(row,"Umbenennen",()->askText("Begehung benennen",s.optString("name"),n->{put(s,"name",n);store.updateSession(s);showScreen("Aufzeichnungen");}));
            button(row,"Fortsetzen",()->{if(!fine()){info("Zuerst auf Karte Standort erlauben.");return;}SurveyService.resume(this,sid);showScreen("Karte");});
            button(row,"Datenfenster",()->chooseWindow(sid));
        }
        for(JSONObject f:store.features(projectId()))button(list,f.optString("type")+" · "+f.optString("name"),()->featureActions(f));
        button(list,"Ausgewählte Begehungen auf Karte",()->showScreen("Karte"));
    }
    private void pickZip(int request) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),request); }
    private void chooseWindow(String sid) {
        long count=store.sampleCount(sid);int pages=(int)Math.min(10000,(count+49999)/50000);
        String[] choices=new String[pages+1];choices[0]="Neueste 50.000 Messwerte (live)";
        for(int i=0;i<pages;i++)choices[i+1]="Messwerte "+(i*50000L+1)+"–"+Math.min(count,(i+1)*50000L);
        new AlertDialog.Builder(this).setTitle("Anzeigezeitraum · Export enthält alle Daten").setItems(choices,(d,w)->{prefs.edit().putInt("window-"+sid,w==0?-1:(w-1)*50000).apply();selectedSessions.add(sid);showScreen("Karte");}).show();
    }
    private void exportProject() {
        if(!requireProject())return;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"MagnetRute_v0.4_"+project.optString("name").replaceAll("[^\\p{L}0-9_-]","_")+".zip");startActivityForResult(i,EXPORT);
    }
    private void deleteProject() {
        if(download!=null&&download.running&&projectId().equals(download.projectId)){info("Download zuerst pausieren.");return;}
        SurveyService.Snapshot s=SurveyService.snapshot();if(projectId().equals(s.projectId)&&("recording".equals(s.state)||"paused".equals(s.state)||"recovered".equals(s.state))){info("Die zugehörige Messung zuerst stoppen.");return;}
        new AlertDialog.Builder(this).setTitle("Projekt endgültig löschen?").setMessage("Karte, Markierungen und Begehungen werden gelöscht. Vorher Projekt-ZIP sichern.").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(d,w)->{store.deleteProject(projectId());project=null;prefs.edit().remove("projectId").apply();showScreen("Karte");}).show();
    }
    private interface Job { Runnable run() throws Exception; }
    private void async(Job job) {
        busy=true;jobStatus="Bitte warten …";IO.execute(()->{
            try {Runnable done=job.run();handler.post(()->{if(!isDestroyed()){busy=false;done.run();lastRefresh=0;}});}
            catch(Exception e){handler.post(()->{if(!isDestroyed()){busy=false;jobStatus="Vorgang abgebrochen; bestehende Daten bleiben erhalten.";error(e);}});}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();String pid=projectId();
        if(request==EXPORT)async(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt");SurveyStore db=new SurveyStore(this)){if(out==null)throw new IllegalArgumentException("Datei nicht beschreibbar.");db.exportProject(pid,out);}return ()->jobStatus="Projekt-ZIP gespeichert.";});
        else if(request==IMPORT||request==LEGACY)async(()->{
            String imported;try(InputStream in=getContentResolver().openInputStream(uri);SurveyStore db=new SurveyStore(this)){if(in==null)throw new IllegalArgumentException("Datei nicht lesbar.");imported=request==IMPORT?db.importProject(in):db.importLegacy(in,pid);}
            return ()->{if(request==IMPORT){project=store.project(imported);onlinePreparation=false;saveProject();}jobStatus="Import erfolgreich · Originaldaten erhalten";showScreen("Aufzeichnungen");};
        });
        else if(request==PHOTO&&photoFeature!=null)async(()->{
            File input=new File(getCacheDir(),"marker-photo-input");
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(input)){
                if(in==null)throw new IllegalArgumentException("Foto nicht lesbar.");byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>30*1024*1024)throw new IllegalArgumentException("Foto größer als 30 MiB.");out.write(b,0,n);}}
            Bitmap image=PlanImages.decode(input);byte[] jpeg=PlanImages.jpeg(image);image.recycle();String filename=id()+".jpg";File dir=new File(getFilesDir(),"survey-photos");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalArgumentException("Fotoordner nicht verfügbar.");
            try(OutputStream out=new FileOutputStream(new File(dir,filename))){out.write(jpeg);}JSONObject feature=findFeature(photoFeature);put(feature,"photoPath",filename);store.putFeature(feature);input.delete();return ()->jobStatus="Foto lokal gespeichert und in Projekt-Sicherungen enthalten.";
        });
    }
    private void showSettings() {
        ScrollView scroll=new ScrollView(this);LinearLayout fields=new LinearLayout(this);fields.setPadding(dp(12),0,dp(12),0);fields.setOrientation(LinearLayout.VERTICAL);scroll.addView(fields);content.addView(scroll);
        fields.addView(label("Kartierung und Heatmap · Rohwerte bleiben unverändert",18));
        button(fields,"Hell / Dunkel wechseln",()->{prefs.edit().putBoolean("light",!prefs.getBoolean("light",false)).apply();recreate();});
        EditText accuracy=number(fields,"Maximale GPS-Genauigkeit (m)",String.valueOf(SurveyService.snapshot().maxAccuracy));
        EditText sensitivity=number(fields,"Empfindlichkeit / Schwelle (µT)",String.valueOf(SurveyService.snapshot().sensitivity));
        EditText cell=number(fields,"Heatmap-Zellgröße (m, mindestens GPS-Unsicherheit)",String.valueOf(prefs.getFloat("cell",10)));
        EditText opacity=number(fields,"Deckkraft (0–1)",String.valueOf(prefs.getFloat("opacity",.35f)));
        EditText scale=number(fields,"Differenz-Skala ±µT",String.valueOf(prefs.getFloat("scale",10)));
        EditText absolute=number(fields,"Absolute Skala 0 bis µT",String.valueOf(prefs.getFloat("absoluteScale",100)));
        EditText threshold=number(fields,"Heatmap-Mindestwert / Betrag (µT)",String.valueOf(prefs.getFloat("heatThreshold",0)));
        EditText smooth=number(fields,"Glättungsradius (m; nur belegte Zellen)",String.valueOf(prefs.getFloat("smooth",0)));
        button(fields,"Einstellungen speichern",()->{
            try {double a=number(accuracy),s=number(sensitivity),c=number(cell),o=number(opacity),r=number(scale),g=number(smooth);
                if(!Double.isFinite(a)||a<1||a>1000||!Double.isFinite(s)||s<.5||s>100||!Double.isFinite(c)||c<1||c>1000||!Double.isFinite(o)||o<0||o>1||!Double.isFinite(r)||r<=0||r>10000||!Double.isFinite(g)||g<0||g>1000)throw new IllegalArgumentException("Einstellungen außerhalb der erlaubten Bereiche.");
                double maxAbsolute=number(absolute),t=number(threshold);if(!Double.isFinite(maxAbsolute)||maxAbsolute<=0||maxAbsolute>100000||!Double.isFinite(t)||t<0||t>10000)throw new IllegalArgumentException("Skala / Schwelle ungültig.");
                SurveyService.configureAccuracy(this,a);SurveyService.sensitivity(this,s);prefs.edit().putFloat("cell",(float)c).putFloat("opacity",(float)o).putFloat("scale",(float)r).putFloat("absoluteScale",(float)maxAbsolute).putFloat("heatThreshold",(float)t).putFloat("smooth",(float)g).apply();
                String active=SurveyService.snapshot().sessionId;
                if(active!=null&&store.session(active)!=null)store.addEvent(active,"KARTEN_EINSTELLUNGEN",obj("maxAccuracy",a,"sensitivity",s,"cellMeters",c,"opacity",o,"deltaScale",r,"absoluteScale",maxAbsolute,"heatThreshold",t,"smoothingMeters",g).toString(),SystemClock.elapsedRealtimeNanos());
                jobStatus="Einstellungen gespeichert. Änderungen im aktiven Messlog protokolliert.";info(jobStatus);
            }catch(Exception e){error(e);}
        });
        fields.addView(label("Kartendaten lokal: "+String.format(Locale.GERMANY,"%.0f MiB frei",getFilesDir().getUsableSpace()/1048576.0)+". Offline-Ausschnitte sind auf 250 MiB / 4000 Kacheln begrenzt. GPS ist auch bei hochauflösenden Luftbildern nicht zentimetergenau.",13));
        button(fields,"Quellen / Genauigkeit",()->info("Datenquelle: basemap.at · CC BY 4.0 · https://basemap.at/\nOptional: Land Steiermark – data.steiermark.gv.at · CC BY 4.0. Kein Kataster-/SAGIS-Overlay integriert.\nBlau: unter Referenz, Rot: darüber. Belegte Rasterzellen verwenden Mediane je Durchgang; lange Standzeit erhält kein höheres Gewicht. Unsichere GPS- und Nachkalibrierungsabschnitte bleiben leer. Magnetfeldabweichungen sind kein Nachweis für Wasseradern."));
        fields.addView(label("Adresssuche verwendet standardmäßig den installierten Android-Adressdienst. Falls er auf diesem Gerät fehlt, bleiben Kartenwahl und gespeicherte Orte nutzbar. Optional kann ein selbst betriebener/ausdrücklich erlaubter HTTPS-Suchdienst eingestellt werden; kein öffentlicher Dienst ist voreingestellt.",12));
        EditText endpoint=new EditText(this);endpoint.setSingleLine();endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);endpoint.setHint("Eigener HTTPS-Suchendpunkt (optional)");endpoint.setText(PlaceSearch.getEndpoint(this));fields.addView(endpoint);
        button(fields,"Adressdienst speichern",()->{try{PlaceSearch.setEndpoint(this,endpoint.getText().toString());info("Adressdienst gespeichert. Leeres Feld verwendet den Android-Dienst.");}catch(Exception e){error(e);}});
    }
    private final Runnable poll=new Runnable(){@Override public void run(){if(!isDestroyed()){try{refresh();}catch(Exception e){jobStatus=e.getMessage();}handler.postDelayed(this,1000);}}};
    private void refresh() {
        SurveyService.Snapshot s=SurveyService.snapshot();GpsQuality.Fix fix=currentFix();
        String title=project==null?"Kein Grundstück gewählt":project.optString("name")+(project.optBoolean("offlineReady")?" · Offline bereit":" · noch nicht offline bereit");
        DownloadJob job=download;
        if(job!=null&&job.projectId.equals(projectId())){
            if(job.running)jobStatus=String.format(Locale.GERMANY,"Download %d%% · %d/%d · %.1f MiB",job.total==0?0:job.done*100/job.total,job.done,job.total,job.bytes/1048576.0);
            else {jobStatus=job.error;JSONObject saved=store.project(projectId());if(saved!=null&&!saved.optString("mapPath").equals(project.optString("mapPath"))){project=saved;onlinePreparation=false;if("Karte".equals(screen))showScreen("Karte");}download=null;}
        }
        boolean liveSample=s.sample!=null&&"recording".equals(s.state)&&SystemClock.elapsedRealtimeNanos()-s.sample.optLong("timestampNs")<2_000_000_000L;
        String live=s.sample==null?"Keine aktuellen Sensorwerte":(liveSample?"":"Letzter Messwert: ")+String.format(Locale.GERMANY,"%.2f µT · Δ %.2f µT · Sensorstatus %d%s",s.sample.optDouble("total"),s.sample.optDouble("delta"),s.sample.optInt("sensorAccuracy",-1),s.sample.optBoolean("uncertain")?" · Kalibrierung unsicher":"");
        JSONObject row=s.sessionId==null?null:store.session(s.sessionId);
        long start=row==null?0:row.optLong("startUnixMs",0);
        long end=row==null?0:row.optLong("endUnixMs",0);
        boolean stopped="stopped".equals(s.state);
        long duration=start==0?0:Math.max(0,((stopped&&end>start?end:System.currentTimeMillis())-start)/1000);
        String time=String.format(Locale.GERMANY,"%02d:%02d",duration/60,duration%60);
        String recording="recording".equals(s.state)?"● AUFNAHME LÄUFT · "+time:
            "paused".equals(s.state)?"‖ PAUSIERT · "+time:
            "recovered".equals(s.state)?"‖ UNTERBROCHEN · "+time:
            stopped?"■ GESTOPPT · "+time+" insgesamt (inkl. Pausen)":"Keine Aufnahme";
        status.setText(title+" · "+recording+"\n"+(fix==null?s.gpsStatus:"GPS ±"+Math.round(fix.accuracy)+" m")+
            (jobStatus.isEmpty()?"":" · "+jobStatus));
        status.setTextColor("recording".equals(s.state)?0xffff6666:"paused".equals(s.state)?0xffffd36a:0xffcfe4ef);
        if(recordButton!=null) {
            boolean active="recording".equals(s.state);
            recordButton.setText(active?"PAUSE  ‖":("paused".equals(s.state)||"recovered".equals(s.state))?"FORTSETZEN  ▶":"AUFNAHME STARTEN  ●");
            prominent(recordButton,active?0xffaf3441:0xff218268);
        }
        if(stopButton!=null)stopButton.setVisibility(("recording".equals(s.state)||"paused".equals(s.state)||"recovered".equals(s.state))?View.VISIBLE:View.GONE);
        if(stageHint!=null)stageHint.setText(mapGuide());
        if(map==null)return;
        map.setLocation(fix);if(project==null)return;if(fix!=null&&pendingLocate){map.center(fix.lat,fix.lon);pendingLocate=false;}
        if(SystemClock.elapsedRealtime()-lastRefresh<3000)return;lastRefresh=SystemClock.elapsedRealtime();
        ArrayList<JSONObject> features=new ArrayList<>(store.features(projectId()));
        for(JSONObject p:store.projects())if(p.optJSONObject("geometry")!=null)features.add(obj("id",p.optString("id"),"name",p.optString("name"),"type","Fläche","geometry",p.optJSONObject("geometry")));
        map.setFeatures(features);ArrayList<JSONObject> fixes=new ArrayList<>(),samples=new ArrayList<>();int displayed=0;boolean windowed=false;
        List<JSONObject> sessions=store.sessions(projectId());String latest=sessions.isEmpty()?"":sessions.get(sessions.size()-1).optString("id");
        for(JSONObject session:sessions){String sid=session.optString("id");
            if(selectedSessions.isEmpty()&&!sid.equals(latest)&&!sid.equals(s.sessionId))continue;
            if(!selectedSessions.isEmpty()&&!selectedSessions.contains(sid)&&!sid.equals(s.sessionId))continue;
            int offset=prefs.getInt("window-"+sid,-1);windowed|=store.sampleCount(sid)>50000;
            List<JSONObject> batch=offset<0?store.samples(sid,50000):store.sampleWindow(sid,offset,50000);
            List<JSONObject> f=offset<0?store.fixes(sid,50000):windowTrack(batch);if(!f.isEmpty())put(f.get(0),"breakTrack",true);fixes.addAll(f);samples.addAll(batch);displayed++;}
        double range="absolute".equals(heatMode)?prefs.getFloat("absoluteScale",100):prefs.getFloat("scale",10);
        cachedSamples=samples;map.setTrack(fixes);map.setSamples(samples,heatMode,prefs.getFloat("opacity",.55f),range,prefs.getFloat("cell",10));map.setSmoothingMeters(prefs.getFloat("smooth",0));map.setThreshold(prefs.getFloat("heatThreshold",0));
        map.setLayers(polygons,markers,lines,path,heat);map.setBaseVisible(base);
        String bounds="absolute".equals(heatMode)?"0 bis "+range+" µT":"abs-delta".equals(heatMode)?"0 bis "+range+" µT":"−"+range+" bis +"+range+" µT";
        String compare="";if(comparison){int consistent=0;for(SurveyComparison.ComparisonCell c:SurveyComparison.compare(gridSamples(samples),prefs.getFloat("cell",10),s.sensitivity))if(c.consistent){consistent++;
            features.add(obj("id","comparison-"+consistent,"name",String.format(Locale.GERMANY,"Wiederholt Δ %.2f µT · Streuung %.2f · %d Durchgänge",c.median,c.spread,c.independentPasses),"type","Vergleich","color","#ffb24c","geometry",obj("type","Point","coordinates",coord(c.lon,c.lat))));}
            map.setFeatures(features);compare=" · "+consistent+" Zellen wiederholt ähnlich (keine Objekterkennung)";}
        int maxPasses=0;for(FieldGrid.Cell c:map.displayedCells())maxPasses=Math.max(maxPasses,c.passes);
        if(legend!=null) {
            boolean show="delta".equals(heatMode)||"absolute".equals(heatMode)||"abs-delta".equals(heatMode);
            legend.setVisibility(show?View.VISIBLE:View.GONE);
            if(show)legend.setText(map.displayedCells().size()+" Messzellen · "+bounds+
                (comparison?compare:"")+(windowed?" · Datenfenster aktiv":""));
        }
    }
    private List<JSONObject> windowTrack(List<JSONObject> samples) {
        List<JSONObject> result=new ArrayList<>();long previous=-1;String segment="";boolean gap=true;
        for(JSONObject sample:samples){JSONObject f=sample.optJSONObject("fix");if(f==null){gap=true;continue;}long stamp=f.optLong("elapsedNs");if(stamp==previous)continue;
            JSONObject copy=obj("lat",f.optDouble("lat"),"lon",f.optDouble("lon"),"accuracy",f.optDouble("accuracy"),"elapsedNs",stamp,"accepted",f.optBoolean("accepted",true));
            String next=sample.optString("passId")+":"+sample.optInt("segmentId");put(copy,"breakTrack",gap||!segment.equals(next));result.add(copy);gap=false;previous=stamp;segment=next;}
        return result;
    }
    private List<FieldGrid.Sample> gridSamples(List<JSONObject> rows) {
        List<FieldGrid.Sample> out=new ArrayList<>();for(JSONObject s:rows){JSONObject f=s.optJSONObject("fix");if(f!=null)out.add(new FieldGrid.Sample(f.optDouble("lat"),f.optDouble("lon"),f.optDouble("accuracy"),s.optDouble("delta"),s.optString("passId"),s.optLong("timestampNs"),s.optBoolean("uncertain")||s.optBoolean("calibrationJump")));}return out;
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll);}
    @Override protected void onPause(){handler.removeCallbacks(poll);handler.removeCallbacks(saveViewport);saveViewportState();prefs.edit().putString("heatMode",heatMode).putBoolean("polygons",polygons).putBoolean("markers",markers).putBoolean("lines",lines).putBoolean("path",path).putBoolean("heat",heat).putBoolean("base",base).putBoolean("follow",following).apply();if(locating&&locations!=null)locations.removeUpdates(this);locating=false;super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("projectId",projectId());out.putString("screen",screen);out.putString("editType",editType);out.putString("editingId",editingId);out.putString("editPoints",editRing(false).toString());out.putString("photoFeature",photoFeature);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);store.close();super.onDestroy();}
}
