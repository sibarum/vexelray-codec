package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One compiled stage of a composition: SPIR-V for one host's vertex or fragment stage.
 *
 * @param host       which ubershader it is: {@value #CANVAS} or {@value #PANEL}
 * @param stage      {@value #VERTEX} or {@value #FRAGMENT}
 * @param entryPoint the SPIR-V entry point's name
 * @param spirv      the module's words, little-endian
 */
@IntrabufMessage(name = "vexelray.asset.ShaderStage", compat = Compat.FULL)
public record ShaderStage(@Field(nonNull = true) String host, @Field(nonNull = true) String stage,
                          @Field(nonNull = true) String entryPoint, @Field(nonNull = true) Packed spirv) {

    /** The 2D canvas's ubershader. */
    public static final String CANVAS = "canvas";
    /** The same geometry on a plane in the world, as vexelray-technique-panel draws it. */
    public static final String PANEL = "panel";
    public static final String VERTEX = "vertex";
    public static final String FRAGMENT = "fragment";

    /** The SPIR-V magic number, the first word of every module. */
    public static final int SPIRV_MAGIC = 0x07230203;

    /** A stage from its SPIR-V words. */
    public static ShaderStage of(String host, String stage, String entryPoint, int[] words) {
        if (words.length == 0 || words[0] != SPIRV_MAGIC) {
            throw new IllegalArgumentException("not SPIR-V: the first word is not 0x07230203");
        }
        return new ShaderStage(host, stage, entryPoint, Packed.of(Channel.ints(words), 4));
    }

    /** The module's words. */
    public int[] words() {
        byte[] b = spirv.unpack();
        if (b.length % 4 != 0) {
            throw new IllegalStateException(host + " " + stage + ": " + b.length + " bytes are not whole words");
        }
        int[] out = new int[b.length / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
        return out;
    }
}
