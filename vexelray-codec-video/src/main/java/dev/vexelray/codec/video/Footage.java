package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Glimpses;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.vulkan.present.MipChain;
import sibarum.ffmpeg.Frame;
import sibarum.ffmpeg.Video;
import sibarum.ffmpeg.VideoException;
import sibarum.ffmpeg.VideoInfo;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * behind it, or further ahead than {@link #RUN_SECONDS}, is sought ({@code Video.seek} is exact).
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

    // The decoder's, on its own thread only.
    /** The last frame decoded, whose span is not known until the next one comes; its number, or -1 for none. */
    private Frame held;
    private int heldAt = -1;
    private byte[][] heldChain;
    /** The stream has ended after {@link #held}. */
    private boolean atEnd;

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
     * speakers if it has any and there are speakers to play it on. Blocking: run it on {@code Gui.offload()}. Throws
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
        Sound sound = o.clip().info().audio() ? Sound.open(motion.stamp().path()) : null;
        return new Footage(o.clip(), sound);
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
            return kept.get(i);
        }
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
        } finally {
            clip.close();
        }
    }

    /**
     * Make frame {@code target}: decode up to it, seeking first if it is behind the decoder or far ahead, and keep
     * every frame passed that the window wants. Returns once it is made, or no longer wanted.
     */
    private void walkTo(int target) {
        Video video = clip.video();
        try {
            boolean sought = false;
            if (heldAt < 0 || target < heldAt || target > heldAt + run) {
                video.seek(startOf(target) * 1000);
                held = null;
                heldChain = null;
                heldAt = -1;
                atEnd = false;
                sought = true;
            }
            while (true) {
                if (atEnd) {
                    // Nothing comes after the last frame: it stands for every number from its own to the end.
                    if (held == null) {
                        ended(target, null);
                        return;
                    }
                    cover(heldAt, Integer.MAX_VALUE);
                    if (!stillWanted(target)) {
                        return;
                    }
                    // Wanted, and not covered: before the last frame. Start again from there.
                    heldAt = -1;
                    return;
                }
                Frame f = video.next();
                if (f == null) {
                    atEnd = true;
                    continue;
                }
                int at = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(f.ptsMicros() * rate / 1e6)));
                if (held == null) {
                    // The first frame after a seek stands for the number sought, whatever its own rounds to.
                    at = sought ? Math.min(at, target) : at;
                } else if (at <= heldAt) {
                    // Two frames on one number: the later one shows.
                    held = f;
                    heldChain = null;
                    continue;
                } else {
                    cover(heldAt, at - 1);
                }
                held = f;
                heldChain = null;
                heldAt = at;
                if (!stillWanted(target)) {
                    return;
                }
                if (at > target + AHEAD + 1) {
                    return;   // past the window: what it wanted is made, or the stream skipped it and it is covered
                }
            }
        } catch (VideoException e) {
            ended(target, e.getMessage());
            heldAt = -1;
        } catch (OutOfMemoryError e) {
            ended(target, "too large to decode");
            heldAt = -1;
        }
    }

    /** Whether {@code target} is still wanted and not yet made, so the walk goes on. */
    private boolean stillWanted(int target) {
        synchronized (lock) {
            return !closed && wanted(target) && !kept.containsKey(target);
        }
    }

    /** {@link #held} shows for frames {@code from} to {@code to}: keep it as each of them the window wants. */
    private void cover(int from, int to) {
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
                if (heldChain == null) {
                    heldChain = MipChain.of(held.rgba(), held.width(), held.height());
                }
                keep(j, heldChain);
            }
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
            }
        }
    }

    /** The stream ends, or stops decoding, before frame {@code at}: no frame from there on can be made. */
    private void ended(int at, String why) {
        synchronized (lock) {
            if (at < frames) {
                frames = Math.max(0, at);
                if (why != null) {
                    failure = why;
                    LOG.warn("{} stopped decoding at frame {}: {}", clip.stamp().path(), at, why);
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
