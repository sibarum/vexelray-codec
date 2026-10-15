package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * STL, binary or ASCII: triangles, each with its own three corners and a facet normal.
 *
 * <p>STL has no shared vertices, so it has no topology to keep, and a mesh of unconnected triangles would have none
 * of the edges or neighbours a caller reads a mesh for. So corners that are the same point, bit for bit (and
 * {@code -0} the same as {@code 0}), are made one vertex. That is no guess: a file that wrote the same three numbers
 * twice meant the same point. Corners that are only near each other are left apart.
 *
 * <p>The facet normal is the faces' {@link Attribute#NORMAL}, as the file gives it, zero where the file left it to be
 * worked out. A binary file's two attribute bytes a triangle, which some programs use for a colour and most leave
 * zero, are an {@code stl_attribute} on the faces when any is not zero.
 */
final class StlReader {

    private StlReader() {
    }

    /** Whether {@code b} is exactly as long as the binary STL its triangle count says, whatever its header says. */
    static boolean binary(byte[] b) {
        if (b.length < 84) {
            return false;
        }
        long n = ByteBuffer.wrap(b, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        return 84 + 50 * n == b.length;
    }

    /** Whether {@code b} starts the way an ASCII STL does, with {@code solid}. */
    static boolean ascii(byte[] b) {
        int i = 0;
        while (i < b.length && (b[i] & 0xFF) <= ' ') {
            i++;
        }
        if (b.length - i < 5 || b[i] != 's' || b[i + 1] != 'o' || b[i + 2] != 'l' || b[i + 3] != 'i'
                || b[i + 4] != 'd') {
            return false;
        }
        return b.length - i == 5 || (b[i + 5] & 0xFF) <= ' ';
    }

    static Mesh read(byte[] b) throws Malformed {
        return binary(b) ? readBinary(b) : readAscii(b);
    }

    private static Mesh readBinary(byte[] b) {
        ByteBuffer in = ByteBuffer.wrap(b, 84, b.length - 84).order(ByteOrder.LITTLE_ENDIAN);
        int n = (b.length - 84) / 50;
        Weld weld = new Weld();
        Mesh.Builder m = Mesh.builder();
        float[] normals = new float[3 * n];
        float[] attributes = new float[n];
        boolean anyAttribute = false;
        int[] face = new int[3];
        for (int t = 0; t < n; t++) {
            normals[3 * t] = in.getFloat();
            normals[3 * t + 1] = in.getFloat();
            normals[3 * t + 2] = in.getFloat();
            for (int c = 0; c < 3; c++) {
                face[c] = weld.at(in.getFloat(), in.getFloat(), in.getFloat());
            }
            int attribute = in.getShort() & 0xFFFF;
            attributes[t] = attribute;
            anyAttribute |= attribute != 0;
            m.face(face);
        }
        m.positions(weld.positions.array()).attribute(Attribute.NORMAL, Scope.FACE, 3, normals);
        if (anyAttribute) {
            m.attribute("stl_attribute", Scope.FACE, 1, attributes);
        }
        return m.build();
    }

    private static Mesh readAscii(byte[] b) throws Malformed {
        Text t = new Text(b, 0, b.length, -1);
        Weld weld = new Weld();
        Mesh.Builder m = Mesh.builder();
        Grow.Floats normals = new Grow.Floats();
        Grow.Ints face = new Grow.Ints();
        String token;
        while ((token = t.nextToken()) != null) {
            switch (token) {
                case "solid", "endsolid" -> {
                    while (t.moreOnLine()) {
                        t.nextToken();      // the solid's name
                    }
                }
                case "facet" -> {
                    expect(t, "normal");
                    normals.add(t.nextNumber("a normal's x"), t.nextNumber("a normal's y"),
                            t.nextNumber("a normal's z"));
                    expect(t, "outer");
                    expect(t, "loop");
                    int start = face.size();
                    String next;
                    while ("vertex".equals(next = t.nextToken())) {
                        face.add(weld.at(t.nextNumber("a vertex's x"), t.nextNumber("a vertex's y"),
                                t.nextNumber("a vertex's z")));
                    }
                    if (!"endloop".equals(next)) {
                        throw Malformed.at(t.line(), "expected vertex or endloop, found " + quote(next));
                    }
                    expect(t, "endfacet");
                    int corners = face.size() - start;
                    if (corners < 3) {
                        throw Malformed.at(t.line(), "a facet has " + corners + " vertices; it needs three");
                    }
                    m.face(face.raw(), start, corners);
                }
                default -> throw Malformed.at(t.line(), "expected facet or endsolid, found " + quote(token));
            }
        }
        return m.positions(weld.positions.array())
                .attribute(Attribute.NORMAL, Scope.FACE, 3, normals.array())
                .build();
    }

    private static void expect(Text t, String word) throws Malformed {
        String found = t.nextToken();
        if (!word.equals(found)) {
            throw Malformed.at(t.line(), "expected " + word + ", found " + quote(found));
        }
    }

    private static String quote(String token) {
        return token == null ? "the end of the file" : Text.clip(token);
    }

    /** Corners made vertices: the same point, bit for bit, is the same vertex. */
    private static final class Weld {
        private final Map<Point, Integer> seen = new HashMap<>();
        final Grow.Floats positions = new Grow.Floats();

        int at(float x, float y, float z) {
            Point p = new Point(bits(x), bits(y), bits(z));
            Integer i = seen.get(p);
            if (i == null) {
                i = seen.size();
                seen.put(p, i);
                positions.add(x, y, z);
            }
            return i;
        }

        private static int bits(float v) {
            return v == 0 ? 0 : Float.floatToIntBits(v);
        }
    }

    private record Point(int x, int y, int z) {
    }
}
