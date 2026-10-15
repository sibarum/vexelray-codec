package dev.vexelray.codec.mesh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Meshes from a file, a resource or bytes in hand: OBJ, PLY, STL and OFF, each as the same {@link Mesh}.
 *
 * <p><b>The bytes say what a file is.</b> PLY and OFF begin with their name, and a binary STL is exactly as long as
 * its triangle count says; an ASCII STL begins with {@code solid}. OBJ has no signature, so a file is taken for one
 * when it is named {@code .obj} or its first statement is one OBJ has. The name is never trusted over the bytes: a
 * {@code .stl} that is PLY inside is read as PLY.
 *
 * <p><b>As the file says it.</b> Faces keep their corner count and order, vertices their order, and every value is the
 * file's own; nothing is triangulated, recomputed, renormalised or flipped to another axis convention. A file's
 * coordinates are in whatever units and handedness it was written in, which none of these formats records. The one
 * change is STL's, whose identical corners are made one vertex (see {@link Mesh}).
 *
 * <p><b>Every failure is a reason, not an exception.</b> A file that is not a mesh, one that breaks its format (named
 * by line, in a text format), one too large to hold: each comes back as a {@link Loaded} with no mesh and a line
 * saying why, so the caller says so and the next file is unaffected.
 *
 * <p>Blocking: run it on {@code Gui.offload()}.
 */
public final class Meshes {

    /** The largest file read. Past it the file is refused, rather than half a gigabyte of floats made from it. */
    public static final long MAX_MESH = 1024L * 1024 * 1024;

    /** The formats read, by the name a reason gives them and the extensions a folder is listed for. */
    enum Format {
        OBJ("obj"), PLY("ply"), STL("stl"), OFF("off");

        final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        Mesh read(byte[] bytes) throws Malformed {
            return switch (this) {
                case OBJ -> ObjReader.read(bytes);
                case PLY -> PlyReader.read(bytes);
                case STL -> StlReader.read(bytes);
                case OFF -> OffReader.read(bytes);
            };
        }
    }

    /** The statements an OBJ file can start with, after its comments. */
    private static final List<String> OBJ_STATEMENTS = List.of("v", "vt", "vn", "vp", "f", "l", "p", "o", "g", "s",
            "mtllib", "usemtl");

    private Meshes() {
    }

    /** The extensions {@link #named} lists, without the dot, in order: an open dialog's filter. */
    public static List<String> extensions() {
        return Arrays.stream(Format.values()).map(f -> f.extension).sorted().toList();
    }

    /** Whether {@code path} is named like a mesh this reads: what a folder of models is listed for. */
    public static boolean named(Path path) {
        return extension(path) != null;
    }

    /** The mesh in {@code path}. */
    public static Loaded of(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return Loaded.none(path, "a folder");
            }
            if (Files.size(path) > MAX_MESH) {
                return Loaded.none(path, "too large to read");
            }
            Path name = path.getFileName();
            return read(path, name == null ? "" : name.toString(), Files.readAllBytes(path));
        } catch (IOException | RuntimeException e) {
            return Loaded.none(path, "unreadable: " + e.getClass().getSimpleName());
        } catch (OutOfMemoryError e) {
            return Loaded.none(path, "too large to read");
        }
    }

    /** The mesh in {@code bytes}, already in hand; {@code name} is what it is called, and its extension a hint. */
    public static Loaded of(String name, byte[] bytes) {
        return read(name, name, bytes);
    }

    /** The mesh in the class-path resource {@code name}, as {@link Class#getResourceAsStream} finds it. */
    public static Loaded resource(Class<?> anchor, String name) {
        try (InputStream in = anchor.getResourceAsStream(name)) {
            if (in == null) {
                return Loaded.none(name, "no such resource");
            }
            byte[] bytes = in.readNBytes((int) Math.min(MAX_MESH + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > MAX_MESH) {
                return Loaded.none(name, "too large to read");
            }
            return read(name, name, bytes);
        } catch (IOException | RuntimeException e) {
            return Loaded.none(name, "unreadable: " + e.getClass().getSimpleName());
        } catch (OutOfMemoryError e) {
            return Loaded.none(name, "too large to read");
        }
    }

    private static Loaded read(Object source, String fileName, byte[] bytes) {
        Format format = probe(bytes, fileName);
        if (format == null) {
            return Loaded.none(source, "not a mesh");
        }
        try {
            Mesh mesh = format.read(bytes);
            return new Loaded(source, identity(format, mesh), mesh);
        } catch (Malformed | IllegalArgumentException e) {
            return Loaded.none(source, "malformed " + format + ": " + e.getMessage());
        } catch (OutOfMemoryError e) {
            return Loaded.none(source, "too large to read");
        }
    }

    /** Which format {@code bytes} are, by their content first and {@code fileName}'s extension only for OBJ. */
    static Format probe(byte[] bytes, String fileName) {
        if (PlyReader.magic(bytes)) {
            return Format.PLY;
        }
        if (OffReader.magic(bytes)) {
            return Format.OFF;
        }
        if (StlReader.binary(bytes) || StlReader.ascii(bytes)) {
            return Format.STL;
        }
        if (extension(fileName) == Format.OBJ || objStatement(bytes)) {
            return Format.OBJ;
        }
        return null;
    }

    private static boolean objStatement(byte[] bytes) {
        Text t = new Text(bytes, 0, Math.min(bytes.length, 64 * 1024), '#');
        while (t.nextLine()) {
            if (t.size() > 0) {
                return OBJ_STATEMENTS.contains(t.token(0));
            }
        }
        return false;
    }

    private static String identity(Format format, Mesh mesh) {
        StringBuilder s = new StringBuilder(format.name()).append(" mesh · ")
                .append(count(mesh.vertexCount(), "vertex", "vertices"))
                .append(" · ").append(count(mesh.faceCount(), "face", "faces"));
        if (mesh.edgeCount() > 0) {
            s.append(" · ").append(count(mesh.edgeCount(), "edge", "edges"));
        }
        return s.toString();
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static Format extension(Path path) {
        Path n = path.getFileName();
        return n == null ? null : extension(n.toString());
    }

    private static Format extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Format f : Format.values()) {
            if (f.extension.equals(ext)) {
                return f;
            }
        }
        return null;
    }
}
