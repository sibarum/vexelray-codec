package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Glimpses;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.codec.image.Reasons;
import sibarum.ffmpeg.Video;
import sibarum.ffmpeg.VideoInfo;
import sibarum.probe.Log;

import java.io.IOException;
import dev.vexelray.vulkan.present.PlaneLayout;
import sibarum.ffmpeg.Planes;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A video as a picture that plays: its frames by number, at the rate the stream declares, each given its mip chain on
 * a thread of its own. What a poster's {@link Motion} opens ({@link Videos#poster(java.nio.file.Path, int, boolean)}),
 * so an {@code ImageView} plays a video through the same {@code Playback} as an animated GIF: the same transport,
 * stepping, looping and holding, posed, zoomed and turned like any picture.
 *
 * <p><b>Frames by number.</b> Frame {@code i} is what shows at {@code i / rate} seconds: the last frame decoded whose
 * time is at or before then. A stream whose frames are not evenly spaced still plays at its own times, a frame
 * standing for every number its time covers.
 *
 * <p><b>Forward by decoding, anywhere else by seeking.</b> The thread decodes the wanted frame and the {@link #AHEAD}
 * after it, then sleeps until the wanted frame moves. A frame a little ahead of the decoder is decoded up to; one
 * behind it, or further ahead than {@link #RUN_SECONDS}, is sought ({@code Video.seek} is exact); and so is one ahead by
 * more frames than seeking has lately cost ({@link #skips}). The wanted frame is kept the moment it is decoded, even if
 * the window has moved past it by then: a decoder slower than the rate makes every frame a little late, and the player
 * shows the newest made, late, rather than none.
 *
 * <p><b>Decoded, and converted only to be shown.</b> Every frame is decoded ({@code Video.decode}), and only one the
 * window wants is converted: scaled to RGBA in one threaded pass straight into a chain's first level
 * ({@code Video.convert}), then given its mips ({@link OpaqueMips}). A frame passed on the way costs its decoding
 * alone. Chains let go of are written again rather than made anew.
 *
 * <p><b>Kept, within a budget</b>, as a {@code Movie} keeps its frames: the least recently shown go first past
 * {@link #BUDGET}, so stepping back a few frames, or round a short loop, costs no decode.
 *
 * <p><b>With its sound, when it has one.</b> {@link #open} opens the file's sound on the speakers as well, and
 * {@link #clock} gives it to the {@code Playback}, which then shows the frames the sound has reached. A file with no
 * sound, or a machine with no sound device, plays its pictures on the playback's own clock, silently.
 *
 * <p>Owns the clip, and closes it on its own thread once {@linkplain #close closed}; and the sound, which it closes
 * then too. Every method may be called from any thread.
 */
public final class Footage implements Playable {

    /** Memory for frames already made: at 1080p, with mips, about two dozen. */
    public static final long BUDGET = 256L * 1024 * 1024;

    /** Frames made beyond the one wanted. */
    public static final int AHEAD = 4;

    /** How far ahead of the decoder a frame is decoded up to rather than sought. */
    static final double RUN_SECONDS = 2;

    /** How many chains let go of are kept to be written again. */
    private static final int SPARE = 4;

    /**
     * What a video's poster carries as its motion: one instance, so two posters of a file are equal. It plays the file
     * as {@code Footage}, and glimpses it as {@link VideoGlimpses}.
     */
    public static final Motion.Source SOURCE = new Motion.Source() {
        @Override
        public Playable open(Motion motion, int side) throws IOException {
            return Footage.open(motion, side);
        }

        @Override
        public Glimpses glimpses(Motion motion, int side) throws IOException {
            return VideoGlimpses.open(motion, side);
        }

        @Override
        public String toString() {
            return "Footage.SOURCE";
        }
    };

    private static final Log LOG = Log.of("codec.video");

    private final Clip clip;
    private final int width;
    private final int height;
    private final double rate;
    private final long duration;
    private final int run;
    private final Sound sound;
    private final Thread thread;

    private final Object lock = new Object();
    // Guarded by lock.
    /** Frames made, each its whole mip chain, the least recently shown first. */
    private final LinkedHashMap<Integer, byte[][]> kept = new LinkedHashMap<>(16, 0.75f, true);
    private long keptBytes;
    private int wanted;
    private boolean wrap = true;
    /** How many frames can be shown: as many as the length and rate make, or fewer if the stream ends before. */
    private int frames;
    private String failure;
    private boolean closed;

    /** Chains let go of, to be written again rather than made: at most {@link #SPARE}. */
    private final ArrayDeque<byte[][]> spare = new ArrayDeque<>();
    /** Whether a frame is given its mips here ({@link #mips}): not when a device feed shows it, whose GPU makes them. */
    private volatile boolean mips = true;
    /** The frames' form when they are kept as the decoder's planes ({@link #planes}); null while they are RGBA. */
    private volatile PlaneLayout form;
    /** The layout of the first frame decoded, or null when the GPU cannot convert it; known once {@link #first} is. */
    private volatile PlaneLayout firstLayout;
    /** Counted down when the first frame is decoded, or the decoding ends without one. */
    private final CountDownLatch first = new CountDownLatch(1);
    /** The chain {@link #frame} last gave out, which the player may be copying: never written again until another is. */
    private byte[][] lent;

    // The decoder's, on its own thread only.
    /**
     * Whether a frame has been decoded since the last seek: the last one, whose span is not known until the next one
     * comes. Its number, or -1 for none; its chain if it was converted (only a frame that may be shown is), else null;
     * and whether the video still holds it decoded, so it can still be converted.
     */
    private boolean held;
    private int heldAt = -1;
    private byte[][] heldChain;
    private boolean heldLive;
    /** {@link #held} is kept already, as the frame asked for, before its span was known. */
    private boolean heldKept;
    /** The stream has ended after {@link #held}. */
    private boolean atEnd;
    /**
     * What one frame decoded costs, in ns, as lately measured, 0 before one is; and what coming to the frame sought
     * costs, {@code Long.MAX_VALUE} before a seek is measured. Whether to seek ahead or decode up to it ({@link #skips}).
     */
    private long stepNanos;
    private long seekNanos = Long.MAX_VALUE;

    /**
     * Play {@code clip}, which this owns from here, silently. Its info must say its length and its rate
     * ({@link #plays}).
     */
    public Footage(Clip clip) {
        this(clip, null);
    }

    /** Play {@code clip} and, if not null, its {@code sound}: both this one's from here. */
    Footage(Clip clip, Sound sound) {
        VideoInfo info = clip.info();
        if (!plays(info)) {
            throw new IllegalArgumentException(clip + " does not say how long it is, or how fast it plays");
        }
        this.clip = clip;
        this.width = clip.width();
        this.height = clip.height();
        this.rate = info.frameRate();
        this.duration = Math.max(1, info.durationMicros() / 1000);
        this.frames = count(info);
        this.run = Math.max(AHEAD + 1, (int) Math.round(rate * RUN_SECONDS));
        this.sound = sound;
        this.thread = Thread.ofPlatform().daemon().name("footage " + clip.stamp().path().getFileName())
                .start(this::run);
    }

    /**
     * Open the file {@code motion} came from, its frames at most {@code side} on their long side, and its sound on the
     * speakers if it has any and there are speakers to play it on. Blocking: run it in a {@code @Job}. Throws
     * {@link IOException} with the reason if it does not open as a video that plays; a sound that will not open is
     * not a reason, and the video plays without it.
     */
    public static Footage open(Motion motion, int side) throws IOException {
        Opened o = Videos.open(motion.stamp().path(), side);
        if (!o.video()) {
            throw new IOException(o.identity());
        }
        if (!plays(o.clip().info())) {
            o.clip().close();
            throw new IOException("a video with no length or rate");
        }
        Sound sound = null;
        try {
            sound = o.clip().info().audio() ? Sound.open(motion.stamp().path()) : null;
            return new Footage(o.clip(), sound);
        } catch (RuntimeException | Error e) {
            // No thread to decode on, say: what was opened for it is closed, not left open with nobody to close it.
            if (sound != null) {
                sound.close();
            }
            o.clip().close();
            throw e;
        }
    }

    /** The sound, as the clock the frames keep time by; null when it plays silently. */
    @Override
    public Clock clock() {
        return sound;
    }

    /** Whether a video can be played by number: it says how long it is, and how many frames a second. */
    public static boolean plays(VideoInfo info) {
        return info.timed() && info.durationMicros() > 0 && info.frameRate() > 0 && info.frameRate() <= 1000;
    }

    /** How many frames a video that {@link #plays} has: its length at its rate, at least one. */
    public static int count(VideoInfo info) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(info.durationMicros() * info.frameRate()
                / 1e6)));
    }

    public Clip clip() {
        return clip;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int count() {
        synchronized (lock) {
            return frames;
        }
    }

    @Override
    public long duration() {
        return duration;
    }

    @Override
    public int delay(int i) {
        return (int) Math.max(1, startOf(i + 1) - startOf(i));
    }

    @Override
    public long startOf(int i) {
        return Math.round(Math.max(0, i) * 1000d / rate);
    }

    @Override
    public int frameAt(long ms) {
        int last = Math.max(0, count() - 1);
        int i = (int) Math.min(last, Math.max(0, Math.floor(ms * rate / 1000d)));
        // startOf rounds, so the estimate can be one off either way: the frame is the last one started by then.
        while (i < last && startOf(i + 1) <= ms) {
            i++;
        }
        while (i > 0 && startOf(i) > ms) {
            i--;
        }
        return i;
    }

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

    @Override
    public byte[][] frame(int i) {
        synchronized (lock) {
            byte[][] chain = kept.get(i);
            if (chain != null) {
                lent = chain;
            }
            return chain;
        }
    }

    /** How long {@link #planes} waits for the first frame to be decoded before keeping the frames RGBA. */
    private static final long FIRST_FRAME_SECONDS = 5;

    /**
     * Keep the frames as the decoder's planes from now, packed, for a device feed to convert on the GPU: when the first
     * frame decoded is in a format the GPU converts and the frames are this size, which is not scaled. Waits for that
     * first frame (it is decoded as soon as the footage opens, frame 0 being wanted from the start). What was kept as
     * RGBA meanwhile is let go, and made again as planes when it is wanted.
     */
    @Override
    public PlaneLayout planes(int width, int height) {
        if (width != this.width || height != this.height) {
            return null;
        }
        try {
            if (!first.await(FIRST_FRAME_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        PlaneLayout layout = firstLayout;
        if (layout == null) {
            return null;
        }
        synchronized (lock) {
            form = layout;
            kept.clear();
            keptBytes = 0;
            spare.clear();
            lock.notifyAll();   // what was kept is missing again
        }
        return layout;
    }

    /** On the decoder's thread, after each frame decoded: the first says which layout the frames come in. */
    private void noteFirst(Video video) {
        if (first.getCount() == 0) {
            return;
        }
        Planes p = video.planes();
        PlaneLayout.Format f = PlaneLayout.Format.named(p.format());
        firstLayout = f != null && p.width() == width && p.height() == height
                ? new PlaneLayout(f, p.matrix(), p.fullRange()) : null;
        first.countDown();
    }

    /** The decoder's frame packed into {@code dst}, plane after plane, each row without its stride's padding. */
    private void pack(Planes planes, PlaneLayout layout, byte[] dst) {
        MemorySegment out = MemorySegment.ofArray(dst);
        for (int p = 0; p < layout.planes(); p++) {
            long at = layout.planeOffset(p, width, height);
            int row = layout.rowBytes(p, width);
            int rows = layout.planeHeight(p, height);
            int stride = planes.strides()[p];
            MemorySegment src = planes.data()[p].reinterpret((long) stride * rows);
            for (int r = 0; r < rows; r++) {
                MemorySegment.copy(src, (long) r * stride, out, at + (long) r * row, row);
            }
        }
    }

    /** Shown through a device feed, a frame is converted into its first level and given no mips here. */
    @Override
    public void mips(boolean wanted) {
        mips = wanted;
    }

    @Override
    public String failure() {
        synchronized (lock) {
            return failure;
        }
    }

    /** How many frames are kept; for a test. */
    int keptFrames() {
        synchronized (lock) {
            return kept.size();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            kept.clear();
            spare.clear();
            keptBytes = 0;
            lock.notifyAll();
        }
        if (sound != null) {
            sound.close();
        }
    }

    /** Whether the thread has stopped and closed the clip; for a test, which can wait on it. */
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
            ended(0, Reasons.failed(e), e);
        } finally {
            first.countDown();   // a player waiting to hear the layout is not left waiting for a thread that has gone
            try {
                clip.close();
            } catch (RuntimeException | LinkageError e) {
                // Nothing left to give back, and a thread of its own has nobody to throw to.
                LOG.debug("{} did not close: {}", clip.stamp().path(), e.toString());
            }
        }
    }

    /**
     * Make frame {@code target}: decode up to it, seeking first if it is behind the decoder or far ahead, and keep
     * every frame passed that the window wants. Returns once it is made, or no longer wanted.
     *
     * <p>Every frame is decoded, and only one that may be shown is converted (scaled to RGBA, given its mips): the
     * frame asked for, or one the window wants under its own number. A frame passed on the way costs its decoding
     * alone. The one case that misses is a frame that turns out to stand for later numbers than its own (a stream
     * whose frames are not evenly spaced) when one of those is wanted: then nothing is kept for them, they stay
     * missing, and the decoder seeks back for them, the first frame after a seek being converted.
     */
    private void walkTo(int target) {
        Video video = clip.video();
        try {
            boolean sought = false;
            long soughtAt = 0;
            if (heldAt < 0 || target < heldAt || target > heldAt + run || skips(target)) {
                soughtAt = System.nanoTime();
                video.seek(startOf(target) * 1000);
                held = false;
                heldChain = null;
                heldKept = false;
                heldLive = false;
                heldAt = -1;
                atEnd = false;
                sought = true;
            }
            while (true) {
                if (atEnd) {
                    // Nothing comes after the last frame: it stands for every number from its own to the end.
                    if (!held) {
                        ended(target, null, null);
                        return;
                    }
                    if (heldChain == null && wantedFrom(heldAt)) {
                        reconvertLast(video);
                    }
                    cover(heldAt, Integer.MAX_VALUE);
                    if (!stillWanted(target)) {
                        return;
                    }
                    // Wanted, and not covered: before the last frame. Start again from there.
                    heldAt = -1;
                    return;
                }
                long t0 = System.nanoTime();
                long pts = video.decode();
                if (pts < 0) {
                    heldLive = false;
                    atEnd = true;
                    continue;
                }
                noteFirst(video);
                measure(!held && sought ? soughtAt : t0, !held && sought, target);
                int at = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(pts * rate / 1e6)));
                if (!held) {
                    // The first frame after a seek stands for the number sought, whatever its own rounds to.
                    at = sought ? Math.min(at, target) : at;
                } else if (at <= heldAt) {
                    // Two frames on one number: the later one shows, in place of the earlier if that was kept already.
                    heldChain = null;
                    heldLive = true;
                    if (heldKept) {
                        keepHeld(video);
                    } else if (heldAt == target || wantedNow(heldAt)) {
                        convertHeld(video);
                    }
                    continue;
                } else {
                    cover(heldAt, at - 1);
                }
                held = true;
                heldChain = null;
                heldLive = true;
                heldAt = at;
                heldKept = false;
                if (at == target) {
                    // The frame asked for is kept at once, under its own number, rather than once the next frame says
                    // how long it lasts; and kept even if the window has moved past it meanwhile. A decoder slower than
                    // the rate is always a little late, and a player late for a frame shows it late rather than none.
                    keepHeld(video);
                } else if (wantedNow(at)) {
                    convertHeld(video);   // covered under its number once the next frame comes
                }
                if (!stillWanted(target)) {
                    return;
                }
                if (at > target + AHEAD + 1) {
                    return;   // past the window: what it wanted is made, or the stream skipped it and it is covered
                }
            }
        } catch (RuntimeException e) {
            // A damaged stream (VideoException), or anything else decoding or scaling it threw: the thread goes on.
            // Said as where it broke; FFmpeg's own words, which name the file and its internals, are logged.
            ended(target, target == 0 ? "does not decode" : "damaged at " + Videos.clock(startOf(target) * 1000), e);
            heldAt = -1;
        } catch (OutOfMemoryError e) {
            ended(target, "too large to decode", e);
            heldAt = -1;
        }
    }

    /**
     * The stream has ended, and its last frame, which stands for every number from its own on, was passed without
     * being converted: seek back to it and convert it, leaving the decoder at the end again.
     */
    private void reconvertLast(Video video) {
        video.seek(startOf(heldAt) * 1000);
        if (video.decode() < 0) {
            return;   // nothing at its own time after all: its numbers stay missing, and the end is said for them
        }
        heldLive = true;
        convertHeld(video);
        heldLive = false;
    }

    /**
     * Whether {@code target}, ahead of the decoder, is reached sooner by seeking than by decoding every frame up to it.
     * A seek decodes from the keyframe before it, at a cost that depends on how far back that keyframe is; decoding up
     * to it costs a step a frame. So a decoder behind the window, whose frames up to the one wanted nobody will see,
     * seeks once the frames between would cost more than seeks have been costing (before one is measured, a quarter of
     * the run's steps). None between is wanted: the target is the first frame of the window not made yet.
     */
    private boolean skips(int target) {
        long between = target - heldAt - 1L;
        long seek = seekNanos != Long.MAX_VALUE ? seekNanos : run / 4 * stepNanos;
        return heldAt >= 0 && between > 0 && stepNanos > 0 && between * stepNanos > seek;
    }

    /**
     * Note what a frame just decoded cost, from {@code since}: the seek that came to it, if it is the first after one,
     * or else the one step. A seek to the first frame is not counted, a keyframe always being there.
     */
    private void measure(long since, boolean afterSeek, int target) {
        long took = System.nanoTime() - since;
        if (!afterSeek) {
            stepNanos = stepNanos == 0 ? took : (7 * stepNanos + took) / 8;
        } else if (target > 0) {
            seekNanos = seekNanos == Long.MAX_VALUE ? took : (seekNanos + took) / 2;
        }
    }

    /** Whether {@code target} is still wanted and not yet made, so the walk goes on. */
    private boolean stillWanted(int target) {
        synchronized (lock) {
            return !closed && wanted(target) && !kept.containsKey(target);
        }
    }

    /** Whether the window wants frame {@code j} and has not got it. */
    private boolean wantedNow(int j) {
        synchronized (lock) {
            return !closed && j < frames && wanted(j) && !kept.containsKey(j);
        }
    }

    /** Whether the window wants any frame from {@code j} on that it has not got. */
    private boolean wantedFrom(int j) {
        synchronized (lock) {
            for (int d = 0; d <= AHEAD; d++) {
                int k = wanted + d;
                if (k >= frames) {
                    if (!wrap || frames == 0) {
                        break;
                    }
                    k %= frames;
                }
                if (k >= j && !kept.containsKey(k)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@link #held} shows for frames {@code from} to {@code to}: keep it as each of them the window wants. */
    private void cover(int from, int to) {
        if (heldChain == null) {
            return;   // not converted: not a frame that was to be shown (see walkTo)
        }
        int last;
        synchronized (lock) {
            last = Math.min(to, frames - 1);
        }
        for (int j = Math.max(0, from); j <= last; j++) {
            boolean keep;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                keep = wanted(j) && !kept.containsKey(j);
            }
            if (keep) {
                keep(j, heldChain);
            }
        }
    }

    /** Keep the frame held as frame {@link #heldAt}, wanted by the window or not, unless the stream is shorter. */
    private void keepHeld(Video video) {
        synchronized (lock) {
            if (closed || heldAt >= frames) {
                return;
            }
        }
        convertHeld(video);
        if (heldChain != null) {
            keep(heldAt, heldChain);
            heldKept = true;
        }
    }

    /**
     * Convert the frame the video holds into a chain, unless it is already: scaled to RGBA straight into a chain's
     * first level, in one pass, and given its mips. The chain is a spare one when there is one.
     */
    private void convertHeld(Video video) {
        if (heldChain != null || !heldLive) {
            return;
        }
        byte[][] chain;
        synchronized (lock) {
            chain = spare.poll();
        }
        PlaneLayout planar = form;
        if (planar != null) {
            // Kept as the decoder made it, for the GPU to convert: the planes packed into one array.
            int bytes = (int) planar.frameBytes(width, height);
            if (chain == null || chain.length != 1 || chain[0].length != bytes) {
                chain = new byte[][] {new byte[bytes]};
            }
            pack(video.planes(), planar, chain[0]);
            heldChain = chain;
            return;
        }
        boolean withMips = mips;
        if (chain == null || (withMips && chain.length == 1)) {
            chain = withMips ? OpaqueMips.chain(width, height) : new byte[][] {new byte[width * height * 4]};
        }
        video.convert(chain[0]);
        if (withMips) {
            OpaqueMips.fill(chain, width, height);
        }
        heldChain = chain;
    }

    /** Whether {@code chain} is in the form the frames are kept in now: the planes packed, or RGBA levels. */
    private boolean inForm(byte[][] chain) {
        PlaneLayout planar = form;
        boolean packed = chain.length == 1 && planar != null && chain[0].length == planar.frameBytes(width, height);
        return planar == null ? chain[0].length == width * height * 4 : packed;
    }

    private void keep(int k, byte[][] chain) {
        if (!inForm(chain)) {
            return;   // made as RGBA before the frames were asked for as planes: made again when wanted
        }
        long bytes = 0;
        for (byte[] level : chain) {
            bytes += level.length;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            byte[][] old = kept.put(k, chain);
            keptBytes += old == null ? bytes : 0;
            // The least recently shown go first, but never the window being played.
            Iterator<Map.Entry<Integer, byte[][]>> it = kept.entrySet().iterator();
            while (keptBytes > BUDGET && kept.size() > AHEAD + 1 && it.hasNext()) {
                Map.Entry<Integer, byte[][]> e = it.next();
                if (wanted(e.getKey())) {
                    continue;
                }
                it.remove();
                keptBytes -= bytes;
                letGo(e.getValue());
            }
            if (old != null && old != chain) {
                letGo(old);
            }
        }
    }

    /**
     * A chain no longer kept under some number: a spare, to be written again, unless it is still kept under another,
     * is the one the decoder holds, or may be being copied by the player. Under lock.
     */
    private void letGo(byte[][] chain) {
        if (spare.size() >= SPARE || chain == lent || chain == heldChain || kept.containsValue(chain)) {
            return;
        }
        spare.push(chain);
    }

    /**
     * The stream ends, or stops decoding, before frame {@code at}: no frame from there on can be made. {@code why} is
     * what a person is shown, {@code cause} what threw, for the log; either may be null.
     */
    private void ended(int at, String why, Throwable cause) {
        synchronized (lock) {
            if (at < frames) {
                frames = Math.max(0, at);
                if (why == null && frames == 0) {
                    why = "no frames to show";   // ended before the first: a reason, or the player is left guessing
                }
                if (why != null) {
                    failure = why;
                    LOG.warn("{} stopped decoding at frame {}: {}", clip.stamp().path(), at,
                            cause == null ? why : cause.toString());
                }
            }
            lock.notifyAll();
        }
    }

    @Override
    public String toString() {
        return "Footage[" + clip.stamp().path().getFileName() + ", " + count() + " frames at " + rate + " fps, "
                + duration + "ms, at " + width + "x" + height + "]";
    }
}
