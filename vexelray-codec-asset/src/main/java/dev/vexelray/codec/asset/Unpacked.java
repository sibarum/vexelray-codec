package dev.vexelray.codec.asset;

/**
 * What {@link Assets} makes of a source: the asset, or, when there is none, why not.
 *
 * @param source   what was read: the file's {@code Path}, or the name of bytes in hand or of a resource
 * @param identity what the source is ("asset · 2 meshes · 1 texture"), or why there is no asset of it ("not an asset
 *                 file", "written by an incompatible version: MAJOR_MISMATCH")
 * @param asset    the asset; null when there is none
 */
public record Unpacked(Object source, String identity, Asset asset) {

    /** A source with no asset, and the reason. */
    public static Unpacked none(Object source, String why) {
        return new Unpacked(source, why, null);
    }

    /** Whether there is an asset. */
    public boolean readable() {
        return asset != null;
    }
}
