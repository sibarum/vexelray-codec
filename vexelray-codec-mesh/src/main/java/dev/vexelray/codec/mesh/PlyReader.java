package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PLY, ASCII or binary either way round: the format whose header names what each element carries, so every property
 * a file declares is kept.
 *
 * <p>The {@code vertex} element's {@code x y z} are the positions, {@code nx ny nz} the normals, {@code u v} (or
 * {@code s t}, or {@code texture_u texture_v}) the uvs, {@code red green blue} and {@code alpha} the colour. The
 * {@code face} element's {@code vertex_indices} (or {@code vertex_index}) are the faces, and a {@code texcoord} list
 * of two numbers a corner the corners' uvs. The {@code edge} element's {@code vertex1 vertex2} are the edges. Every
 * other scalar property of those three is an attribute of its own name on the vertices, faces or edges; a colour on
 * a face or an edge is that face's or edge's {@link Attribute#COLOR}.
 *
 * <p>Not kept, for now: list properties other than those two, and elements other than those three (a scan's
 * {@code range_grid}, a material list). They are read past, so the elements after them are read right.
 */
final class PlyReader {

    private PlyReader() {
    }

    private enum Type {
        INT8(1, true), UINT8(1, true), INT16(2, true), UINT16(2, true), INT32(4, true), UINT32(4, true),
        FLOAT32(4, false), FLOAT64(8, false);

        final int size;
        final boolean integer;

        Type(int size, boolean integer) {
            this.size = size;
            this.integer = integer;
        }

        static Type of(String s) {
            return switch (s) {
                case "char", "int8" -> INT8;
                case "uchar", "uint8" -> UINT8;
                case "short", "int16" -> INT16;
                case "ushort", "uint16" -> UINT16;
                case "int", "int32" -> INT32;
                case "uint", "uint32" -> UINT32;
                case "float", "float32" -> FLOAT32;
                case "double", "float64" -> FLOAT64;
                default -> null;
            };
        }

        /** What an integer colour channel of this type is divided by to land in [0,1]. */
        float full() {
            return switch (this) {
                case UINT8 -> 255f;
                case UINT16 -> 65535f;
                default -> 1f;
            };
        }
    }

    /** A property: scalar when {@code count} is null, a list with a {@code count}-typed length when not. */
    private record Property(String name, Type type, Type count) {
    }

    private static final class Element {
        final String name;
        final int count;
        final List<Property> properties = new ArrayList<>();
        /** Scalar properties' values, one array per property (null for a list), filled as the body is read. */
        double[][] scalars;
        /** List properties' lengths per element and their values end to end (null for a scalar). */
        Grow.Ints[] lengths;
        Grow.Floats[] lists;
        /** Integer-typed lists' values, which are indices and must stay exact past 2^24 (null for any other). */
        Grow.Ints[] ints;

        Element(String name, int count) {
            this.name = name;
            this.count = count;
        }

        int index(String property) {
            for (int i = 0; i < properties.size(); i++) {
                if (properties.get(i).name.equals(property)) {
                    return i;
                }
            }
            return -1;
        }
    }

    static Mesh read(byte[] bytes) throws Malformed {
        // The header is ASCII lines up to end_header; the body starts the byte after its line break.
        Text t = new Text(bytes, 0, bytes.length, -1);
        String format = null;
        List<Element> elements = new ArrayList<>();
        int bodyAt = -1;
        int offset = 0;
        while (t.nextLine()) {
            int lineEnd = indexOf(bytes, (byte) '\n', offset);
            int next = lineEnd < 0 ? bytes.length : lineEnd + 1;
            if (t.size() == 0) {
                offset = next;
                continue;
            }
            String key = t.token(0);
            switch (key) {
                case "ply", "comment", "obj_info" -> {
                }
                case "format" -> {
                    if (t.size() < 2) {
                        throw Malformed.at(t.line(), "format names no encoding");
                    }
                    format = t.token(1);
                }
                case "element" -> {
                    if (t.size() < 3) {
                        throw Malformed.at(t.line(), "an element needs a name and a count");
                    }
                    int count = t.integer(2, "an element count");
                    if (count < 0) {
                        throw Malformed.at(t.line(), "an element count is negative");
                    }
                    elements.add(new Element(t.token(1), count));
                }
                case "property" -> {
                    if (elements.isEmpty()) {
                        throw Malformed.at(t.line(), "a property before any element");
                    }
                    elements.getLast().properties.add(property(t));
                }
                case "end_header" -> bodyAt = next;
                default -> throw Malformed.at(t.line(), "the header has a line PLY does not: " + Text.clip(key));
            }
            offset = next;
            if (bodyAt >= 0) {
                break;
            }
        }
        if (bodyAt < 0) {
            throw new Malformed("the header has no end_header");
        }
        if (format == null) {
            throw new Malformed("the header names no format");
        }
        for (Element e : elements) {
            // Every value is at least a byte, so an element count past the body's length is a lie to refuse before
            // allocating for it.
            if ((long) e.count * e.properties.size() > bytes.length - bodyAt) {
                throw new Malformed(e.name + ": " + e.count + " elements cannot fit in what is left of the file");
            }
            e.scalars = new double[e.properties.size()][];
            e.lengths = new Grow.Ints[e.properties.size()];
            e.lists = new Grow.Floats[e.properties.size()];
            e.ints = new Grow.Ints[e.properties.size()];
            for (int p = 0; p < e.properties.size(); p++) {
                Property prop = e.properties.get(p);
                if (prop.count == null) {
                    e.scalars[p] = new double[e.count];
                } else {
                    e.lengths[p] = new Grow.Ints();
                    if (prop.type.integer) {
                        e.ints[p] = new Grow.Ints();
                    } else {
                        e.lists[p] = new Grow.Floats();
                    }
                }
            }
        }

        switch (format) {
            case "ascii" -> ascii(new Text(bytes, bodyAt, bytes.length, -1), elements);
            case "binary_little_endian" -> binary(ByteBuffer.wrap(bytes, bodyAt, bytes.length - bodyAt)
                    .order(ByteOrder.LITTLE_ENDIAN), elements);
            case "binary_big_endian" -> binary(ByteBuffer.wrap(bytes, bodyAt, bytes.length - bodyAt)
                    .order(ByteOrder.BIG_ENDIAN), elements);
            default -> throw new Malformed("not a PLY encoding: " + Text.clip(format));
        }
        return assemble(elements);
    }

    private static Property property(Text t) throws Malformed {
        if (t.size() >= 2 && t.is(1, "list")) {
            if (t.size() < 5) {
                throw Malformed.at(t.line(), "a list property needs a count type, a value type and a name");
            }
            Type count = type(t, 2);
            if (!count.integer) {
                throw Malformed.at(t.line(), "a list's count is a " + t.token(2) + ", not a whole number");
            }
            return new Property(t.token(4), type(t, 3), count);
        }
        if (t.size() < 3) {
            throw Malformed.at(t.line(), "a property needs a type and a name");
        }
        return new Property(t.token(2), type(t, 1), null);
    }

    private static Type type(Text t, int i) throws Malformed {
        Type type = Type.of(t.token(i));
        if (type == null) {
            throw Malformed.at(t.line(), "not a PLY type: " + Text.clip(t.token(i)));
        }
        return type;
    }

    private static void ascii(Text t, List<Element> elements) throws Malformed {
        for (Element e : elements) {
            for (int i = 0; i < e.count; i++) {
                for (int p = 0; p < e.properties.size(); p++) {
                    Property prop = e.properties.get(p);
                    if (prop.count == null) {
                        e.scalars[p][i] = t.nextValue(e.name + " " + i + "'s " + prop.name);
                        continue;
                    }
                    double n = t.nextValue(e.name + " " + i + "'s " + prop.name + " length");
                    if (n < 0 || n != (int) n) {
                        throw Malformed.at(t.line(), e.name + " " + i + "'s " + prop.name + " has length " + n);
                    }
                    e.lengths[p].add((int) n);
                    for (int k = 0; k < (int) n; k++) {
                        double value = t.nextValue(e.name + " " + i + "'s " + prop.name);
                        if (e.ints[p] != null) {
                            e.ints[p].add((int) value);
                        } else {
                            e.lists[p].add((float) value);
                        }
                    }
                }
            }
        }
    }

    private static void binary(ByteBuffer in, List<Element> elements) throws Malformed {
        try {
            for (Element e : elements) {
                for (int i = 0; i < e.count; i++) {
                    for (int p = 0; p < e.properties.size(); p++) {
                        Property prop = e.properties.get(p);
                        if (prop.count == null) {
                            e.scalars[p][i] = value(in, prop.type);
                            continue;
                        }
                        double n = value(in, prop.count);
                        if (n < 0 || n * prop.type.size > in.remaining()) {
                            throw new Malformed(e.name + " " + i + "'s " + prop.name + " has length " + (long) n
                                    + ", past the end of the file");
                        }
                        e.lengths[p].add((int) n);
                        for (int k = 0; k < (int) n; k++) {
                            double value = value(in, prop.type);
                            if (e.ints[p] != null) {
                                e.ints[p].add((int) value);
                            } else {
                                e.lists[p].add((float) value);
                            }
                        }
                    }
                }
            }
        } catch (BufferUnderflowException x) {
            throw new Malformed("the file ends before the elements its header declares");
        }
    }

    private static double value(ByteBuffer in, Type type) {
        return switch (type) {
            case INT8 -> in.get();
            case UINT8 -> in.get() & 0xFF;
            case INT16 -> in.getShort();
            case UINT16 -> in.getShort() & 0xFFFF;
            case INT32 -> in.getInt();
            case UINT32 -> in.getInt() & 0xFFFFFFFFL;
            case FLOAT32 -> in.getFloat();
            case FLOAT64 -> in.getDouble();
        };
    }

    private static Mesh assemble(List<Element> elements) throws Malformed {
        Element vertex = find(elements, "vertex");
        if (vertex == null) {
            throw new Malformed("no vertex element");
        }
        int x = vertex.index("x");
        int y = vertex.index("y");
        int z = vertex.index("z");
        if (x < 0 || y < 0 || z < 0 || vertex.scalars[x] == null || vertex.scalars[y] == null
                || vertex.scalars[z] == null) {
            throw new Malformed("the vertex element has no x, y and z");
        }
        Mesh.Builder b = Mesh.builder().positions(interleave(vertex, x, y, z));
        Set<Integer> used = new HashSet<>(List.of(x, y, z));
        named(b, vertex, Scope.VERTEX, Attribute.NORMAL, used, "nx", "ny", "nz");
        if (!named(b, vertex, Scope.VERTEX, Attribute.UV, used, "u", "v")
                && !named(b, vertex, Scope.VERTEX, Attribute.UV, used, "s", "t")) {
            named(b, vertex, Scope.VERTEX, Attribute.UV, used, "texture_u", "texture_v");
        }
        color(b, vertex, Scope.VERTEX, used);
        rest(b, vertex, Scope.VERTEX, used);

        Element face = find(elements, "face");
        if (face != null) {
            int at = face.index("vertex_indices");
            if (at < 0) {
                at = face.index("vertex_index");
            }
            if (at < 0 || face.ints[at] == null) {
                throw new Malformed("the face element has no vertex_indices list of whole numbers");
            }
            Grow.Ints lengths = face.lengths[at];
            int[] corners = face.ints[at].raw();
            int from = 0;
            for (int f = 0; f < lengths.size(); f++) {
                int n = lengths.get(f);
                if (n < 3) {
                    throw new Malformed("face " + f + " has " + n + " corners; a face needs three");
                }
                b.face(corners, from, n);
                from += n;
            }
            Set<Integer> faceUsed = new HashSet<>(List.of(at));
            int uv = face.index("texcoord");
            if (uv >= 0 && face.lists[uv] != null) {
                Grow.Ints uvLengths = face.lengths[uv];
                boolean fits = uvLengths.size() == lengths.size();
                for (int f = 0; fits && f < lengths.size(); f++) {
                    fits = uvLengths.get(f) == 2 * lengths.get(f);
                }
                if (fits) {
                    b.attribute(Attribute.UV, Scope.CORNER, 2, face.lists[uv].array());
                    faceUsed.add(uv);
                }
            }
            color(b, face, Scope.FACE, faceUsed);
            rest(b, face, Scope.FACE, faceUsed);
        }

        Element edge = find(elements, "edge");
        if (edge != null) {
            int a = edge.index("vertex1");
            int c = edge.index("vertex2");
            if (a < 0 || c < 0 || edge.scalars[a] == null || edge.scalars[c] == null) {
                throw new Malformed("the edge element has no vertex1 and vertex2");
            }
            for (int i = 0; i < edge.count; i++) {
                b.edge((int) edge.scalars[a][i], (int) edge.scalars[c][i]);
            }
            Set<Integer> edgeUsed = new HashSet<>(List.of(a, c));
            color(b, edge, Scope.EDGE, edgeUsed);
            rest(b, edge, Scope.EDGE, edgeUsed);
        }
        return b.build();
    }

    /** The properties called {@code names}, all scalar, interleaved as one attribute. False when any is missing. */
    private static boolean named(Mesh.Builder b, Element e, Scope scope, String name, Set<Integer> used,
                                 String... names) {
        int[] at = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            at[i] = e.index(names[i]);
            if (at[i] < 0 || e.scalars[at[i]] == null) {
                return false;
            }
        }
        b.attribute(name, scope, names.length, interleave(e, at));
        for (int i : at) {
            used.add(i);
        }
        return true;
    }

    /** {@code red green blue} and an {@code alpha} if there is one, each in [0,1]. */
    private static void color(Mesh.Builder b, Element e, Scope scope, Set<Integer> used) {
        int[] rgb = {e.index("red"), e.index("green"), e.index("blue")};
        int alpha = e.index("alpha");
        if (rgb[0] < 0 || rgb[1] < 0 || rgb[2] < 0) {
            rgb = new int[]{e.index("diffuse_red"), e.index("diffuse_green"), e.index("diffuse_blue")};
            alpha = -1;
        }
        for (int i : rgb) {
            if (i < 0 || e.scalars[i] == null) {
                return;
            }
        }
        int[] at = alpha >= 0 && e.scalars[alpha] != null ? new int[]{rgb[0], rgb[1], rgb[2], alpha} : rgb;
        float[] values = interleave(e, at);
        for (int c = 0; c < at.length; c++) {
            float full = e.properties.get(at[c]).type.full();
            if (full != 1f) {
                for (int i = c; i < values.length; i += at.length) {
                    values[i] /= full;
                }
            }
            used.add(at[c]);
        }
        b.attribute(Attribute.COLOR, scope, at.length, values);
    }

    /** Every scalar property not yet {@code used}, an attribute of its own name. */
    private static void rest(Mesh.Builder b, Element e, Scope scope, Set<Integer> used) {
        for (int p = 0; p < e.properties.size(); p++) {
            if (used.contains(p) || e.scalars[p] == null) {
                continue;
            }
            String name = e.properties.get(p).name;
            // A property that happens to have a well-known name, such as a scalar "color", keeps its element's
            // name in front, so it is never mistaken for the attribute every reader means by it.
            if (name.equals(Attribute.POSITION) || name.equals(Attribute.NORMAL) || name.equals(Attribute.UV)
                    || name.equals(Attribute.COLOR)) {
                name = e.name + "." + name;
            }
            b.attribute(name, scope, 1, interleave(e, p));
        }
    }

    private static float[] interleave(Element e, int... at) {
        int k = at.length;
        float[] out = new float[e.count * k];
        for (int c = 0; c < k; c++) {
            double[] src = e.scalars[at[c]];
            for (int i = 0; i < e.count; i++) {
                out[i * k + c] = (float) src[i];
            }
        }
        return out;
    }

    private static Element find(List<Element> elements, String name) {
        for (Element e : elements) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static int indexOf(byte[] b, byte c, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == c) {
                return i;
            }
        }
        return -1;
    }

    static boolean magic(byte[] b) {
        return b.length >= 4 && new String(b, 0, 3, StandardCharsets.ISO_8859_1).equals("ply")
                && (b[3] == '\n' || b[3] == '\r');
    }
}
