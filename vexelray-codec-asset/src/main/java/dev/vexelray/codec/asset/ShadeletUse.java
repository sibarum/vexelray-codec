package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * One shadelet in a {@link Look}, and its shared parameters: {@code Look.Use} in vexelray-canvas, with the layout it
 * was written for.
 *
 * @param shadelet   the shadelet's name
 * @param layoutHash its style layout's hash when the look was written, checked against the {@link Shader}'s
 * @param shared     its shared style floats, in layout order
 */
@IntrabufMessage(name = "vexelray.asset.ShadeletUse", compat = Compat.FULL)
public record ShadeletUse(@Field(nonNull = true) String shadelet, @Field(fixed = true) long layoutHash,
                          @Field(nonNull = true) List<Float> shared) {

    public ShadeletUse {
        shared = List.copyOf(shared);
    }

    /** {@code shadelet}, laid out as {@code layoutHash} says, with {@code shared} floats. */
    public static ShadeletUse of(String shadelet, long layoutHash, float... shared) {
        Float[] boxed = new Float[shared.length];
        for (int i = 0; i < shared.length; i++) {
            boxed[i] = shared[i];
        }
        return new ShadeletUse(shadelet, layoutHash, List.of(boxed));
    }

    /** The shared floats, unboxed: what {@code Look.Use} takes. */
    public float[] floats() {
        float[] out = new float[shared.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = shared.get(i);
        }
        return out;
    }
}
