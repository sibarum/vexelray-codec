package dev.vexelray.codec.asset;

import dev.vexelray.codec.mesh.Attribute;
import dev.vexelray.codec.mesh.Mesh;
import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Mesh} as stored: vertices, faces and edges by index, and the data on each as {@link Channel}s, one list
 * per scope. The vertices' {@code position} channel is always there, three {@value Channel#F32} each.
 *
 * @param name        what the asset calls it
 * @param vertexCount how many vertices
 * @param corners     every face's vertex indices end to end, {@value Channel#I32} each
 * @param faceSizes   each face's corner count, {@value Channel#I32} each; null when every face is a triangle, which is
 *                    the common case and needs none
 * @param edges       the mesh's own listed edges, two {@value Channel#I32} indices each; null for none
 * @param vertexData  one value per vertex, {@code position} first
 * @param cornerData  one value per face corner, in {@code corners} order
 * @param faceData    one value per face
 * @param edgeData    one value per listed edge
 */
@IntrabufMessage(name = "vexelray.asset.Mesh", compat = Compat.FULL)
public record MeshData(String name, int vertexCount, @Field(nonNull = true) Packed corners, Packed faceSizes,
                       Packed edges, @Field(nonNull = true) List<Channel> vertexData,
                       @Field(nonNull = true) List<Channel> cornerData, @Field(nonNull = true) List<Channel> faceData,
                       @Field(nonNull = true) List<Channel> edgeData) {

    public MeshData {
        vertexData = List.copyOf(vertexData);
        cornerData = List.copyOf(cornerData);
        faceData = List.copyOf(faceData);
        edgeData = List.copyOf(edgeData);
    }

    /** {@code mesh} as stored, every attribute as a {@value Channel#F32} channel of its name. */
    public static MeshData of(String name, Mesh mesh) {
        List<Channel> vertex = new ArrayList<>();
        List<Channel> corner = new ArrayList<>();
        List<Channel> face = new ArrayList<>();
        List<Channel> edge = new ArrayList<>();
        for (Attribute a : mesh.attributes()) {
            Channel c = Channel.f32(a.name(), a.components(), a.values());
            switch (a.scope()) {
                case VERTEX -> vertex.add(c);
                case CORNER -> corner.add(c);
                case FACE -> face.add(c);
                case EDGE -> edge.add(c);
            }
        }
        Packed sizes = null;
        if (!mesh.triangles()) {
            int[] s = new int[mesh.faceCount()];
            for (int f = 0; f < s.length; f++) {
                s[f] = mesh.faceSize(f);
            }
            sizes = Packed.of(Channel.ints(s), 4);
        }
        Packed edges = mesh.edgeCount() == 0 ? null : Packed.of(Channel.ints(mesh.edges()), 4);
        return new MeshData(name, mesh.vertexCount(), Packed.of(Channel.ints(mesh.corners()), 4), sizes, edges,
                vertex, corner, face, edge);
    }

    /**
     * The mesh again. Every channel must be one this reader knows, and the position must be there.
     *
     * @throws IllegalStateException when a channel or a packing is one this reader does not know, or the stored
     *                               mesh does not hold together; the message says which
     */
    public Mesh toMesh() {
        Channel position = channel(vertexData, Attribute.POSITION);
        if (position == null || position.components() != 3) {
            throw new IllegalStateException(label() + " has no three-component position");
        }
        float[] xyz = position.floats();
        if (xyz.length != 3 * vertexCount) {
            throw new IllegalStateException(label() + " has " + xyz.length / 3 + " positions for " + vertexCount
                    + " vertices");
        }
        Mesh.Builder b = Mesh.builder().positions(xyz);
        int[] c = new Channel("corners", Channel.I32, 1, corners).ints();
        if (faceSizes == null) {
            if (c.length % 3 != 0) {
                throw new IllegalStateException(label() + " has " + c.length + " corners, not whole triangles");
            }
            for (int at = 0; at < c.length; at += 3) {
                b.face(c, at, 3);
            }
        } else {
            int at = 0;
            for (int size : new Channel("faceSizes", Channel.I32, 1, faceSizes).ints()) {
                if (size < 0 || at + size > c.length) {
                    throw new IllegalStateException(label() + "'s face sizes run past its corners");
                }
                b.face(c, at, size);
                at += size;
            }
            if (at != c.length) {
                throw new IllegalStateException(label() + "'s face sizes leave corners over");
            }
        }
        if (edges != null) {
            int[] e = new Channel("edges", Channel.I32, 2, edges).ints();
            for (int i = 0; i + 1 < e.length; i += 2) {
                b.edge(e[i], e[i + 1]);
            }
        }
        add(b, vertexData, Attribute.Scope.VERTEX);
        add(b, cornerData, Attribute.Scope.CORNER);
        add(b, faceData, Attribute.Scope.FACE);
        add(b, edgeData, Attribute.Scope.EDGE);
        try {
            return b.build();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(label() + ": " + e.getMessage());
        }
    }

    /** The channel called {@code name} in {@code list}, or null. */
    static Channel channel(List<Channel> list, String name) {
        for (Channel c : list) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static void add(Mesh.Builder b, List<Channel> list, Attribute.Scope scope) {
        for (Channel c : list) {
            if (scope == Attribute.Scope.VERTEX && c.name().equals(Attribute.POSITION)) {
                continue;
            }
            b.attribute(c.name(), scope, c.components(), c.floats());
        }
    }

    private String label() {
        return name == null ? "a mesh" : "mesh " + name;
    }
}
