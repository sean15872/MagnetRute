package at.andi.magnetrute;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class PlaceSearchTest {
    private Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication();context.getSharedPreferences("place-search-v04",0).edit().clear().commit(); }
    private static JSONObject place(String name,double lat,double lon) throws Exception {
        return new JSONObject().put("name",name).put("latitude",lat).put("longitude",lon);
    }
    @Test public void offlineSearchNeverCallsBackendAndOnlyUsesSelectedHistory() throws Exception {
        PlaceSearch.remember(context,place("Garten Rinegg",47.15,14.13));
        PlaceSearch.Backend forbidden=q -> { throw new AssertionError("Offline lookup accessed network/geocoder"); };
        assertEquals(1,PlaceSearch.search(context,"rinegg",true,forbidden).size());
        assertEquals(0,PlaceSearch.search(context,"Graz",true,forbidden).size());
        assertEquals(1,PlaceSearch.search(context,"",true,forbidden).size());
        assertEquals("Garten Rinegg",PlaceSearch.history(context.createPackageContext(context.getPackageName(),0)).get(0).getString("name"));
    }
    @Test public void explicitOnlineSearchCachesQueriesButOnlyRememberMakesOfflinePlace() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        PlaceSearch.Backend backend=q -> {
            calls.incrementAndGet();
            try { return Arrays.asList(place("Rinegg, Ranten, 8844, Österreich",47.1586349,14.1333556)); }
            catch(Exception e) { throw new IOException(e); }
        };
        List<JSONObject> first=PlaceSearch.search(context,"Rinegg 69",false,backend);
        assertEquals(1,calls.get());assertEquals(0,PlaceSearch.history(context).size());
        assertEquals(1,PlaceSearch.search(context,"  RINEGG 69  ",false,backend).size());assertEquals(1,calls.get());
        assertEquals(0,PlaceSearch.search(context,"Rinegg",true,backend).size());
        PlaceSearch.remember(context,first.get(0));
        assertEquals(1,PlaceSearch.search(context,"Rinegg",true,backend).size());assertEquals(1,calls.get());
    }
    @Test public void historyIsBoundedDeduplicatedAndDoesNotExposeMutablePreferenceData() throws Exception {
        for(int i=0;i<25;i++) PlaceSearch.remember(context,place("Ort "+i,47,14+i*.001));
        assertEquals(20,PlaceSearch.history(context).size());
        JSONObject selected=PlaceSearch.history(context).get(10);PlaceSearch.remember(context,selected);
        assertEquals(20,PlaceSearch.history(context).size());assertEquals(selected.getString("name"),PlaceSearch.history(context).get(0).getString("name"));
        selected.put("latitude",48);
        assertEquals(47,PlaceSearch.history(context).get(0).getDouble("latitude"),0);
    }
    @Test public void customEndpointIsOptionalHttpsAndChangesInvalidateQueryCacheKey() throws Exception {
        assertEquals("",PlaceSearch.getEndpoint(context));
        for(String bad:Arrays.asList("http://example.org/search","https://a:b@example.org/search","https://example.org/search?key=x","https://example.org/search#x"))
            assertThrows(IOException.class,()->PlaceSearch.setEndpoint(context,bad));
        PlaceSearch.setEndpoint(context,"https://example.org/search");assertEquals("https://example.org/search",PlaceSearch.getEndpoint(context));
        AtomicInteger calls=new AtomicInteger();PlaceSearch.Backend b=q -> { calls.incrementAndGet();return Collections.emptyList(); };
        PlaceSearch.search(context,"Wien",false,b);PlaceSearch.search(context,"Wien",false,b);assertEquals(1,calls.get());
        PlaceSearch.setEndpoint(context,"");PlaceSearch.search(context,"Wien",false,b);assertEquals(2,calls.get());
    }
    @Test public void customHttpParsingIsBoundedRateLimitedAndIncludesAttribution() throws Exception {
        class FakeClock implements PlaceSearch.Clock {
            long now=100;int sleeps;
            public long nowMillis(){return now;}
            public void sleep(long ms){assertTrue(ms>0);now+=ms;sleeps++;}
        }
        FakeClock clock=new FakeClock();PlaceSearch.RateLimiter limiter=new PlaceSearch.RateLimiter(clock);
        AtomicInteger calls=new AtomicInteger();PlaceSearch.Transport t=url -> {
            calls.incrementAndGet();assertTrue(url.startsWith("https://example.org/search?format=jsonv2&countrycodes=at&limit=5&q="));
            assertTrue(url.endsWith("Rinegg+69"));
            return "[{\"display_name\":\"Rinegg, 8844, Österreich\",\"lat\":\"47.1586349\",\"lon\":\"14.1333556\",\"licence\":\"© OpenStreetMap contributors\"}]";
        };
        List<JSONObject> found=PlaceSearch.httpSearch("https://example.org/search","Rinegg 69",t,limiter);
        assertEquals(47.1586349,found.get(0).getDouble("latitude"),0);assertTrue(found.get(0).getString("attribution").contains("OpenStreetMap"));
        PlaceSearch.httpSearch("https://example.org/search","Rinegg 69",t,limiter);
        assertEquals(2,calls.get());assertEquals(1,clock.sleeps);assertEquals(1100,clock.now);
        assertThrows(IOException.class,()->PlaceSearch.httpSearch("https://example.org/search","x",u -> "{}",limiter));
        assertThrows(IOException.class,()->PlaceSearch.httpSearch("https://example.org/search","x",u -> "[{\"display_name\":\"bad\",\"lat\":999,\"lon\":14}]",limiter));
    }
    @Test public void invalidInputFailsBeforeBackendAndFailuresNeverInventLocation() throws Exception {
        AtomicInteger calls=new AtomicInteger();PlaceSearch.Backend backend=q -> { calls.incrementAndGet();throw new IOException("No geocoder"); };
        assertThrows(IOException.class,()->PlaceSearch.search(context,"ab",false,backend));assertEquals(0,calls.get());
        assertThrows(IOException.class,()->PlaceSearch.search(context,"Rinegg",false,backend));assertEquals(1,calls.get());
        assertEquals(0,PlaceSearch.history(context).size());
        assertThrows(IOException.class,()->PlaceSearch.remember(context,place("Ungültig",91,14)));
    }
}
