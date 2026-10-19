package dev.vexelray.codec.image;

import sibarum.imagelib.Frames;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * A decoded image, made ready to upload: every frame packed into one RGBA8 sheet, scaled down to what the window can
 * show.
 *
 * <p><b>One sheet, however many frames.</b> A texture is uploaded once and never rewritten, so an animation is shown
 * as one texture and a moving rectangle of it ({@code ImageRegion.cell(i, columns, rows)}), and a still is the sheet
 * of one. The packing happens here, off the GUI thread, so the GUI thread's part is a single upload.
 *
 * <p><b>Scaled down, never up.</b> A 6000 px photograph is 144 MB of RGBA and a thumbnail shows a few hundred pixels of
 * it, and a texture larger than the device allows does not upload at all. So each frame is box-filtered to at most the
 * side asked for on its long side, and an animation further until its sheet fits in {@link #SHEET_SIDE}.
 *
 * <p>Pixels are straight (non-premultiplied) RGBA8, top row first, as imagelib gives them and as the GUI uploads them.
 * The arrays are live: do not write to them.
 *
 * @param key          which version of what this was decoded from, compared by value: the cache key. A file's
 *                     {@link Stamp}, or the name of bytes in hand
 * @param sheet        {@code sheetWidth * sheetHeight * 4} bytes
 * @param frameWidth   one cell's width in px
 * @param frameHeight  one cell's height in px
 * @param columns      cells across the sheet
 * @param rows         cells down the sheet
 * @param delays       each frame's display time in ms, 0 where the file gave none; the length is the frame count
 * @param sourceWidth  the size the file says it is: for display, and for saying so in a header
 * @param sourceHeight the same, down
 * @param vector       whether this was rasterised from a document with no pixel size of its own
 * @param motion       when this is the first frame of a file that plays, what the file says about its playing; a
 *                     {@link Movie} plays it. Null for a still, and for an animation packed whole into the sheet
 */
public record Picture(Object key, byte[] sheet, int frameWidth, int frameHeight, int columns, int rows,
                      int[] delays, int sourceWidth, int sourceHeight, boolean vector, Motion motion) {

    /** The longest side of a sheet. Every Vulkan device samples at least 4096. */
    public static final int SHEET_SIDE = 4096;

    /** Frames past this are thinned evenly, their time folded into the frames kept, so the loop keeps its length. */
    public static final int MAX_FRAMES = 128;

    /** A picture that does not play: a still, or every frame packed into the sheet. */
    public Picture(Object key, byte[] sheet, int frameWidth, int frameHeight, int columns, int rows, int[] delays,
                   int sourceWidth, int sourceHeight, boolean vector) {
        this(key, sheet, frameWidth, frameHeight, columns, rows, delays, sourceWidth, sourceHeight, vector, null);
    }

    /** How many frames in the sheet. 1 for a still, and for the poster of a file that {@linkplain #plays plays}. */
    public int count() {
        return delays.length;
    }

    /** Whether the sheet holds more than one frame, stepped through a cell at a time. */
    public boolean animated() {
        return delays.length > 1;
    }

    /** Whether this is the first frame of a file a {@link Movie} plays: {@link #motion} says how. */
    public boolean plays() {
        return motion != null;
    }

    /**
     * Whether, fitted, this may be shown larger than its own pixels to fill its stage: a {@link #vector}, which has no
     * pixel size of its own, and a video ({@link Motion#fills}). A raster still or an animated image never is.
     */
    public boolean enlarges() {
        return vector || motion != null && motion.fills();
    }

    /** Whether this moves at all, one way or the other: a part made sharper from the file's own pixels would not. */
    public boolean moves() {
        return animated() || plays();
    }

    public int sheetWidth() {
        return frameWidth * columns;
    }

    public int sheetHeight() {
        return frameHeight * rows;
    }

    /** What this holds in memory, and what an upload of it costs. */
    public long bytes() {
        return sheet.length;
    }

    /**
     * Pack {@code frames} with each frame at most {@code side} on its long side (0 or less: as large as a sheet holds).
     * {@code vector} only says where the pixels came from. One decode can make several sizes, which is why the size is
     * a parameter and not a decode.
     */
    public static Picture of(Object key, Frames frames, int sourceWidth, int sourceHeight, boolean vector, int side) {
        if (side <= 0) {
            side = SHEET_SIDE;
        }
        int[] keep = thin(frames.count());
        int n = keep.length;
        int[] delays = new int[n];
        for (int k = 0; k < n; k++) {
            int end = k + 1 < n ? keep[k + 1] : frames.count();
            for (int i = keep[k]; i < end; i++) {
                delays[k] += frames.delayMillis(i);
            }
        }
        int columns = (int) Math.ceil(Math.sqrt(n));
        int rows = (n + columns - 1) / columns;
        int w = frames.width();
        int h = frames.height();
        double scale = Math.min(1d, Math.min((double) side / Math.max(w, h),
                Math.min((double) SHEET_SIDE / ((long) columns * w), (double) SHEET_SIDE / ((long) rows * h))));
        int fw = Math.max(1, (int) Math.round(w * scale));
        int fh = Math.max(1, (int) Math.round(h * scale));
        int sheetW = fw * columns;
        byte[] sheet = new byte[sheetW * fh * rows * 4];
        for (int k = 0; k < n; k++) {
            int x = (k % columns) * fw;
            int y = (k / columns) * fh;
            fit(frames.rgba(keep[k]), w, h, sheet, sheetW, x, y, fw, fh);
        }
        return new Picture(key, sheet, fw, fh, columns, rows, delays, sourceWidth, sourceHeight, vector);
    }

    /** Which source frames to keep: all of them up to {@link #MAX_FRAMES}, and an even spread past it. */
    static int[] thin(int count) {
        int n = Math.min(count, MAX_FRAMES);
        int[] keep = new int[n];
        for (int k = 0; k < n; k++) {
            keep[k] = (int) ((long) k * count / n);
        }
        return keep;
    }

    /**
     * A {@code w x h} image in native memory as an {@code fw x fh} one, box-filtered as {@link #fit(byte[], int, int,
     * byte[], int, int, int, int, int) fit} does: for a frame read where its decoder left it. The rows each output row
     * covers are copied out together, so a canvas of any size is read once and never whole.
     */
    static byte[] fit(MemorySegment src, int w, int h, int fw, int fh) {
        long rowBytes = (long) w * 4;
        if (fw == w && fh == h) {
            return src.asSlice(0, rowBytes * h).toArray(ValueLayout.JAVA_BYTE);
        }
        byte[] dst = new byte[fw * fh * 4];
        byte[] band = new byte[0];
        for (int dy = 0; dy < fh; dy++) {
            int sy0 = (int) ((long) dy * h / fh);
            int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * h / fh));
            int bandBytes = (int) (rowBytes * (sy1 - sy0));
            if (band.length < bandBytes) {
                band = new byte[bandBytes];
            }
            MemorySegment.copy(src, ValueLayout.JAVA_BYTE, rowBytes * sy0, band, 0, bandBytes);
            fit(band, w, sy1 - sy0, dst, fw, 0, dy, fw, 1);
        }
        return dst;
    }

    /**
     * Copy a {@code w x h} image into the {@code fw x fh} cell at {@code (x, y)} of {@code dst}, box-filtering when it
     * shrinks. The average is taken premultiplied and divided back out, so a transparent pixel's colour, which means
     * nothing, does not bleed into the edge of the opaque one beside it.
     */
    static void fit(byte[] src, int w, int h, byte[] dst, int dstWidth, int x, int y, int fw, int fh) {
        if (fw == w && fh == h) {
            for (int row = 0; row < h; row++) {
                System.arraycopy(src, row * w * 4, dst, ((y + row) * dstWidth + x) * 4, w * 4);
            }
            return;
        }
        for (int dy = 0; dy < fh; dy++) {
            int sy0 = (int) ((long) dy * h / fh);
            int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * h / fh));
            for (int dx = 0; dx < fw; dx++) {
                int sx0 = (int) ((long) dx * w / fw);
                int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * w / fw));
                long r = 0, g = 0, b = 0, a = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int p = (sy * w + sx0) * 4;
                    for (int sx = sx0; sx < sx1; sx++, p += 4) {
                        int alpha = src[p + 3] & 0xFF;
                        r += (src[p] & 0xFF) * alpha;
                        g += (src[p + 1] & 0xFF) * alpha;
                        b += (src[p + 2] & 0xFF) * alpha;
                        a += alpha;
                    }
                }
                long area = (long) (sx1 - sx0) * (sy1 - sy0);
                int q = ((y + dy) * dstWidth + x + dx) * 4;
                if (a > 0) {
                    dst[q] = (byte) ((r + a / 2) / a);
                    dst[q + 1] = (byte) ((g + a / 2) / a);
                    dst[q + 2] = (byte) ((b + a / 2) / a);
                }
                dst[q + 3] = (byte) ((a + area / 2) / area);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        Object name = key instanceof Stamp s && s.path().getFileName() != null ? s.path().getFileName() : key;
        return "Picture[" + name + ", " + sourceWidth + "x" + sourceHeight + ", " + count()
                + " frame" + (count() == 1 ? "" : "s") + " in " + sheetWidth() + "x" + sheetHeight()
                + ", delays " + (delays.length > 8 ? delays.length + " values" : Arrays.toString(delays)) + "]";
    }
}
