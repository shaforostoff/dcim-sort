package com.shaforostoff.dcimsort.work;

import static org.junit.Assert.assertEquals;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;

import org.junit.Test;

import java.util.Collections;

public class SizeEstimatorTest {
    private static final long MB = 1024 * 1024;

    private static MediaImage photo(int w, int h, long size) {
        return new MediaImage(1, "IMG.jpg", "DCIM/Camera/", null, 1, size, false, "image/jpeg",
                w, h, null);
    }

    @Test
    public void cappedMpScalesByTheLongSide() {
        assertEquals(2048 * 1536 / 1e6, SizeEstimator.cappedMp(photo(4000, 3000, 0), 2048), 1e-3);
        assertEquals(12.0, SizeEstimator.cappedMp(photo(4000, 3000, 0), 0), 1e-9);
        assertEquals(12.0, SizeEstimator.cappedMp(photo(4000, 3000, 0), 8192), 1e-9);
    }

    /** A 2048 px calibration encode of 1 MB means ~3.8 MB for the full 12 MP WebP output. */
    @Test
    public void calibrationRatioCarriesOverToFullResolution() {
        MediaImage m = photo(4000, 3000, 20 * MB);
        double ratio = MB / SizeEstimator.cappedMp(m, 2048);
        long est = SizeEstimator.estimateWithRatio(Collections.singletonList(m), ratio,
                CompressMode.WEBP, false, false, SizeEstimator.DEFAULT_MIN_GAIN_PERCENT);
        assertEquals(12 / (2048 * 1536 / 1e6) * MB, est, MB / 100.0);
    }

    /** HEIC output is capped at 4096 px, so a 50 MP photo is estimated as ~12.5 MP. */
    @Test
    public void heicEstimateUsesTheCappedSize() {
        MediaImage m = photo(8160, 6120, 100 * MB);
        long est = SizeEstimator.estimateWithRatio(Collections.singletonList(m), MB,
                CompressMode.HEIC, false, false, SizeEstimator.DEFAULT_MIN_GAIN_PERCENT);
        assertEquals(4096 * 3072 / 1e6 * MB, est, MB / 100.0);
    }
}
