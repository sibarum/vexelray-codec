package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Bulk bytes, as stored: the bytes, how they were packed, and how many there are unpacked. Every array in an asset
 * file (vertices, texels, voxels, SPIR-V words) is one of these.
 *
 * <p><b>The codec is a name, not an enum</b>, so a reader that meets one newer than itself can say which and leave that
 * one item unread, rather than fail the whole file the way an unknown enum ordinal would. {@link #known()} says whether
 * this reader can unpack it.
 *
 * <ul>
 *   <li>{@value #RAW}: the bytes as they are.</li>
 *   <li>{@value #DEFLATE}: zlib's deflate.</li>
 *   <li>{@code shuffle2-deflate}, {@code shuffle4-deflate}, {@code shuffle8-deflate}: the bytes of each 2-, 4- or
 *       8-byte element regrouped by position (every element's first byte, then every second byte, and so on), then
 *       deflated. A float's exponent bytes are alike from one to the next and its low bytes are not, so regrouped
 *       they compress far better: positions, normals and distances shrink by much more than deflate alone shrinks
 *       them.</li>
 * </ul>
 *
 * @param codec how {@code bytes} are packed
 * @param size  how many bytes they unpack to
 * @param bytes the bytes, packed. Live: do not write to them
 */
@IntrabufMessage(name = "vexelray.asset.Packed", compat = Compat.FULL)
public record Packed(@Field(nonNull = true) String codec, long size, @Field(nonNull = true) byte[] bytes) {

    /** Not packed. */
    public static final String RAW = "raw";

    /** Deflated. */
    public static final String DEFLATE = "deflate";

    private static final String SHUFFLE = "shuffle";
    private static final String SHUFFLED_DEFLATE = "-deflate";

    /** {@code data} as it is. */
    public static Packed raw(byte[] data) {
        return new Packed(RAW, data.length, data);
    }

    /**
     * {@code data} packed as small as this knows how: regrouped by byte position when its elements are {@code stride}
     * bytes (2, 4 or 8) and deflated, or deflated alone, or left raw when neither makes it smaller. Deterministic: the
     * same bytes pack to the same bytes.
     */
    public static Packed of(byte[] data, int stride) {
        if (data.length < 64) {
            return raw(data);
        }
        Packed best = raw(data);
        byte[] deflated = deflate(data);
        if (deflated.length < best.bytes.length) {
            best = new Packed(DEFLATE, data.length, deflated);
        }
        if ((stride == 2 || stride == 4 || stride == 8) && data.length % stride == 0) {
            byte[] shuffled = deflate(shuffle(data, stride));
            if (shuffled.length < best.bytes.length) {
                best = new Packed(SHUFFLE + stride + SHUFFLED_DEFLATE, data.length, shuffled);
            }
        }
        return best;
    }

    /** Whether this reader can unpack {@link #codec}. */
    public boolean known() {
        return codec.equals(RAW) || codec.equals(DEFLATE) || stride() > 0;
    }

    /**
     * The bytes, unpacked.
     *
     * @throws IllegalStateException when the codec is not one this reader knows, or the bytes do not unpack to
     *                               {@link #size}: the item is unreadable, the file is not
     */
    public byte[] unpack() {
        if (size < 0 || size > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("packed bytes of " + size + " cannot be held");
        }
        if (codec.equals(RAW)) {
            if (bytes.length != size) {
                throw new IllegalStateException(bytes.length + " raw bytes where " + size + " were recorded");
            }
            return bytes;
        }
        int stride = stride();
        if (!codec.equals(DEFLATE) && stride == 0) {
            throw new IllegalStateException("packed as " + codec + ", which this reader does not know");
        }
        byte[] out = inflate(bytes, (int) size);
        return stride == 0 ? out : unshuffle(out, stride);
    }

    @Override
    public String toString() {
        return "Packed[" + codec + ", " + bytes.length + " of " + size + " bytes]";
    }

    /** The element size of a shuffle codec, or 0 for any other. */
    private int stride() {
        return switch (codec) {
            case "shuffle2-deflate" -> 2;
            case "shuffle4-deflate" -> 4;
            case "shuffle8-deflate" -> 8;
            default -> 0;
        };
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] chunk = new byte[64 * 1024];
            while (!d.finished()) {
                out.write(chunk, 0, d.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    private static byte[] inflate(byte[] data, int size) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            byte[] out = new byte[size];
            int n = 0;
            while (n < size && !inf.finished()) {
                int got = inf.inflate(out, n, size - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += got;
            }
            if (n != size || !inf.finished()) {
                throw new IllegalStateException("deflated bytes unpack to other than the " + size + " recorded");
            }
            return out;
        } catch (DataFormatException e) {
            throw new IllegalStateException("deflated bytes are corrupt: " + e.getMessage());
        } finally {
            inf.end();
        }
    }

    static byte[] shuffle(byte[] data, int stride) {
        int n = data.length / stride;
        byte[] out = new byte[data.length];
        for (int i = 0; i < n; i++) {
            for (int b = 0; b < stride; b++) {
                out[b * n + i] = data[i * stride + b];
            }
        }
        return out;
    }

    static byte[] unshuffle(byte[] data, int stride) {
        if (data.length % stride != 0) {
            throw new IllegalStateException(data.length + " bytes are not whole " + stride + "-byte elements");
        }
        int n = data.length / stride;
        byte[] out = new byte[data.length];
        for (int i = 0; i < n; i++) {
            for (int b = 0; b < stride; b++) {
                out[i * stride + b] = data[b * n + i];
            }
        }
        return out;
    }
}
