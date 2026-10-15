package dev.vexelray.codec.asset;

import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IntrabufException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * The stack's asset file, {@code .vxa}: an {@link Asset} written and read.
 *
 * <p><b>On disk</b> a file is {@link #SIGNATURE}, eight bytes, then one intrabuf payload of {@link Asset}. The
 * signature says what the file is before anything is decoded, and is built as PNG's is: a byte with the high bit set,
 * so a channel that strips it is caught, then {@code VXA}, then a CR LF, a DOS end-of-file and an LF, so a text-mode
 * transfer that rewrites line endings is caught too. The payload's own header carries the schema's version and
 * compatibility facts; intrabuf decides from it whether this reader can read the file.
 *
 * <p><b>As text</b>, for a person to read, diff or write by hand: {@link #toText} is the same asset in intrabuf's
 * text format, its bulk bytes base64. {@link #read} takes either, telling them apart by their first bytes.
 *
 * <p><b>Every failure is a reason, not an exception</b>, as everywhere in vexelray-codec. And a file that opens may
 * still hold an item this reader cannot unpack, packed with a codec or typed with a format newer than it: each item's
 * {@code known()} says, and the rest of the file is unaffected.
 *
 * <p>Blocking: run it on {@code Gui.offload()}.
 */
public final class Assets {

    /** The first eight bytes of every binary asset file. */
    public static final byte[] SIGNATURE = {(byte) 0x89, 'V', 'X', 'A', '\r', '\n', 0x1A, '\n'};

    /** The extension, without the dot. */
    public static final String EXTENSION = "vxa";

    /** The largest file read: a Java array's limit, near enough. */
    public static final long MAX_ASSET = Integer.MAX_VALUE - 64;

    private static final byte[] TEXT_START = "#intrabuf".getBytes(StandardCharsets.US_ASCII);

    private Assets() {
    }

    /** {@code asset} as a binary asset file's bytes: the signature, then the payload. */
    public static byte[] toBytes(Asset asset) {
        byte[] payload = AssetIntrabuf.toBytes(asset);
        byte[] out = Arrays.copyOf(SIGNATURE, SIGNATURE.length + payload.length);
        System.arraycopy(payload, 0, out, SIGNATURE.length, payload.length);
        return out;
    }

    /** {@code asset} in intrabuf's text format, which {@link #read} also reads. */
    public static String toText(Asset asset) {
        return AssetIntrabuf.toText(asset);
    }

    /**
     * Writes {@code asset} to {@code path}, whole or not at all: to a file beside it first, then moved over it, so a
     * reader never sees half a file and a failure leaves what was there.
     */
    public static void write(Asset asset, Path path) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(dir, "." + path.getFileName(), ".tmp");
        try {
            Files.write(temp, toBytes(asset));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Whether {@code path} is named like an asset file. */
    public static boolean named(Path path) {
        Path n = path.getFileName();
        return n != null && n.toString().toLowerCase(Locale.ROOT).endsWith("." + EXTENSION);
    }

    /** The asset in {@code path}. */
    public static Unpacked read(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return Unpacked.none(path, "a folder");
            }
            if (Files.size(path) > MAX_ASSET) {
                return Unpacked.none(path, "too large to read");
            }
            return read(path, Files.readAllBytes(path));
        } catch (IOException | RuntimeException e) {
            return Unpacked.none(path, "unreadable: " + e.getClass().getSimpleName());
        } catch (OutOfMemoryError e) {
            return Unpacked.none(path, "too large to read");
        }
    }

    /** The asset in {@code bytes}, already in hand, called {@code name}. */
    public static Unpacked read(String name, byte[] bytes) {
        return read((Object) name, bytes);
    }

    /** The asset in the class-path resource {@code name}, as {@link Class#getResourceAsStream} finds it. */
    public static Unpacked resource(Class<?> anchor, String name) {
        try (InputStream in = anchor.getResourceAsStream(name)) {
            if (in == null) {
                return Unpacked.none(name, "no such resource");
            }
            return read((Object) name, in.readAllBytes());
        } catch (IOException | RuntimeException e) {
            return Unpacked.none(name, "unreadable: " + e.getClass().getSimpleName());
        } catch (OutOfMemoryError e) {
            return Unpacked.none(name, "too large to read");
        }
    }

    private static Unpacked read(Object source, byte[] bytes) {
        boolean binary = startsWith(bytes, SIGNATURE);
        if (!binary && !startsWith(bytes, TEXT_START)) {
            return Unpacked.none(source, "not an asset file");
        }
        try {
            Asset asset = binary
                    ? AssetIntrabuf.CODEC.decode(bytes, SIGNATURE.length, bytes.length - SIGNATURE.length)
                    : AssetIntrabuf.CODEC.decodeText(new String(bytes, StandardCharsets.UTF_8));
            return new Unpacked(source, identity(asset), asset);
        } catch (IncompatiblePayloadException e) {
            return Unpacked.none(source, "written by an incompatible version: " + e.reason());
        } catch (IntrabufException | IllegalArgumentException e) {
            return Unpacked.none(source, "malformed: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            return Unpacked.none(source, "too large to read");
        }
    }

    /** "asset · 2 meshes · 1 texture", or "empty asset". */
    static String identity(Asset a) {
        StringBuilder s = new StringBuilder(a.name() == null ? "asset" : a.name());
        if (a.items() == 0) {
            return s.append(" · empty").toString();
        }
        count(s, a.meshes().size(), "mesh", "meshes");
        count(s, a.pointClouds().size(), "point cloud", "point clouds");
        count(s, a.volumes().size(), "volume", "volumes");
        count(s, a.textures().size(), "texture", "textures");
        count(s, a.fields().size(), "distance field", "distance fields");
        count(s, a.shaders().size(), "shader", "shaders");
        count(s, a.looks().size(), "look", "looks");
        return s.toString();
    }

    private static void count(StringBuilder s, int n, String one, String many) {
        if (n > 0) {
            s.append(" · ").append(n).append(' ').append(n == 1 ? one : many);
        }
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && Arrays.equals(b, 0, prefix.length, prefix, 0, prefix.length);
    }
}
