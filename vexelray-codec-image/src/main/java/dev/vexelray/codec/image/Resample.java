package dev.vexelray.codec.image;

import dev.vexelray.canvas.ImageFilter;

import java.util.stream.IntStream;

/**
 * An exact resample: any rectangle of an RGBA8 image to any size, with any {@link ImageFilter}, in linear light, with
 * premultiplied alpha. What a picture is drawn from once it has stopped moving, where the GPU draws it from a
 * shrunk copy and, shrinking it further, approximates.
 *
 * <ul>
 *   <li><b>The filter's own weights</b> ({@link ImageFilter#weight}), widened by the shrink factor when shrinking,
 *       so every source pixel under an output pixel is weighed and none is skipped.</li>
 *   <li><b>In linear light</b>: the stored values are sRGB, and averaging those darkens fine bright detail on dark
 *       ground. Each value is decoded to light, filtered, and encoded again.</li>
 *   <li><b>Premultiplied</b>: a transparent pixel's colour means nothing, so it is weighed by its alpha and does not
 *       bleed into the edge of the opaque pixel beside it.</li>
 * </ul>
 *
 * <p>Separable: across, then down, each over rows in parallel. Every filter offered is separable without bias: the
 * Gaussian is round, the rest are products whose lobes the clamp below keeps in range. Pixels outside the image
 * repeat its edge. A filter with negative lobes overshoots at a hard edge; the result is clamped. Blocking and
 * CPU-heavy: off the GUI thread.
 */
public final class Resample {

    /** sRGB byte to linear light. */
    private static final float[] DECODE = new float[256];
    /** Linear light, in {@code ENCODE.length - 1} steps, to an sRGB byte. */
    private static final byte[] ENCODE = new byte[4096];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            DECODE[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < ENCODE.length; i++) {
            double l = i / (double) (ENCODE.length - 1);
            double c = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
            ENCODE[i] = (byte) Math.round(Math.max(0, Math.min(1, c)) * 255);
        }
    }

    private Resample() {
    }

    /** {@link #of} with {@link ImageFilter#LANCZOS}. */
    public static byte[] lanczos(byte[] src, int srcW, int srcH, double x, double y, double w, double h,
                                 int outW, int outH) {
        return of(ImageFilter.LANCZOS, src, srcW, srcH, x, y, w, h, outW, outH);
    }

    /**
     * The rectangle {@code (x, y, w, h)} of {@code src}, in source pixels and not necessarily whole ones, as an
     * {@code outW x outH} image scaled with {@code filter}: straight RGBA8, top row first, like the source.
     */
    public static byte[] of(ImageFilter filter, byte[] src, int srcW, int srcH, double x, double y, double w,
                            double h, int outW, int outH) {
        if (srcW <= 0 || srcH <= 0 || src.length < (long) srcW * srcH * 4) {
            throw new IllegalArgumentException("not a " + srcW + "x" + srcH + " RGBA image");
        }
        if (outW <= 0 || outH <= 0 || !(w > 0) || !(h > 0)) {
            throw new IllegalArgumentException("nothing to make: " + outW + "x" + outH + " of " + w + "x" + h);
        }
        Taps across = Taps.of(filter, x, w, outW, srcW);
        Taps down = Taps.of(filter, y, h, outH, srcH);
        int top = down.lowest();
        int rows = down.highest() - top + 1;

        // Across: each source row the vertical filter will read, to linear premultiplied light at the output width.
        float[] mid = new float[rows * outW * 4];
        IntStream.range(0, rows).parallel().forEach(r -> {
            int row = (top + r) * srcW * 4;
            int o = r * outW * 4;
            for (int i = 0; i < outW; i++, o += 4) {
                float cr = 0, cg = 0, cb = 0, ca = 0;
                int first = across.first[i];
                int at = i * across.width;
                for (int k = 0; k < across.width; k++) {
                    float wt = across.weights[at + k];
                    if (wt == 0f) {
                        continue;
                    }
                    int p = row + clamp(first + k, srcW) * 4;
                    float a = (src[p + 3] & 0xFF) / 255f;
                    float wa = wt * a;
                    cr += DECODE[src[p] & 0xFF] * wa;
                    cg += DECODE[src[p + 1] & 0xFF] * wa;
                    cb += DECODE[src[p + 2] & 0xFF] * wa;
                    ca += wa;
                }
                mid[o] = cr;
                mid[o + 1] = cg;
                mid[o + 2] = cb;
                mid[o + 3] = ca;
            }
        });

        // Down: each output row from the rows above, then out of premultiplied light and back to sRGB bytes.
        byte[] out = new byte[outW * outH * 4];
        int last = ENCODE.length - 1;
        IntStream.range(0, outH).parallel().forEach(j -> {
            int first = down.first[j];
            int at = j * down.width;
            int o = j * outW * 4;
            for (int i = 0; i < outW; i++, o += 4) {
                float cr = 0, cg = 0, cb = 0, ca = 0;
                for (int k = 0; k < down.width; k++) {
                    float wt = down.weights[at + k];
                    if (wt == 0f) {
                        continue;
                    }
                    int m = ((Math.min(Math.max(first + k, 0), srcH - 1) - top) * outW + i) * 4;
                    cr += mid[m] * wt;
                    cg += mid[m + 1] * wt;
                    cb += mid[m + 2] * wt;
                    ca += mid[m + 3] * wt;
                }
                if (ca <= 1e-6f) {
                    continue;   // transparent: all zero
                }
                float a = Math.min(1f, ca);
                out[o] = ENCODE[(int) (Math.max(0f, Math.min(1f, cr / ca)) * last + 0.5f)];
                out[o + 1] = ENCODE[(int) (Math.max(0f, Math.min(1f, cg / ca)) * last + 0.5f)];
                out[o + 2] = ENCODE[(int) (Math.max(0f, Math.min(1f, cb / ca)) * last + 0.5f)];
                out[o + 3] = (byte) Math.round(a * 255f);
            }
        });
        return out;
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : Math.min(v, size - 1);
    }

    /**
     * For one axis, which source pixels each output pixel reads and how much of each: {@code width} weights per output
     * pixel from source pixel {@code first[i]} on, normalised to sum to 1. Indices may run off the image; the reader
     * repeats the edge.
     */
    private record Taps(int[] first, float[] weights, int width, int size) {

        static Taps of(ImageFilter filter, double start, double length, int out, int size) {
            double step = length / out;
            double support = filter.reach(step);   // shrinking widens it
            int width = (int) Math.ceil(2 * support) + 1;
            int[] first = new int[out];
            float[] weights = new float[out * width];
            for (int i = 0; i < out; i++) {
                double centre = start + (i + 0.5) * step;
                int lo = (int) Math.floor(centre - support);
                first[i] = lo;
                double sum = 0;
                for (int k = 0; k < width; k++) {
                    double wt = filter.weight(lo + k + 0.5 - centre, step);
                    weights[i * width + k] = (float) wt;
                    sum += wt;
                }
                if (sum != 0) {
                    for (int k = 0; k < width; k++) {
                        weights[i * width + k] /= (float) sum;
                    }
                }
            }
            return new Taps(first, weights, width, size);
        }

        /** The first source index any output pixel reads, held to the image. */
        int lowest() {
            return clamp(first[0], size);
        }

        /** The last, held to the image. */
        int highest() {
            return clamp(first[first.length - 1] + width - 1, size);
        }
    }
}
