package dev.vexelray.codec.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Which version of a file something was made from: its path, its length and when it last changed.
 *
 * <p>The key for anything cached from a file's contents. Not a hash, on purpose: a hash means reading the file, and the
 * point of a cache is not to. A file rewritten in place to the same length within the file system's timestamp grain
 * would fool it, and a picture is a place where that costs nothing worse than a stale picture until the next change.
 */
public record Stamp(Path path, long size, long modified) {

    /** The stamp {@code path} has now. One attribute read; blocking, like every other file call. */
    public static Stamp of(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
        return new Stamp(path, a.size(), a.lastModifiedTime().toMillis());
    }
}
