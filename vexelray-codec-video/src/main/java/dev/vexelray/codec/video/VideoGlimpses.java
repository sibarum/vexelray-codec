package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Glimpses;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Stamp;
import sibarum.ffmpeg.Frame;
import sibarum.ffmpeg.Video;
import sibarum.ffmpeg.VideoInfo;
import sibarum.probe.Log;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A video's frames one at a time by time, small, for a timeline to show under the pointer: what {@link Footage#SOURCE}
 * gives for {@link Motion#glimpses}. A clip of its own, so the one playing is never moved by it.
 *
 * <p><b>Frames by number</b>, at the rate the stream declares, as {@link Footage} numbers them: an ask for any time in a
 * frame is an ask for that frame, and the {@link #KEPT} made most recently are handed over again without a decode.
 *
 * <p><b>Forward by decoding, anywhere else by seeking</b>, as {@link Footage} does: a frame less than {@link #WALK_US}
 * ahead of the last one made is decoded up to, so a pointer moving right along a timeline costs a few frames each
 * time; one behind, or further, is sought ({@code Video.seek} is exact, so it costs up to a keyframe's interval).
 *
 * <p>Owns the clip, and closes it on its own thread once {@linkplain #close closed}.
 */
public final class VideoGlimpses implements Glimpses {

    /** Frames kept once made. At 240 px across, about 230 KB each. */
    static final int KEPT = 64;

    /** How far ahead of the last frame made a frame is decoded up to rather than sought: a usual keyframe interval. */
    static final long WALK_US = 2_000_000;

    private static final Log LOG = Log.of("codec.video");

    private final Clip clip;
    private final double rate;
    private final int frames;
    private final Thread thread;

    private final Object lock = new Object();
    // Guarded by lock.
    /** Frames made, by number, the least recently asked for first. */
    private final LinkedHashMap<Integer, Picture> kept = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Picture> eldest) {
            return size() > KEPT;
        }
    };
    /** Every ask, counted, so a frame made for one that has since been overtaken is kept but not handed over. */
    private long asks;
    /** The frame asked for and who to tell, or -1 once taken. */
    private int wanted = -1;
    private Consumer<Picture> then;
    private boolean closed;

    // The decoder's, on its own thread only.
    /** The last frame made, and a frame read past it that has not been made yet, if any. */
    private Frame held;
    private Frame ahead;

    /** Glimpses of {@code clip}, which this owns from here. Its info must say its length and rate ({@link Footage#plays}). */
    VideoGlimpses(Clip clip) {
        VideoInfo info = clip.info();
        if (!Footage.plays(info)) {
            throw new IllegalArgumentException(clip + " does not say how long it is, or how fast it plays");
        }
        this.clip = clip;
        this.rate = info.frameRate();
        this.frames = Footage.count(info);
        this.thread = Thread.ofPlatform().daemon().name("glimpses " + clip.stamp().path().getFileName())
                .start(this::run);
    }

    /**
     * Open the file {@code motion} came from again, its frames at most {@code side} on their long side. Blocking: run
     * it on {@code Gui.offload()}. Throws {@link IOException} with the reason if it does not open as a video that plays.
     */
    public static VideoGlimpses open(Motion motion, int side) throws IOException {
        Opened o = Videos.open(motion.stamp().path(), side);
        if (!o.video()) {
            throw new IOException(o.identity());
        }
        if (!Footage.plays(o.clip().info())) {
            o.clip().close();
            throw new IOException("a video with no length or rate");
        }
        try {
            return new VideoGlimpses(o.clip());
        } catch (RuntimeException | Error e) {
            o.clip().close();   // no thread to own it, so nobody else would
            throw e;
        }
    }

    @Override
    public void want(long ms, Consumer<Picture> then) {
        int i = frameAt(ms);
        Picture made;
        synchronized (lock) {
            if (closed) {
                return;
            }
            asks++;
            made = kept.get(i);
            if (made == null) {
                wanted = i;
                this.then = then;
                lock.notifyAll();
                return;
            }
            // Kept: whatever was waiting, or being made, is overtaken by this.
            wanted = -1;
            this.then = null;
        }
        then.accept(made);
    }

    /** The frame showing {@code ms} into a pass. */
    int frameAt(long ms) {
        return (int) Math.max(0, Math.min(frames - 1, Math.floor(Math.max(0, ms) * rate / 1000.0)));
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            then = null;
            kept.clear();
            lock.notifyAll();
        }
    }

    /** Whether the thread has stopped and closed the clip; for a test, which can wait on it. */
    boolean stopped(long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    private void run() {
        try {
            while (true) {
                int target;
                long ask;
                Consumer<Picture> tell;
                synchronized (lock) {
                    while (!closed && wanted < 0) {
                        lock.wait();
                    }
                    if (closed) {
                        return;
                    }
                    target = wanted;
                    ask = asks;
                    tell = then;
                    wanted = -1;
                    then = null;
                }
                Picture p = make(target);
                if (p == null) {
                    continue;
                }
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    kept.put(target, p);
                    if (asks != ask) {
                        continue;   // overtaken while it was made: the newer ask is told instead
                    }
                }
                try {
                    tell.accept(p);
                } catch (RuntimeException e) {
                    // The caller's, not the file's: said, and the glimpses go on for the next ask.
                    LOG.warn("a glimpse of {} was refused: {}", clip.stamp().path(), e.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clip.close();
        }
    }

    /** Frame {@code i} as a still, or null if the file would not give it. */
    private Picture make(int i) {
        // The middle of the frame, so a time a microsecond either side of where it starts is not the frame before.
        long t = Math.round((i + 0.5) * 1e6 / rate);
        try {
            Frame f = frameShowing(t);
            if (f == null) {
                return null;
            }
            return new Picture(new Key(clip.stamp(), i), f.rgba(), f.width(), f.height(), 1, 1, new int[1],
                    clip.info().width(), clip.info().height(), false);
        } catch (RuntimeException e) {
            // A damaged stream (VideoException), or anything else the decoder threw: no glimpse, and the next ask
            // starts afresh.
            LOG.debug("{} gave no glimpse at frame {}: {}", clip.stamp().path(), i, e.toString());
            held = null;
            ahead = null;
            return null;
        } catch (OutOfMemoryError e) {
            held = null;
            ahead = null;
            return null;
        }
    }

    /** The frame showing {@code t} µs in: decoded up to from the last one made if it is a little ahead, else sought. */
    private Frame frameShowing(long t) {
        Video video = clip.video();
        if (held == null || t < held.ptsMicros() || t - held.ptsMicros() > WALK_US) {
            video.seek(t);
            held = video.next();
            ahead = null;
            return held;
        }
        Frame best = held;
        while (true) {
            Frame next = ahead != null ? ahead : video.next();
            ahead = null;
            if (next == null) {
                break;   // the end: the last frame shows from there on
            }
            if (next.ptsMicros() > t) {
                ahead = next;
                break;
            }
            best = next;
        }
        held = best;
        return best;
    }

    /** What a glimpse is keyed by: the file, at its version, and the frame. */
    record Key(Stamp stamp, int frame) {
    }

    @Override
    public String toString() {
        return "VideoGlimpses[" + clip.stamp().path().getFileName() + "]";
    }
}
