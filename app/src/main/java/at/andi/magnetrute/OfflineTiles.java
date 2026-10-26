package at.andi.magnetrute;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A bounded, resumable MBTiles package. Reading local tiles never makes a network request. */
public final class OfflineTiles implements AutoCloseable {
    public static final String BASEMAP_ORTHO="basemap-ortho";
    public static final String BASEMAP_STANDARD="basemap-standard";
    public static final String GIS_STMK="gis-stmk";
    public static final int MAX_TILES=4000;
    public static final long MAX_BYTES=250L*1024*1024;
    public static final int MAX_TILE_BYTES=1024*1024;
    private static final long MIN_FREE_BYTES=16L*1024*1024;
    private static final String PLAN_KEY="magnetrute_plan";
    private static final String SCHEMA="1";
    private final SQLiteDatabase db;

    public interface Progress { void update(int done,int total,long bytes); }
    public interface Control { boolean paused(); }
    public interface Transport { byte[] fetch(String url) throws IOException; }

    public static final class Plan {
        public final int tileCount,minZoom,maxZoom;
        public final long estimatedBytes;
        public final double west,south,east,north,resolutionMeters;
        public final String source,attribution;
        private final List<Tile> tiles;
        private Plan(double west,double south,double east,double north,int minZoom,int maxZoom,String source,List<Tile> tiles) {
            this.west=west;this.south=south;this.east=east;this.north=north;
            this.minZoom=minZoom;this.maxZoom=maxZoom;this.source=source;this.tiles=tiles;
            tileCount=tiles.size();estimatedBytes=tileCount*(BASEMAP_STANDARD.equals(source)?55_000L:40_000L);
            attribution=attribution(source);resolutionMeters=GeoMath.metersPerPixel((north+south)/2,maxZoom);
        }
        public JSONObject json() {
            try {
                return new JSONObject().put("schema",SCHEMA).put("source",source).put("minZoom",minZoom).put("maxZoom",maxZoom)
                    .put("bounds",new JSONArray(new double[]{west,south,east,north})).put("total",tileCount)
                    .put("estimatedBytes",estimatedBytes).put("resolutionMeters",resolutionMeters)
                    .put("attribution",attribution).put("licenseUrl",licenseUrl(source));
            } catch(JSONException e) { throw new IllegalStateException(e); }
        }
        private String identity() { return sha256(json().toString().getBytes(StandardCharsets.UTF_8)); }
    }
    private static final class Tile {
        final int z,x,y;
        Tile(int z,int x,int y) { this.z=z;this.x=x;this.y=y; }
        int row() { return (1<<z)-1-y; }
    }

    /** Polygon coordinates are GeoJSON order longitude,latitude; buffer is ground metres. */
    public static Plan estimate(JSONArray polygonRingLonLat,double bufferMeters,int minZoom,int maxZoom,String source) {
        sourceCheck(source);
        if(polygonRingLonLat==null || polygonRingLonLat.length()<3 || polygonRingLonLat.length()>1000
                || !Double.isFinite(bufferMeters) || bufferMeters<0 || bufferMeters>1000
                || minZoom<0 || maxZoom>20 || minZoom>maxZoom)
            throw new IllegalArgumentException("Polygon, Rand oder Zoomstufen sind ungültig (Zoom 0–20, Rand 0–1000 m).");
        double west=180,east=-180,south=90,north=-90;
        try {
            for(int i=0;i<polygonRingLonLat.length();i++) {
                JSONArray c=polygonRingLonLat.getJSONArray(i);double lon=c.getDouble(0),lat=c.getDouble(1);
                if(c.length()!=2 || !GeoMath.valid(lat,lon) || Math.abs(lat)>GeoMath.MAX_MERCATOR_LATITUDE)
                    throw new IllegalArgumentException("Ungültige WGS84-Polygonkoordinate.");
                west=Math.min(west,lon);east=Math.max(east,lon);south=Math.min(south,lat);north=Math.max(north,lat);
            }
        } catch(JSONException e) { throw new IllegalArgumentException("Polygonkoordinaten fehlen.",e); }
        if(east-west<=0 || north-south<=0 || east-west>180)
            throw new IllegalArgumentException("Messfläche benötigt ein flächiges Polygon ohne Datumsgrenzen-Überquerung.");
        double latBuffer=Math.toDegrees(bufferMeters/GeoMath.EARTH_RADIUS_METERS);
        double lonBuffer=latBuffer/Math.max(.01,Math.cos(Math.toRadians((north+south)/2)));
        west=Math.max(-180,west-lonBuffer);east=Math.min(180,east+lonBuffer);
        south=Math.max(-GeoMath.MAX_MERCATOR_LATITUDE,south-latBuffer);
        north=Math.min(GeoMath.MAX_MERCATOR_LATITUDE,north+latBuffer);
        List<Tile> tiles=enumerate(west,south,east,north,minZoom,maxZoom);
        Plan plan=new Plan(west,south,east,north,minZoom,maxZoom,source,tiles);
        if(plan.estimatedBytes>MAX_BYTES) throw new IllegalArgumentException("Der Kartenausschnitt überschreitet 250 MiB. Fläche oder Zoom reduzieren.");
        return plan;
    }

    private static List<Tile> enumerate(double west,double south,double east,double north,int minZoom,int maxZoom) {
        List<Tile> tiles=new ArrayList<>();
        for(int z=minZoom;z<=maxZoom;z++) {
            double[] a=GeoMath.world(north,west,z),b=GeoMath.world(south,east,z);int max=(1<<z)-1;
            int x0=Math.max(0,(int)Math.floor(a[0]/256)),y0=Math.max(0,(int)Math.floor(a[1]/256));
            int x1=Math.min(max,(int)Math.floor(b[0]/256)),y1=Math.min(max,(int)Math.floor(b[1]/256));
            long count=(long)(x1-x0+1)*(y1-y0+1);
            if(count<1 || count+tiles.size()>MAX_TILES)
                throw new IllegalArgumentException("Zu viele Kartenkacheln (maximal 4000). Fläche, Rand oder Zoom reduzieren.");
            for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) tiles.add(new Tile(z,x,y));
        }
        return tiles;
    }

    public OfflineTiles(File file) throws IOException {
        if(file==null || !file.isFile()) throw new IOException("Offline-Kartenpaket fehlt.");
        SQLiteDatabase opened=null;
        try {
            opened=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            readPlan(opened);db=opened;
        } catch(IOException|RuntimeException e) {
            if(opened!=null) opened.close();
            throw new IOException("Offline-Kartenpaket ist beschädigt.",e);
        }
    }

    /** Missing or damaged tiles return null; no remote fallback is performed. XYZ input, TMS storage. */
    public synchronized Bitmap get(int z,int x,int y) {
        if(z<0 || z>20 || x<0 || y<0 || x>=(1<<z) || y>=(1<<z)) return null;
        try {
            byte[] data=verified(db,new Tile(z,x,y));
            return data==null?null:BitmapFactory.decodeByteArray(data,0,data.length);
        } catch(RuntimeException e) { return null; }
    }
    public synchronized JSONObject metadata() throws IOException {
        try {
            JSONObject info=readPlan(db);
            String source=info.getString("source");
            info.put("attribution",attribution(source));info.put("licenseUrl",licenseUrl(source));
            info.put("ready","true".equals(value(db,"magnetrute_ready")));
            info.put("done",Long.parseLong(valueOr(db,"magnetrute_done","0")));
            info.put("bytes",Long.parseLong(valueOr(db,"magnetrute_bytes","0")));
            info.put("downloadedAtUnixMs",Long.parseLong(valueOr(db,"magnetrute_downloaded_at","0")));
            return info;
        } catch(JSONException|RuntimeException e) { throw new IOException("Karten-Metadaten beschädigt.",e); }
    }
    @Override public synchronized void close() { db.close(); }

    public static void download(File target,Plan plan,Progress progress,Control control) throws IOException {
        download(target,plan,progress,control,OfflineTiles::fetch);
    }

    /** Verifies retained tiles before resuming. A paused or failed package is never marked ready. */
    public static synchronized void download(File target,Plan plan,Progress progress,Control control,Transport transport) throws IOException {
        if(target==null || plan==null || transport==null) throw new IllegalArgumentException("Kartenpaket oder Downloadplan fehlt.");
        File parent=target.getAbsoluteFile().getParentFile();
        if(parent==null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Kartenordner nicht verfügbar.");
        SQLiteDatabase writable=null;
        try {
            writable=SQLiteDatabase.openOrCreateDatabase(target,null);
            writable.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY,value TEXT NOT NULL)");
            writable.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB NOT NULL,PRIMARY KEY(zoom_level,tile_column,tile_row))");
            writable.execSQL("CREATE TABLE IF NOT EXISTS tile_checksums (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,sha256 TEXT NOT NULL,PRIMARY KEY(zoom_level,tile_column,tile_row))");
            String identity=value(writable,"magnetrute_identity");
            if(identity!=null && !identity.equals(plan.identity())) throw new IOException("Vorhandenes Kartenpaket gehört zu einem anderen Ausschnitt. Neues Paket anlegen.");
            if(identity==null && count(writable,"tiles")!=0) throw new IOException("Vorhandenes fremdes MBTiles-Paket wird nicht überschrieben.");
            writable.beginTransaction();
            try {
                put(writable,PLAN_KEY,plan.json().toString());put(writable,"magnetrute_identity",plan.identity());
                put(writable,"magnetrute_ready","false");put(writable,"name","MagnetRute Offline-Karte");
                put(writable,"type","baselayer");put(writable,"version","1.3");
                put(writable,"format",BASEMAP_STANDARD.equals(plan.source)?"png":"jpg");
                put(writable,"bounds",String.format(Locale.US,"%.9f,%.9f,%.9f,%.9f",plan.west,plan.south,plan.east,plan.north));
                put(writable,"minzoom",Integer.toString(plan.minZoom));put(writable,"maxzoom",Integer.toString(plan.maxZoom));
                put(writable,"attribution",plan.attribution);put(writable,"scheme","tms");
                writable.setTransactionSuccessful();
            } finally { writable.endTransaction(); }
            int done=0;long bytes=0;
            for(Tile t:plan.tiles) {
                byte[] existing=verified(writable,t);
                if(existing!=null) { done++;bytes+=existing.length; }
            }
            if(bytes>MAX_BYTES) throw new IOException("Kartenpaket überschreitet 250 MiB.");
            progress(writable,done,bytes,plan,progress);
            if(done==plan.tileCount && count(writable,"tiles")==plan.tileCount) {
                put(writable,"magnetrute_ready","true");return;
            }
            long required=Math.max(MIN_FREE_BYTES,plan.estimatedBytes-bytes+MIN_FREE_BYTES);
            if(parent.getUsableSpace()<required) throw new IOException("Zu wenig freier Speicher für das geschätzte Kartenpaket (zusätzlich 16 MiB Reserve).");
            for(Tile t:plan.tiles) {
                if(control!=null && control.paused()) return;
                if(verified(writable,t)!=null) continue;
                if(parent.getUsableSpace()<MIN_FREE_BYTES+MAX_TILE_BYTES) throw new IOException("Speicher ist fast voll. Download pausiert; Speicher freigeben und fortsetzen.");
                byte[] data=transport.fetch(previewUrl(plan.source,t.z,t.x,t.y));
                if(!validImage(data)) throw new IOException("Kartenquelle lieferte keine gültige 256-Pixel-Bildkachel.");
                if(GIS_STMK.equals(plan.source) && emptyRegionalImage(data))
                    throw new IOException("GIS Steiermark liefert hier NoData/kein erkennbares Bild. Ausschnitt prüfen oder basemap.at wählen.");
                if(bytes+data.length>MAX_BYTES) throw new IOException("Downloadlimit von 250 MiB erreicht. Kleineres Kartenpaket wählen.");
                writable.beginTransaction();
                try {
                    ContentValues tile=new ContentValues();tile.put("zoom_level",t.z);tile.put("tile_column",t.x);tile.put("tile_row",t.row());tile.put("tile_data",data);
                    writable.insertWithOnConflict("tiles",null,tile,SQLiteDatabase.CONFLICT_REPLACE);
                    ContentValues checksum=new ContentValues();checksum.put("zoom_level",t.z);checksum.put("tile_column",t.x);checksum.put("tile_row",t.row());checksum.put("sha256",sha256(data));
                    writable.insertWithOnConflict("tile_checksums",null,checksum,SQLiteDatabase.CONFLICT_REPLACE);
                    done++;bytes+=data.length;put(writable,"magnetrute_done",Integer.toString(done));put(writable,"magnetrute_bytes",Long.toString(bytes));
                    writable.setTransactionSuccessful();
                } finally { writable.endTransaction(); }
                if(progress!=null) progress.update(done,plan.tileCount,bytes);
            }
            if(done!=plan.tileCount || count(writable,"tiles")!=plan.tileCount) throw new IOException("Kartenpaket ist unvollständig. Download fortsetzen.");
            for(Tile t:plan.tiles) if(verified(writable,t)==null) throw new IOException("Eine Kartenkachel ist beschädigt. Download fortsetzen.");
            put(writable,"magnetrute_downloaded_at",Long.toString(System.currentTimeMillis()));put(writable,"magnetrute_ready","true");
        } catch(RuntimeException e) { throw new IOException("Kartenpaket konnte nicht gespeichert werden: "+e.getMessage(),e); }
        finally { if(writable!=null) writable.close(); }
    }

    private static void progress(SQLiteDatabase db,int done,long bytes,Plan plan,Progress progress) {
        put(db,"magnetrute_done",Integer.toString(done));put(db,"magnetrute_bytes",Long.toString(bytes));
        if(progress!=null) progress.update(done,plan.tileCount,bytes);
    }

    /** Full integrity check used for startup/import: incomplete packages cannot become offline-ready. */
    public static boolean validate(File file) {
        if(file==null || !file.isFile() || file.length()>MAX_BYTES+16L*1024*1024) return false;
        try(OfflineTiles local=new OfflineTiles(file)) {
            JSONObject p=local.metadata();
            if(!p.optBoolean("ready")) return false;
            String source=p.getString("source");sourceCheck(source);
            JSONArray b=p.getJSONArray("bounds");int min=p.getInt("minZoom"),max=p.getInt("maxZoom");
            if(min<0 || max>20 || min>max || b.length()!=4) return false;
            double west=b.getDouble(0),south=b.getDouble(1),east=b.getDouble(2),north=b.getDouble(3);
            if(!GeoMath.valid(south,west) || !GeoMath.valid(north,east) || west>=east || south>=north) return false;
            List<Tile> planned=enumerate(west,south,east,north,min,max);
            if(planned.size()!=p.getInt("total") || count(local.db,"tiles")!=planned.size()) return false;
            long bytes=0;
            for(Tile t:planned) { byte[] data=verified(local.db,t);if(data==null) return false;bytes+=data.length; }
            return bytes<=MAX_BYTES && bytes==p.getLong("bytes");
        } catch(Exception e) { return false; }
    }

    public static String previewUrl(String source,int z,int x,int y) {
        sourceCheck(source);
        if(z<0 || z>20 || x<0 || y<0 || x>=(1<<z) || y>=(1<<z)) throw new IllegalArgumentException("Ungültige Kartenkachel.");
        if(GIS_STMK.equals(source)) {
            double span=2*Math.PI*GeoMath.EARTH_RADIUS_METERS/(1<<z),origin=Math.PI*GeoMath.EARTH_RADIUS_METERS;
            double left=-origin+x*span,right=left+span,top=origin-y*span,bottom=top-span;
            return String.format(Locale.US,"https://gis.stmk.gv.at/image/rest/services/OGD_DOP/Orthofotos_akt/ImageServer/exportImage?f=image&bbox=%.8f,%.8f,%.8f,%.8f&bboxSR=3857&imageSR=3857&size=256,256&format=jpg&interpolation=RSP_BilinearInterpolation",left,bottom,right,top);
        }
        return "https://mapsneu.wien.gv.at/basemap/"+(BASEMAP_ORTHO.equals(source)?"bmaporthofoto30cm":"geolandbasemap")
            +"/normal/google3857/"+z+"/"+y+"/"+x+(BASEMAP_ORTHO.equals(source)?".jpeg":".png");
    }
    public static String attribution(String source) {
        sourceCheck(source);
        return GIS_STMK.equals(source)?"Datenquelle: CC-BY-4.0: Land Steiermark - data.steiermark.gv.at":"Datenquelle: basemap.at";
    }
    public static String licenseUrl(String source) {
        sourceCheck(source);return GIS_STMK.equals(source)?"https://data.steiermark.at/cms/beitrag/12867727/97428847/":"https://basemap.at/orthofoto-offline/";
    }
    private static void sourceCheck(String source) {
        if(!BASEMAP_ORTHO.equals(source) && !BASEMAP_STANDARD.equals(source) && !GIS_STMK.equals(source)) throw new IllegalArgumentException("Unbekannte Kartenquelle.");
    }
    private static byte[] fetch(String address) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","MagnetRute/0.4.0 bounded offline map downloader");
        try {
            if(connection.getResponseCode()!=200) throw new IOException("Kartenquelle antwortet mit HTTP "+connection.getResponseCode()+". Download kann fortgesetzt werden.");
            String type=connection.getContentType();
            if(type==null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) throw new IOException("Kartenquelle lieferte kein Bild.");
            if(connection.getContentLengthLong()>MAX_TILE_BYTES) throw new IOException("Kartenkachel überschreitet 1 MiB.");
            try(InputStream in=connection.getInputStream()) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) { if(out.size()+n>MAX_TILE_BYTES) throw new IOException("Kartenkachel überschreitet 1 MiB.");out.write(buffer,0,n); }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
    private static boolean validImage(byte[] data) {
        if(data==null || data.length<20 || data.length>MAX_TILE_BYTES) return false;
        boolean jpeg=(data[0]&255)==255 && (data[1]&255)==216 && (data[data.length-2]&255)==255 && (data[data.length-1]&255)==217;
        boolean png=data.length>=8 && data[0]==(byte)137 && data[1]==80 && data[2]==78 && data[3]==71 && data[4]==13 && data[5]==10 && data[6]==26 && data[7]==10;
        if(!jpeg && !png) return false;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if(bounds.outWidth!=256 || bounds.outHeight!=256) return false;
        Bitmap decoded=BitmapFactory.decodeByteArray(data,0,data.length);
        if(decoded==null) return false;decoded.recycle();return true;
    }
    private static byte[] verified(SQLiteDatabase db,Tile t) {
        try(Cursor c=db.rawQuery("SELECT t.tile_data,c.sha256 FROM tiles t JOIN tile_checksums c ON t.zoom_level=c.zoom_level AND t.tile_column=c.tile_column AND t.tile_row=c.tile_row WHERE t.zoom_level=? AND t.tile_column=? AND t.tile_row=?",new String[]{Integer.toString(t.z),Integer.toString(t.x),Integer.toString(t.row())})) {
            if(!c.moveToFirst()) return null;byte[] data=c.getBlob(0);
            return c.getString(1).equals(sha256(data)) && validImage(data)?data:null;
        }
    }
    private static JSONObject readPlan(SQLiteDatabase db) throws IOException {
        String plan=value(db,PLAN_KEY);if(plan==null) throw new IOException("Offline-Kartenmetadaten fehlen.");
        try { JSONObject p=new JSONObject(plan);if(!SCHEMA.equals(p.optString("schema"))) throw new IOException("Unbekanntes Kartenformat.");return p; }
        catch(JSONException e) { throw new IOException("Offline-Kartenmetadaten sind beschädigt.",e); }
    }
    private static boolean emptyRegionalImage(byte[] data) {
        Bitmap image=BitmapFactory.decodeByteArray(data,0,data.length);if(image==null)return true;
        int min=255,max=0;boolean transparent=true;
        for(int y=0;y<256;y+=8)for(int x=0;x<256;x+=8){int p=image.getPixel(x,y);if((p>>>24)!=0)transparent=false;
            int r=(p>>16)&255,g=(p>>8)&255,b=p&255;min=Math.min(min,Math.min(r,Math.min(g,b)));max=Math.max(max,Math.max(r,Math.max(g,b)));}
        image.recycle();return transparent || max-min<=2&&(min>=250||max<=2);
    }
    private static String valueOr(SQLiteDatabase db,String name,String fallback) { String v=value(db,name);return v==null?fallback:v; }
    private static String value(SQLiteDatabase db,String name) {
        try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE name=?",new String[]{name})) { return c.moveToFirst()?c.getString(0):null; }
    }
    private static long count(SQLiteDatabase db,String table) {
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table,null)) { c.moveToFirst();return c.getLong(0); }
    }
    private static void put(SQLiteDatabase db,String name,String value) {
        ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);
        db.insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);char[] out=new char[64];
            final char[] hex="0123456789abcdef".toCharArray();
            for(int i=0;i<digest.length;i++) { out[i*2]=hex[(digest[i]&255)>>>4];out[i*2+1]=hex[digest[i]&15]; }
            return new String(out);
        }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
