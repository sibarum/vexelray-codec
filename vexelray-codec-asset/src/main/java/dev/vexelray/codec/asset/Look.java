package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * How something is drawn, by shadelets: a source of distance, the modifiers that reshape it and the painters that
 * colour it, front to back, each with its parameters. {@code Look} in vexelray-canvas, with a name and a subject so
 * an asset can say how its own pieces are meant to be drawn.
 *
 * @param name      what the asset calls it
 * @param subject   the name of the item in the same asset it draws (a distance field, a texture), or null for a look
 *                  that stands alone
 * @param source    the source of distance
 * @param modifiers the modifiers, in order
 * @param painters  the painters, front to back
 */
@IntrabufMessage(name = "vexelray.asset.Look", compat = Compat.FULL)
public record Look(String name, String subject, @Field(nonNull = true) ShadeletUse source,
                   @Field(nonNull = true) List<ShadeletUse> modifiers,
                   @Field(nonNull = true) List<ShadeletUse> painters) {

    public Look {
        modifiers = List.copyOf(modifiers);
        painters = List.copyOf(painters);
    }
}
