package at.andi.magnetrute;

import static org.junit.Assert.*;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OfflineTilesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    private static OfflineTiles.Plan plan() throws Exception {
        return OfflineTiles.estimate(new JSONArray("[[14.031,47.145],[14.034,47.145],[14.034,47.147],[14.031,47.147],[14.031,47.145]]"),20,17,18,OfflineTiles.BASEMAP_ORTHO);
    }
    private static byte[] tile() {
        Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(30,180,70));
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));b.recycle();return out.toByteArray();
    }
    private File target(String name) throws IOException { return new File(temporary.getRoot(),name); }
    private static int[] firstTile(File file) {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                Cursor c=db.rawQuery("SELECT zoom_level,tile_column,tile_row FROM tiles ORDER BY zoom_level,tile_column,tile_row LIMIT 1",null)) {
            assertTrue(c.moveToFirst());int z=c.getInt(0);return new int[]{z,c.getInt(1),(1<<z)-1-c.getInt(2)};
        }
    }

    @Test public void boundedPlanIncludesBufferAndEverySelectedZoom() throws Exception {
        OfflineTiles.Plan p=plan();
        assertTrue(p.tileCount>2);assertTrue(p.tileCount<=4000);assertEquals(17,p.minZoom);assertEquals(18,p.maxZoom);
        assertTrue(p.west<14.031);assertTrue(p.east>14.034);assertTrue(p.south<47.145);assertTrue(p.north>47.147);
        assertEquals("Datenquelle: basemap.at",p.attribution);assertTrue(p.resolutionMeters>0);
        assertEquals(4,p.json().getJSONArray("bounds").length());
        assertThrows(IllegalArgumentException.class,()->OfflineTiles.estimate(new JSONArray("[[-180,-80],[180,-80],[180,80],[-180,80],[-180,-80]]"),20,0,19,OfflineTiles.BASEMAP_ORTHO));
        assertThrows(IllegalArgumentException.class,()->OfflineTiles.estimate(new JSONArray("[[14,47],[15,47],[15,48],[14,48]]"),20,17,19,OfflineTiles.BASEMAP_ORTHO));
        assertThrows(IllegalArgumentException.class,()->OfflineTiles.estimate(new JSONArray("[[14,47],[14,47],[14,47]]"),20,17,18,OfflineTiles.BASEMAP_ORTHO));
        assertThrows(IllegalArgumentException.class,()->OfflineTiles.estimate(new JSONArray("[[14,47],[15,47],[15,48]]"),-1,17,18,OfflineTiles.BASEMAP_ORTHO));
    }

    @Test public void downloadedTilesArePortableTmsAndReadableWithoutTransport() throws Exception {
        File file=target("ready.mbtiles");OfflineTiles.Plan p=plan();AtomicInteger fetches=new AtomicInteger();
        OfflineTiles.download(file,p,null,()->false,url->{fetches.incrementAndGet();return tile();});
        assertEquals(p.tileCount,fetches.get());assertTrue(OfflineTiles.validate(file));int[] t=firstTile(file);
        try(OfflineTiles local=new OfflineTiles(file)) {
            JSONObject m=local.metadata();assertTrue(m.getBoolean("ready"));assertEquals(p.tileCount,m.getInt("done"));
            assertEquals(p.tileCount,m.getInt("total"));assertTrue(m.getLong("bytes")>0);
            Bitmap image=local.get(t[0],t[1],t[2]);assertNotNull(image);assertEquals(256,image.getWidth());
            assertEquals(Color.rgb(30,180,70),image.getPixel(128,128));image.recycle();
            assertNull(local.get(19,0,0));assertNull(local.get(-1,0,0));
        }
        assertEquals(p.tileCount,fetches.get());
    }

    @Test public void pausePersistsProgressAndResumeFetchesOnlyMissingTiles() throws Exception {
        File file=target("pause.mbtiles");OfflineTiles.Plan p=plan();AtomicBoolean pause=new AtomicBoolean();AtomicInteger fetches=new AtomicInteger();
        OfflineTiles.download(file,p,(done,total,bytes)->{if(done>=1) pause.set(true);},pause::get,url->{fetches.incrementAndGet();return tile();});
        assertEquals(1,fetches.get());assertFalse(OfflineTiles.validate(file));
        try(OfflineTiles local=new OfflineTiles(file)) {assertFalse(local.metadata().getBoolean("ready"));assertEquals(1,local.metadata().getInt("done"));}
        OfflineTiles.download(file,p,null,()->false,url->{fetches.incrementAndGet();return tile();});
        assertEquals(p.tileCount,fetches.get());assertTrue(OfflineTiles.validate(file));
        // Reopening an already complete package while pause is requested must keep it ready.
        OfflineTiles.download(file,p,null,()->true,url->{throw new AssertionError("Complete package must not fetch");});
        assertTrue(OfflineTiles.validate(file));
    }

    @Test public void interruptionPreservesVerifiedTilesAndCanResume() throws Exception {
        File file=target("interrupted.mbtiles");OfflineTiles.Plan p=plan();AtomicInteger calls=new AtomicInteger();
        assertThrows(IOException.class,()->OfflineTiles.download(file,p,null,()->false,url->{if(calls.incrementAndGet()==2) throw new IOException("connection interrupted");return tile();}));
        try(OfflineTiles local=new OfflineTiles(file)) {assertEquals(1,local.metadata().getInt("done"));assertFalse(local.metadata().getBoolean("ready"));}
        AtomicInteger resumed=new AtomicInteger();OfflineTiles.download(file,p,null,()->false,url->{resumed.incrementAndGet();return tile();});
        assertEquals(p.tileCount-1,resumed.get());assertTrue(OfflineTiles.validate(file));
    }

    @Test public void corruptTileIsNeverShownAndIsRepairedOnResume() throws Exception {
        File file=target("corrupt.mbtiles");OfflineTiles.Plan p=plan();OfflineTiles.download(file,p,null,()->false,url->tile());
        int[] t=firstTile(file);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            ContentValues v=new ContentValues();v.put("tile_data",new byte[]{1,2,3});
            assertEquals(1,db.update("tiles",v,"zoom_level=? AND tile_column=? AND tile_row=?",new String[]{""+t[0],""+t[1],""+((1<<t[0])-1-t[2])}));
        }
        assertFalse(OfflineTiles.validate(file));try(OfflineTiles local=new OfflineTiles(file)) {assertNull(local.get(t[0],t[1],t[2]));}
        AtomicInteger fetched=new AtomicInteger();OfflineTiles.download(file,p,null,()->false,url->{fetched.incrementAndGet();return tile();});
        assertEquals(1,fetched.get());assertTrue(OfflineTiles.validate(file));
    }

    @Test public void badPayloadAndWrongPlanNeverBecomeReadyOrOverwriteExistingMap() throws Exception {
        OfflineTiles.Plan p=plan();File invalid=target("invalid.mbtiles");
        assertThrows(IOException.class,()->OfflineTiles.download(invalid,p,null,()->false,url->"<html>not a tile</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(OfflineTiles.validate(invalid));
        File file=target("preserved.mbtiles");OfflineTiles.download(file,p,null,()->false,url->tile());
        OfflineTiles.Plan other=OfflineTiles.estimate(new JSONArray("[[14.031,47.145],[14.034,47.145],[14.034,47.147],[14.031,47.147]]"),20,17,18,OfflineTiles.BASEMAP_STANDARD);
        assertThrows(IOException.class,()->OfflineTiles.download(file,other,null,()->false,url->{throw new AssertionError("Wrong plan must be rejected first");}));
        assertTrue(OfflineTiles.validate(file));try(OfflineTiles local=new OfflineTiles(file)) {assertEquals(OfflineTiles.BASEMAP_ORTHO,local.metadata().getString("source"));}
    }

    @Test public void sourceTemplatesKeepXyzOrderAndGisProjectionExplicit() {
        assertEquals("https://mapsneu.wien.gv.at/basemap/bmaporthofoto30cm/normal/google3857/18/92047/141290.jpeg",OfflineTiles.previewUrl(OfflineTiles.BASEMAP_ORTHO,18,141290,92047));
        assertTrue(OfflineTiles.previewUrl(OfflineTiles.BASEMAP_STANDARD,18,141290,92047).endsWith("/18/92047/141290.png"));
        String gis=OfflineTiles.previewUrl(OfflineTiles.GIS_STMK,18,141290,92047);
        assertTrue(gis.contains("bboxSR=3857&imageSR=3857"));assertTrue(gis.contains("size=256,256"));assertTrue(gis.contains("format=jpg"));
        assertThrows(IllegalArgumentException.class,()->OfflineTiles.previewUrl("unknown",18,1,1));
    }
    @Test public void exhaustedStorageStopsBeforeNetworkAndNeverClaimsReady() throws Exception {
        File file=new File(temporary.getRoot(),"low-space.mbtiles") {
            @Override public File getAbsoluteFile() { return this; }
            @Override public File getParentFile() { return new File(temporary.getRoot().getPath()) {
                @Override public long getUsableSpace() { return 0; }
            }; }
        };
        AtomicInteger requests=new AtomicInteger();
        IOException failure=assertThrows(IOException.class,()->OfflineTiles.download(file,plan(),null,()->false,url->{requests.incrementAndGet();return tile();}));
        assertTrue(failure.getMessage().contains("Speicher"));assertEquals(0,requests.get());assertFalse(OfflineTiles.validate(file));
    }
    @Test public void blankRegionalNoDataImageIsNotAnOfflinePackage() throws Exception {
        Bitmap white=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);white.eraseColor(Color.WHITE);
        ByteArrayOutputStream image=new ByteArrayOutputStream();white.compress(Bitmap.CompressFormat.JPEG,90,image);white.recycle();
        OfflineTiles.Plan p=OfflineTiles.estimate(new JSONArray("[[14.031,47.145],[14.034,47.145],[14.034,47.147],[14.031,47.147],[14.031,47.145]]"),0,17,17,OfflineTiles.GIS_STMK);
        File file=target("regional-no-data.mbtiles");
        IOException failure=assertThrows(IOException.class,()->OfflineTiles.download(file,p,null,()->false,url->image.toByteArray()));
        assertTrue(failure.getMessage().contains("NoData"));assertFalse(OfflineTiles.validate(file));
    }
}
