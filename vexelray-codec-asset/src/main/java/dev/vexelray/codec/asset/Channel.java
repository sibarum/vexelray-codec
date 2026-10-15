package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A named array of numbers, a fixed count of them per element: a mesh's normals, a point cloud's intensities, a
 * volume's densities. Little-endian, element after element, each element's components together.
 *
 * <p>The type is a name, like {@link Packed}'s codec and for the same reason:
 * {@value #U8}, {@value #I8}, {@value #U16}, {@value #I16}, {@value #U32}, {@value #I32}, {@value #F16},
 * {@value #F32}, {@value #F64}.
 *
 * @param name       what the values are: {@code position}, {@code normal}, {@code uv}, {@code color}, or the
 *                   writer's own name
 * @param type       each component's type
 * @param components numbers per element
 * @param data       the values
 */
@IntrabufMessage(name = "vexelray.asset.Channel", compat = Compat.FULL)
public record Channel(@Field(nonNull = true) String name, @Field(nonNull = true) String type, int components,
                      @Field(nonNull = true) Packed data) {

    public static final String U8 = "u8";
    public static final String I8 = "i8";
    public static final String U16 = "u16";
    public static final String I16 = "i16";
    public static final String U32 = "u32";
    public static final String I32 = "i32";
    /** IEEE half precision. */
    public static final String F16 = "f16";
    public static final String F32 = "f32";
    public static final String F64 = "f64";

    /** {@code values} as 32-bit floats, {@code components} to an element. */
    public static Channel f32(String name, int components, float[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(values);
        return new Channel(name, F32, components, Packed.of(b.array(), 4));
    }

    /** {@code values} as 32-bit signed integers, {@code components} to an element. */
    public static Channel i32(String name, int components, int[] values) {
        return new Channel(name, I32, components, Packed.of(ints(values), 4));
    }

    /** {@code values} as bytes, {@code components} to an element: a colour or a mask. */
    public static Channel u8(String name, int components, byte[] values) {
        return new Channel(name, U8, components, Packed.of(values, 1));
    }

    /** The bytes one component takes, or 0 for a type this reader does not know. */
    public static int size(String type) {
        return switch (type) {
            case U8, I8 -> 1;
            case U16, I16, F16 -> 2;
            case U32, I32, F32 -> 4;
            case F64 -> 8;
            default -> 0;
        };
    }

    /** Whether this reader can read the values: it knows the type and the codec. */
    public boolean known() {
        return size(type) > 0 && data.known();
    }

    /** How many elements. */
    public long count() {
        int s = size(type);
        return s == 0 || components == 0 ? 0 : data.size() / ((long) s * components);
    }

    /** The values unpacked, as stored. */
    public byte[] bytes() {
        return data.unpack();
    }

    /**
     * The values as floats, whatever their type: integers as their value (an 8-bit colour is 0 to 255, not 0 to 1),
     * halves and doubles converted.
     *
     * @throws IllegalStateException when the type or the codec is not one this reader knows
     */
    public float[] floats() {
        ByteBuffer b = buffer();
        int n = b.remaining() / size(type);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = switch (type) {
                case U8 -> b.get() & 0xFF;
                case I8 -> b.get();
                case U16 -> b.getShort() & 0xFFFF;
                case I16 -> b.getShort();
                case F16 -> Float.float16ToFloat(b.getShort());
                case U32 -> b.getInt() & 0xFFFFFFFFL;
                case I32 -> b.getInt();
                case F32 -> b.getFloat();
                default -> (float) b.getDouble();
            };
        }
        return out;
    }

    /**
     * The values as ints: integer types as they are (a {@value #U32} past 2<sup>31</sup> wraps), floats truncated.
     *
     * @throws IllegalStateException when the type or the codec is not one this reader knows
     */
    public int[] ints() {
        if (type.equals(I32) || type.equals(U32)) {
            ByteBuffer b = buffer();
            int[] out = new int[b.remaining() / 4];
            b.asIntBuffer().get(out);
            return out;
        }
        float[] f = floats();
        int[] out = new int[f.length];
        for (int i = 0; i < f.length; i++) {
            out[i] = (int) f[i];
        }
        return out;
    }

    @Override
    public String toString() {
        return "Channel[" + name + ", " + components + " × " + type + ", " + count() + "]";
    }

    private ByteBuffer buffer() {
        int s = size(type);
        if (s == 0) {
            throw new IllegalStateException(name + " is of type " + type + ", which this reader does not know");
        }
        byte[] raw = data.unpack();
        if (raw.length % s != 0) {
            throw new IllegalStateException(name + ": " + raw.length + " bytes are not whole " + type + " values");
        }
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] ints(int[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(values);
        return b.array();
    }
}
