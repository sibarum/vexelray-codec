package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;

import java.util.Arrays;

/**
 * Geomview's OFF, and the variants its header's prefix names: {@code ST} texture coordinates, {@code C} colours and
 * {@code N} normals on the vertices, as in {@code COFF}, {@code NOFF} or {@code STCNOFF}. A face may end with a colour
 * of its own. Four-dimensional and n-dimensional OFF ({@code 4OFF}, {@code nOFF}) are not read.
 *
 * <p>Colours are RGBA in {@code [0,1]}, an alpha of 1 where the file gives three numbers. A colour written as 0 to 255,
 * which many writers do, is told by any of its numbers being over 1, and divided by 255. A face colour given as an
 * index into a colour map, which OFF allows and nothing here has, is left out; a face with none is {@code NaN}.
 *
 * <p>The edge count in the header is only a count, as in every OFF file: OFF lists no edges, so a mesh read from one
 * has none of its own and its {@link Mesh#wireframe()} is its faces' sides.
 */
final class OffReader {

    private OffReader() {
    }

    /** Whether {@code b}'s first word is an OFF header, of any variant (including those not read). */
    static boolean magic(byte[] b) {
        Text t = new Text(b, 0, Math.min(b.length, 4096), '#');
        while (t.nextLine()) {
            if (t.size() > 0) {
                return header(t.token(0)) != null;
            }
        }
        return false;
    }

    /** The prefix letters of an OFF header word ("STCN" of "STCNOFF"), or null when the word is not one. */
    private static String header(String word) {
        if (!word.endsWith("OFF")) {
            return null;
        }
        String prefix = word.substring(0, word.length() - 3);
        return prefix.matches("(ST)?C?N?4?n?") ? prefix : null;
    }

    static Mesh read(byte[] bytes) throws Malformed {
        Text t = new Text(bytes, 0, bytes.length, '#');
        if (!nonEmpty(t)) {
            throw new Malformed("the file is empty");
        }
        String prefix = header(t.token(0));
        if (prefix == null) {
            throw Malformed.at(t.line(), "not an OFF header: " + Text.clip(t.token(0)));
        }
        if (prefix.contains("4") || prefix.contains("n")) {
            throw Malformed.at(t.line(), t.token(0) + " is not three-dimensional; only 3D OFF is read");
        }
        boolean st = prefix.startsWith("ST");
        boolean colors = prefix.contains("C");
        boolean normals = prefix.contains("N");
        t.nextToken();
        int vertices = count(t, "the vertex count");
        int faces = count(t, "the face count");
        if (t.moreOnLine()) {
            t.nextToken();          // the edge count: nothing to read
        }
        if ((long) vertices * 3 + faces > bytes.length) {
            throw new Malformed(vertices + " vertices and " + faces + " faces cannot fit in a file this size");
        }

        float[] xyz = new float[3 * vertices];
        float[] nxyz = normals ? new float[3 * vertices] : null;
        float[] rgba = colors ? new float[4 * vertices] : null;
        float[] uv = st ? new float[2 * vertices] : null;
        int fixed = 3 + (normals ? 3 : 0) + (st ? 2 : 0);
        for (int v = 0; v < vertices; v++) {
            if (!nonEmpty(t)) {
                throw new Malformed("the file ends after " + v + " of " + vertices + " vertices");
            }
            int n = t.size();
            int colorCount = colors ? n - fixed : 0;
            if (n < fixed || (colors && colorCount != 3 && colorCount != 4)) {
                throw Malformed.at(t.line(), "vertex " + v + " has " + n + " numbers; a "
                        + prefix + "OFF vertex has " + fixed + (colors ? " and a colour of three or four" : ""));
            }
            int i = 0;
            for (int c = 0; c < 3; c++) {
                xyz[3 * v + c] = t.number(i++, "a vertex coordinate");
            }
            if (normals) {
                for (int c = 0; c < 3; c++) {
                    nxyz[3 * v + c] = t.number(i++, "a normal");
                }
            }
            if (colors) {
                i = color(t, i, colorCount, rgba, 4 * v);
            }
            if (st) {
                uv[2 * v] = t.number(i++, "a texture coordinate");
                uv[2 * v + 1] = t.number(i, "a texture coordinate");
            }
        }

        Mesh.Builder b = Mesh.builder().positions(xyz);
        float[] faceRgba = new float[4 * faces];
        Arrays.fill(faceRgba, Float.NaN);
        boolean faceColors = false;
        int[] corners = new int[16];
        for (int f = 0; f < faces; f++) {
            if (!nonEmpty(t)) {
                throw new Malformed("the file ends after " + f + " of " + faces + " faces");
            }
            int n = t.integer(0, "a face's corner count");
            if (n < 3) {
                throw Malformed.at(t.line(), "face " + f + " has " + n + " corners; a face needs three");
            }
            if (t.size() < 1 + n) {
                throw Malformed.at(t.line(), "face " + f + " says " + n + " corners and lists " + (t.size() - 1));
            }
            if (corners.length < n) {
                corners = new int[n];
            }
            for (int c = 0; c < n; c++) {
                corners[c] = t.integer(1 + c, "a vertex index");
            }
            b.face(corners, 0, n);
            int extra = t.size() - 1 - n;
            if (extra == 3 || extra == 4) {
                color(t, 1 + n, extra, faceRgba, 4 * f);
                faceColors = true;
            }
        }

        if (normals) {
            b.attribute(Attribute.NORMAL, Scope.VERTEX, 3, nxyz);
        }
        if (st) {
            b.attribute(Attribute.UV, Scope.VERTEX, 2, uv);
        }
        if (colors) {
            b.attribute(Attribute.COLOR, Scope.VERTEX, 4, rgba);
        }
        if (faceColors) {
            b.attribute(Attribute.COLOR, Scope.FACE, 4, faceRgba);
        }
        return b.build();
    }

    /** {@code n} colour numbers from token {@code i} into {@code out} at {@code at}, as RGBA in [0,1]. */
    private static int color(Text t, int i, int n, float[] out, int at) throws Malformed {
        boolean bytes = false;
        for (int c = 0; c < n; c++) {
            out[at + c] = t.number(i + c, "a colour");
            bytes |= out[at + c] > 1;
        }
        if (n == 3) {
            out[at + 3] = bytes ? 255 : 1;
        }
        if (bytes) {
            for (int c = 0; c < 4; c++) {
                out[at + c] /= 255;
            }
        }
        return i + n;
    }

    private static int count(Text t, String what) throws Malformed {
        String s;
        while ((s = t.nextToken()) != null) {
            try {
                int n = Integer.parseInt(s);
                if (n < 0) {
                    throw Malformed.at(t.line(), what + " is negative");
                }
                return n;
            } catch (NumberFormatException e) {
                throw Malformed.at(t.line(), what + " is not a whole number: " + Text.clip(s));
            }
        }
        throw new Malformed("the file ends before " + what);
    }

    /** On to the next line with anything on it; false at the end of the file. */
    private static boolean nonEmpty(Text t) {
        while (t.nextLine()) {
            if (t.size() > 0) {
                return true;
            }
        }
        return false;
    }
}
