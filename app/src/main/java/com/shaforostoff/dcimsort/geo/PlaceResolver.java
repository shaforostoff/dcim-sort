package com.shaforostoff.dcimsort.geo;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import com.shaforostoff.dcimsort.util.Sdk;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a coordinate to a place name. Order: ~10 km grid cache → on-device {@link Geocoder} →
 * online {@link NominatimClient} fallback. Results (including "no place") are cached per cell so
 * thousands of nearby photos cost at most one lookup per cell.
 */
public class PlaceResolver {
    private final GeoCache cache;
    private final Geocoder geocoder;        // null if not present on device
    private final NominatimClient online;

    public PlaceResolver(Context ctx, GeoCache cache) {
        this.cache = cache;
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
        String key = GeoCache.cellKey(lat, lon);
        if (cache.contains(key)) {
            return cache.get(key);
        }
        String place = lookup(lat, lon);
        cache.put(key, place); // caches empty for "not found"
        return place;
    }

    private String lookup(double lat, double lon) {
        String place = geocode(lat, lon);
        if (place == null) {
            place = online.reverse(lat, lon);
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

    private static String pick(List<Address> list) {
        if (list == null || list.isEmpty()) return null;
        Address a = list.get(0);
        if (a == null) return null;
        if (notEmpty(a.getLocality())) return a.getLocality();
        if (notEmpty(a.getSubAdminArea())) return a.getSubAdminArea();
        if (notEmpty(a.getAdminArea())) return a.getAdminArea();
        if (notEmpty(a.getCountryName())) return a.getCountryName();
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
