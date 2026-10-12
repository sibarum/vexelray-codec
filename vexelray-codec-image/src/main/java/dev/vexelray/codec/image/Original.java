package dev.vexelray.codec.image;

import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A still picture at its own full size, kept while it is looked at closely: what any part of it is drawn from at any
 * size ({@link #detail}), where a {@link Picture} is a copy shrunk once to fit a sheet.
 *
 * <p>A raster is its decoded pixels, which for a large photograph is a great deal of memory (a 24 megapixel one is
 * 96 MB), so hold one at a time. A vector is its document, rasterised at the size a detail needs, up to a
 * {@linkplain Picture#SHEET_SIDE sheet}, and resampled from there past it. An animation has none: its frames are shown
 * as they come.
 *
 * <p>Blocking and CPU-heavy, both {@link #of} and {@link #detail}: off the GUI thread.
 */
public final class Original {

    /** Which version of which file, as a {@link Picture}'s key is. */
    private final Stamp stamp;
    private final int width;
    private final int height;
    private final boolean vector;
    /** A raster's pixels; for a vector, its last rasterisation. Guarded by this. */
    private byte[] rgba;
    private int rgbaW;
    private int rgbaH;
    /** A vector's document; null for a raster. */
    private final byte[] svg;

    private Original(Stamp stamp, int width, int height, byte[] rgba, byte[] svg) {
        this.stamp = stamp;
        this.width = width;
        this.height = height;
        this.vector = svg != null;
        this.rgba = rgba;
        this.rgbaW = rgba == null ? 0 : width;
        this.rgbaH = rgba == null ? 0 : height;
        this.svg = svg;
    }

    /**
     * {@code path} at its own size, or null if it is not a still picture or does not decode: too large to read, an
     * animation, not an image at all. Never throws for what is in the file.
     */
    public static Original of(Path path) {
        try {
            if (Files.isDirectory(path) || Files.size(path) > Pictures.MAX_IMAGE) {
                return null;
            }
            Stamp stamp = Stamp.of(path);
            byte[] bytes = Files.readAllBytes(path);
            Imagelib.Kind kind = Imagelib.probe(bytes);
            if (kind == Imagelib.Kind.VECTOR) {
                Imagelib.Size size = Imagelib.svgSize(bytes);
                return new Original(stamp, Math.max(1, Math.round(size.width())),
                        Math.max(1, Math.round(size.height())), null, bytes);
            }
            if (kind == Imagelib.Kind.UNKNOWN) {
                return null;
            }
            Frames frames = Imagelib.decode(bytes);
            if (frames.animated()) {
                return null;
            }
            return new Original(stamp, frames.width(), frames.height(), frames.rgba(0), null);
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
            return null;
        }
    }

    /** The file this is, and which version of it. */
    public Stamp stamp() {
        return stamp;
    }

    /** Its own size: a raster's pixels, a vector's document units. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The part of the picture from {@code (u0, v0)} to {@code (u1, v1)}, in fractions of its width and height, as an
     * {@code outW x outH} picture: resampled from the full-size pixels with {@code filter} ({@link Resample#of}), or
     * for a vector rasterised at the size the part is drawn at, as near as a sheet allows, and resampled the rest of
     * the way. Null if it cannot be made.
     */
    public Picture detail(dev.vexelray.canvas.ImageFilter filter, float u0, float v0, float u1, float v1,
                          int outW, int outH) {
        if (!(u1 > u0) || !(v1 > v0) || outW <= 0 || outH <= 0) {
            return null;
        }
        try {
            byte[] pixels;
            int pw;
            int ph;
            synchronized (this) {
                if (vector) {
                    // The whole document at the size this part of it is drawn at, as far as a sheet allows.
                    double wholeW = outW / (u1 - u0);
                    double wholeH = outH / (v1 - v0);
                    double fit = Math.min(1, Picture.SHEET_SIDE / Math.max(wholeW, wholeH));
                    int rw = Math.max(1, (int) Math.round(wholeW * fit));
                    int rh = Math.max(1, (int) Math.round(wholeH * fit));
                    if (rgba == null || rgbaW != rw || rgbaH != rh) {
                        rgba = null;   // let the last go before making the next
                        rgba = Imagelib.rasterize(svg, rw, rh).rgba(0);
                        rgbaW = rw;
                        rgbaH = rh;
                    }
                }
                pixels = rgba;
                pw = rgbaW;
                ph = rgbaH;
            }
            byte[] out = Resample.of(filter, pixels, pw, ph, u0 * pw, v0 * ph, (u1 - u0) * pw, (v1 - v0) * ph,
                    outW, outH);
            return new Picture(new Part(stamp, u0, v0, u1, v1), out, outW, outH, 1, 1, new int[1], width, height,
                    vector);
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            return null;
        }
    }

    /** A detail's key: which part of which version of which file. */
    private record Part(Stamp stamp, float u0, float v0, float u1, float v1) {
    }
}
