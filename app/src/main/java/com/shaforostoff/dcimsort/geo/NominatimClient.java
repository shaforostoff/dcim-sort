package com.shaforostoff.dcimsort.geo;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Online reverse-geocode fallback using OpenStreetMap Nominatim. Used only when the on-device
 * {@link android.location.Geocoder} is unavailable. Respects Nominatim policy: descriptive
 * User-Agent and a hard ≤1 request/second rate limit.
 */
public class NominatimClient {
    private static final String ENDPOINT =
            "https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&addressdetails=1";
    private static final String USER_AGENT = "DCIMSort/1.0 (Android photo organizer)";
    private static final long MIN_INTERVAL_MS = 1100;

    private final Object lock = new Object();
    private long lastRequestAt = 0;

    /** @return a place name (city/town/village) or null. Blocking; call off the main thread. */
    public String reverse(double lat, double lon) {
        rateLimit();
        HttpURLConnection conn = null;
        try {
            String url = ENDPOINT
                    + "&lat=" + String.format(Locale.US, "%.6f", lat)
                    + "&lon=" + String.format(Locale.US, "%.6f", lon);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            try (JsonReader r = new JsonReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return parse(r);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void rateLimit() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long wait = lastRequestAt + MIN_INTERVAL_MS - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    /** Reads the top-level object, descending into "address" to pull a city-level name. */
    private String parse(JsonReader r) throws Exception {
        String result = null;
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("address".equals(name) && r.peek() == JsonToken.BEGIN_OBJECT) {
                result = parseAddress(r);
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return result;
    }

    private String parseAddress(JsonReader r) throws Exception {
        String city = null, town = null, village = null, municipality = null, county = null, state = null;
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if (r.peek() == JsonToken.STRING) {
                String val = r.nextString();
                switch (key) {
                    case "city": city = val; break;
                    case "town": town = val; break;
                    case "village": village = val; break;
                    case "municipality": municipality = val; break;
                    case "county": county = val; break;
                    case "state": state = val; break;
                    default: break;
                }
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (city != null) return city;
        if (town != null) return town;
        if (village != null) return village;
        if (municipality != null) return municipality;
        if (county != null) return county;
        return state;
    }
}
