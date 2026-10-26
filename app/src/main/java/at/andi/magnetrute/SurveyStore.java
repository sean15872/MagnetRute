package at.andi.magnetrute;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

/** Versioned survey storage. Existing v0.3 sessions and floor-plans are never rewritten. */
public final class SurveyStore extends SQLiteOpenHelper {
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final long MAX_ARCHIVE=512L*1024*1024,MAX_DATA=64L*1024*1024;
    private final Context context;
    public SurveyStore(Context c) { super(c.getApplicationContext(),"survey-v1.sqlite",null,1);context=c.getApplicationContext();setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,data TEXT NOT NULL)");
        db.execSQL("CREATE TABLE features(id TEXT PRIMARY KEY NOT NULL,projectId TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,data TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL,projectId TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,data TEXT NOT NULL)");
        for(String t:new String[]{"fixes","samples","events"}) {
            db.execSQL("CREATE TABLE "+t+"(seq INTEGER PRIMARY KEY AUTOINCREMENT,sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,data TEXT NOT NULL)");
            db.execSQL("CREATE INDEX "+t+"_session ON "+t+"(sessionId,seq)");
        }
        db.execSQL("CREATE INDEX features_project ON features(projectId)");
        db.execSQL("CREATE INDEX sessions_project ON sessions(projectId)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unbekannte Datenbankversion; Daten bleiben erhalten."); }
    private static JSONObject json(String value) { try{return new JSONObject(value);}catch(JSONException e){throw new IllegalStateException("Beschädigter Datensatz",e);} }
    private static JSONObject copy(JSONObject value) { if(value==null)throw new IllegalArgumentException("Datensatz fehlt");return json(value.toString()); }
    private static void put(JSONObject o,String key,Object value) { try{o.put(key,value);}catch(JSONException e){throw new IllegalArgumentException("Ungültiger Wert: "+key,e);} }
    private static String id(String id) { if(id==null || !id.matches(UUID_PATTERN))throw new IllegalArgumentException("Ungültige ID");return id; }
    private static String required(JSONObject o,String key) { String s=o.optString(key,"").trim();if(s.isEmpty()||s.length()>200)throw new IllegalArgumentException("Ungültiges Feld: "+key);return s; }
    private static String mapName(JSONObject project) {
        String name=project.optString("mapPath","");
        if(!name.isEmpty() && !name.matches("[A-Za-z0-9_-]{1,160}\\.mbtiles"))throw new IllegalArgumentException("Ungültiger Kartenpfad");return name;
    }
    private static void geometry(JSONObject g) {
        if(g==null)throw new IllegalArgumentException("Geometrie fehlt");
        String type=g.optString("type");JSONArray coordinates=g.optJSONArray("coordinates");
        if(coordinates==null)throw new IllegalArgumentException("Koordinaten fehlen");
        if("Point".equals(type)) coordinate(coordinates);
        else if("LineString".equals(type)) { if(coordinates.length()<2 || coordinates.length()>100000)throw new IllegalArgumentException("Linie ungültig");for(int i=0;i<coordinates.length();i++)coordinate(coordinates.optJSONArray(i)); }
        else if("Polygon".equals(type)) {
            if(coordinates.length()<1 || coordinates.length()>50)throw new IllegalArgumentException("Polygon ungültig");
            for(int r=0;r<coordinates.length();r++) {
                JSONArray ring=coordinates.optJSONArray(r);if(ring==null||ring.length()<4||ring.length()>10000)throw new IllegalArgumentException("Polygonring ungültig");
                for(int i=0;i<ring.length();i++)coordinate(ring.optJSONArray(i));
                JSONArray first=ring.optJSONArray(0),last=ring.optJSONArray(ring.length()-1);
                if(first.optDouble(0)!=last.optDouble(0)||first.optDouble(1)!=last.optDouble(1))throw new IllegalArgumentException("Polygon muss geschlossen sein");
            }
        } else throw new IllegalArgumentException("Geometrietyp nicht unterstützt");
    }
    private static void coordinate(JSONArray a) { if(a==null||a.length()<2||!Double.isFinite(a.optDouble(0))||!Double.isFinite(a.optDouble(1))||Math.abs(a.optDouble(0))>180||Math.abs(a.optDouble(1))>90)throw new IllegalArgumentException("Ungültige WGS84-Koordinate"); }
    private static void projectCheck(JSONObject p) {
        id(p.optString("id"));required(p,"name");mapName(p);
        String pending=p.optString("pendingMap","");
        if(!pending.isEmpty()&&!pending.matches(UUID_PATTERN+"\\.mbtiles"))throw new IllegalArgumentException("Ungültiger temporärer Kartenpfad");
        if(p.has("geometry")&&!p.isNull("geometry")) { geometry(p.optJSONObject("geometry"));if(!"Polygon".equals(p.optJSONObject("geometry").optString("type")))throw new IllegalArgumentException("Grundstück benötigt Polygon"); }
        for(String k:new String[]{"latitude","longitude"}) if(p.has(k)&&(!Double.isFinite(p.optDouble(k))||Math.abs(p.optDouble(k))>("latitude".equals(k)?90:180)))throw new IllegalArgumentException("Kartenmittelpunkt ungültig");
    }
    private static void featureCheck(JSONObject f) { id(f.optString("id"));id(f.optString("projectId"));required(f,"name");geometry(f.optJSONObject("geometry"));photoName(f);if(f.optString("note","").length()>10000)throw new IllegalArgumentException("Notiz zu lang"); }
    private static String photoName(JSONObject f) {String name=f.optString("photoPath","");if(!name.isEmpty()&&!name.matches(UUID_PATTERN+"\\.jpg"))throw new IllegalArgumentException("Ungültiger Fotopfad");return name;}
    private void writeEntity(String table,JSONObject value,String parent) {
        ContentValues v=new ContentValues();v.put("id",value.optString("id"));if(parent!=null)v.put("projectId",value.optString("projectId"));v.put("data",value.toString());
        getWritableDatabase().insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private JSONObject one(String table,String value) {
        try(Cursor c=getReadableDatabase().query(table,new String[]{"data"},"id=?",new String[]{id(value)},null,null,null)) { return c.moveToFirst()?json(c.getString(0)):null; }
    }
    private List<JSONObject> list(String table,String column,String value,int limit) {
        String selection=column==null?null:column+"=?";String[] args=column==null?null:new String[]{id(value)};
        ArrayList<JSONObject> out=new ArrayList<>();String order=("projects".equals(table)||"features".equals(table)||"sessions".equals(table))?"rowid ASC":"seq ASC";
        if(limit>0 && order.startsWith("seq")) {
            try(Cursor c=getReadableDatabase().query(table,new String[]{"data"},selection,args,null,null,"seq DESC",Integer.toString(limit))) { while(c.moveToNext())out.add(json(c.getString(0))); }Collections.reverse(out);return out;
        }
        try(Cursor c=getReadableDatabase().query(table,new String[]{"data"},selection,args,null,null,order,limit>0?Integer.toString(limit):null)) { while(c.moveToNext())out.add(json(c.getString(0))); }return out;
    }
    public synchronized List<JSONObject> projects() { return list("projects",null,null,0); }
    public synchronized JSONObject project(String projectId) { return one("projects",projectId); }
    public synchronized void putProject(JSONObject value) { JSONObject p=copy(value);projectCheck(p);upsert("projects",p,null); }
    private void upsert(String table,JSONObject value,String parent) {
        ContentValues v=new ContentValues();v.put("data",value.toString());if(parent!=null)v.put("projectId",value.optString("projectId"));
        SQLiteDatabase db=getWritableDatabase();if(db.update(table,v,"id=?",new String[]{value.optString("id")})==0) {v.put("id",value.optString("id"));db.insertOrThrow(table,null,v);}
    }
    public synchronized void deleteProject(String projectId) {
        JSONObject p=project(projectId);if(p==null)return;
        List<JSONObject> photos=features(projectId),recordings=sessions(projectId);
        getWritableDatabase().delete("projects","id=?",new String[]{id(projectId)});
        for(String name:new String[]{mapName(p),p.optString("pendingMap","")})if(!name.isEmpty()) {
            boolean used=false;for(JSONObject other:projects())used|=name.equals(mapName(other))||name.equals(other.optString("pendingMap"));
            if(!used)new File(new File(context.getFilesDir(),"offline-maps"),name).delete();
        }
        for(JSONObject f:photos)deletePhotoIfUnused(photoName(f));
        for(JSONObject s:recordings)removeTree(originalFolder(s.optString("id")));
    }
    public synchronized List<JSONObject> features(String projectId) { return list("features","projectId",projectId,0); }
    public synchronized void putFeature(JSONObject value) { JSONObject f=copy(value);featureCheck(f);upsert("features",f,"projectId"); }
    public synchronized void deleteFeature(String featureId) {
        JSONObject existing=one("features",featureId);getWritableDatabase().delete("features","id=?",new String[]{id(featureId)});
        if(existing!=null)deletePhotoIfUnused(photoName(existing));
    }
    private void deletePhotoIfUnused(String filename) {
        if(filename.isEmpty())return;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM features",null)) {while(c.moveToNext())if(filename.equals(json(c.getString(0)).optString("photoPath")))return;}
        new File(new File(context.getFilesDir(),"survey-photos"),filename).delete();
    }
    public synchronized String createSession(String projectId,String name) {
        id(projectId);JSONObject s=new JSONObject();String sid=UUID.randomUUID().toString();put(s,"id",sid);put(s,"projectId",projectId);put(s,"name",name);put(s,"startUnixMs",System.currentTimeMillis());put(s,"endUnixMs",0);put(s,"status","recording");put(s,"config",new JSONObject());updateSession(s);return sid;
    }
    public synchronized JSONObject session(String sessionId) { return one("sessions",sessionId); }
    public synchronized List<JSONObject> sessions(String projectId) { return list("sessions","projectId",projectId,0); }
    public synchronized void updateSession(JSONObject value) { JSONObject s=copy(value);id(s.optString("id"));id(s.optString("projectId"));required(s,"name");if(!Arrays.asList("recording","paused","stopped","recovered").contains(s.optString("status")))throw new IllegalArgumentException("Unbekannter Sitzungsstatus");upsert("sessions",s,"projectId"); }
    private void append(String table,String sessionId,JSONObject value) {
        ContentValues v=new ContentValues();v.put("sessionId",id(sessionId));v.put("data",copy(value).toString());getWritableDatabase().insertOrThrow(table,null,v);
    }
    public synchronized void addFix(String sessionId,JSONObject fix) { append("fixes",sessionId,fix); }
    public synchronized void addSample(String sessionId,JSONObject sample) { append("samples",sessionId,sample); }
    public synchronized void addEvent(String sessionId,String name,String details,long timestampNs) {
        JSONObject e=new JSONObject();put(e,"name",name);put(e,"details",details);put(e,"timestampNs",timestampNs);put(e,"unixMs",System.currentTimeMillis());append("events",sessionId,e);
    }
    public synchronized List<JSONObject> fixes(String sessionId) { return list("fixes","sessionId",sessionId,0); }
    public synchronized List<JSONObject> fixes(String sessionId,int limit) { return list("fixes","sessionId",sessionId,limit); }
    public synchronized List<JSONObject> samples(String sessionId) { return list("samples","sessionId",sessionId,0); }
    public synchronized List<JSONObject> samples(String sessionId,int limit) { return list("samples","sessionId",sessionId,limit); }
    public synchronized long sampleCount(String sessionId) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM samples WHERE sessionId=?",new String[]{id(sessionId)})){c.moveToFirst();return c.getLong(0);}
    }
    public synchronized List<JSONObject> sampleWindow(String sessionId,int offset,int limit) {
        if(offset<0||limit<1||limit>50000)throw new IllegalArgumentException("Ungültiges Datenfenster");
        ArrayList<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("samples",new String[]{"data"},"sessionId=?",new String[]{id(sessionId)},null,null,"seq ASC",offset+","+limit)){while(c.moveToNext())result.add(json(c.getString(0)));}return result;
    }
    public synchronized List<JSONObject> events(String sessionId) { return list("events","sessionId",sessionId,0); }

    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONArray array(List<JSONObject> values) { JSONArray a=new JSONArray();for(JSONObject v:values)a.put(v);return a; }
    private void lines(ZipOutputStream zip,String name,String table,String sid) throws IOException {
        zip.putNextEntry(new ZipEntry(name));try(Cursor c=getReadableDatabase().query(table,new String[]{"data"},"sessionId=?",new String[]{sid},null,null,"seq ASC")) { while(c.moveToNext()){zip.write(utf8(c.getString(0)));zip.write('\n');} }zip.closeEntry();
    }
    private static final String[] SAMPLE_COLUMNS={"timestampNs","unixMs","x","y","z","total","vertical","horizontal","uncalX","uncalY","uncalZ","biasX","biasY","biasZ","sensorAccuracy","filtered","gravityX","gravityY","gravityZ","motion","uncalTimestampNs","reference","delta","sensitivity","calibrationJump","uncertain","calibrationReason","passId","segmentId","fix.unixMs","fix.speed","fix.lat","fix.lon","fix.accuracy","fix.elapsedNs","fix.provider"};
    private static final String[] FIX_COLUMNS={"elapsedNs","unixMs","lat","lon","accuracy","provider","speed","accepted","reason","breakTrack"};
    private static final String[] EVENT_COLUMNS={"timestampNs","unixMs","name","details"};
    private static String csv(Object value) { if(value==null||value==JSONObject.NULL)return "";return '"'+String.valueOf(value).replace("\"","\"\"")+ '"'; }
    private void csvEntry(ZipOutputStream zip,String name,String table,String sid,String[] columns) throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(utf8(String.join(",",columns)+"\n"));
        try(Cursor c=getReadableDatabase().query(table,new String[]{"data"},"sessionId=?",new String[]{sid},null,null,"seq ASC")) {
            while(c.moveToNext()) { JSONObject o=json(c.getString(0));StringBuilder row=new StringBuilder();
                for(String col:columns) {if(row.length()>0)row.append(',');Object v;if(col.contains(".")){String[] keys=col.split("\\.");JSONObject nested=o.optJSONObject(keys[0]);v=nested==null?null:nested.opt(keys[1]);}else v=o.opt(col);row.append(csv(v));}zip.write(utf8(row+"\n"));
            }
        }zip.closeEntry();
    }
    public synchronized void exportProject(String projectId,OutputStream output) throws IOException {
        JSONObject p=project(projectId);if(p==null)throw new IOException("Grundstück nicht gefunden");List<JSONObject> ss=sessions(projectId);
        JSONObject manifest=new JSONObject();put(manifest,"format","MagnetRuteSurvey");put(manifest,"version",1);put(manifest,"project",p);put(manifest,"features",array(features(projectId)));put(manifest,"sessions",array(ss));
        ZipOutputStream zip=new ZipOutputStream(output);entry(zip,"project.json",utf8(manifest.toString()));
        for(JSONObject s:ss) {
            String sid=s.optString("id"),prefix="sessions/"+sid+"/";
            for(String table:new String[]{"samples","fixes","events"})lines(zip,prefix+table+".ndjson",table,sid);
            csvEntry(zip,prefix+"messwerte.csv","samples",sid,SAMPLE_COLUMNS);csvEntry(zip,prefix+"standorte.csv","fixes",sid,FIX_COLUMNS);csvEntry(zip,prefix+"ereignisse.csv","events",sid,EVENT_COLUMNS);
            File original=originalFolder(sid);
            if(original.isDirectory())for(String name:LEGACY_NAMES){File file=new File(original,name);if(file.isFile())fileEntry(zip,prefix+"original/"+name,file);}
            // Keep the complete original legacy logger output alongside the extended survey export.
            String legacy=s.optString("legacyFolder","");
            if(legacy.matches("session_[0-9_]+")) {File folder=new File(new File(context.getFilesDir(),"sessions"),legacy);for(String name:LEGACY_NAMES){File file=new File(folder,name);if(file.isFile()&&!new File(original,name).exists())fileEntry(zip,prefix+"original/"+name,file);}}
        }
        entry(zip,"karte.geojson",utf8(geojson(projectId).toString()));
        Set<String> photoNames=new HashSet<>();long photoBytes=0;for(JSONObject f:features(projectId)){String photo=photoName(f);if(!photo.isEmpty()&&photoNames.add(photo)){File file=new File(new File(context.getFilesDir(),"survey-photos"),photo);validatePhoto(file);photoBytes+=file.length();if(photoBytes>40L*1024*1024)throw new IOException("Markerfotos größer als 40 MiB");fileEntry(zip,"photos/"+photo,file);}}
        String map=mapName(p);if(!map.isEmpty()){File f=new File(new File(context.getFilesDir(),"offline-maps"),map);if(!f.isFile())throw new IOException("Offline-Kartendatei fehlt; Projekt nicht vollständig gesichert");fileEntry(zip,"map.mbtiles",f);}
        zip.finish();zip.flush();
    }
    private static void fileEntry(ZipOutputStream zip,String name,File file) throws IOException {zip.putNextEntry(new ZipEntry(name));try(InputStream in=new FileInputStream(file)){transfer(in,zip,MAX_ARCHIVE);}zip.closeEntry();}
    private static JSONObject feature(JSONObject geometry,JSONObject properties) {JSONObject f=new JSONObject();put(f,"type","Feature");put(f,"geometry",geometry);put(f,"properties",properties);return f;}
    public synchronized JSONObject geojson(String projectId) {
        JSONObject p=project(projectId);if(p==null)throw new IllegalArgumentException("Grundstück fehlt");JSONArray features=new JSONArray();
        if(p.optJSONObject("geometry")!=null)features.put(feature(p.optJSONObject("geometry"),p));
        for(JSONObject f:features(projectId))features.put(feature(f.optJSONObject("geometry"),f));
        for(JSONObject s:sessions(projectId)) {
            JSONArray segment=new JSONArray();
            for(JSONObject fix:fixes(s.optString("id"))) {
                if(!fix.optBoolean("accepted",false)||fix.optBoolean("breakTrack",false)){addTrack(features,segment,s);segment=new JSONArray();}
                if(fix.optBoolean("accepted",false)){JSONArray point=new JSONArray();point.put((Object)fix.optDouble("lon"));point.put((Object)fix.optDouble("lat"));segment.put(point);}
            }addTrack(features,segment,s);
        }
        JSONObject collection=new JSONObject();put(collection,"type","FeatureCollection");put(collection,"features",features);return collection;
    }
    private static void addTrack(JSONArray features,JSONArray points,JSONObject session) {if(points.length()<2)return;JSONObject g=new JSONObject();put(g,"type","LineString");put(g,"coordinates",points);JSONObject props=copy(session);put(props,"kind","measurement-track");features.put(feature(g,props));}
    private File originalFolder(String sid) {return new File(new File(context.getFilesDir(),"survey-originals"),id(sid));}
    private static final String[] LEGACY_NAMES={"messwerte.csv","standorte.csv","ereignisse.csv","diagnose.txt","kartenpunkte.geojson"};
    private static boolean legacyName(String name) {return Arrays.asList(LEGACY_NAMES).contains(name);}
    private static long transfer(InputStream in,OutputStream out,long limit) throws IOException {byte[] b=new byte[32768];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IOException("Archiv überschreitet Größenlimit");out.write(b,0,n);}return total;}
    private static File stage(File root,String name) throws IOException {File f=new File(root,name);if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Ungültiger ZIP-Pfad");File parent=f.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Temporärer Speicher nicht verfügbar");return f;}
    private static void removeTree(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)removeTree(child);f.delete();}
    private File unpack(InputStream in,boolean legacy) throws IOException {
        File root=Files.createTempDirectory(context.getCacheDir().toPath(),"survey-import-").toFile();
        File source=new File(root,"source.zip");Set<String> names=new HashSet<>(),expected=new HashSet<>();long total=0;
        try {
            try(OutputStream out=new FileOutputStream(source)){transfer(in,out,MAX_ARCHIVE);}
            // ZipInputStream alone accepts archives whose central directory was truncated.
            try(ZipFile index=new ZipFile(source)) {
                Enumeration<? extends ZipEntry> all=index.entries();
                while(all.hasMoreElements()) {ZipEntry e=all.nextElement();if(!expected.add(e.getName())||expected.size()>10000)throw new IOException("Doppelter ZIP-Eintrag");}
            }
            try(ZipInputStream zip=new ZipInputStream(new FileInputStream(source))) {ZipEntry e;while((e=zip.getNextEntry())!=null){String n=e.getName();
                boolean valid=legacy?legacyName(n):n.equals("project.json")||n.equals("map.mbtiles")||n.equals("karte.geojson")||n.matches("photos/"+UUID_PATTERN+"\\.jpg")||n.matches("sessions/"+UUID_PATTERN+"/(samples\\.ndjson|fixes\\.ndjson|events\\.ndjson|messwerte\\.csv|standorte\\.csv|ereignisse\\.csv|original/(messwerte\\.csv|standorte\\.csv|ereignisse\\.csv|diagnose\\.txt|kartenpunkte\\.geojson))");
                if(e.isDirectory()||!valid||!expected.contains(n)||!names.add(n)||names.size()>10000)throw new IOException("Unerwarteter oder doppelter ZIP-Eintrag");
                File f=stage(root,n);long cap=n.equals("map.mbtiles")?MAX_ARCHIVE:MAX_DATA;
                try(OutputStream out=new FileOutputStream(f)){total+=transfer(zip,out,cap);if(total>MAX_ARCHIVE)throw new IOException("Projektarchiv größer als 512 MiB");}zip.closeEntry();
            }}
            if(!names.equals(expected))throw new IOException("Unvollständiges ZIP");source.delete();return root;
        } catch(Exception ex){removeTree(root);if(ex instanceof IOException)throw (IOException)ex;throw new IOException("Archiv ungültig",ex);}
    }
    private static String read(File file,long max) throws IOException {if(!file.isFile()||file.length()>max)throw new IOException("Datei fehlt oder ist zu groß: "+file.getName());return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private static void validateMap(File file) throws IOException {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            try(Cursor c=db.rawQuery("PRAGMA quick_check",null)){if(!c.moveToFirst()||!"ok".equals(c.getString(0)))throw new IOException("Offline-Karte beschädigt");}
            try(Cursor c=db.rawQuery("SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles LIMIT 1",null)){if(!c.moveToFirst()||c.getBlob(3)==null)throw new IOException("Offline-Karte enthält keine Kacheln");}
            try(Cursor ignored=db.rawQuery("SELECT name,value FROM metadata LIMIT 1",null)){ignored.moveToFirst();}
        }catch(RuntimeException e){throw new IOException("Ungültige MBTiles-Karte",e);}
    }
    private static void validatePhoto(File file) throws IOException {
        if(!file.isFile()||file.length()<4||file.length()>8L*1024*1024)throw new IOException("Markerfoto fehlt oder ist zu groß");
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);
        if(!"image/jpeg".equals(o.outMimeType)||o.outWidth<1||o.outHeight<1||o.outWidth>2400||o.outHeight>2400)throw new IOException("Markerfoto ist kein unterstütztes JPEG");
        Bitmap image=BitmapFactory.decodeFile(file.getPath());if(image==null)throw new IOException("Markerfoto beschädigt");image.recycle();
    }
    public synchronized String importProject(InputStream input) throws IOException {
        File staged=unpack(input,false);File installedMap=null;ArrayList<File> installedOriginals=new ArrayList<>();ArrayList<File> installedPhotos=new ArrayList<>();SQLiteDatabase db=getWritableDatabase();boolean success=false;
        try {
            JSONObject manifest=new JSONObject(read(new File(staged,"project.json"),MAX_DATA));
            if(!"MagnetRuteSurvey".equals(manifest.optString("format"))||manifest.optInt("version")!=1)throw new IOException("Unbekanntes Projektformat");
            JSONObject p=manifest.getJSONObject("project");projectCheck(p);String oldProject=p.getString("id"),newProject=UUID.randomUUID().toString();put(p,"id",newProject);
            JSONArray fs=manifest.getJSONArray("features"),ss=manifest.getJSONArray("sessions");if(fs.length()>100000||ss.length()>10000)throw new IOException("Zu viele Datensätze");
            String oldMap=mapName(p);File map=new File(staged,"map.mbtiles");
            if(!oldMap.isEmpty()){if(!map.isFile())throw new IOException("Offline-Karte fehlt");validateMap(map);
                if(p.optBoolean("offlineReady")&&!OfflineTiles.validate(map))throw new IOException("Offline-Paket ist nicht vollständig oder seine Prüfsummen sind ungültig.");
                String name=newProject+".mbtiles";File dir=new File(context.getFilesDir(),"offline-maps");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Kartenspeicher nicht verfügbar");installedMap=new File(dir,name);Files.move(map.toPath(),installedMap.toPath(),StandardCopyOption.ATOMIC_MOVE);put(p,"mapPath",name);
            }else if(map.exists()||p.optBoolean("offlineReady"))throw new IOException("Offline-Karte ohne Projektzuordnung");
            db.beginTransaction();putProject(p);
            Set<String> referencedPhotos=new HashSet<>();long photoBytes=0;
            for(int i=0;i<fs.length();i++){
                JSONObject f=fs.getJSONObject(i);if(!oldProject.equals(f.optString("projectId")))throw new IOException("Marker gehört zu anderem Grundstück");
                String newFeature=UUID.randomUUID().toString(),photo=photoName(f);put(f,"id",newFeature);put(f,"projectId",newProject);
                if(!photo.isEmpty()){
                    File image=new File(staged,"photos/"+photo);validatePhoto(image);if(referencedPhotos.add(photo))photoBytes+=image.length();if(photoBytes>40L*1024*1024)throw new IOException("Markerfotos größer als 40 MiB");
                    File dir=new File(context.getFilesDir(),"survey-photos");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Fotospeicher nicht verfügbar");File target=new File(dir,newFeature+".jpg");Files.copy(image.toPath(),target.toPath());installedPhotos.add(target);put(f,"photoPath",target.getName());
                }
                putFeature(f);
            }
            File[] photos=new File(staged,"photos").listFiles();if(photos!=null)for(File photo:photos)if(!referencedPhotos.contains(photo.getName()))throw new IOException("Foto ohne Markerzuordnung");
            Set<String> sessionIds=new HashSet<>();
            for(int i=0;i<ss.length();i++) {JSONObject s=ss.getJSONObject(i);String oldId=id(s.optString("id"));if(!sessionIds.add(oldId)||!oldProject.equals(s.optString("projectId")))throw new IOException("Sitzungszuordnung ungültig");String sid=UUID.randomUUID().toString();put(s,"id",sid);put(s,"projectId",newProject);put(s,"status","stopped");updateSession(s);
                for(String table:new String[]{"samples","fixes","events"})importLines(new File(staged,"sessions/"+oldId+"/"+table+".ndjson"),table,sid);
                File originals=new File(staged,"sessions/"+oldId+"/original");if(originals.isDirectory()){File dest=originalFolder(sid);File parent=dest.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Originalspeicher nicht verfügbar");Files.move(originals.toPath(),dest.toPath());installedOriginals.add(dest);}
            }
            File sessionsRoot=new File(staged,"sessions");File[] archivedSessions=sessionsRoot.listFiles();if(archivedSessions!=null)for(File f:archivedSessions)if(!sessionIds.contains(f.getName()))throw new IOException("Nicht zugeordnete Sitzung im ZIP");
            db.setTransactionSuccessful();success=true;return newProject;
        }catch(Exception e){if(e instanceof IOException)throw (IOException)e;throw new IOException("Projektimport fehlgeschlagen: "+e.getMessage(),e);}
        finally {if(db.inTransaction())db.endTransaction();if(!success){if(installedMap!=null)installedMap.delete();for(File f:installedOriginals)removeTree(f);for(File f:installedPhotos)f.delete();}removeTree(staged);}
    }
    private void importLines(File file,String table,String sid) throws IOException {
        if(!file.isFile())throw new IOException("Sitzungsdaten fehlen: "+table);
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;int count=0;while((line=in.readLine())!=null){if(line.length()>256*1024||++count>2000000)throw new IOException("Sitzungsdaten überschreiten Limit");if(!line.isEmpty())append(table,sid,json(line));}}
    }

    /** Import old v0.3 ZIPs without altering their bytes; poor fixes remain diagnostic originals. */
    public synchronized String importLegacy(InputStream input,String projectId) throws IOException {
        id(projectId);if(project(projectId)==null)throw new IOException("Grundstück fehlt");File staged=unpack(input,true);SQLiteDatabase db=getWritableDatabase();String sid=null;File original=null;boolean success=false;
        try {
            File samplesFile=new File(staged,"messwerte.csv");if(!samplesFile.isFile())throw new IOException("messwerte.csv fehlt im v0.3-Log");
            db.beginTransaction();sid=createSession(projectId,"v0.3 Import "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.GERMANY).format(new Date()));
            JSONObject session=session(sid);put(session,"status","stopped");put(session,"importedFrom","v0.3");updateSession(session);
            Map<Long,GpsQuality.Fix> acceptedFixes=new HashMap<>();GpsQuality quality=new GpsQuality();
            File locationFile=new File(staged,"standorte.csv");boolean hasLocationFile=locationFile.isFile();
            try(CsvRows gps=csvRows(locationFile)) { for(JSONObject row:gps) {
                double lat=number(row,"lat"),lon=number(row,"lon"),accuracy=number(row,"accuracy_m");long elapsed=longNumber(row,"fix_elapsed_ns");
                GpsQuality.Fix raw=new GpsQuality.Fix(lat,lon,accuracy,elapsed,longNumber(row,"fix_unix_ms"),row.optString("provider"),number(row,"speed_ms"));
                GpsQuality.Decision decision=quality.inspect(raw,elapsed,10);JSONObject f=new JSONObject();
                put(f,"lat",finite(lat));put(f,"lon",finite(lon));put(f,"accuracy",finite(accuracy));put(f,"elapsedNs",elapsed);put(f,"unixMs",raw.unixMs);put(f,"provider",raw.provider);put(f,"speed",finite(raw.speed));put(f,"accepted",decision.accepted);put(f,"reason",decision.reason);put(f,"breakTrack",decision.breakTrack);put(f,"original",row);addFix(sid,f);
                if(decision.accepted)acceptedFixes.put(elapsed,raw);
            }}
            int pass=0;long previousTimestamp=0,legacyOriginNs=0;CalibrationGuard importedCalibration=new CalibrationGuard();
            try(CsvRows sampleRows=csvRows(samplesFile)) { for(JSONObject row:sampleRows) {
                JSONObject sample=new JSONObject();long timestamp=longNumber(row,"sensor_timestamp_ns");if(previousTimestamp>0&&timestamp-previousTimestamp>5_000_000_000L)pass++;if(legacyOriginNs==0&&timestamp>0)legacyOriginNs=timestamp-longNumber(row,"seit_start_ms")*1_000_000L;previousTimestamp=timestamp;
                put(sample,"timestampNs",timestamp);put(sample,"unixMs",isoMillis(row.optString("uhrzeit_iso")));put(sample,"passId",sid);put(sample,"segmentId",pass);put(sample,"original",row);
                String[][] names={{"x","x_uT"},{"y","y_uT"},{"z","z_uT"},{"total","b_gesamt_uT"},{"vertical","b_vertikal_uT"},{"horizontal","b_horizontal_uT"},{"reference","referenz_uT"},{"delta","abweichung_uT"},{"sensitivity","empfindlichkeit_uT"},{"uncalX","uncal_x_uT"},{"uncalY","uncal_y_uT"},{"uncalZ","uncal_z_uT"},{"biasX","bias_x_uT"},{"biasY","bias_y_uT"},{"biasZ","bias_z_uT"}};for(String[] pair:names)put(sample,pair[0],finite(number(row,pair[1])));
                int accuracyStatus=(int)longNumber(row,"genauigkeit");put(sample,"sensorAccuracy",accuracyStatus);
                CalibrationGuard.State calibration=importedCalibration.observe(timestamp,accuracyStatus,number(row,"bias_x_uT"),number(row,"bias_y_uT"),number(row,"bias_z_uT"));
                put(sample,"calibrationJump",calibration.jump);put(sample,"uncertain",calibration.uncertain);put(sample,"calibrationReason",calibration.reason);
                if(calibration.jump||calibration.statusChanged)addEvent(sid,"LEGACY_CALIBRATION",calibration.reason,timestamp);
                double lat=number(row,"gps_lat"),lon=number(row,"gps_lon"),accuracy=number(row,"gps_accuracy_m"),age=number(row,"gps_age_ms");long fixNs=longNumber(row,"gps_fix_elapsed_ns");
                boolean accepted=Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180&&Double.isFinite(accuracy)&&accuracy>=0&&accuracy<=10&&fixNs>0&&timestamp>=fixNs&&timestamp-fixNs<=5_000_000_000L&&(!Double.isFinite(age)||age>=0&&age<=5000);
                GpsQuality.Fix acceptedFix=acceptedFixes.get(fixNs);
                if(accepted && hasLocationFile) accepted=acceptedFix!=null&&GeoMath.distance(lat,lon,acceptedFix.lat,acceptedFix.lon)<1;
                if(accepted && !hasLocationFile && acceptedFix==null) {
                    GpsQuality.Fix candidate=new GpsQuality.Fix(lat,lon,accuracy,fixNs,longNumber(row,"gps_fix_unix_ms"),row.optString("gps_provider"),number(row,"gps_speed_ms"));
                    GpsQuality.Decision decision=quality.inspect(candidate,timestamp,10);accepted=decision.accepted;
                    if(accepted){acceptedFixes.put(fixNs,candidate);acceptedFix=candidate;}
                    JSONObject f=new JSONObject();put(f,"lat",lat);put(f,"lon",lon);put(f,"accuracy",accuracy);put(f,"elapsedNs",fixNs);put(f,"unixMs",candidate.unixMs);put(f,"provider",candidate.provider);put(f,"accepted",accepted);put(f,"reason",decision.reason);put(f,"breakTrack",decision.breakTrack);addFix(sid,f);
                }
                if(accepted) {JSONObject f=new JSONObject();put(f,"lat",lat);put(f,"lon",lon);put(f,"accuracy",accuracy);put(f,"elapsedNs",fixNs);put(f,"unixMs",longNumber(row,"gps_fix_unix_ms"));put(f,"provider",row.optString("gps_provider"));put(f,"accepted",true);put(sample,"fix",f);}else{put(sample,"fix",JSONObject.NULL);put(sample,"gpsRejection","legacy_quality_or_age");}
                addSample(sid,sample);
            }}
            try(CsvRows rows=csvRows(new File(staged,"ereignisse.csv"))) {for(JSONObject row:rows){JSONObject event=new JSONObject();put(event,"name",row.optString("ereignis"));put(event,"details",row.optString("details"));put(event,"timestampNs",legacyOriginNs+longNumber(row,"seit_start_ms")*1_000_000L);put(event,"unixMs",isoMillis(row.optString("uhrzeit_iso")));put(event,"original",row);append("events",sid,event);}}
            original=originalFolder(sid);File parent=original.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Originalspeicher nicht verfügbar");Files.move(staged.toPath(),original.toPath());addEvent(sid,"LEGACY_IMPORT","Originaldaten erhalten; kartografische Genauigkeitsgrenze 10 m",0);
            db.setTransactionSuccessful();success=true;return sid;
        }catch(Exception e){if(e instanceof IOException)throw (IOException)e;throw new IOException("v0.3-Import fehlgeschlagen",e);}finally{if(db.inTransaction())db.endTransaction();if(!success&&original!=null)removeTree(original);removeTree(staged);}
    }
    private static long isoMillis(String value){try{return new java.text.SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ss.SSSZ",Locale.US).parse(value).getTime();}catch(java.text.ParseException|NullPointerException e){return 0;}}
    private static Object finite(double n) {return Double.isFinite(n)?n:JSONObject.NULL;}
    private static double number(JSONObject row,String name) {try{return Double.parseDouble(row.optString(name));}catch(NumberFormatException e){return Double.NaN;}}
    private static long longNumber(JSONObject row,String name){try{return Long.parseLong(row.optString(name));}catch(NumberFormatException e){return 0;}}
    private static double distance(double a,double b,double c,double d){double x=Math.toRadians(c-a),y=Math.toRadians(d-b),h=Math.sin(x/2)*Math.sin(x/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(y/2)*Math.sin(y/2);return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));}
    private static CsvRows csvRows(File file) throws IOException {return new CsvRows(file);}
    /** Streams legacy CSV rather than expanding a long recording into a second in-memory copy. */
    private static final class CsvRows implements Iterable<JSONObject>,Iterator<JSONObject>,AutoCloseable {
        private final PushbackReader reader;private final List<String> header;private JSONObject next;private int count;
        CsvRows(File file) throws IOException {
            reader=file.isFile()?new PushbackReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8),1):null;
            header=reader==null?null:csvRecord(reader);
            if(header!=null&&!header.isEmpty())header.set(0,header.get(0).replace("\uFEFF",""));
        }
        @Override public Iterator<JSONObject> iterator(){return this;}
        @Override public boolean hasNext(){
            if(next!=null)return true;if(reader==null||header==null)return false;
            try {List<String> values;while((values=csvRecord(reader))!=null){
                if(values.size()==1&&values.get(0).isEmpty())continue;
                if(values.size()!=header.size()||++count>2000000)throw new IOException("CSV-Spaltenanzahl oder Zeilenzahl ungültig");
                next=new JSONObject();for(int i=0;i<header.size();i++)put(next,header.get(i),values.get(i));return true;
            }return false;}catch(IOException e){throw new UncheckedIOException(e);}
        }
        @Override public JSONObject next(){if(!hasNext())throw new NoSuchElementException();JSONObject value=next;next=null;return value;}
        @Override public void close() throws IOException{if(reader!=null)reader.close();}
    }
    private static List<String> csvRecord(PushbackReader reader) throws IOException {
        ArrayList<String> values=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,started=false;int ch;
        while((ch=reader.read())!=-1){started=true;char c=(char)ch;if(c=='"'){if(quoted){int next=reader.read();if(next=='"')field.append('"');else{quoted=false;if(next!=-1)reader.unread(next);}}else if(field.length()==0)quoted=true;else throw new IOException("CSV-Anführungszeichen ungültig");}
            else if(!quoted&&(c==','||c=='\n'||c=='\r')){values.add(field.toString());field.setLength(0);if(c!=','){if(c=='\r'){int next=reader.read();if(next!='\n'&&next!=-1)reader.unread(next);}return values;}}
            else field.append(c);if(field.length()>1024*1024||values.size()>200)throw new IOException("CSV-Zeile zu groß");
        }
        if(quoted)throw new IOException("CSV-Zeile unvollständig");if(!started)return null;values.add(field.toString());return values;
    }
}
