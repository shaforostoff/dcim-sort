package com.shaforostoff.dcimsort.geo;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import com.shaforostoff.dcimsort.util.Sdk;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a coordinate to a place name. Order: ~10 km grid cache → on-device {@link Geocoder} →
 * online {@link NominatimClient} fallback. Results (including "no place") are cached per cell so
 * thousands of nearby photos cost at most one lookup per cell.
 */
public class PlaceResolver {
    private final GeoDb cache;
    private final Geocoder geocoder;        // null if not present on device
    private final NominatimClient online;
    // Per-cell locks so concurrent callers for the same uncached cell do one lookup, not N.
    private final ConcurrentHashMap<String, Object> inFlight = new ConcurrentHashMap<>();
    // Cells whose lookup failed this run. Not persisted (a later run retries), but remembered so an
    // offline run costs one attempt per cell rather than one per photo.
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public PlaceResolver(Context ctx) {
        this.cache = GeoDb.get(ctx);
        Geocoder g = null;
        try {
            if (Geocoder.isPresent()) {
                g = new Geocoder(ctx.getApplicationContext(), Locale.getDefault());
            }
        } catch (Throwable ignore) {
            g = null;
        }
        this.geocoder = g;
        this.online = new NominatimClient();
    }

    /** @return place name, or null if unknown. Blocking; call off the main thread. */
    public String resolve(double lat, double lon) {
        String key = cellKey(lat, lon);
        String cached = cache.place(key);
        if (cached != null || failed.contains(key)) return nameOrNull(cached);
        // Serialize lookups for the same cell so parallel callers (Preview/Organize pools) collapse
        // to a single geocoder/network request; the first writes the cache, the rest read it.
        Object lock = inFlight.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = cache.place(key);
            if (cached != null || failed.contains(key)) return nameOrNull(cached);
            String place = lookup(lat, lon);
            // "" caches "no place here"; a failure must not, or one offline run blanks the cell forever.
            if (place != null) cache.putPlace(key, place);
            else failed.add(key);
            inFlight.remove(key);
            return nameOrNull(place);
        }
    }

    private static String nameOrNull(String place) {
        return place == null || place.isEmpty() ? null : place;
    }

    /** Snaps a coordinate to a ~10–11 km grid cell, adjusting longitude width by latitude. */
    static String cellKey(double lat, double lon) {
        long latCell = Math.round(lat / 0.1);
        double centerLat = latCell * 0.1;
        double cosLat = Math.max(0.05, Math.cos(Math.toRadians(centerLat)));
        double lonStep = 0.1 / cosLat;
        long lonCell = Math.round(lon / lonStep);
        return latCell + "," + lonCell;
    }

    /** Place name, "" when a source answered that there is none, or null when every lookup failed. */
    private String lookup(double lat, double lon) {
        String place = geocode(lat, lon);
        if (place == null || place.isEmpty()) {
            String o = online.reverse(lat, lon);
            if (o != null) place = o;
        }
        return place;
    }

    private String geocode(double lat, double lon) {
        if (geocoder == null) return null;
        if (Sdk.atLeastT()) {
            return geocodeAsync(lat, lon);
        }
        try {
            List<Address> list = geocoder.getFromLocation(lat, lon, 1);
            return pick(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** API 33+ async API wrapped back into a blocking call for our worker model. */
    private String geocodeAsync(double lat, double lon) {
        final String[] result = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            geocoder.getFromLocation(lat, lon, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    result[0] = pick(addresses);
                    latch.countDown();
                }

                @Override
                public void onError(String errorMessage) {
                    latch.countDown();
                }
            });
            latch.await(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
        return result[0];
    }

    /** The best name in a successful geocoder answer, or "" when it has none. */
    private static String pick(List<Address> list) {
        if (list == null || list.isEmpty()) return "";
        Address a = list.get(0);
        if (a == null) return "";
        if (notEmpty(a.getLocality())) return a.getLocality();
        if (notEmpty(a.getSubAdminArea())) return a.getSubAdminArea();
        if (notEmpty(a.getAdminArea())) return a.getAdminArea();
        if (notEmpty(a.getCountryName())) return a.getCountryName();
        return "";
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
