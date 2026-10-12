package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Lanczos resample: flat stays flat, a copy is a copy, light is averaged as light, and alpha does not bleed. */
class ResampleTest {

    private static byte[] fill(int w, int h, int r, int g, int b, int a) {
        byte[] p = new byte[w * h * 4];
        for (int i = 0; i < p.length; i += 4) {
            p[i] = (byte) r;
            p[i + 1] = (byte) g;
            p[i + 2] = (byte) b;
            p[i + 3] = (byte) a;
        }
        return p;
    }

    private static int at(byte[] p, int w, int x, int y, int c) {
        return p[(y * w + x) * 4 + c] & 0xFF;
    }

    @Test
    void aFlatColourStaysFlatAtAnySize() {
        byte[] src = fill(37, 23, 200, 120, 40, 255);
        for (int[] size : new int[][] {{7, 5}, {37, 23}, {150, 90}}) {
            byte[] out = Resample.lanczos(src, 37, 23, 0, 0, 37, 23, size[0], size[1]);
            for (int i = 0; i < out.length; i += 4) {
                assertEquals(200, out[i] & 0xFF, 1);
                assertEquals(120, out[i + 1] & 0xFF, 1);
                assertEquals(40, out[i + 2] & 0xFF, 1);
                assertEquals(255, out[i + 3] & 0xFF);
            }
        }
    }

    @Test
    void everyFilterKeepsAFlatColourFlat() {
        byte[] src = fill(19, 13, 30, 160, 220, 255);
        for (dev.vexelray.canvas.ImageFilter f : dev.vexelray.canvas.ImageFilter.values()) {
            for (int[] size : new int[][] {{5, 3}, {19, 13}, {77, 41}}) {
                byte[] out = Resample.of(f, src, 19, 13, 0, 0, 19, 13, size[0], size[1]);
                for (int i = 0; i < out.length; i += 4) {
                    assertEquals(30, out[i] & 0xFF, 1, f + " at " + size[0]);
                    assertEquals(160, out[i + 1] & 0xFF, 1, f.toString());
                    assertEquals(220, out[i + 2] & 0xFF, 1, f.toString());
                    assertEquals(255, out[i + 3] & 0xFF, f.toString());
                }
            }
        }
    }

    @Test
    void nearestAndSharpBilinearDoubledAreSquares() {
        byte[] src = new byte[4 * 4 * 4];
        new java.util.Random(3).nextBytes(src);
        for (int i = 3; i < src.length; i += 4) {
            src[i] = (byte) 255;
        }
        for (dev.vexelray.canvas.ImageFilter f : new dev.vexelray.canvas.ImageFilter[] {
                dev.vexelray.canvas.ImageFilter.NEAREST, dev.vexelray.canvas.ImageFilter.SHARP_BILINEAR}) {
            byte[] out = Resample.of(f, src, 4, 4, 0, 0, 4, 4, 8, 8);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    for (int c = 0; c < 3; c++) {
                        assertEquals(at(src, 4, x / 2, y / 2, c), at(out, 8, x, y, c), 1, f + " at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void theBellsNeverOvershoot() {
        // A hard black-to-white edge, enlarged: Lanczos rings past both, the bells stay between them.
        byte[] src = new byte[8 * 1 * 4];
        for (int x = 0; x < 8; x++) {
            int v = x < 4 ? 40 : 220;
            src[x * 4] = src[x * 4 + 1] = src[x * 4 + 2] = (byte) v;
            src[x * 4 + 3] = (byte) 255;
        }
        for (dev.vexelray.canvas.ImageFilter f : new dev.vexelray.canvas.ImageFilter[] {
                dev.vexelray.canvas.ImageFilter.GAUSSIAN, dev.vexelray.canvas.ImageFilter.B_SPLINE}) {
            byte[] out = Resample.of(f, src, 8, 1, 0, 0, 8, 1, 64, 1);
            for (int x = 0; x < 64; x++) {
                int v = at(out, 64, x, 0, 0);
                assertTrue(v >= 40 && v <= 220, f + " overshot to " + v + " at " + x);
            }
        }
    }

    @Test
    void theSameSizeOnWholePixelsIsACopy() {
        byte[] src = new byte[16 * 16 * 4];
        new java.util.Random(7).nextBytes(src);
        for (int i = 3; i < src.length; i += 4) {
            src[i] = (byte) 255;
        }
        byte[] out = Resample.lanczos(src, 16, 16, 4, 2, 8, 8, 8, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                for (int c = 0; c < 4; c++) {
                    assertEquals(at(src, 16, x + 4, y + 2, c), at(out, 8, x, y, c), 1, "at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void blackAndWhiteHalveToTheGreyOfHalfTheLightNotHalfTheValue() {
        // A one-pixel checkerboard shrunk by half is half the light: sRGB 188, where averaging the stored values
        // would give 128 and draw it too dark.
        byte[] src = new byte[64 * 64 * 4];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int v = (x + y) % 2 == 0 ? 255 : 0;
                int p = (y * 64 + x) * 4;
                src[p] = src[p + 1] = src[p + 2] = (byte) v;
                src[p + 3] = (byte) 255;
            }
        }
        byte[] out = Resample.lanczos(src, 64, 64, 0, 0, 64, 64, 32, 32);
        int mid = at(out, 32, 16, 16, 0);
        assertEquals(188, mid, 3);
    }

    @Test
    void aTransparentPixelsColourDoesNotBleed() {
        // Opaque red beside transparent green: shrunk together, the colour is red, at half the alpha.
        byte[] src = new byte[2 * 1 * 4];
        src[0] = (byte) 255;
        src[3] = (byte) 255;
        src[5] = (byte) 255;
        byte[] out = Resample.lanczos(src, 2, 1, 0, 0, 2, 1, 1, 1);
        assertEquals(255, out[0] & 0xFF, 1);
        assertEquals(0, out[1] & 0xFF, 1);
        assertTrue(Math.abs((out[3] & 0xFF) - 128) <= 2, "half covered: " + (out[3] & 0xFF));
    }
}
