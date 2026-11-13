package dev.vexelray.codec.video;

import dev.vexelray.vulkan.present.MipChain;

import java.util.stream.IntStream;

/**
 * {@link MipChain}'s levels for an opaque image, as a decoded video frame always is, written into arrays already
 * made: the same texels {@code MipChain.of} makes, byte for byte, without what it spends on transparency (every
 * texel's alpha is 1, so each is weighed alike) or on allocating, and with each level's rows shared among the cores.
 *
 * <p>Averaged in linear light, as {@code MipChain} does, and stored as sRGB. Its tables are made the same way as
 * {@code MipChain}'s, so the two agree exactly (a test holds them to it).
 */
final class OpaqueMips {

    /** sRGB byte to linear light. */
    private static final float[] DECODE = new float[256];
    /** Linear light, in {@code ENCODE.length - 1} steps, to an sRGB byte. */
    private static final byte[] ENCODE = new byte[4096];
    /** Below this many texels a level is made on the calling thread: sharing it costs more than it saves. */
    private static final int SHARED_FROM = 64 * 1024;

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

    private OpaqueMips() {
    }

    /** A chain of every level of a {@code width x height} image, uninitialised: for {@link #fill}. */
    static byte[][] chain(int width, int height) {
        int n = MipChain.count(width, height);
        byte[][] levels = new byte[n][];
        for (int l = 0; l < n; l++) {
            levels[l] = new byte[MipChain.size(width, l) * MipChain.size(height, l) * 4];
        }
        return levels;
    }

    /** Make levels 1 on of {@code levels} from level 0, an opaque {@code width x height} RGBA image. */
    static void fill(byte[][] levels, int width, int height) {
        for (int l = 1; l < levels.length; l++) {
            byte[] src = levels[l - 1];
            byte[] dst = levels[l];
            int sw = MipChain.size(width, l - 1);
            int sh = MipChain.size(height, l - 1);
            int dw = MipChain.size(width, l);
            int dh = MipChain.size(height, l);
            if (dw * dh >= SHARED_FROM) {
                IntStream.range(0, dh).parallel().forEach(y -> row(src, sw, sh, dst, dw, y));
            } else {
                for (int y = 0; y < dh; y++) {
                    row(src, sw, sh, dst, dw, y);
                }
            }
        }
    }

    /** Row {@code y} of a level from the one above it: each texel the two by two it covers, the last row or column repeated. */
    private static void row(byte[] src, int sw, int sh, byte[] dst, int dw, int y) {
        int last = ENCODE.length - 1;
        int y0 = Math.min(2 * y, sh - 1) * sw;
        int y1 = Math.min(2 * y + 1, sh - 1) * sw;
        for (int x = 0; x < dw; x++) {
            int x0 = Math.min(2 * x, sw - 1);
            int x1 = Math.min(2 * x + 1, sw - 1);
            int p0 = (y0 + x0) * 4;
            int p1 = (y0 + x1) * 4;
            int p2 = (y1 + x0) * 4;
            int p3 = (y1 + x1) * 4;
            // In MipChain's order, each weighed by an alpha of exactly 1, so the sums and the result are its own.
            float r = DECODE[src[p0] & 0xFF];
            r += DECODE[src[p1] & 0xFF];
            r += DECODE[src[p2] & 0xFF];
            r += DECODE[src[p3] & 0xFF];
            float g = DECODE[src[p0 + 1] & 0xFF];
            g += DECODE[src[p1 + 1] & 0xFF];
            g += DECODE[src[p2 + 1] & 0xFF];
            g += DECODE[src[p3 + 1] & 0xFF];
            float b = DECODE[src[p0 + 2] & 0xFF];
            b += DECODE[src[p1 + 2] & 0xFF];
            b += DECODE[src[p2 + 2] & 0xFF];
            b += DECODE[src[p3 + 2] & 0xFF];
            int o = (y * dw + x) * 4;
            dst[o] = ENCODE[(int) (Math.min(1f, r / 4f) * last + 0.5f)];
            dst[o + 1] = ENCODE[(int) (Math.min(1f, g / 4f) * last + 0.5f)];
            dst[o + 2] = ENCODE[(int) (Math.min(1f, b / 4f) * last + 0.5f)];
            dst[o + 3] = (byte) 255;
        }
    }
}
