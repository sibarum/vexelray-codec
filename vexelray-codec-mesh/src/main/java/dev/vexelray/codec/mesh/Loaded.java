package dev.vexelray.codec.mesh;

/**
 * What {@link Meshes} makes of a source: its mesh, or, when there is none, why not.
 *
 * @param source   what was read: the file's {@code Path}, or the name of bytes in hand or of a resource
 * @param identity what the source is ("PLY mesh · 8 vertices · 6 faces"), or why there is no mesh of it ("not a
 *                 mesh", "malformed OBJ: line 12: a face needs three corners, this has 2")
 * @param mesh     the mesh; null when there is none
 */
public record Loaded(Object source, String identity, Mesh mesh) {

    /** A source with no mesh, and the reason. */
    public static Loaded none(Object source, String why) {
        return new Loaded(source, why, null);
    }

    /** Whether there is a mesh. */
    public boolean geometry() {
        return mesh != null;
    }
}
