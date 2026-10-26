package at.andi.magnetrute;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit, user-triggered address lookup. Offline calls never invoke a geocoder or HTTP. */
public final class PlaceSearch {
    private static final String PREFS="place-search-v04";
    private static final int MAX_HISTORY=20,MAX_RESULTS=5,MAX_RESPONSE=1024*1024;
    public static final String OSM_ATTRIBUTION="© OpenStreetMap-Mitwirkende · ODbL · https://www.openstreetmap.org/copyright";
    private static final RateLimiter HTTP_LIMITER=new RateLimiter(new Clock() {
        @Override public long nowMillis() { return System.nanoTime()/1_000_000L; }
        @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
    });
    private PlaceSearch() { }

    /** Backend injection keeps tests independent of the OS geocoding provider. */
    public interface Backend { List<JSONObject> search(String query) throws IOException; }
    public interface Transport { String fetch(String url) throws IOException; }
    public interface Clock { long nowMillis(); void sleep(long millis) throws InterruptedException; }

    /** Serializes custom HTTP requests and leaves at least one second between starts. */
    public static final class RateLimiter {
        private final Clock clock;
        private long last=-1;
        public RateLimiter(Clock clock) { this.clock=clock; }
        public synchronized String fetch(Transport transport,String url) throws IOException {
            if(last>=0) {
                long wait=1000-(clock.nowMillis()-last);
                if(wait>0) {
                    try { clock.sleep(wait); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("Adresssuche unterbrochen.",e); }
                }
            }
            last=clock.nowMillis();
            return transport.fetch(url);
        }
    }

    public static List<JSONObject> search(Context context,String query,boolean offline) throws IOException {
        return search(context,query,offline,q -> {
            String endpoint=getEndpoint(context);
            return endpoint.isEmpty()?platformSearch(context,q):httpSearch(endpoint,q,PlaceSearch::fetch,HTTP_LIMITER);
        });
    }

    public static List<JSONObject> search(Context context,String query,boolean offline,Backend backend) throws IOException {
        String q=query==null?"":query.trim();
        // This branch deliberately precedes all endpoint, geocoder and network operations.
        if(offline || q.isEmpty()) {
            List<JSONObject> found=new ArrayList<>();
            for(JSONObject place:history(context)) if(q.isEmpty() || place.optString("name").toLowerCase(Locale.GERMAN)
                    .contains(q.toLowerCase(Locale.GERMAN))) found.add(place);
            return found;
        }
        if(q.length()<3 || q.length()>200) throw new IOException("Für die Adresssuche 3–200 Zeichen eingeben.");
        String key=cacheKey(getEndpoint(context)+"\n"+q.toLowerCase(Locale.GERMAN));
        JSONArray cache=array(prefs(context).getString("queries","[]"));
        for(int i=0;i<cache.length();i++) {
            JSONObject entry=cache.optJSONObject(i);
            if(entry!=null && key.equals(entry.optString("key"))) return validated(entry.optJSONArray("results"),MAX_RESULTS);
        }
        List<JSONObject> raw=backend.search(q);
        JSONArray result=new JSONArray();
        if(raw!=null) for(JSONObject place:raw) {
            if(result.length()>=MAX_RESULTS) break;
            result.put(normalize(place));
        }
        JSONArray updated=new JSONArray();
        try { updated.put(new JSONObject().put("key",key).put("results",result)); }
        catch(JSONException e) { throw new IOException("Suchergebnis konnte nicht gespeichert werden.",e); }
        for(int i=0;i<cache.length() && updated.length()<MAX_HISTORY;i++) {
            JSONObject entry=cache.optJSONObject(i);if(entry!=null && !key.equals(entry.optString("key"))) updated.put(entry);
        }
        if(!prefs(context).edit().putString("queries",updated.toString()).commit())
            throw new IOException("Suchergebnis konnte nicht lokal gespeichert werden.");
        return validated(result,MAX_RESULTS);
    }

    /** Only selected places enter the offline history; search candidates are not GPS fixes. */
    public static synchronized void remember(Context context,JSONObject selected) throws IOException {
        JSONObject normalized=normalize(selected);JSONArray updated=new JSONArray();updated.put(normalized);
        for(JSONObject old:history(context)) {
            if(updated.length()>=MAX_HISTORY) break;
            if(old.optString("name").equals(normalized.optString("name"))
                    && Double.compare(old.optDouble("latitude"),normalized.optDouble("latitude"))==0
                    && Double.compare(old.optDouble("longitude"),normalized.optDouble("longitude"))==0) continue;
            updated.put(old);
        }
        if(!prefs(context).edit().putString("history",updated.toString()).commit())
            throw new IOException("Ausgewählter Ort konnte nicht gespeichert werden.");
    }

    public static List<JSONObject> history(Context context) {
        JSONArray values=array(prefs(context).getString("history","[]"));List<JSONObject> places=new ArrayList<>();
        for(int i=0;i<values.length() && places.size()<MAX_HISTORY;i++) {
            try { places.add(normalize(values.optJSONObject(i))); }
            catch(IOException ignored) { /* Ignore corrupt optional history entries, never invent coordinates. */ }
        }
        return places;
    }

    /** Empty endpoint means Android's installed Geocoder, not a built-in public API. */
    public static String getEndpoint(Context context) { return prefs(context).getString("endpoint",""); }
    public static void setEndpoint(Context context,String endpoint) throws IOException {
        String value=endpoint==null?"":endpoint.trim();
        if(!value.isEmpty()) validateEndpoint(value);
        if(!prefs(context).edit().putString("endpoint",value).commit()) throw new IOException("Adressdienst konnte nicht gespeichert werden.");
    }

    private static List<JSONObject> platformSearch(Context context,String query) throws IOException {
        if(!Geocoder.isPresent()) throw new IOException("Auf diesem Gerät ist keine Android-Adresssuche verfügbar. "
            +"Gespeicherte Orte öffnen, Karte manuell wählen oder einen eigenen Adressdienst in Einstellungen festlegen.");
        List<Address> addresses=new Geocoder(context,Locale.GERMANY).getFromLocationName(query,MAX_RESULTS,46.3,8.7,49.1,17.6);
        List<JSONObject> results=new ArrayList<>();
        if(addresses!=null) for(Address a:addresses) {
            if(!a.hasLatitude() || !a.hasLongitude() || (a.getCountryCode()!=null && !"AT".equalsIgnoreCase(a.getCountryCode()))) continue;
            String name=a.getMaxAddressLineIndex()>=0?a.getAddressLine(0):a.getFeatureName();
            if(name==null || name.trim().isEmpty()) continue;
            try { results.add(normalize(new JSONObject().put("name",name).put("latitude",a.getLatitude())
                .put("longitude",a.getLongitude()).put("source","Android-Adresssuche").put("attribution","Adressdienst des Geräts"))); }
            catch(JSONException e) { throw new IOException("Ungültige Adresse vom Gerätedienst.",e); }
        }
        return results;
    }

    /** Optional operator-configured Nominatim-compatible endpoint; no public service is preset. */
    public static List<JSONObject> httpSearch(String endpoint,String query,Transport transport,RateLimiter limiter) throws IOException {
        validateEndpoint(endpoint);
        String url=endpoint+"?format=jsonv2&countrycodes=at&limit=5&q="+URLEncoder.encode(query,StandardCharsets.UTF_8.name());
        String response=limiter.fetch(transport,url);
        if(response==null || response.length()>MAX_RESPONSE) throw new IOException("Adressdienst-Antwort ist zu groß oder leer.");
        List<JSONObject> result=new ArrayList<>();
        try {
            JSONArray rows=new JSONArray(response);
            for(int i=0;i<rows.length() && result.size()<MAX_RESULTS;i++) {
                JSONObject row=rows.getJSONObject(i);
                result.add(normalize(new JSONObject().put("name",row.getString("display_name"))
                    .put("latitude",row.getDouble("lat")).put("longitude",row.getDouble("lon"))
                    .put("source",new URL(endpoint).getHost()).put("attribution",row.optString("licence",OSM_ATTRIBUTION))));
            }
        } catch(JSONException e) { throw new IOException("Ungültiges Adressdienst-Format.",e); }
        return result;
    }

    private static void validateEndpoint(String endpoint) throws IOException {
        URL url=new URL(endpoint);
        if(!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo()!=null
                || url.getQuery()!=null || url.getRef()!=null || endpoint.length()>500)
            throw new IOException("Adressdienst benötigt eine HTTPS-Such-URL ohne Zugangsdaten oder Abfrageparameter.");
    }
    private static String fetch(String url) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15_000);connection.setReadTimeout(20_000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","MagnetRute/0.4.0 (https://github.com/sean15872/MagnetRute)");
        connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Language","de");
        try {
            int code=connection.getResponseCode();
            if(code!=200) throw new IOException("Adressdienst antwortet mit HTTP "+code+". Gespeicherte Orte bleiben offline verfügbar.");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) {
                    if(out.size()+n>MAX_RESPONSE) throw new IOException("Adressdienst-Antwort überschreitet 1 MiB.");
                    out.write(buffer,0,n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    private static JSONObject normalize(JSONObject place) throws IOException {
        if(place==null) throw new IOException("Ort fehlt.");
        String name=place.optString("name","").trim();double lat=place.optDouble("latitude",Double.NaN),lon=place.optDouble("longitude",Double.NaN);
        if(name.isEmpty() || name.length()>500 || !Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat >90 || lon < -180 || lon>180) throw new IOException("Ort enthält ungültige Koordinaten oder Namen.");
        try { return new JSONObject().put("name",name).put("latitude",lat).put("longitude",lon)
            .put("source",place.optString("source","")).put("attribution",place.optString("attribution","")); }
        catch(JSONException e) { throw new IOException("Ort konnte nicht gelesen werden.",e); }
    }
    private static List<JSONObject> validated(JSONArray rows,int max) throws IOException {
        List<JSONObject> result=new ArrayList<>();
        if(rows==null) throw new IOException("Beschädigter Suchcache.");
        for(int i=0;i<rows.length() && result.size()<max;i++) result.add(normalize(rows.optJSONObject(i)));
        return result;
    }
    private static JSONArray array(String json) {
        if(json==null || json.length()>MAX_RESPONSE) return new JSONArray();
        try { return new JSONArray(json); } catch(JSONException e) { return new JSONArray(); }
    }
    private static SharedPreferences prefs(Context c) {
        Context app=c.getApplicationContext();
        return (app==null?c:app).getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    private static String cacheKey(String value) throws IOException {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder();for(byte b:digest) key.append(String.format(Locale.US,"%02x",b&255));return key.toString();
        } catch(java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
