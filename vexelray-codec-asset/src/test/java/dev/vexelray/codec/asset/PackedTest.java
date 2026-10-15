package dev.vexelray.codec.asset;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedTest {

    @Test
    void smallBytesStayRaw() {
        Packed p = Packed.of(new byte[]{1, 2, 3}, 1);
        assertEquals(Packed.RAW, p.codec());
        assertArrayEquals(new byte[]{1, 2, 3}, p.unpack());
    }

    @Test
    void floatsAreRegroupedBeforeDeflate() {
        // A smooth surface's coordinates: alike in their high bytes, not in their low ones.
        float[] f = new float[3000];
        for (int i = 0; i < f.length; i++) {
            f[i] = (float) Math.sin(i * 0.01) * 10;
        }
        ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(f);
        byte[] raw = b.array();

        Packed p = Packed.of(raw, 4);
        assertEquals("shuffle4-deflate", p.codec());
        assertTrue(p.bytes().length < Packed.of(raw, 1).bytes().length, "smaller than deflate alone");
        assertArrayEquals(raw, p.unpack());
        assertEquals(raw.length, p.size());
    }

    @Test
    void incompressibleBytesStayRaw() {
        byte[] noise = new byte[4096];
        new java.util.Random(7).nextBytes(noise);
        assertEquals(Packed.RAW, Packed.of(noise, 4).codec());
    }

    @Test
    void shuffleIsItsOwnInverse() {
        byte[] b = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        assertArrayEquals(new byte[]{1, 5, 9, 2, 6, 10, 3, 7, 11, 4, 8, 12}, Packed.shuffle(b, 4));
        assertArrayEquals(b, Packed.unshuffle(Packed.shuffle(b, 4), 4));
    }

    @Test
    void aCodecFromTheFutureIsUnknownNotAnError() {
        Packed p = new Packed("zstd", 10, new byte[]{1, 2, 3});
        assertFalse(p.known());
        IllegalStateException e = assertThrows(IllegalStateException.class, p::unpack);
        assertEquals("packed as zstd, which this reader does not know", e.getMessage());
    }

    @Test
    void bytesThatDoNotUnpackToTheirSizeAreRefused() {
        Packed good = Packed.of(new byte[1000], 1);
        assertEquals(Packed.DEFLATE, good.codec());
        Packed lying = new Packed(Packed.DEFLATE, 999, good.bytes());
        assertThrows(IllegalStateException.class, lying::unpack);
        assertThrows(IllegalStateException.class, () -> new Packed(Packed.RAW, 4, new byte[3]).unpack());
    }

    @Test
    void channelsReadBackAsNumbers() {
        Channel c = Channel.f32("density", 2, new float[]{1.5f, -2, 3, 4});
        assertEquals(2, c.count());
        assertArrayEquals(new float[]{1.5f, -2, 3, 4}, c.floats());
        assertArrayEquals(new int[]{1, -2, 3, 4}, c.ints());
        Channel u = Channel.u8("mask", 1, new byte[]{(byte) 255, 0});
        assertArrayEquals(new float[]{255, 0}, u.floats(), "a byte is its value, not a share of 255");
        Channel future = new Channel("x", "bf16", 1, Packed.raw(new byte[2]));
        assertFalse(future.known());
        assertThrows(IllegalStateException.class, future::floats);
    }
}
