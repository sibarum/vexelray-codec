package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * A 2D distance field, compressed: a shape, an icon or a glyph atlas as the canvas draws it, kept in a fraction of its
 * texture's bytes.
 *
 * <p>Each texel holds {@code 0.5 + d / (2 * spread)} as a byte, {@code d} in texels and positive inside, as
 * {@code vexelray-canvas}'s {@code DistanceField} does. That one writes the same byte into all four channels of an
 * RGBA8 texture, for the GPU; this keeps only the channels that differ, so a plain field is a quarter of the size
 * before packing, and a distance changes smoothly from texel to texel, so it deflates well after.
 *
 * <p>The kind is a name: {@value #SDF}, one channel; {@value #MSDF}, three (the median of which is the distance, as
 * text is drawn); {@value #MTSDF}, those three and a true distance in a fourth. A 3D field is a {@link Volume} with a
 * {@value Volume#DISTANCE} channel.
 *
 * @param name   what the asset calls it
 * @param kind   how many channels a texel has, and what they mean
 * @param width  texels across, margin included
 * @param height texels down, margin included
 * @param spread the margin round the shape, and the farthest distance the field holds, in texels
 * @param texels {@code width * height} texels of the kind's channel count, top row first
 */
@IntrabufMessage(name = "vexelray.asset.DistanceField", compat = Compat.FULL)
public record DistanceField(String name, @Field(nonNull = true) String kind, int width, int height, int spread,
                            @Field(nonNull = true) Packed texels) {

    public static final String SDF = "sdf";
    public static final String MSDF = "msdf";
    public static final String MTSDF = "mtsdf";

    /**
     * A plain field from the RGBA8 texture the canvas draws ({@code DistanceField.rgba()}): {@code inner} texels of
     * shape and {@code spread} round it, so {@code inner + 2 * spread} a side. Only the red channel is kept.
     */
    public static DistanceField ofRgba(String name, byte[] rgba, int inner, int spread) {
        int side = inner + 2 * spread;
        if (rgba.length != side * side * 4) {
            throw new IllegalArgumentException("a field " + side + " texels across is " + side * side * 4
                    + " bytes; was given " + rgba.length);
        }
        byte[] r = new byte[side * side];
        for (int i = 0; i < r.length; i++) {
            r[i] = rgba[i * 4];
        }
        return new DistanceField(name, SDF, side, side, spread, Packed.of(r, 1));
    }

    /** Channels a texel of {@code kind} has, or 0 for a kind this reader does not know. */
    public static int channels(String kind) {
        return switch (kind) {
            case SDF -> 1;
            case MSDF -> 3;
            case MTSDF -> 4;
            default -> 0;
        };
    }

    /** The shape's own extent across, without the margin: the canvas's {@code inner}. */
    public int inner() {
        return width - 2 * spread;
    }

    /** Whether this reader can read it: it knows the kind and the packing. */
    public boolean known() {
        return channels(kind) > 0 && texels.known();
    }

    /** The texels, {@link #channels} bytes each. */
    public byte[] unpacked() {
        int k = channels(kind);
        if (k == 0) {
            throw new IllegalStateException(name + " is a " + kind + " field, which this reader does not know");
        }
        byte[] t = texels.unpack();
        if (t.length != (long) width * height * k) {
            throw new IllegalStateException(name + " is " + t.length + " bytes, not " + width + " × " + height
                    + " × " + k);
        }
        return t;
    }

    /**
     * The RGBA8 texture the canvas uploads: a plain field's distance in every colour channel, or a multi-channel
     * field's in red, green and blue (and alpha, for {@value #MTSDF}); alpha opaque otherwise.
     */
    public byte[] rgba() {
        int k = channels(kind);
        byte[] t = unpacked();
        int n = width * height;
        byte[] out = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            byte first = t[i * k];
            out[i * 4] = first;
            out[i * 4 + 1] = k == 1 ? first : t[i * k + 1];
            out[i * 4 + 2] = k == 1 ? first : t[i * k + 2];
            out[i * 4 + 3] = k == 4 ? t[i * k + 3] : (byte) 0xFF;
        }
        return out;
    }
}
