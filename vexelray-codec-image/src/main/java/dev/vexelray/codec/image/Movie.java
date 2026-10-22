package dev.vexelray.codec.image;

import dev.vexelray.vulkan.present.MipChain;
import sibarum.imagelib.Animation;
import sibarum.imagelib.ImageException;
import sibarum.imagelib.Imagelib;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A file that plays, made a frame at a time on a thread of its own: whoever shows it says which frame it wants
 * ({@link #want}) and takes the frames that are ready ({@link #frame}), and never waits for a decode. What a
 * {@link Picture} with a {@link Motion} is played by, as a {@link Playable}.
 *
 * <p><b>Any length, any size.</b> The decoder holds one canvas ({@link Animation}), each frame is scaled to the side
 * it is shown at and given its mip chain here, off the GUI thread, and only the frames kept are held. Nothing about
 * the file's frame count or canvas size is held whole.
 *
 * <p><b>A few frames ahead.</b> The thread makes the wanted frame and the {@link #AHEAD} after it, round to the start
 * when the caller loops, then sleeps until the wanted frame moves.
 *
 * <p><b>Kept, within a budget, so seeking back is cheap.</b> A GIF or APNG frame is drawn over the frames before it,
 * so a frame behind the decoder is made by starting again and walking forward. Every frame made is kept, the least
 * recently shown given up first past {@link #BUDGET}, so a short animation is decoded once and then only shown, and a
 * long one seeks back at once within the last few hundred megabytes of what it showed. A seek outside that walks
 * forward from the start, making only the frames it lands on.
 *
 * <p><b>Times as a player shows them.</b> A delay under 20 ms, which GIF writers use to mean "as fast as you like",
 * is 100 ms here as it is in browsers ({@link #timed}); every time this reports is after that.
 *
 * <p>Every method may be called from any thread.
 */
public final class Movie implements Playable {

    /** Memory for frames already made, by default: what makes seeking back cheap. */
    public static final long BUDGET = 512L * 1024 * 1024;

    /** Frames made beyond the one wanted. */
    public static final int AHEAD = 3;

    /** GIF's degenerate delays (0 and 10 ms) mean "as fast as you like"; browsers show them at 100 ms, as does this. */
    static final int FLOOR_MS = 20;
    static final int DEGENERATE_MS = 100;

    private static final Log LOG = Log.of("codec.image");

    private final Stamp stamp;
    private final Animation animation;
    private final int width;
    private final int height;
    private final int[] delays;
    /** When each frame starts, in ms from the start of a pass. */
    private final long[] starts;
    private final long duration;
    private final int loops;
    private final long budget;
    private final Thread thread;

    private final Object lock = new Object();
    // Guarded by lock.
    /** Frames made, each its whole mip chain, the least recently shown first. */
    private final LinkedHashMap<Integer, byte[][]> kept = new LinkedHashMap<>(16, 0.75f, true);
    private long keptBytes;
    private int wanted;
    private boolean wrap = true;
    /** How many frames can be made: the count the file declared, or fewer if it is damaged after them. */
    private int frames;
    private String failure;
    private boolean closed;

    private Movie(Stamp stamp, Animation animation, int side, long budget) {
        this.stamp = stamp;
        this.animation = animation;
        int[] size = fitted(animation.width(), animation.height(), side);
        this.width = size[0];
        this.height = size[1];
        int n = animation.count();
        this.delays = new int[n];
        this.starts = new long[n];
        long t = 0;
        for (int i = 0; i < n; i++) {
            starts[i] = t;
            delays[i] = timed(animation.delayMillis(i));
            t += delays[i];
        }
        this.duration = t;
        this.loops = animation.loops();
        this.budget = budget;
        this.frames = n;
        this.thread = Thread.ofPlatform().daemon().name("movie " + stamp.path().getFileName()).start(this::run);
    }

    /**
     * Open the file {@code motion} came from, its frames scaled to at most {@code side} on their long side. Blocking: it
     * reads the whole file; run it on {@code Gui.offload()}. Throws {@link IOException} if the file cannot be read and
     * {@link ImageException} if it does not decode. The caller closes it.
     */
    public static Movie open(Motion motion, int side) throws IOException {
        return open(motion, side, BUDGET);
    }

    /** {@link #open(Motion, int)}, keeping at most {@code budget} bytes of frames already made. */
    public static Movie open(Motion motion, int side, long budget) throws IOException {
        byte[] bytes = Files.readAllBytes(motion.stamp().path());
        Animation a = Imagelib.animate(bytes);
        try {
            if (a.count() == 0) {
                // Changed since its poster was made, or never had any: nothing would ever come to show.
                throw new ImageException("the file has no frames");
            }
            return new Movie(motion.stamp(), a, side, budget);
        } catch (RuntimeException | Error e) {
            a.close();
            throw e;
        }
    }

    /** What {@code a}, opened from {@code stamp}, says about its playing, timed as this times it. */
    public static Motion motion(Stamp stamp, Animation a) {
        long ms = 0;
        for (int i = 0; i < a.count(); i++) {
            ms += timed(a.delayMillis(i));
        }
        return new Motion(stamp, a.count(), ms, a.loops());
    }

    /**
     * A {@code w x h} frame's size scaled down, never up, to {@code side} on its long side (0 or less: its own), and to
     * what a GPU holds.
     */
    public static int[] fitted(int w, int h, int side) {
        int most = side > 0 ? Math.min(side, Picture.SHEET_SIDE) : Picture.SHEET_SIDE;
        double scale = Math.min(1d, (double) most / Math.max(1, Math.max(w, h)));
        return new int[] {Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    /** How long a frame the file times at {@code fileMs} is shown: as given, or 100 ms for a degenerate delay. */
    public static int timed(int fileMs) {
        return fileMs < FLOOR_MS ? DEGENERATE_MS : fileMs;
    }

    public Stamp stamp() {
        return stamp;
    }

    /** The size every frame is made at. */
    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /** The file's own canvas size. */
    public int sourceWidth() {
        return animation.width();
    }

    public int sourceHeight() {
        return animation.height();
    }

    /** How many frames can be shown: the count the file declared, or fewer if it turned out damaged after them. */
    @Override
    public int count() {
        synchronized (lock) {
            return frames;
        }
    }

    /** One pass, in ms, over the frames the file declared. */
    @Override
    public long duration() {
        return duration;
    }

    /** How many times the file asks to be played: 0 for forever. */
    public int loops() {
        return loops;
    }

    /** How long frame {@code i} is shown, in ms. */
    @Override
    public int delay(int i) {
        return delays[i];
    }

    /** When frame {@code i} starts, in ms from the start of a pass. */
    @Override
    public long startOf(int i) {
        return starts[i];
    }

    /** The frame shown {@code ms} into a pass: the last one there is, at or past the end. */
    @Override
    public int frameAt(long ms) {
        int i = Arrays.binarySearch(starts, Math.max(0, ms));
        int at = i >= 0 ? i : -i - 2;
        return Math.min(at, Math.max(0, count() - 1));
    }

    /** Bytes in each frame made: every level of its mip chain. */
    public long frameBytes() {
        long total = 0;
        for (int l = 0; l < MipChain.count(width, height); l++) {
            total += (long) MipChain.size(width, l) * MipChain.size(height, l) * 4;
        }
        return total;
    }

    /**
     * Make frame {@code i} next, and the {@link #AHEAD} after it: round to frame 0 past the end with {@code wrap}, or
     * stopping at the last frame without.
     */
    @Override
    public void want(int i, boolean wrap) {
        synchronized (lock) {
            if (wanted == i && this.wrap == wrap) {
                return;
            }
            wanted = Math.max(0, i);
            this.wrap = wrap;
            lock.notifyAll();
        }
    }

    /**
     * Frame {@code i}'s mip chain ({@link MipChain}), {@link #width} by {@link #height} at level 0, or null if it has
     * not been made. The arrays are live: do not write to them.
     */
    @Override
    public byte[][] frame(int i) {
        synchronized (lock) {
            return kept.get(i);
        }
    }

    /** Why the file stopped decoding early, or null if it has not. */
    @Override
    public String failure() {
        synchronized (lock) {
            return failure;
        }
    }

    /** None: every frame of an animated image is shown, a late one slowing it down. */
    @Override
    public boolean drops() {
        return false;
    }

    /** How many frames are kept; for a test. */
    int keptFrames() {
        synchronized (lock) {
            return kept.size();
        }
    }

    /** Stop decoding and close the file, on the movie's own thread. Closing twice does nothing. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            kept.clear();
            keptBytes = 0;
            lock.notifyAll();
        }
    }

    /** Whether the thread has stopped and closed the file; for a test, which can wait on it. */
    boolean stopped(long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    /** The first frame of the wanted window not yet made, or -1. Under lock. */
    private int missing() {
        if (frames == 0) {
            return -1;
        }
        for (int k = 0; k <= AHEAD; k++) {
            int j = wanted + k;
            if (j >= frames) {
                if (!wrap) {
                    return -1;
                }
                j %= frames;
            }
            if (!kept.containsKey(j)) {
                return j;
            }
        }
        return -1;
    }

    /** Whether frame {@code j} is one the window wants. Under lock. */
    private boolean wanted(int j) {
        int d = j - wanted;
        if (d < 0 && wrap) {
            d += frames;
        }
        return d >= 0 && d <= AHEAD;
    }

    private void run() {
        try {
            while (true) {
                int target;
                synchronized (lock) {
                    while (!closed && missing() < 0) {
                        lock.wait();
                    }
                    if (closed) {
                        return;
                    }
                    target = missing();
                }
                walkTo(target);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Error e) {
            // The decoder itself has gone (its native library, the stack): nothing more is made, and whoever plays
            // this is told so, rather than waiting on a thread that is no longer there.
            ended(0, e.toString());
        } finally {
            animation.close();
        }
    }

    /**
     * Decode to {@code target}, starting again if it is behind, and keep every frame passed that the window wants.
     * A frame passed on the way that nobody wants is decoded, since the next is drawn over it, but not scaled or kept.
     */
    private void walkTo(int target) {
        // The frame being made, where a failure ends the file: not the one after it, which would be wanted, made and
        // failed again for ever.
        int at = target;
        try {
            if (animation.index() >= target) {
                animation.rewind();
            }
            while (animation.index() < target) {
                at = animation.index() + 1;
                if (!animation.next()) {
                    ended(at, null);
                    return;
                }
                int k = animation.index();
                boolean keep;
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    keep = k == target || wanted(k);
                }
                if (keep) {
                    byte[] level0 = Picture.fit(animation.pixels(), animation.width(), animation.height(), width,
                            height);
                    keep(k, MipChain.of(level0, width, height));
                }
            }
        } catch (RuntimeException e) {
            // A damaged frame (ImageException), or anything else decoding or scaling it threw: the thread goes on.
            ended(at, e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (OutOfMemoryError e) {
            ended(at, "too large to decode");
        }
    }

    private void keep(int k, byte[][] chain) {
        long bytes = 0;
        for (byte[] level : chain) {
            bytes += level.length;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            byte[][] old = kept.put(k, chain);
            keptBytes += bytes - (old == null ? 0 : bytes);
            // The least recently shown go first, but never the window being played: AHEAD frames and the one wanted.
            Iterator<Map.Entry<Integer, byte[][]>> it = kept.entrySet().iterator();
            while (keptBytes > budget && kept.size() > AHEAD + 1 && it.hasNext()) {
                Map.Entry<Integer, byte[][]> e = it.next();
                if (wanted(e.getKey())) {
                    continue;
                }
                it.remove();
                keptBytes -= bytes;
            }
        }
    }

    /** The file ends, or stops decoding, before frame {@code at}: no frame from there on can be made. */
    private void ended(int at, String why) {
        synchronized (lock) {
            if (at < frames) {
                frames = Math.max(0, at);
                if (why == null && frames == 0) {
                    why = "no frames to show";   // ended before the first: a reason, or the player is left guessing
                }
                if (why != null) {
                    failure = why;
                    LOG.warn("{} stopped decoding at frame {}: {}", stamp.path(), at, why);
                }
            }
            lock.notifyAll();
        }
    }

    @Override
    public String toString() {
        return "Movie[" + stamp.path().getFileName() + ", " + count() + " frames, " + duration + "ms, at " + width
                + "x" + height + "]";
    }
}
