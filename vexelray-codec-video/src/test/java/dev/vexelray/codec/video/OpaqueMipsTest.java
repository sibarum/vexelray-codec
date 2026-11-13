package dev.vexelray.codec.video;

import dev.vexelray.vulkan.present.MipChain;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link OpaqueMips} makes the levels {@link MipChain#of} makes for an opaque image, byte for byte. */
class OpaqueMipsTest {

    @Test
    void itsLevelsAreMipChainsExactly() {
        // Odd sides, so the last row and column are repeated; large enough that the first levels are shared out.
        for (int[] size : new int[][] {{1, 1}, {3, 1}, {7, 5}, {641, 363}, {1920, 1080}}) {
            int w = size[0];
            int h = size[1];
            byte[] rgba = new byte[w * h * 4];
            new Random(w * 31L + h).nextBytes(rgba);
            for (int p = 3; p < rgba.length; p += 4) {
                rgba[p] = (byte) 255;   // opaque, as a decoded video frame is
            }
            byte[][] expected = MipChain.of(rgba.clone(), w, h);
            byte[][] chain = OpaqueMips.chain(w, h);
            System.arraycopy(rgba, 0, chain[0], 0, rgba.length);
            OpaqueMips.fill(chain, w, h);
            assertEquals(expected.length, chain.length, w + "x" + h + ": as many levels");
            for (int l = 0; l < chain.length; l++) {
                assertArrayEquals(expected[l], chain[l], w + "x" + h + ": level " + l);
            }
        }
    }

    @Test
    void aChainWrittenAgainHoldsOnlyTheNewImage() {
        int w = 64;
        int h = 48;
        byte[][] chain = OpaqueMips.chain(w, h);
        for (int pass = 0; pass < 2; pass++) {
            byte[] rgba = new byte[w * h * 4];
            new Random(pass).nextBytes(rgba);
            for (int p = 3; p < rgba.length; p += 4) {
                rgba[p] = (byte) 255;
            }
            System.arraycopy(rgba, 0, chain[0], 0, rgba.length);
            OpaqueMips.fill(chain, w, h);
            byte[][] expected = MipChain.of(rgba, w, h);
            for (int l = 1; l < chain.length; l++) {
                assertArrayEquals(expected[l], chain[l], "pass " + pass + ", level " + l);
            }
        }
    }
}
