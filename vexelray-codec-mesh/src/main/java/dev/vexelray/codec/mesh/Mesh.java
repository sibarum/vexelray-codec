package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Geometry as an asset file keeps it: vertices, the faces and edges between them, and the data on each. The same
 * shape whatever the file was, so a caller reads a PLY scan and an OBJ model the same way.
 *
 * <h2>Indexed, and polygons as they are</h2>
 *
 * <p>A face is a list of vertex indices in the file's order around its edge, of whatever length the file gave: a quad
 * stays a quad and an n-gon an n-gon. Nothing is triangulated, welded, split or reordered, so the topology is the
 * file's, and two faces that share a vertex in the file share it here. (STL, which has no topology, is the one
 * exception: its corners are welded where they are bit-for-bit the same point. See {@link Meshes}.)
 *
 * <p>Faces are stored flat: {@link #corners()} is every face's indices end to end, and face {@code f} is
 * {@link #faceSize}{@code (f)} of them starting at {@link #faceStart}{@code (f)}. A corner is one entry of that array,
 * and is what a {@link Scope#CORNER} attribute has one value of.
 *
 * <h2>Edges</h2>
 *
 * <p>{@link #edges()} are the edges the file lists for their own sake: OBJ's {@code l} lines, PLY's {@code edge}
 * element. Most files list none, and a face's sides are not among them. {@link #wireframe()} is every distinct edge of
 * the mesh, the faces' sides and the listed ones together, each once.
 *
 * <h2>Vertex data</h2>
 *
 * <p>Everything else is an {@link Attribute}: a name, a {@link Scope}, and a fixed number of floats each. The position
 * is the one every mesh has. A file's normals and texture coordinates are {@link Scope#VERTEX} where each vertex has
 * one value, and {@link Scope#CORNER} where a vertex has a different one in each face it is in, which OBJ allows and
 * a seam needs.
 *
 * <p>Immutable, and the arrays it hands out are live: do not write to them.
 */
public final class Mesh {

    private final int vertexCount;
    private final float[] positions;
    private final int[] faceStarts;
    private final int[] corners;
    private final int[] edges;
    private final List<Attribute> attributes;
    private volatile int[] wireframe;

    private Mesh(int vertexCount, float[] positions, int[] faceStarts, int[] corners, int[] edges,
                 List<Attribute> attributes) {
        this.vertexCount = vertexCount;
        this.positions = positions;
        this.faceStarts = faceStarts;
        this.corners = corners;
        this.edges = edges;
        this.attributes = attributes;
    }

    /** A builder with no vertices yet. */
    public static Builder builder() {
        return new Builder();
    }

    /** How many vertices. */
    public int vertexCount() {
        return vertexCount;
    }

    /** Every vertex's x, y and z, three floats a vertex: the {@link Attribute#POSITION} attribute's values. */
    public float[] positions() {
        return positions;
    }

    /** How many faces. */
    public int faceCount() {
        return faceStarts.length - 1;
    }

    /** Where face {@code f}'s indices start in {@link #corners()}. */
    public int faceStart(int f) {
        return faceStarts[f];
    }

    /** How many corners face {@code f} has: 3 for a triangle, 4 for a quad. */
    public int faceSize(int f) {
        return faceStarts[f + 1] - faceStarts[f];
    }

    /** Face {@code f}'s vertex indices, in order around it: a copy. */
    public int[] face(int f) {
        return Arrays.copyOfRange(corners, faceStarts[f], faceStarts[f + 1]);
    }

    /** Every face's vertex indices, end to end, face by face. */
    public int[] corners() {
        return corners;
    }

    /** How many corners all the faces have between them: the length of {@link #corners()}. */
    public int cornerCount() {
        return corners.length;
    }

    /** Whether every face is a triangle; true of a mesh with no faces. */
    public boolean triangles() {
        return corners.length == 3 * faceCount();
    }

    /** How many edges the file listed. */
    public int edgeCount() {
        return edges.length / 2;
    }

    /** The edges the file listed for their own sake, two vertex indices each, in the file's order. */
    public int[] edges() {
        return edges;
    }

    /**
     * Every distinct edge of the mesh, the faces' sides and {@link #edges()} together, two vertex indices each, the
     * lower first, in ascending order. An edge two faces share is here once, and so is one a face has and the file
     * also listed. Worked out the first time it is asked for.
     */
    public int[] wireframe() {
        int[] w = wireframe;
        if (w == null) {
            wireframe = w = computeWireframe();
        }
        return w;
    }

    /** Every attribute, the position first, then in the order the file gave them. */
    public List<Attribute> attributes() {
        return attributes;
    }

    /** The attribute called {@code name} kept on {@code scope}, or null when there is none. */
    public Attribute attribute(String name, Scope scope) {
        for (Attribute a : attributes) {
            if (a.scope() == scope && a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }

    /**
     * The attribute called {@code name} on the vertices, or failing that on the corners: what a renderer that wants a
     * normal or a uv at each corner asks for. Null when there is neither.
     */
    public Attribute attribute(String name) {
        Attribute a = attribute(name, Scope.VERTEX);
        return a != null ? a : attribute(name, Scope.CORNER);
    }

    @Override
    public String toString() {
        return "Mesh[" + vertexCount + " vertices, " + faceCount() + " faces, " + edgeCount() + " edges; "
                + attributes + "]";
    }

    private int[] computeWireframe() {
        int faces = faceCount();
        long[] keys = new long[corners.length + edgeCount()];
        int n = 0;
        for (int f = 0; f < faces; f++) {
            int start = faceStarts[f];
            int end = faceStarts[f + 1];
            for (int i = start; i < end; i++) {
                int j = i + 1 == end ? start : i + 1;
                if (corners[i] != corners[j]) {
                    keys[n++] = key(corners[i], corners[j]);
                }
            }
        }
        for (int e = 0; e < edges.length; e += 2) {
            if (edges[e] != edges[e + 1]) {
                keys[n++] = key(edges[e], edges[e + 1]);
            }
        }
        Arrays.sort(keys, 0, n);
        int[] out = new int[2 * n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && keys[i] == keys[i - 1]) {
                continue;
            }
            out[m++] = (int) (keys[i] >>> 32);
            out[m++] = (int) keys[i];
        }
        return Arrays.copyOf(out, m);
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }

    /**
     * Assembles a mesh: positions, then faces, edges and attributes in any order. {@link #build} checks that every
     * index is a vertex and every attribute is the size of its scope, so a reader can add what the file says and leave
     * the checking to it. Not thread-safe.
     */
    public static final class Builder {

        private float[] positions = new float[0];
        private int[] faceStarts = new int[64];
        private int faces;
        private int[] corners = new int[256];
        private int cornerCount;
        private int[] edges = new int[0];
        private int edgeInts;
        private final List<Attribute> attributes = new ArrayList<>();

        private Builder() {
        }

        /** The vertices' positions, x, y and z each: the vertex count is a third of the length. */
        public Builder positions(float[] xyz) {
            if (xyz.length % 3 != 0) {
                throw new IllegalArgumentException("positions are three floats a vertex, got " + xyz.length);
            }
            positions = xyz;
            return this;
        }

        /** One face: its vertex indices in order around its edge, at least three. */
        public Builder face(int... indices) {
            return face(indices, 0, indices.length);
        }

        /** One face: {@code count} vertex indices of {@code indices} from {@code from}. */
        public Builder face(int[] indices, int from, int count) {
            if (count < 3) {
                throw new IllegalArgumentException("face " + faces + " has " + count + " corners; a face needs three");
            }
            if (faces + 2 > faceStarts.length) {
                faceStarts = Arrays.copyOf(faceStarts, faceStarts.length * 2);
            }
            if (cornerCount + count > corners.length) {
                corners = Arrays.copyOf(corners, Math.max(corners.length * 2, cornerCount + count));
            }
            System.arraycopy(indices, from, corners, cornerCount, count);
            faceStarts[faces] = cornerCount;
            cornerCount += count;
            faces++;
            faceStarts[faces] = cornerCount;
            return this;
        }

        /** One edge from vertex {@code a} to vertex {@code b}. */
        public Builder edge(int a, int b) {
            if (edgeInts + 2 > edges.length) {
                edges = Arrays.copyOf(edges, Math.max(16, edges.length * 2));
            }
            edges[edgeInts++] = a;
            edges[edgeInts++] = b;
            return this;
        }

        /** How many faces so far: the index the next one will have. */
        public int faceCount() {
            return faces;
        }

        /** How many corners so far: the index the next face's first corner will have. */
        public int cornerCount() {
            return cornerCount;
        }

        /** How many edges so far. */
        public int edgeCount() {
            return edgeInts / 2;
        }

        /**
         * Data on the mesh: {@code components} floats per element of {@code scope}. The position is set with
         * {@link #positions}, not here.
         */
        public Builder attribute(String name, Scope scope, int components, float[] values) {
            if (name.equals(Attribute.POSITION)) {
                throw new IllegalArgumentException("the position is set with positions(), not as an attribute");
            }
            for (Attribute a : attributes) {
                if (a.scope() == scope && a.name().equals(name)) {
                    throw new IllegalArgumentException("two " + scope + " attributes called " + name);
                }
            }
            attributes.add(new Attribute(name, scope, components, values));
            return this;
        }

        /**
         * The mesh. Every face and edge index must be a vertex, and every attribute as long as its scope.
         *
         * @throws IllegalArgumentException naming the first index or attribute that is not
         */
        public Mesh build() {
            int vertices = positions.length / 3;
            int[] c = Arrays.copyOf(corners, cornerCount);
            for (int i = 0; i < c.length; i++) {
                if (c[i] < 0 || c[i] >= vertices) {
                    throw new IllegalArgumentException("face " + faceOf(i) + " names vertex " + c[i] + " of "
                            + vertices);
                }
            }
            int[] e = Arrays.copyOf(edges, edgeInts);
            for (int i = 0; i < e.length; i++) {
                if (e[i] < 0 || e[i] >= vertices) {
                    throw new IllegalArgumentException("edge " + i / 2 + " names vertex " + e[i] + " of " + vertices);
                }
            }
            List<Attribute> all = new ArrayList<>(attributes.size() + 1);
            all.add(new Attribute(Attribute.POSITION, Scope.VERTEX, 3, positions));
            for (Attribute a : attributes) {
                int expected = switch (a.scope()) {
                    case VERTEX -> vertices;
                    case CORNER -> cornerCount;
                    case FACE -> faces;
                    case EDGE -> edgeInts / 2;
                };
                if (a.count() != expected) {
                    throw new IllegalArgumentException(a.name() + " has " + a.count() + " values for " + expected
                            + " " + a.scope().name().toLowerCase(Locale.ROOT) + " elements");
                }
                all.add(a);
            }
            return new Mesh(vertices, positions, Arrays.copyOf(faceStarts, faces + 1), c, e, List.copyOf(all));
        }

        private int faceOf(int corner) {
            int f = Arrays.binarySearch(faceStarts, 0, faces + 1, corner);
            return f >= 0 ? f : -f - 2;
        }
    }
}
