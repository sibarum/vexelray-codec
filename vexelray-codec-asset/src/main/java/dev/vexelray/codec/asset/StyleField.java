package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * One parameter of a shadelet's style, as {@code StyleLayout.Field} in vexelray-canvas has it.
 *
 * @param name        the parameter
 * @param width       floats it takes: 1 for a number, 4 for a colour
 * @param perInstance in each element's own record, rather than the style table
 */
@IntrabufMessage(name = "vexelray.asset.StyleField", compat = Compat.FULL)
public record StyleField(@Field(nonNull = true) String name, int width, boolean perInstance) {
}
