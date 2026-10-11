package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Packing and shrinking: what the GUI thread is spared. */
class PictureTest {

    @Test
    void aFrameThatFitsIsCopiedExactly() {
        byte[] src = new byte[2 * 2 * 4];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) i;
        }
        byte[] dst = new byte[4 * 2 * 4];
        Picture.fit(src, 2, 2, dst, 4, 2, 0, 2, 2);
        assertEquals(0, dst[3], "the left cell is untouched");
        assertEquals(0, dst[2 * 4]);
        assertEquals(3, dst[2 * 4 + 3]);
        assertEquals(15, dst[(4 + 3) * 4 + 3]);
    }

    @Test
    void shrinkingAveragesAndATransparentPixelsColourDoesNotBleed() {
        // Left column opaque red, right column fully transparent green: the green means nothing and must not show.
        byte[] src = {
                (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, 0,
                (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, 0,
        };
        byte[] dst = new byte[4];
        Picture.fit(src, 2, 2, dst, 1, 0, 0, 1, 1);
        assertEquals(255, dst[0] & 0xFF, "red, undiluted by the transparent half");
        assertEquals(0, dst[1] & 0xFF, "no green from a pixel nobody can see");
        assertEquals(128, dst[3] & 0xFF, "half covered");
    }

    @Test
    void framesPastTheCapAreThinnedEvenly() {
        assertArrayEquals(new int[] {0, 1, 2}, Picture.thin(3));
        int[] keep = Picture.thin(Picture.MAX_FRAMES * 2);
        assertEquals(Picture.MAX_FRAMES, keep.length);
        assertEquals(0, keep[0]);
        assertEquals(2, keep[1]);
        assertEquals(Picture.MAX_FRAMES * 2 - 2, keep[keep.length - 1]);
    }

    @Test
    void aStillIsASheetOfOne() {
        Picture p = new Picture(new Stamp(Path.of("x"), 1, 1), new byte[16], 2, 2, 1, 1, new int[] {0}, 2, 2, false);
        assertEquals(1, p.count());
        assertEquals(2, p.sheetWidth());
        assertEquals(16, p.bytes());
    }
}
