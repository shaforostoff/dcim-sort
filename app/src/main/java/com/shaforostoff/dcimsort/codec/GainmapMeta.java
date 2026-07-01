package com.shaforostoff.dcimsort.codec;

/**
 * Plain holder for UltraHDR gain-map metadata, decoupled from {@code android.graphics.Gainmap}
 * (API 34+) so it can live in the shared {@code main} source set and be passed to the
 * flavor-specific {@link NativeCodecs}. The lite flavor never populates one; the full flavor maps
 * these fields onto libavif's gain-map metadata.
 *
 * <p>Each per-channel array holds {R, G, B}. Values mirror {@code android.graphics.Gainmap}.
 */
public final class GainmapMeta {
    public float[] ratioMin = {1f, 1f, 1f};
    public float[] ratioMax = {2f, 2f, 2f};
    public float[] gamma = {1f, 1f, 1f};
    public float[] epsilonSdr = {0f, 0f, 0f};
    public float[] epsilonHdr = {0f, 0f, 0f};
    public float displayRatioSdr = 1f;
    public float displayRatioHdr = 2f;
}
