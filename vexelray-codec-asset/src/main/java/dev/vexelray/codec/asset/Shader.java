package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * A compiled shadelet composition: the ubershader an application composed from the shadelets it draws with, as
 * SPIR-V for each host and stage, and the manifest that says which shadelets it holds and how their styles are laid
 * out. With it, an asset can carry the shader it is drawn with, and a {@link Look} can be checked against it.
 *
 * @param name       what the asset calls it
 * @param stages     each host's vertex and fragment stages
 * @param shadelets  the manifest: every shadelet composed in, in id order
 */
@IntrabufMessage(name = "vexelray.asset.Shader", compat = Compat.FULL)
public record Shader(String name, @Field(nonNull = true) List<ShaderStage> stages,
                     @Field(nonNull = true) List<ShadeletInfo> shadelets) {

    public Shader {
        stages = List.copyOf(stages);
        shadelets = List.copyOf(shadelets);
    }

    /** The stage for {@code host} and {@code stage}, or null. */
    public ShaderStage stage(String host, String stage) {
        for (ShaderStage s : stages) {
            if (s.host().equals(host) && s.stage().equals(stage)) {
                return s;
            }
        }
        return null;
    }

    /** The shadelet called {@code name}, or null. */
    public ShadeletInfo shadelet(String name) {
        for (ShadeletInfo s : shadelets) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Why {@code look} cannot be drawn with this shader, or null when it can: every shadelet it uses is here, in the
     * role it is used in, with the layout and the number of shared floats it was written for.
     */
    public String mismatch(Look look) {
        String m = mismatch(look.source(), ShadeletInfo.SOURCE);
        for (ShadeletUse u : look.modifiers()) {
            m = m != null ? m : mismatch(u, ShadeletInfo.MODIFIER);
        }
        for (ShadeletUse u : look.painters()) {
            m = m != null ? m : mismatch(u, ShadeletInfo.PAINTER);
        }
        return m;
    }

    private String mismatch(ShadeletUse use, String role) {
        ShadeletInfo s = shadelet(use.shadelet());
        if (s == null) {
            return "no shadelet " + use.shadelet();
        }
        if (!s.role().equals(role)) {
            return use.shadelet() + " is a " + s.role() + ", used as a " + role;
        }
        if (use.layoutHash() != s.layoutHash()) {
            return use.shadelet() + "'s style was laid out otherwise when the look was written";
        }
        if (use.shared().size() != s.sharedFloats()) {
            return use.shadelet() + " takes " + s.sharedFloats() + " shared floats, given " + use.shared().size();
        }
        return null;
    }
}
