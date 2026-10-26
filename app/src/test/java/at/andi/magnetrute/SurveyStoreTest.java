package at.andi.magnetrute;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SurveyStoreTest {
    private Context context;private SurveyStore store;
    private static JSONObject object(Object... pairs) throws JSONException {JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}
    private static JSONArray coords(double x,double y) throws JSONException {JSONArray a=new JSONArray();a.put(x);a.put(y);return a;}
    private JSONObject project() throws JSONException {
        String id=UUID.randomUUID().toString();JSONObject p=object("id",id,"name","Garten","latitude",47.2,"longitude",14.1,"zoom",17,"mapPath","","offlineReady",false,"source","basemap.at","attribution","Datenquelle: basemap.at");store.putProject(p);return p;
    }
    private JSONObject marker(String project) throws JSONException {return object("id",UUID.randomUUID().toString(),"projectId",project,"name","Baum","type","Baum","note","Eiche, Nordseite","geometry",object("type","Point","coordinates",coords(14.1,47.2)),"originalGps",object("lat",47.2001,"lon",14.1001,"accuracy",4));}
    @Before public void setUp(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("survey-v1.sqlite");store=new SurveyStore(context);}
    @After public void tearDown(){store.close();context.deleteDatabase("survey-v1.sqlite");}

    @Test public void editsPersistWithoutDeletingChildrenAndReopenKeepsRows() throws Exception {
        JSONObject p=project();String pid=p.getString("id");JSONObject f=marker(pid);store.putFeature(f);String sid=store.createSession(pid,"Hinweg");
        store.addSample(sid,object("timestampNs",1000000000L,"x",3,"y",4,"z",0,"total",5));store.addFix(sid,object("elapsedNs",1000000000L,"lat",47.2,"lon",14.1,"accuracy",3,"accepted",true));store.addEvent(sid,"REFERENZ","50 µT",1000000000L);
        p.put("name","Wiese");store.putProject(p);JSONObject session=store.session(sid);session.put("status","paused");store.updateSession(session);
        assertEquals(1,store.features(pid).size());assertEquals(1,store.samples(sid).size());assertEquals(1,store.fixes(sid).size());assertEquals(1,store.events(sid).size());
        store.close();store=new SurveyStore(context);assertEquals("Wiese",store.project(pid).getString("name"));assertEquals("paused",store.session(sid).getString("status"));assertEquals(5,store.samples(sid).get(0).getDouble("total"),0);
    }
    @Test public void latestLimitedRowsStayChronologicalAndProjectDeletionIsScoped() throws Exception {
        String pid=project().getString("id"),other=project().getString("id");String sid=store.createSession(pid,"Messung");
        for(int i=0;i<10;i++)store.addSample(sid,object("timestampNs",i));
        assertEquals(7,store.samples(sid,3).get(0).getInt("timestampNs"));assertEquals(9,store.samples(sid,3).get(2).getInt("timestampNs"));
        assertEquals(10,store.sampleCount(sid));assertEquals(3,store.sampleWindow(sid,3,3).get(0).getInt("timestampNs"));assertEquals(5,store.sampleWindow(sid,3,3).get(2).getInt("timestampNs"));
        JSONObject invalid=store.project(pid);invalid.put("pendingMap","../../survey-v1.sqlite");assertThrows(IllegalArgumentException.class,()->store.putProject(invalid));
        store.deleteProject(pid);assertNull(store.session(sid));assertTrue(store.samples(sid).isEmpty());assertNotNull(store.project(other));
    }
    @Test public void projectArchiveRestoresNewIdsRawValuesMarkerAndTracks() throws Exception {
        String pid=project().getString("id");JSONObject marker=marker(pid);store.putFeature(marker);String sid=store.createSession(pid,"26.10. Hinweg");
        store.addSample(sid,object("timestampNs",2000000000L,"total",48.125,"reference",47,"sensitivity",3,"biasX",2));
        store.addFix(sid,object("elapsedNs",1000000000L,"lat",47.2,"lon",14.1,"accepted",true,"breakTrack",true));store.addFix(sid,object("elapsedNs",2000000000L,"lat",47.20001,"lon",14.10001,"accepted",true,"breakTrack",false));store.addEvent(sid,"EMPFINDLICHKEIT","3 µT",2000000000L);
        ByteArrayOutputStream out=new ByteArrayOutputStream();store.exportProject(pid,out);Map<String,byte[]> files=unzip(out.toByteArray());
        assertTrue(files.containsKey("sessions/"+sid+"/messwerte.csv"));assertTrue(new String(files.get("karte.geojson"),StandardCharsets.UTF_8).contains("LineString"));
        String imported=store.importProject(new ByteArrayInputStream(out.toByteArray()));assertNotEquals(pid,imported);assertEquals(2,store.projects().size());assertEquals(1,store.features(imported).size());
        JSONObject restoredMarker=store.features(imported).get(0);assertNotEquals(marker.getString("id"),restoredMarker.getString("id"));assertEquals(4,restoredMarker.getJSONObject("originalGps").getInt("accuracy"));
        String restoredSid=store.sessions(imported).get(0).getString("id");assertEquals(48.125,store.samples(restoredSid).get(0).getDouble("total"),0);assertEquals(1,store.events(restoredSid).size());assertEquals("stopped",store.session(restoredSid).getString("status"));
    }
    @Test public void maliciousAndTruncatedArchivesNeverAlterExistingData() throws Exception {
        String pid=project().getString("id");store.putFeature(marker(pid));ByteArrayOutputStream archive=new ByteArrayOutputStream();store.exportProject(pid,archive);byte[] bytes=archive.toByteArray();
        assertThrows(IOException.class,()->store.importProject(new ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length-22))));
        assertThrows(IOException.class,()->store.importProject(new ByteArrayInputStream(zip(Collections.singletonMap("../survey-v1.sqlite",new byte[]{1,2,3})))));
        Map<String,byte[]> entries=unzip(bytes);JSONObject manifest=new JSONObject(new String(entries.get("project.json"),StandardCharsets.UTF_8));manifest.getJSONArray("features").getJSONObject(0).getJSONObject("geometry").put("coordinates",coords(999,47));entries.put("project.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->store.importProject(new ByteArrayInputStream(zip(entries))));assertEquals(1,store.projects().size());assertEquals(1,store.features(pid).size());
    }
    @Test public void legacyImportPreservesQuotedOriginalsAndRejectsBadGpsBeforeHeatmap() throws Exception {
        String pid=project().getString("id");String header="sensor_timestamp_ns,x_uT,y_uT,z_uT,b_gesamt_uT,referenz_uT,abweichung_uT,empfindlichkeit_uT,genauigkeit,gps_lat,gps_lon,gps_accuracy_m,gps_fix_elapsed_ns,gps_fix_unix_ms,gps_age_ms,gps_provider\n";
        String samples=header+"1100000000,3,4,0,5,4,1,3,3,47.2,14.1,900,1000000000,1793010000000,100,network\n"+"2100000000,3,4,1,5.1,4,1.1,3,3,47.2,14.1,4,2000000000,1793010001000,100,gps\n";
        String gps="fix_elapsed_ns,fix_unix_ms,lat,lon,accuracy_m,provider,speed_ms\n1000000000,1793010000000,47.2,14.1,900,network,0\n2000000000,1793010001000,47.2,14.1,4,gps,0\n";
        String events="seit_start_ms,ereignis,details\n100,MARKIERUNG,\"Baum, mit \"\"Eiche\"\"\"\n";Map<String,byte[]> entries=new LinkedHashMap<>();entries.put("messwerte.csv",samples.getBytes(StandardCharsets.UTF_8));entries.put("standorte.csv",gps.getBytes(StandardCharsets.UTF_8));entries.put("ereignisse.csv",events.getBytes(StandardCharsets.UTF_8));
        String sid=store.importLegacy(new ByteArrayInputStream(zip(entries)),pid);assertEquals(2,store.fixes(sid).size());assertFalse(store.fixes(sid).get(0).getBoolean("accepted"));assertEquals("network_accuracy",store.fixes(sid).get(0).getString("reason"));assertFalse(store.fixes(sid).get(1).getBoolean("accepted"));assertEquals("gps_acquiring",store.fixes(sid).get(1).getString("reason"));
        assertTrue(store.samples(sid).get(0).isNull("fix"));assertEquals(5,store.samples(sid).get(0).getDouble("total"),0);assertNull(store.samples(sid).get(1).optJSONObject("fix"));assertEquals("Baum, mit \"Eiche\"",store.events(sid).get(0).getString("details"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();store.exportProject(pid,out);assertArrayEquals(samples.getBytes(StandardCharsets.UTF_8),unzip(out.toByteArray()).get("sessions/"+sid+"/original/messwerte.csv"));
    }
    @Test public void photosAndMbtilesRoundTripWithoutExternalUris() throws Exception {
        JSONObject p=project();String pid=p.getString("id");File mapDir=new File(context.getFilesDir(),"offline-maps");mapDir.mkdirs();String mapName=pid+".mbtiles";File map=new File(mapDir,mapName);
        Bitmap tile=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);ByteArrayOutputStream tileBytes=new ByteArrayOutputStream();tile.compress(Bitmap.CompressFormat.PNG,100,tileBytes);tile.recycle();
        OfflineTiles.Plan plan=OfflineTiles.estimate(new JSONArray("[[14.1,47.2],[14.10001,47.2],[14.10001,47.20001],[14.1,47.20001],[14.1,47.2]]"),0,17,17,OfflineTiles.BASEMAP_ORTHO);
        OfflineTiles.download(map,plan,null,()->false,url->tileBytes.toByteArray());
        p.put("mapPath",mapName);p.put("offlineReady",true);store.putProject(p);JSONObject f=marker(pid);String photo=f.getString("id")+".jpg";File photoDir=new File(context.getFilesDir(),"survey-photos");photoDir.mkdirs();File originalPhoto=new File(photoDir,photo);Bitmap b=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(originalPhoto)){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}b.recycle();f.put("photoPath",photo);store.putFeature(f);
        ByteArrayOutputStream out=new ByteArrayOutputStream();store.exportProject(pid,out);String restored=store.importProject(new ByteArrayInputStream(out.toByteArray()));JSONObject restoredFeature=store.features(restored).get(0);
        assertNotEquals(photo,restoredFeature.getString("photoPath"));assertTrue(new File(photoDir,restoredFeature.getString("photoPath")).isFile());assertArrayEquals(Files.readAllBytes(originalPhoto.toPath()),Files.readAllBytes(new File(photoDir,restoredFeature.getString("photoPath")).toPath()));assertTrue(new File(mapDir,store.project(restored).getString("mapPath")).isFile());
        f.put("photoPath","../../other.jpg");assertThrows(IllegalArgumentException.class,()->store.putFeature(f));
        store.deleteFeature(f.getString("id"));assertFalse(originalPhoto.exists());assertTrue(new File(photoDir,restoredFeature.getString("photoPath")).isFile());
    }
    @Test public void rejectedGpsAlwaysBreaksGeojsonTracks() throws Exception {
        String pid=project().getString("id"),sid=store.createSession(pid,"Zwei Abschnitte");
        for(int i=0;i<5;i++)store.addFix(sid,object("lat",47.2+i*.00001,"lon",14.1,"accepted",i!=2,"breakTrack",i==0||i==2||i==3));
        JSONArray features=store.geojson(pid).getJSONArray("features");assertEquals(2,features.length());for(int i=0;i<2;i++)assertEquals(2,features.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates").length());
    }
    private static byte[] zip(Map<String,byte[]> entries) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return out.toByteArray();}
    private static Map<String,byte[]> unzip(byte[] bytes) throws IOException {Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=zip.read(b))!=-1)out.write(b,0,n);result.put(e.getName(),out.toByteArray());}}return result;}
}
