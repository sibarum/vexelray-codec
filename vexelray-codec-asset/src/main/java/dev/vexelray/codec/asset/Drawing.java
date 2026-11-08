package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * A drawing in signed distance graphics: full-colour layers, each a distance field and a paint, sharp at any size
 * (vexelray-fields' {@code docs/sdg.md}). Held as its compiled form, an {@code .sdgb} file's bytes, which vexelray-fields'
 * {@code SdgFile.read} reads: the drawing has a format of its own, which grows by its own rules, so the asset carries
 * it whole rather than a second copy of its schema.
 *
 * @param name what the asset calls it
 * @param sdgb the drawing, compiled: {@code SdgFile.bytes}
 */
@IntrabufMessage(name = "vexelray.asset.Drawing", compat = Compat.FULL)
public record Drawing(String name, @Field(nonNull = true) byte[] sdgb) {

    public Drawing {
        sdgb = sdgb.clone();
    }

    /** The compiled drawing, a copy. */
    @Override
    public byte[] sdgb() {
        return sdgb.clone();
    }
}
