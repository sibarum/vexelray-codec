package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * A texture map, 2D or 3D, with its mip chain if it has one: texels ready to upload, top row first, and for 3D the
 * slices in order of depth.
 *
 * <p>The format is a name, as a {@link Channel}'s type is: {@value #R8}, {@value #RG8}, {@value #RGBA8},
 * {@value #RGBA8_SRGB}, {@value #R16F}, {@value #RG16F}, {@value #RGBA16F}, {@value #R32F}, {@value #RGBA32F}.
 * Eight-bit colour is straight (not premultiplied) alpha, as the GUI uploads it.
 *
 * @param name   what the asset calls it
 * @param format how a texel is laid out
 * @param width  level 0's width in texels
 * @param height level 0's height
 * @param depth  level 0's depth: 1 for a 2D texture
 * @param levels level 0 first, then each half the last on every side (rounded down, never below 1)
 */
@IntrabufMessage(name = "vexelray.asset.Texture", compat = Compat.FULL)
public record Texture(String name, @Field(nonNull = true) String format, int width, int height, int depth,
                      @Field(nonNull = true) List<Packed> levels) {

    public static final String R8 = "r8";
    public static final String RG8 = "rg8";
    public static final String RGBA8 = "rgba8";
    /** RGBA8 whose colour is sRGB-encoded, as a photograph's is; alpha is linear. */
    public static final String RGBA8_SRGB = "rgba8-srgb";
    public static final String R16F = "r16f";
    public static final String RG16F = "rg16f";
    public static final String RGBA16F = "rgba16f";
    public static final String R32F = "r32f";
    public static final String RGBA32F = "rgba32f";

    public Texture {
        levels = List.copyOf(levels);
    }

    /** A 2D texture of one level. */
    public static Texture of2d(String name, String format, int width, int height, byte[] texels) {
        return of(name, format, width, height, 1, texels);
    }

    /** A texture of one level: {@code depth} 1 for 2D. */
    public static Texture of(String name, String format, int width, int height, int depth, byte[] texels) {
        int bytes = bytesPerTexel(format);
        if (bytes == 0) {
            throw new IllegalArgumentException("not a texture format: " + format);
        }
        if (texels.length != (long) width * height * depth * bytes) {
            throw new IllegalArgumentException(width + " × " + height + " × " + depth + " " + format + " is "
                    + (long) width * height * depth * bytes + " bytes; was given " + texels.length);
        }
        return new Texture(name, format, width, height, depth, List.of(Packed.of(texels, stride(format))));
    }

    /** The bytes a texel of {@code format} takes, or 0 for a format this reader does not know. */
    public static int bytesPerTexel(String format) {
        return switch (format) {
            case R8 -> 1;
            case RG8, R16F -> 2;
            case RGBA8, RGBA8_SRGB, RG16F, R32F -> 4;
            case RGBA16F -> 8;
            case RGBA32F -> 16;
            default -> 0;
        };
    }

    /** Whether it is 3D. */
    public boolean volumetric() {
        return depth > 1;
    }

    /** Whether this reader can read its texels: it knows the format and every level's packing. */
    public boolean known() {
        return bytesPerTexel(format) > 0 && levels.stream().allMatch(Packed::known);
    }

    /**
     * Level {@code level}'s texels.
     *
     * @throws IllegalStateException when the packing is unknown, or the texels are not the level's size
     */
    public byte[] texels(int level) {
        byte[] t = levels.get(level).unpack();
        long expected = (long) Math.max(1, width >> level) * Math.max(1, height >> level)
                * Math.max(1, depth >> level) * bytesPerTexel(format);
        if (t.length != expected) {
            throw new IllegalStateException("level " + level + " of " + name + " is " + t.length + " bytes, not "
                    + expected);
        }
        return t;
    }

    /** What a texel's numbers are, for regrouping before deflate: a half, a float, or a byte (no regrouping). */
    private static int stride(String format) {
        return switch (format) {
            case R16F, RG16F, RGBA16F -> 2;
            case R32F, RGBA32F -> 4;
            default -> 1;
        };
    }
}
