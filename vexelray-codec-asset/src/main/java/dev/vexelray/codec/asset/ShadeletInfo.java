package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * One shadelet in a compiled composition, as its manifest entry has it ({@code Manifest.Entry} in vexelray-canvas),
 * with its style's layout spelled out: what a look that uses it must give, and where.
 *
 * @param name       unique in the composition and stable across builds; a look names the shadelet by it
 * @param role       {@value #SOURCE}, {@value #MODIFIER} or {@value #PAINTER}
 * @param id         the number the composed shader switches on
 * @param style      its parameters, in layout order
 * @param layoutHash the style layout's hash, as {@code StyleLayout.hash()} computes it: a look written for another
 *                   layout reads its parameters at the wrong offsets, and this is how that is caught
 */
@IntrabufMessage(name = "vexelray.asset.ShadeletInfo", compat = Compat.FULL)
public record ShadeletInfo(@Field(nonNull = true) String name, @Field(nonNull = true) String role, int id,
                           @Field(nonNull = true) List<StyleField> style, @Field(fixed = true) long layoutHash) {

    public static final String SOURCE = "source";
    public static final String MODIFIER = "modifier";
    public static final String PAINTER = "painter";

    public ShadeletInfo {
        style = List.copyOf(style);
    }

    /** Floats of its style in the style table. */
    public int sharedFloats() {
        return style.stream().filter(f -> !f.perInstance()).mapToInt(StyleField::width).sum();
    }

    /** Floats of its style in each element's record. */
    public int instanceFloats() {
        return style.stream().filter(StyleField::perInstance).mapToInt(StyleField::width).sum();
    }
}
