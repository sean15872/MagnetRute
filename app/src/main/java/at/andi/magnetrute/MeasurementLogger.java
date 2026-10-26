package at.andi.magnetrute;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.location.Location;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileInputStream;

/** Persistentes CSV-Protokoll (10 Hz), optionale Standortspuren und ZIP-Export. */
public final class MeasurementLogger {
    private final Context context;
    private File sessionFolder;
    private BufferedWriter valuesOut, eventsOut, locationOut, geoFeaturesOut;
    private Location lastLocation;
    private boolean locationTrackingEnabled = false;
    private long locations = 0, geoPoints = 0;
    private static final long MAX_FIX_AGE_NS = 5_000_000_000L;

    private long sessionStartMillis, sessionStartElapsed;
    private long samples;
    private int flushCounter;
    private double minDelta = Double.POSITIVE_INFINITY;
    private double maxDelta = Double.NEGATIVE_INFINITY;
    private double maxAbsDelta = 0;
    private String sensorDescription = "Unbekannt";

    public MeasurementLogger(Context context) { this.context = context.getApplicationContext(); }
    public boolean isRecording() { return valuesOut != null; }
    public int getCount() { return (int) Math.min(Integer.MAX_VALUE, samples); }
    public File getLastFolder() {
        String name = context.getSharedPreferences("sessions", Context.MODE_PRIVATE).getString("last", "");
        if (name.length() == 0 || name.contains("/") || name.contains("..")) return null;
        File folder = new File(new File(context.getFilesDir(), "sessions"), name);
        return folder.isDirectory() ? folder : null;
    }
    public void setSensorDescription(String desc) { sensorDescription = desc; }
    private static String time() {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        return df.format(new Date());
    }
    private static String csv(Object v) {
        String s = String.valueOf(v == null ? "" : v);
        return "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
    private static String n(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.5f", v) : "";
    }
    private static String coord(double v) { return String.format(Locale.US,"%.7f",v); }
    private static String json(String value) {
        if(value == null) return "";
        StringBuilder out=new StringBuilder();
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (c=='\\') out.append("\\\\");
            else if (c=='\"') out.append("\\\"");
            else if (c=='\n') out.append("\\n");
            else if (c=='\r') out.append("\\r");
            else if (c=='\t') out.append("\\t");
            else if (c<32) out.append(String.format(Locale.US,"\\u%04x", (int)c));
            else out.append(c);
        }
        return out.toString();
    }
    /** Neue Standortdaten gelten nur fuer die Messung, wenn der Benutzer das explizit aktiviert hat. */
    public void setLocationTrackingEnabled(boolean enabled) {
        if (enabled==locationTrackingEnabled) return;
        locationTrackingEnabled=enabled;
        if (!enabled) lastLocation=null; // Keine alten Koordinaten uebernehmen.
        event(enabled?"GPS_AN":"GPS_AUS", "Standortaufzeichnung="+(enabled?"ein":"aus"));
    }
    private Location freshLocation(long timestampNs) {
        if(!locationTrackingEnabled || lastLocation==null || timestampNs<=0) return null;
        long age=timestampNs-lastLocation.getElapsedRealtimeNanos();
        return age>=0 && age<=MAX_FIX_AGE_NS ? lastLocation : null;
    }
    public void onLocation(Location location) {
        if(!locationTrackingEnabled || location==null) return;
        lastLocation=new Location(location);
        if(!isRecording() || locationOut==null) return;
        try {
            locationOut.write(csv(time())+","+(SystemClock.elapsedRealtime()-sessionStartElapsed)
                +","+location.getTime()+","+location.getElapsedRealtimeNanos()
                +","+coord(location.getLatitude())+","+coord(location.getLongitude())
                +","+(location.hasAccuracy()?n(location.getAccuracy()):"")
                +","+(location.hasAltitude()?n(location.getAltitude()):"")
                +","+(location.hasSpeed()?n(location.getSpeed()):"")
                +","+(location.hasBearing()?n(location.getBearing()):"")
                +","+csv(location.getProvider())+","+location.isFromMockProvider()+"\n");
            locations++;
            locationOut.flush();
        } catch(IOException e) { android.util.Log.e("MagnetRute", "GPS CSV error", e); }
    }

    public void start(int mode, double sensitivity, String sensorInfo) throws IOException {
        if (isRecording()) return;
        File root = new File(context.getFilesDir(), "sessions");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create sessions folder");
        String tag = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        sessionFolder = new File(root, "session_" + tag);
        if (!sessionFolder.mkdirs()) throw new IOException("Cannot create session folder");
        sessionStartMillis = System.currentTimeMillis();
        sessionStartElapsed = SystemClock.elapsedRealtime();
        samples = 0;
        locations = 0;
        geoPoints = 0;
        flushCounter = 0;
        lastLocation = null;
        minDelta = Double.POSITIVE_INFINITY;
        maxDelta = Double.NEGATIVE_INFINITY;
        maxAbsDelta = 0;
        sensorDescription = sensorInfo;
        valuesOut = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(new File(sessionFolder, "messwerte.csv")), StandardCharsets.UTF_8));
        eventsOut = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(new File(sessionFolder, "ereignisse.csv")), StandardCharsets.UTF_8));
        locationOut = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(new File(sessionFolder, "standorte.csv")), StandardCharsets.UTF_8));
        geoFeaturesOut = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(new File(sessionFolder, "kartenpunkte.ndjson")), StandardCharsets.UTF_8));
        valuesOut.write("uhrzeit_iso,seit_start_ms,sensor_timestamp_ns,x_uT,y_uT,z_uT," +
            "b_gesamt_uT,b_vertikal_uT,b_horizontal_uT,g_x_ms2,g_y_ms2,g_z_ms2," +
            "gefiltert_uT,referenz_uT,abweichung_uT,empfindlichkeit_uT,modus," +
            "genauigkeit,bewegung_ms2,rutenausschlag_prozent," +
            "uncal_x_uT,uncal_y_uT,uncal_z_uT,bias_x_uT,bias_y_uT,bias_z_uT,uncal_timestamp_ns," +
            "gps_lat,gps_lon,gps_accuracy_m,gps_altitude_m,gps_speed_ms,gps_bearing_deg," +
            "gps_provider,gps_fix_unix_ms,gps_fix_elapsed_ns,gps_age_ms\n");
        eventsOut.write("uhrzeit_iso,seit_start_ms,ereignis,details,gps_lat,gps_lon,gps_accuracy_m,gps_fix_age_ms\n");
        locationOut.write("erfasst_iso,seit_start_ms,fix_unix_ms,fix_elapsed_ns,lat,lon,accuracy_m," +
            "altitude_m,speed_ms,bearing_deg,provider,is_mock\n");
        context.getSharedPreferences("sessions", Context.MODE_PRIVATE).edit()
            .putString("last", sessionFolder.getName()).apply();
        event("START", "mode=" + mode + "; sensitivity_uT=" + sensitivity + "; " + sensorInfo);
    }
    public void event(String name, String details) {
        if (!isRecording() || eventsOut == null) return;
        try {
            long stamp=SystemClock.elapsedRealtimeNanos();
            Location loc=freshLocation(stamp);
            long age=loc==null?0:Math.max(0,(stamp-loc.getElapsedRealtimeNanos())/1_000_000L);
            String iso=time();
            eventsOut.write(csv(iso) + "," + (SystemClock.elapsedRealtime() - sessionStartElapsed)
                + "," + csv(name) + "," + csv(details)
                + ","+(loc!=null?coord(loc.getLatitude()):"")
                + ","+(loc!=null?coord(loc.getLongitude()):"")
                + ","+(loc!=null&&loc.hasAccuracy()?n(loc.getAccuracy()):"")
                + ","+(loc!=null?String.valueOf(age):"")+"\n");
            eventsOut.flush();
            if(loc!=null && geoFeaturesOut!=null &&
                (name.equals("MARKIERUNG") || name.equals("AUSSCHLAG_BEGIN") || name.equals("AUSSCHLAG_ENDE"))) {
                String feature="{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":["
                    +coord(loc.getLongitude())+","+coord(loc.getLatitude())+"]},\"properties\":{\"ereignis\":\""
                    +json(name)+"\",\"details\":\""+json(details)+"\",\"uhrzeit\":\""
                    +json(iso)+"\",\"genauigkeit_m\":"
                    +(loc.hasAccuracy()?n(loc.getAccuracy()):"null")+",\"fix_alter_ms\":"+age+"}}";
                geoFeaturesOut.write(feature+"\n");
                geoFeaturesOut.flush();
                geoPoints++;
            }
        } catch (IOException e) { android.util.Log.e("MagnetRute", "Event log error", e); }
    }
    public void sample(long sensorTimestamp, float mx, float my, float mz,
                       SensorMath.Components c, float[] g, double filtered, double baseline,
                       double delta, double sensitivity, int mode, int accuracy,
                       double motion, double rodProgress, float[] uncal, long uncalTimestamp) {
        if (!isRecording()) return;
        try {
            long nowMs = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime() - sessionStartElapsed;
            // Sensor-event timestamp is monotonic nanoseconds, kept unmodified.
            String line = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(nowMs))
                + "," + elapsed + "," + sensorTimestamp
                + "," + n(mx) + "," + n(my) + "," + n(mz)
                + "," + n(c.total) + "," + n(c.vertical) + "," + n(c.horizontal)
                + "," + n(g[0]) + "," + n(g[1]) + "," + n(g[2])
                + "," + n(filtered) + "," + n(baseline) + "," + n(delta)
                + "," + n(sensitivity) + "," + mode + "," + accuracy
                + "," + n(motion) + "," + n(rodProgress * 100.0)
                + "," + n(uncal[0]) + "," + n(uncal[1]) + "," + n(uncal[2])
                + "," + n(uncal[3]) + "," + n(uncal[4]) + "," + n(uncal[5])
                + "," + (uncalTimestamp > 0 ? String.valueOf(uncalTimestamp) : "");
            Location loc=freshLocation(sensorTimestamp);
            if (loc!=null) {
                long ageNs=sensorTimestamp-loc.getElapsedRealtimeNanos();
                line+=","+coord(loc.getLatitude())+","+coord(loc.getLongitude())
                    +","+(loc.hasAccuracy()?n(loc.getAccuracy()):"")
                    +","+(loc.hasAltitude()?n(loc.getAltitude()):"")
                    +","+(loc.hasSpeed()?n(loc.getSpeed()):"")
                    +","+(loc.hasBearing()?n(loc.getBearing()):"")
                    +","+csv(loc.getProvider())+","+loc.getTime()
                    +","+loc.getElapsedRealtimeNanos()+","+(ageNs/1_000_000L);
            } else line+=",,,,,,,,,,";
            line+="\n";
            valuesOut.write(line);
            samples++;
            if (Double.isFinite(delta)) {
                minDelta = Math.min(minDelta, delta);
                maxDelta = Math.max(maxDelta, delta);
                maxAbsDelta = Math.max(maxAbsDelta, Math.abs(delta));
            }
            if (++flushCounter >= 10) { valuesOut.flush(); flushCounter = 0; }
        } catch (IOException e) { android.util.Log.e("MagnetRute", "CSV log error", e); }
    }
    public void flush() {
        try { if (valuesOut != null) valuesOut.flush(); if (eventsOut != null) eventsOut.flush();
              if (locationOut!=null) locationOut.flush(); if (geoFeaturesOut!=null) geoFeaturesOut.flush(); }
        catch (IOException e) { android.util.Log.e("MagnetRute", "Flush error", e); }
    }
    public void stop() {
        if (!isRecording()) return;
        event("STOP", "samples=" + samples);
        flush();
        try { valuesOut.close(); } catch (IOException ignored) { }
        try { eventsOut.close(); } catch (IOException ignored) { }
        try { if(locationOut!=null) locationOut.close(); } catch(IOException ignored) { }
        try { if(geoFeaturesOut!=null) geoFeaturesOut.close(); } catch(IOException ignored) { }
        valuesOut = null;
        eventsOut = null;
        locationOut = null;
        geoFeaturesOut = null;
        writeGeoJson();
        writeSummary();
    }
    private void writeSummary() {
        if (sessionFolder == null) return;
        File file = new File(sessionFolder, "diagnose.txt");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write("MagnetRute v0.3.0 – Diagnosebericht\n");
            w.write("Erstellt: " + time() + "\n");
            w.write("Android-Version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            w.write("Geraet: " + Build.MANUFACTURER + " / " + Build.MODEL + "\n");
            w.write("Magnetfeldsensor: " + sensorDescription + "\n");
            w.write("Beginn (Unix ms): " + sessionStartMillis + "\n");
            w.write("CSV-Zeilen (ohne Header): " + samples + "\n");
            w.write("Standortfixes: " + locations + "\n");
            w.write("GeoJSON-Kartenpunkte: " + geoPoints + "\n");
            w.write("Standortaufzeichnung bei Berichtserstellung aktiviert: " + locationTrackingEnabled + "\n");
            w.write("Delta min (uT): " + (Double.isFinite(minDelta) ? n(minDelta) : "k.A.") + "\n");
            w.write("Delta max (uT): " + (Double.isFinite(maxDelta) ? n(maxDelta) : "k.A.") + "\n");
            w.write("Max. Betrag Delta (uT): " + n(maxAbsDelta) + "\n\n");
            w.write("Modus-Codes: 0=Gesamtbetrag, 1=vertikal (in Schwerkraftrichtung), 2=horizontal.\n");
            w.write("Genauigkeit: 0=unzuverlaessig, 1=niedrig, 2=mittel, 3=hoch.\n");
            w.write("X/Y/Z sind GERAETE-Achsen, keine festen Himmelsrichtungen.\n");
            w.write("Uncal-Felder: unkalibriertes Magnetometer, wenn Hardware vorhanden; bias=geschaetzte Offsetkorrektur.\n");
            w.write("Senkrecht/waagrecht erfordern gueltigen Schwerkraftvektor.\n");
            w.write("Leere numerische CSV-Felder bedeuten nicht verfuegbar.\n");
            w.write("GPS optional mit expliziter Freigabe. Koordinaten, Genauigkeit und Fix-Alter in CSV.\n");
            w.write("Eine leere GPS-Zelle bedeutet: aus, nicht vorhanden, noch kein Fix oder aelter als 5 Sekunden.\n");
            w.write("Standortgenauigkeit ist eine Schaetzung, KEINE Geraetegenauigkeit fuer Nahbereichs-Kartierung.\n");
            w.write("GeoJSON: WGS84, Koordinatenreihenfolge [longitude,latitude].\n");
            w.write("WICHTIG: ZIP-Logs koennen praezise persoenliche Standortdaten enthalten; nur bewusst teilen.\n");
            w.write("Keine Internetberechtigung, keine automatische Uebertragung.\n");
            w.write("Interpretation: Magnetische Stoerung ist kein Nachweis einer Wasserader.\n");
        } catch (IOException e) { android.util.Log.e("MagnetRute", "Summary error", e); }
    }
    private void writeGeoJson() {
        if(sessionFolder==null) return;
        File in=new File(sessionFolder,"kartenpunkte.ndjson");
        File out=new File(sessionFolder,"kartenpunkte.geojson");
        try(BufferedWriter w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out),StandardCharsets.UTF_8))) {
            w.write("{\"type\":\"FeatureCollection\",\"features\":[\n");
            if (geoFeaturesOut!=null) geoFeaturesOut.flush();
            if(in.isFile()) {
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(in), StandardCharsets.UTF_8))) {
                    String line;boolean first=true;
                    while((line=reader.readLine())!=null) {
                        if(line.isEmpty()) continue;
                        if(!first) w.write(",\n");
                        w.write(line);
                        first=false;
                    }
                }
            }
            w.write("\n]}\n");
        } catch(IOException e) { android.util.Log.e("MagnetRute", "GeoJSON error", e); }
    }
    private static void addFile(ZipOutputStream z, File file) throws IOException {
        z.putNextEntry(new ZipEntry(file.getName()));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) z.write(buffer, 0, read);
        }
        z.closeEntry();
    }
    public File export() throws IOException {
        File folder = isRecording() ? sessionFolder : getLastFolder();
        if (folder == null) throw new IOException("Es gibt noch keine Aufzeichnung.");
        if (isRecording()) { flush(); writeGeoJson(); writeSummary(); }
        else { // v0.2 Datensaetze enthalten GeoJSON; fuer alte Logs bleiben nur die vorhandenen Dateien.
            sessionFolder=folder;
        }
        File out = new File(context.getCacheDir(), "MagnetRute_Log.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (String name : new String[] {"messwerte.csv", "ereignisse.csv", "standorte.csv",
                    "kartenpunkte.geojson", "diagnose.txt"}) {
                File f = new File(folder, name);
                if (f.isFile()) addFile(z, f);
            }
        }
        return out;
    }
}
