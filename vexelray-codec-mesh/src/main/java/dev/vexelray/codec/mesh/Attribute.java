package dev.vexelray.codec.mesh;

import java.util.Locale;
import java.util.Objects;

/**
 * One kind of data a file keeps on its mesh: a name, what it is kept on, and a fixed number of floats for each of those.
 *
 * <p>The well-known names are {@link #POSITION}, {@link #NORMAL}, {@link #UV} and {@link #COLOR}, and every reader
 * maps its format's spelling of them onto these (PLY's {@code nx}/{@code ny}/{@code nz}, OBJ's {@code vn}, OFF's
 * {@code NOFF} and so on). Anything else a file names is kept under the file's own name: a PLY vertex property
 * {@code confidence} is an attribute called {@code confidence}.
 *
 * <p>Values are floats whatever the file stored. A colour stored as bytes is divided by 255, so colours are always in
 * {@code [0,1]}; any other integer is its value, exact up to 2<sup>24</sup>. A value the file did not give, such as
 * the texture coordinate of an OBJ face that has none while others do, is {@code NaN}.
 *
 * @param name       {@link #POSITION}, {@link #NORMAL}, {@link #UV}, {@link #COLOR}, or the file's own name for it
 * @param scope      what there is one value of
 * @param components floats per value: 3 for a position, 2 for a uv, 3 or 4 for a colour, 1 for most else
 * @param values     {@code components} floats per element of the scope, in the mesh's order. Live: do not write to it
 */
public record Attribute(String name, Scope scope, int components, float[] values) {

    /** Where each vertex is: x, y, z, in the file's own units and axes. Always present, always {@link Scope#VERTEX}. */
    public static final String POSITION = "position";

    /** A unit-ish normal, x, y, z, as the file gives it: not renormalised. */
    public static final String NORMAL = "normal";

    /** A texture coordinate, u, v, with v as the file has it (OBJ and PLY put v = 0 at the bottom). */
    public static final String UV = "uv";

    /** Linear-or-not RGB or RGBA in {@code [0,1]}, as the file stores it: no colour space is assumed. */
    public static final String COLOR = "color";

    /** What an attribute has one value of. */
    public enum Scope {
        /** One per vertex. */
        VERTEX,
        /**
         * One per corner of a face, in {@link Mesh#corners()} order: where a vertex has a different value in each face
         * it is in, as a texture seam or a hard edge's normals do. Kept on the corners so that the vertex stays one
         * vertex and its edges stay shared.
         */
        CORNER,
        /** One per face. */
        FACE,
        /** One per edge of {@link Mesh#edges()}. */
        EDGE
    }

    public Attribute {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(values, "values");
        if (components < 1) {
            throw new IllegalArgumentException(name + ": an attribute has at least one component, got " + components);
        }
        if (values.length % components != 0) {
            throw new IllegalArgumentException(name + ": " + values.length + " floats is not a whole number of "
                    + components + "-component values");
        }
    }

    /** How many values: the size of the scope it was made for. */
    public int count() {
        return values.length / components;
    }

    /** Component {@code c} of value {@code i}. */
    public float get(int i, int c) {
        return values[i * components + c];
    }

    @Override
    public String toString() {
        return name + " (" + scope.name().toLowerCase(Locale.ROOT) + ", " + components + " × " + count() + ")";
    }
}
