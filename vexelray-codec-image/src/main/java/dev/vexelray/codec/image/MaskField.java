package dev.vexelray.codec.image;

import dev.vexelray.canvas.DistanceField;
import sibarum.imagelib.Imagelib;

/**
 * A mask made into a {@link DistanceField}, as a picture to upload: the part of making a field that needs a
 * rasteriser. The field's own logic is in {@code vexelray-canvas}, beside the shader that draws it; this rasterises an
 * SVG whose one fill is the shape (the icon set's {@code -mask.svg} files) at the size the field asks for, through
 * imagelib, and wraps the result as a {@link Picture} so {@code Textures.showData} can upload it.
 *
 * @param picture the field, as a picture to upload
 * @param field   the field itself: its size, margin and texels
 */
public record MaskField(Picture picture, DistanceField field) {

    /** The texels across the whole texture. */
    public int side() {
        return field.side();
    }

    /** The margin round the shape's square, in texels. */
    public int spread() {
        return field.spread();
    }

    /**
     * The field of the mask {@code svg}, its square {@code inner} texels across and {@code spread} texels of margin
     * round it. Keyed by {@code key} as any picture is. Null if the mask does not rasterise. Blocking.
     */
    public static MaskField of(Object key, byte[] svg, int inner, int spread) {
        if (inner <= 0 || spread < 0) {
            throw new IllegalArgumentException("a field of " + inner + " texels with " + spread + " round it");
        }
        byte[] mask;
        int fine = DistanceField.maskSide(inner);
        try {
            mask = Imagelib.rasterize(svg, fine, fine).rgba(0);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        DistanceField f = DistanceField.of(mask, inner, spread);
        int side = f.side();
        return new MaskField(new Picture(key, f.rgba(), side, side, 1, 1, new int[1], inner, inner, true), f);
    }
}
