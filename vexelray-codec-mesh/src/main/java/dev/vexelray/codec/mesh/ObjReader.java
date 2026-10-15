package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;

import java.util.Arrays;

/**
 * Wavefront OBJ: the geometry of it. {@code v} (with the common {@code v x y z r g b} vertex colour), {@code vt},
 * {@code vn}, {@code f} and {@code l}; indices from 1, or negative from the latest. Everything else, groups, objects,
 * smoothing groups, materials and curves, is passed over for now.
 *
 * <p>OBJ indexes positions, texture coordinates and normals separately, so a vertex may have one uv in one face and
 * another in the next. Where every face agrees on each vertex's uv (or normal), it is kept on the vertex; where any
 * does not, it is kept on the corners, and the vertex stays one vertex either way. A corner that gives none, in a file
 * where others do, is {@code NaN}.
 */
final class ObjReader {

    private ObjReader() {
    }

    static Mesh read(byte[] bytes) throws Malformed {
        Text t = new Text(bytes, 0, bytes.length, '#');
        Grow.Floats v = new Grow.Floats();
        Grow.Floats colors = new Grow.Floats();
        Grow.Floats vt = new Grow.Floats();
        Grow.Floats vn = new Grow.Floats();
        // Per corner: the position, uv and normal it names, -1 for none.
        Grow.Ints cv = new Grow.Ints();
        Grow.Ints ct = new Grow.Ints();
        Grow.Ints cn = new Grow.Ints();
        Grow.Ints faceSizes = new Grow.Ints();
        Grow.Ints edges = new Grow.Ints();
        int colored = 0;
        int[] ref = new int[3];

        while (t.nextLine()) {
            int n = t.size();
            if (n == 0) {
                continue;
            }
            if (t.is(0, "v")) {
                if (n < 4) {
                    throw Malformed.at(t.line(), "a vertex needs x, y and z");
                }
                v.add(t.number(1, "x"), t.number(2, "y"), t.number(3, "z"));
                if (n >= 7) {
                    // v x y z r g b: the extension MeshLab, ZBrush and most scanners write. A fourth number alone
                    // is the rational w, which only curves use.
                    while (colors.size() < 3 * (v.size() / 3 - 1)) {
                        colors.add(Float.NaN);
                    }
                    colors.add(t.number(4, "red"), t.number(5, "green"), t.number(6, "blue"));
                    colored++;
                }
            } else if (t.is(0, "vt")) {
                if (n < 2) {
                    throw Malformed.at(t.line(), "a texture coordinate needs u");
                }
                vt.add(t.number(1, "u"), n > 2 ? t.number(2, "v") : 0);
            } else if (t.is(0, "vn")) {
                if (n < 4) {
                    throw Malformed.at(t.line(), "a normal needs x, y and z");
                }
                vn.add(t.number(1, "x"), t.number(2, "y"), t.number(3, "z"));
            } else if (t.is(0, "f")) {
                if (n < 4) {
                    throw Malformed.at(t.line(), "a face needs three corners, this has " + (n - 1));
                }
                for (int i = 1; i < n; i++) {
                    corner(t, i, v.size() / 3, vt.size() / 2, vn.size() / 3, ref);
                    cv.add(ref[0]);
                    ct.add(ref[1]);
                    cn.add(ref[2]);
                }
                faceSizes.add(n - 1);
            } else if (t.is(0, "l")) {
                if (n < 3) {
                    throw Malformed.at(t.line(), "a line needs two vertices");
                }
                int prev = -1;
                for (int i = 1; i < n; i++) {
                    corner(t, i, v.size() / 3, vt.size() / 2, vn.size() / 3, ref);
                    if (prev >= 0) {
                        edges.add(prev);
                        edges.add(ref[0]);
                    }
                    prev = ref[0];
                }
            }
        }

        int vertices = v.size() / 3;
        Mesh.Builder b = Mesh.builder().positions(v.array());
        int[] corners = cv.raw();
        int at = 0;
        for (int f = 0; f < faceSizes.size(); f++) {
            int size = faceSizes.get(f);
            b.face(corners, at, size);
            at += size;
        }
        for (int e = 0; e < edges.size(); e += 2) {
            b.edge(edges.get(e), edges.get(e + 1));
        }
        if (colored > 0) {
            while (colors.size() < 3 * vertices) {
                colors.add(Float.NaN);
            }
            b.attribute(Attribute.COLOR, Scope.VERTEX, 3, colors.array());
        }
        scoped(b, Attribute.UV, ct, cv, vt, 2, vertices);
        scoped(b, Attribute.NORMAL, cn, cv, vn, 3, vertices);
        return b.build();
    }

    /** Corner token {@code i}, {@code v}, {@code v/t}, {@code v//n} or {@code v/t/n}, into {@code ref}: 0-based, -1 for none. */
    private static void corner(Text t, int i, int vs, int ts, int ns, int[] ref) throws Malformed {
        String s = t.token(i);
        int a = s.indexOf('/');
        int c = a < 0 ? -1 : s.indexOf('/', a + 1);
        ref[0] = index(t, a < 0 ? s : s.substring(0, a), vs, "vertex");
        ref[1] = a < 0 || a + 1 == (c < 0 ? s.length() : c) ? -1
                : index(t, s.substring(a + 1, c < 0 ? s.length() : c), ts, "texture coordinate");
        ref[2] = c < 0 || c + 1 == s.length() ? -1 : index(t, s.substring(c + 1), ns, "normal");
    }

    /** An OBJ index, 1-based or negative from the end of the {@code count} so far, made 0-based. */
    private static int index(Text t, String s, int count, String what) throws Malformed {
        int i;
        try {
            i = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw Malformed.at(t.line(), "a " + what + " index is not a whole number: " + Text.clip(s));
        }
        if (i == 0) {
            throw Malformed.at(t.line(), "a " + what + " index is 0; OBJ counts from 1");
        }
        int r = i > 0 ? i - 1 : count + i;
        if (r < 0) {
            throw Malformed.at(t.line(), "a " + what + " index " + i + " is before the first");
        }
        return r;
    }

    /**
     * The uv or normal named at each corner, on the vertices if every corner of a vertex names the same one, and on
     * the corners if not. Nothing when no corner names one.
     */
    private static void scoped(Mesh.Builder b, String name, Grow.Ints named, Grow.Ints vertexOf, Grow.Floats pool,
                               int k, int vertices) throws Malformed {
        int corners = named.size();
        int poolSize = pool.size() / k;
        boolean any = false;
        for (int i = 0; i < corners; i++) {
            int r = named.get(i);
            if (r >= poolSize) {
                throw new Malformed("a face names " + name + " " + (r + 1) + " of " + poolSize);
            }
            any |= r >= 0;
        }
        if (!any) {
            return;
        }
        // -2: no corner of this vertex seen yet.
        int[] perVertex = new int[vertices];
        Arrays.fill(perVertex, -2);
        boolean consistent = true;
        for (int i = 0; i < corners && consistent; i++) {
            int vertex = vertexOf.get(i);
            if (vertex >= vertices) {
                break;          // build() says which face
            }
            int r = named.get(i);
            if (perVertex[vertex] == -2) {
                perVertex[vertex] = r;
            } else if (perVertex[vertex] != r) {
                consistent = false;
            }
        }
        if (consistent) {
            b.attribute(name, Scope.VERTEX, k, gather(perVertex, pool, k));
        } else {
            b.attribute(name, Scope.CORNER, k, gather(Arrays.copyOf(named.raw(), corners), pool, k));
        }
    }

    private static float[] gather(int[] refs, Grow.Floats pool, int k) {
        float[] out = new float[refs.length * k];
        for (int i = 0; i < refs.length; i++) {
            int r = refs[i];
            for (int c = 0; c < k; c++) {
                out[i * k + c] = r >= 0 ? pool.get(r * k + c) : Float.NaN;
            }
        }
        return out;
    }
}
