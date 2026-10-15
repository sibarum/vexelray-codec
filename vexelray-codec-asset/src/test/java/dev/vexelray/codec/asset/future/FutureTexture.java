package dev.vexelray.codec.asset.future;

import dev.vexelray.codec.asset.Packed;
import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/** {@code Texture} at a later minor: layers, for an array texture, on the end. */
@IntrabufMessage(name = "vexelray.asset.Texture", minor = 1, compat = Compat.FULL)
public record FutureTexture(String name, @Field(nonNull = true) String format, int width, int height, int depth,
                            @Field(nonNull = true) List<Packed> levels, @Field(since = 1) Integer layers) {
}
