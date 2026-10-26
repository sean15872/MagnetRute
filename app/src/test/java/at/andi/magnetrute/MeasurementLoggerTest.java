package at.andi.magnetrute;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

/** Regression coverage for the pre-scanner measurement, GPS, diagnostics and export contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class MeasurementLoggerTest {
    private Context context;
    private MeasurementLogger logger;
    private Locale oldLocale;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("sessions", Context.MODE_PRIVATE).edit().clear().commit();
        remove(new File(context.getFilesDir(), "sessions"));
        logger = new MeasurementLogger(context);
        oldLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
    }

    @After public void tearDown() {
        logger.stop();
        Locale.setDefault(oldLocale);
    }

    private static void remove(File f) throws Exception {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (f.exists() && !f.delete()) throw new Exception("Cannot clear " + f);
    }

    private void sample(long timestamp) {
        logger.sample(timestamp, 3, 4, 12,
                SensorMath.components(3, 4, 12, 0, 0, 9.81, true),
                new float[] {0, 0, 9.81f}, 12.5, 10, 2.5, 3,
                0, 3, 0.125, 0.5, new float[] {4, 5, 13, 1, 1, 1}, timestamp - 1);
    }

    private static Location fix(long timestamp) {
        Location location = new Location("gps");
        location.setLatitude(48.2082);
        location.setLongitude(16.3738);
        location.setAccuracy(2.5f);
        location.setTime(1700000000000L);
        location.setElapsedRealtimeNanos(timestamp);
        return location;
    }

    private List<String[]> rows(String filename) throws Exception {
        List<String[]> result = new ArrayList<>();
        for (String line : Files.readAllLines(new File(logger.getLastFolder(), filename).toPath(),
                StandardCharsets.UTF_8)) result.add(csv(line));
        return result;
    }

    // Parse quoted fields as well: events can contain commas and escaped quotation marks.
    private static String[] csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(c);
        }
        assertFalse("Unbalanced CSV quotation", quoted);
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private static String field(List<String[]> rows, int row, String name) {
        int index = Arrays.asList(rows.get(0)).indexOf(name);
        assertTrue("Column " + name + " exists", index >= 0);
        assertEquals("CSV column count", rows.get(0).length, rows.get(row).length);
        return rows.get(row)[index];
    }

    @Test public void magneticRawDerivedBiasAndDiagnosticValuesSurviveExport() throws Exception {
        assertThrows(java.io.IOException.class, () -> logger.export());
        logger.start(0, 3, "Regression sensor");
        assertTrue(logger.isRecording());
        sample(123456789L);
        logger.event("MARKIERUNG", "Kamin, \"Nord\"\n2 m");
        logger.stop();
        assertFalse(logger.isRecording());
        assertEquals(1, logger.getCount());
        sample(123456790L); // stopped sessions must not acquire additional measurements
        assertEquals(1, logger.getCount());
        List<String[]> values = rows("messwerte.csv");
        assertEquals(2, values.size());
        assertEquals("123456789", field(values, 1, "sensor_timestamp_ns"));
        assertEquals("3.00000", field(values, 1, "x_uT"));
        assertEquals("4.00000", field(values, 1, "y_uT"));
        assertEquals("12.00000", field(values, 1, "z_uT"));
        assertEquals("13.00000", field(values, 1, "b_gesamt_uT"));
        assertEquals("12.00000", field(values, 1, "b_vertikal_uT"));
        assertEquals("5.00000", field(values, 1, "b_horizontal_uT"));
        assertEquals("2.50000", field(values, 1, "abweichung_uT"));
        assertEquals("50.00000", field(values, 1, "rutenausschlag_prozent"));
        assertEquals("4.00000", field(values, 1, "uncal_x_uT"));
        assertEquals("1.00000", field(values, 1, "bias_z_uT"));
        assertEquals("123456788", field(values, 1, "uncal_timestamp_ns"));
        assertEquals("", field(values, 1, "gps_lat"));
        List<String[]> events = rows("ereignisse.csv");
        assertEquals("MARKIERUNG", field(events, 2, "ereignis"));
        assertEquals("Kamin, \"Nord\" 2 m", field(events, 2, "details"));
        Map<String, String> archive = zip(logger.export());
        assertEquals(new java.util.HashSet<>(Arrays.asList("messwerte.csv", "standorte.csv",
                "ereignisse.csv", "kartenpunkte.geojson", "diagnose.txt")), archive.keySet());
        assertTrue(archive.get("messwerte.csv").contains("123456789"));
        assertTrue(archive.get("diagnose.txt").contains("Regression sensor"));
        assertTrue(archive.get("diagnose.txt").contains("CSV-Zeilen (ohne Header): 1"));
        assertTrue(archive.get("diagnose.txt").contains("Delta max (uT): 2.50000"));
        assertEquals(0, new JSONObject(archive.get("kartenpunkte.geojson")).getJSONArray("features").length());
    }

    @Test public void gpsRequiresOptInAndRejectsStaleFutureAndDisabledFixes() throws Exception {
        logger.start(0, 3, "GPS sensor");
        long stamp = 10_000_000_000L;
        logger.onLocation(fix(stamp)); // ignored before opt-in
        sample(stamp);
        logger.setLocationTrackingEnabled(true);
        logger.onLocation(fix(stamp));
        sample(stamp + 5_000_000_000L); // inclusive five-second boundary
        sample(stamp + 5_000_000_001L); // too old
        sample(stamp - 1); // a fix from the future must not attach to an earlier sample
        logger.setLocationTrackingEnabled(false);
        logger.onLocation(fix(stamp)); // still ignored after disabling
        sample(stamp);
        logger.stop();
        List<String[]> values = rows("messwerte.csv");
        assertEquals(6, values.size());
        assertEquals("48.2082000", field(values, 2, "gps_lat"));
        assertEquals("16.3738000", field(values, 2, "gps_lon"));
        assertEquals("2.50000", field(values, 2, "gps_accuracy_m"));
        assertEquals("5000", field(values, 2, "gps_age_ms"));
        assertEquals("1700000000000", field(values, 2, "gps_fix_unix_ms"));
        for (int row : new int[] {1, 3, 4, 5}) {
            for (int column = 27; column < values.get(0).length; column++) {
                assertEquals("GPS must be absent for row " + row, "", values.get(row)[column]);
            }
        }
        assertEquals(2, rows("standorte.csv").size()); // only the explicitly enabled fix
    }

    @Test public void activeExportFlushesAndKeepsGpsGeoJsonAndSessionReadable() throws Exception {
        logger.start(0, 3, "Live sensor");
        logger.setLocationTrackingEnabled(true);
        long now = SystemClock.elapsedRealtimeNanos();
        logger.onLocation(fix(now));
        sample(now);
        String details = "Tür \"Ost\"\nSchritt\\1";
        logger.event("MARKIERUNG", details);
        Map<String, String> during = zip(logger.export());
        assertTrue(logger.isRecording());
        assertTrue(during.get("messwerte.csv").contains("48.2082000"));
        JSONObject geo = new JSONObject(during.get("kartenpunkte.geojson"));
        assertEquals("FeatureCollection", geo.getString("type"));
        JSONArray features = geo.getJSONArray("features");
        assertEquals(1, features.length());
        JSONArray coordinates = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
        assertEquals(16.3738, coordinates.getDouble(0), 0.0000001);
        assertEquals(48.2082, coordinates.getDouble(1), 0.0000001);
        assertEquals(details, features.getJSONObject(0).getJSONObject("properties").getString("details"));
        assertFalse(during.get("diagnose.txt").contains("48.2082"));
        assertFalse(during.get("diagnose.txt").contains("16.3738"));
        sample(now + 1_000_000L);
        logger.stop();
        MeasurementLogger recreated = new MeasurementLogger(context);
        assertEquals(logger.getLastFolder(), recreated.getLastFolder());
        assertTrue(zip(recreated.export()).get("diagnose.txt").contains("CSV-Zeilen (ohne Header): 2"));
    }

    @Test public void exportProviderOnlyExposesTheExactZipReadOnly() throws Exception {
        logger.start(0, 3, "Provider sensor");
        sample(123L);
        logger.stop();
        File exported = logger.export();
        LogExportProvider provider = new LogExportProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = LogExportProvider.AUTHORITY;
        info.exported = false;
        info.grantUriPermissions = true;
        provider.attachInfo(context, info);
        assertEquals("application/zip", provider.getType(LogExportProvider.URI));
        try (Cursor cursor = provider.query(LogExportProvider.URI, null, null, null, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            assertEquals(exported.getName(), cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(exported.length(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        try (ParcelFileDescriptor descriptor = provider.openFile(LogExportProvider.URI, "r");
             FileInputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
            assertEquals('P', in.read());
            assertEquals('K', in.read());
        }
        for (String mode : new String[] {"w", "rw", "rwt", "wa"}) {
            assertThrows(java.io.FileNotFoundException.class, () -> provider.openFile(LogExportProvider.URI, mode));
        }
        for (String path : new String[] {"/../files/sessions/private.csv", "/MagnetRute_Log.zip/../private",
                "/%2e%2e%2ffiles%2fprivate", "/private.zip"}) {
            Uri uri = Uri.parse("content://" + LogExportProvider.AUTHORITY + path);
            assertNull(provider.getType(uri));
            assertNull(provider.query(uri, null, null, null, null));
            assertThrows(java.io.FileNotFoundException.class, () -> provider.openFile(uri, "r"));
        }
        context.getSharedPreferences("sessions", Context.MODE_PRIVATE).edit().putString("last", "../private").commit();
        assertNull(logger.getLastFolder());
    }

    private static Map<String, String> zip(File file) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull("Duplicate ZIP entry", entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return entries;
    }
}
