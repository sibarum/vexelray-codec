package dev.vexelray.codec.image;

import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;
import sibarum.probe.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pixels from a file, a resource or bytes in hand: anything imagelib can decode (PNG, JPEG, GIF, WebP, SVG and the
 * rest), animated where the source is, as a {@link Picture} ready to upload at each size asked for.
 *
 * <p><b>Sizes are the caller's.</b> Each is the longest side a frame keeps, and each comes back as its own
 * {@link Picture}, all from one decode, which is the expensive part: a viewer asks for a thumbnail's size and its own
 * in one call. A raster is scaled down to a size and never up. A vector has no size until one is given, so it is
 * rasterised once, at the largest size asked, and the smaller ones are shrinks of it. Asking no size at all means the
 * source's own, as far as a sheet holds ({@link Picture#SHEET_SIDE}).
 *
 * <p><b>Nothing executed.</b> The first {@link #SAMPLE} bytes of a file say whether imagelib wants it; only then is the
 * whole of it read, up to {@link #MAX_IMAGE}, and handed to imagelib, whose decoders are pure Rust that turn bytes into
 * pixels and run nothing the file names.
 *
 * <p><b>Every failure is a reason, not an exception.</b> A file that says it is an image and will not decode, one too
 * large to hold, or a machine where the native decoder will not load: each comes back as a {@link Decoded} with no
 * picture and a line saying why, so the GUI says so and the next file is unaffected.
 *
 * <p>Blocking: run it on {@code Gui.offload()}.
 */
public final class Pictures {

    /** How much of a file is read to decide what it is: enough for an SVG whose first element is far in. */
    public static final int SAMPLE = 64 * 1024;

    /** The largest image file decoded. Past it the file is refused; its pixels would not fit anyway. */
    public static final long MAX_IMAGE = 128L * 1024 * 1024;

    /**
     * What a folder is listed for: the extensions of what imagelib decodes, as its Cargo features name them. A guess
     * from the name, which is what a listing can afford; the bytes decide when the file is shown.
     */
    static final Set<String> EXTENSIONS = Set.of("png", "apng", "jpg", "jpeg", "jfif", "gif", "webp", "bmp", "ico",
            "tif", "tiff", "tga", "qoi", "pnm", "pbm", "pgm", "ppm", "pam", "svg", "svgz");

    private static final Log LOG = Log.of("codec.image");

    /** Said once: a decoder that will not load fails the same way for every file after the first. */
    private static volatile boolean decoderReported;

    private Pictures() {
    }

    /** The extensions {@link #named} lists, without the dot, in order: an open dialog's filter. */
    public static List<String> extensions() {
        return EXTENSIONS.stream().sorted().toList();
    }

    /** Whether {@code path} is named like an image this decodes: what a folder of pictures is listed for. */
    public static boolean named(Path path) {
        Path n = path.getFileName();
        if (n == null) {
            return false;
        }
        String s = n.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(s.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * {@code path} at each of {@code sides}, from one decode. The pictures are keyed by the file's {@link Stamp}, so a
     * file changed since is a different key.
     */
    public static Decoded of(Path path, int... sides) {
        try {
            if (Files.isDirectory(path)) {
                return Decoded.none(path, "a folder");
            }
            byte[] sample;
            try (InputStream in = Files.newInputStream(path)) {
                sample = in.readNBytes(SAMPLE);
            }
            return decode(path, sample, Files.size(path), sides);
        } catch (IOException | RuntimeException e) {
            return Decoded.none(path, "unreadable: " + e.getClass().getSimpleName());
        }
    }

    /**
     * {@code path} from {@code sample}, its first bytes (at least {@link #SAMPLE} of them, or the whole file), and its
     * {@code size}: for a caller that has read the start of the file already to decide what else it might be.
     */
    public static Decoded decode(Path path, byte[] sample, long size, int... sides) {
        Imagelib.Kind kind = probe(sample);
        if (kind == null) {
            return Decoded.none(path, "the image decoder is unavailable");
        }
        if (kind == Imagelib.Kind.UNKNOWN) {
            return Decoded.none(path, "not an image");
        }
        if (size > MAX_IMAGE) {
            return Decoded.none(path, "too large to show");
        }
        byte[] bytes;
        Stamp stamp;
        try {
            stamp = Stamp.of(path);
            bytes = Files.readAllBytes(path);
        } catch (IOException | RuntimeException e) {
            return Decoded.none(path, "unreadable: " + e.getClass().getSimpleName());
        } catch (OutOfMemoryError e) {
            LOG.warn("{} is too large to read", path);
            return Decoded.none(path, "too large to decode");
        }
        return decode(path, stamp, bytes, kind, sides);
    }

    /**
     * {@code bytes}, already in hand, at each of {@code sides}. The pictures are keyed by {@code name}, so two calls
     * with one name are taken to be the same image: name each distinct image distinctly.
     */
    public static Decoded of(String name, byte[] bytes, int... sides) {
        Imagelib.Kind kind = probe(bytes);
        if (kind == null) {
            return Decoded.none(name, "the image decoder is unavailable");
        }
        if (kind == Imagelib.Kind.UNKNOWN) {
            return Decoded.none(name, "not an image");
        }
        return decode(name, name, bytes, kind, sides);
    }

    /**
     * The class-path resource {@code name}, resolved as {@code anchor.getResource(name)} resolves it (relative to
     * {@code anchor}'s package, or absolute with a leading {@code /}): an image the application ships. Keyed by the
     * resource's URL. A resource that is not there is a reason, like any other failure.
     */
    public static Decoded resource(Class<?> anchor, String name, int... sides) {
        URL url = anchor.getResource(name);
        if (url == null) {
            return Decoded.none(name, "no such resource");
        }
        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return Decoded.none(name, "unreadable: " + e.getClass().getSimpleName());
        }
        return of(url.toString(), bytes, sides);
    }

    /** What {@code sample} is, or null if the decoder will not load. */
    private static Imagelib.Kind probe(byte[] sample) {
        try {
            return Imagelib.probe(sample);
        } catch (RuntimeException | LinkageError e) {
            unavailable(e);
            return null;
        }
    }

    private static Decoded decode(Object source, Object key, byte[] bytes, Imagelib.Kind kind, int[] sides) {
        try {
            List<Picture> pictures = new ArrayList<>(Math.max(1, sides.length));
            String what;
            if (kind == Imagelib.Kind.VECTOR) {
                Imagelib.Size intrinsic = Imagelib.svgSize(bytes);
                int w = Math.round(intrinsic.width());
                int h = Math.round(intrinsic.height());
                // Rasterised once, at the largest size asked for; the others are shrinks of it.
                int side = sides.length == 0 ? Math.max(1, Math.max(w, h)) : largest(sides);
                int[] box = vectorBox(intrinsic.width(), intrinsic.height(), Math.min(side, Picture.SHEET_SIDE));
                Frames frames = Imagelib.rasterize(bytes, box[0], box[1]);
                for (int s : sides(sides)) {
                    pictures.add(Picture.of(key, frames, w, h, true, s));
                }
                what = "SVG image";
            } else {
                Frames frames = Imagelib.decode(bytes);
                for (int s : sides(sides)) {
                    pictures.add(Picture.of(key, frames, frames.width(), frames.height(), false, s));
                }
                what = identify(bytes);
            }
            return new Decoded(source, describe(what, pictures.getFirst()), pictures);
        } catch (OutOfMemoryError e) {
            // The one error worth catching: it is this source's pixels that did not fit, and the next one's will.
            LOG.warn("{} is too large to decode", source);
            return Decoded.none(source, "too large to decode");
        } catch (RuntimeException | LinkageError e) {
            if (e instanceof LinkageError) {
                unavailable(e);
            }
            return Decoded.none(source, "does not decode as an image");
        }
    }

    /** The sizes to pack: those asked for, or one as large as a sheet holds. */
    private static int[] sides(int[] sides) {
        return sides.length == 0 ? new int[] {Picture.SHEET_SIDE} : sides;
    }

    private static int largest(int[] sides) {
        int max = 1;
        for (int s : sides) {
            max = Math.max(max, s);
        }
        return max;
    }

    /** A vector's raster size: its own aspect, with the long side at {@code side}, and never zero. */
    static int[] vectorBox(float width, float height, int side) {
        float w = width > 0f ? width : 1f;
        float h = height > 0f ? height : 1f;
        float scale = side / Math.max(w, h);
        return new int[] {Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
    }

    /** "PNG image · 1920 × 1080", and for an animation its frame count and length. */
    static String describe(String what, Picture p) {
        StringBuilder s = new StringBuilder(what).append(" · ").append(p.sourceWidth()).append(" × ")
                .append(p.sourceHeight());
        if (p.animated()) {
            long ms = 0;
            for (int d : p.delays()) {
                ms += d;
            }
            s.append(" · ").append(p.count()).append(" frames");
            if (ms > 0) {
                s.append(String.format(Locale.ROOT, ", %.1f s", ms / 1000d));
            }
        }
        return s.toString();
    }

    /** What the first bytes say the image is. Small on purpose: a signature table is a promise to keep it right. */
    static String identify(byte[] b) {
        if (starts(b, 0x89, 'P', 'N', 'G')) {
            return "PNG image";
        }
        if (starts(b, 0xFF, 0xD8, 0xFF)) {
            return "JPEG image";
        }
        if (starts(b, 'G', 'I', 'F', '8')) {
            return "GIF image";
        }
        if (starts(b, 'R', 'I', 'F', 'F') && b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P') {
            return "WebP image";
        }
        if (starts(b, 'B', 'M')) {
            return "BMP image";
        }
        return "image";
    }

    private static boolean starts(byte[] b, int... signature) {
        if (b.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((b[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static void unavailable(Throwable e) {
        if (!decoderReported) {
            decoderReported = true;
            LOG.warn("no pictures: the image decoder is unavailable ({})", e.toString());
        }
    }
}
