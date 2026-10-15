package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Movie;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.StreamTexture;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Time;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A picture that plays, as a movie does: play, pause, seek, step a frame, and loop. What {@link ImageView} shows a
 * {@link dev.vexelray.codec.image.Picture} with a {@link Motion} through, and what a transport bar drives
 * ({@link ImageView#playback}).
 *
 * <p><b>Paced by krono, made by a {@link Movie}, shown through a {@link StreamTexture}.</b> A shred on the timeline
 * keeps the picture's clock: it advances to each frame's time, asks the movie for the frame due, and hands it to the
 * stream, whose slot the plate is then pointed at. So a picture plays on the same clock as every animation in the
 * window, its frames are made ahead off the GUI thread, and a frame on screen costs a copy into a slot made once. A
 * frame the movie was too late with is looked for again a few milliseconds on, and frames passed meanwhile are not
 * shown, so a file that cannot be decoded as fast as it plays shows fewer frames rather than falling behind. Until
 * the first frame after a start or a seek has come, the clock waits for it.
 *
 * <p><b>Held, not stopped.</b> {@link #hold} pauses it without forgetting that it was playing: the movie keeps the
 * frames it made and its place in the file, the stream keeps the frame on screen, and nothing is decoded or copied
 * until it is let go, when it plays on if it was playing. A view holds its pictures while its window is not focused.
 * A pause of the viewer's own is not undone by letting go.
 *
 * <p>Plays as soon as it opens, round and round: {@link #loop} is on until it is turned off. Every method may be
 * called from any thread; {@code changed} is told on the timeline or the GUI thread whenever what a transport bar
 * shows has moved: the frame, the clock's state, the loop.
 */
public final class Playback {

    /** Where a frame goes once it is on the GPU: the plate, pointed at {@code frame}, {@code width x height}. */
    public interface Screen {
        void show(SampledImage frame, int width, int height);
    }

    /** The longest step of the clock: a frame held for minutes is still watched for a seek or a pause. */
    static final long MAX_STEP_MS = 100;
    /** How soon a frame the movie has not made yet is looked for again. */
    static final long POLL_MS = 8;

    private final KronoGui krono;
    private final Textures textures;
    private final Screen screen;
    private final Motion motion;
    private final int side;
    private final Runnable changed;

    /** Bumped by every play, pause, seek and close, so a stale clock stops. */
    private final AtomicInteger generation = new AtomicInteger();

    // Guarded by this.
    private Movie movie;
    private StreamTexture stream;
    private Scheduled clock;
    private boolean wanted = true;
    private boolean held;
    private boolean loop = true;
    private boolean closed;
    private String failure;
    /** A frame on its way to the GPU, and the newest one due since, which goes next. */
    private boolean inFlight;
    private byte[][] pending;
    private int pendingFrame;

    /** Where the clock is, in ms into a pass, and the frame last put on the plate: written by the clock, read by anyone. */
    private volatile long position;
    private volatile int frame = -1;

    Playback(KronoGui krono, Textures textures, Screen screen, Motion motion, int side, Runnable changed) {
        this.krono = krono;
        this.textures = textures;
        this.screen = screen;
        this.motion = motion;
        this.side = side;
        this.changed = changed;
    }

    /** Open the file on {@code offload}, make the stream, and play from where the clock is. */
    void start(Executor offload) {
        offload.execute(() -> {
            Movie m;
            try {
                m = Movie.open(motion, side);
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                changed.run();
                return;
            }
            synchronized (this) {
                if (closed) {
                    m.close();
                    return;
                }
                movie = m;
            }
            boolean windowed = textures.stream(m.width(), m.height(), s -> {
                synchronized (this) {
                    if (closed) {
                        textures.release(s);
                        return;
                    }
                    stream = s;
                    run(position, !playing());
                }
                changed.run();
            });
            if (!windowed) {
                // No window to show it in (a headless run): the clock still keeps time, and nothing is uploaded.
                synchronized (this) {
                    run(position, !playing());
                }
            }
        });
    }

    /** Whether the clock runs: asked to play, and not held. */
    public synchronized boolean playing() {
        return wanted && !held && !closed;
    }

    /** Whether it has been asked to play, held or not: what a play button shows. */
    public synchronized boolean wanted() {
        return wanted;
    }

    /** Play from where the clock is; from the start, if it stopped at the end. */
    public void play() {
        synchronized (this) {
            if (wanted) {
                return;
            }
            wanted = true;
            if (movie != null && !loop && position >= movie.duration()) {
                position = 0;
            }
            if (playing()) {
                run(position, false);
            }
        }
        changed.run();
    }

    /** Stop the clock where it is, the frame showing staying up. */
    public void pause() {
        synchronized (this) {
            if (!wanted) {
                return;
            }
            wanted = false;
            stopClock();
        }
        changed.run();
    }

    /** {@link #play} if paused, else {@link #pause}. */
    public void toggle() {
        if (wanted()) {
            pause();
        } else {
            play();
        }
    }

    /**
     * Pause without being paused: the clock stops, and starts again when let go if it was running. For a window that
     * is not focused. Everything made is kept, and nothing is made meanwhile.
     */
    public void hold(boolean hold) {
        synchronized (this) {
            if (held == hold) {
                return;
            }
            boolean was = playing();
            held = hold;
            if (was && !playing()) {
                stopClock();
            } else if (!was && playing() && movie != null) {
                run(position, false);
            }
        }
        changed.run();
    }

    public synchronized boolean held() {
        return held;
    }

    /** Go to {@code ms} into a pass. Paused, the frame there is shown and the clock stays there. */
    public void seek(long ms) {
        synchronized (this) {
            position = Math.max(0, Math.min(ms, duration()));
            if (movie != null) {
                run(position, !playing());
            }
        }
        changed.run();
    }

    /**
     * Pause, and show the frame {@code frames} on from the one showing: back for a negative count. Round from the last
     * frame to the first, and back, when looping.
     */
    public void step(int frames) {
        int to;
        synchronized (this) {
            wanted = false;
            stopClock();
            int n = count();
            int at = Math.max(0, frame);
            to = loop ? Math.floorMod(at + frames, n) : Math.max(0, Math.min(n - 1, at + frames));
        }
        seek(startOf(to));
    }

    /** Start again from the beginning whenever the end is reached: on by default. */
    public void loop(boolean on) {
        synchronized (this) {
            loop = on;
        }
        changed.run();
    }

    public synchronized boolean loop() {
        return loop;
    }

    /** How far into a pass the clock is, in ms. */
    public long position() {
        return position;
    }

    /** One pass, in ms. */
    public synchronized long duration() {
        return movie != null ? movie.duration() : motion.durationMillis();
    }

    /** The frame on the plate, from 0; -1 while the first is coming, when the plate still shows the poster. */
    public int frame() {
        return frame;
    }

    /** How many frames. */
    public synchronized int count() {
        return movie != null ? Math.max(1, movie.count()) : motion.frames();
    }

    /** When frame {@code i} starts, in ms. */
    public synchronized long startOf(int i) {
        if (movie != null) {
            return movie.startOf(Math.max(0, Math.min(i, movie.count() - 1)));
        }
        return 0;
    }

    /** Why the picture will not play, or null: the poster stays up. */
    public synchronized String failure() {
        if (failure != null) {
            return failure;
        }
        return movie == null ? null : movie.failure();
    }

    /** What this plays. */
    public Motion motion() {
        return motion;
    }

    /** Stop for good: the clock, the movie and its file, and the stream, once no node names it. */
    void close() {
        StreamTexture s;
        Movie m;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopClock();
            s = stream;
            m = movie;
            stream = null;
            movie = null;
            pending = null;
        }
        if (m != null) {
            m.close();
        }
        if (s != null) {
            textures.release(s);
        }
    }

    private void stopClock() {
        generation.incrementAndGet();
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
    }

    /**
     * Start the clock at {@code from}, as a new generation: it shows each frame as its time comes, or with {@code once}
     * shows the frame at {@code from} and stops, which is how a paused picture shows where it was sought to. Under this.
     */
    private void run(long from, boolean once) {
        int g = generation.incrementAndGet();
        if (clock != null) {
            clock.cancel();
        }
        Movie m = movie;
        clock = krono.spork("picture-movie", () -> {
            long pos = from;
            int shown = -1;
            while (generation.get() == g) {
                boolean looping;
                synchronized (this) {
                    looping = loop;
                }
                int i = m.frameAt(pos);
                m.want(i, looping);
                if (i != shown) {
                    byte[][] chain = m.frame(i);
                    if (chain != null) {
                        present(i, chain);
                        shown = i;
                        if (once) {
                            return;
                        }
                    } else if (shown < 0) {
                        // The clock waits for the first frame, so a seek or a slow open does not skip the start.
                        Time.advance(Dur.ms(POLL_MS));
                        continue;
                    }
                }
                long next = m.startOf(i) + m.delay(i);
                long step = Math.max(1, Math.min(next - pos, MAX_STEP_MS));
                if (i != shown) {
                    step = Math.min(step, POLL_MS);   // late: look for it again soon
                }
                Time.advance(Dur.ms(step));
                if (generation.get() != g) {
                    return;
                }
                pos += step;
                if (pos >= m.duration()) {
                    if (looping) {
                        pos %= Math.max(1, m.duration());
                    } else {
                        position = m.duration();
                        synchronized (this) {
                            if (generation.get() == g) {
                                wanted = false;
                            }
                        }
                        changed.run();
                        return;
                    }
                }
                position = pos;
            }
        });
    }

    /** Put frame {@code i} on the plate: copy it to the GPU, unless one is on its way, in which case it goes next. */
    private void present(int i, byte[][] chain) {
        StreamTexture s;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (inFlight) {
                pending = chain;
                pendingFrame = i;
                return;
            }
            inFlight = true;
            s = stream;
        }
        send(s, i, chain);
    }

    private void send(StreamTexture s, int i, byte[][] chain) {
        int w = s == null ? 0 : s.width();
        int h = s == null ? 0 : s.height();
        boolean sent = s != null && textures.present(s, chain, slot -> {
            byte[][] next;
            int nextFrame;
            synchronized (this) {
                if (closed) {
                    return;
                }
                next = pending;
                nextFrame = pendingFrame;
                pending = null;
                if (next == null) {
                    inFlight = false;
                }
            }
            if (slot != null) {
                // Outside this lock: the screen takes the view's, and the view calls in here holding it.
                screen.show(slot, w, h);
                frame = i;
                changed.run();
            }
            if (next != null) {
                send(s, nextFrame, next);
            }
        });
        if (!sent) {
            // No window: the frame was due, and is the one "shown".
            synchronized (this) {
                inFlight = false;
                pending = null;
            }
            frame = i;
            changed.run();
        }
    }

    @Override
    public String toString() {
        return "Playback[" + motion.stamp().path().getFileName() + ", frame " + frame + " at " + position + "ms"
                + (playing() ? ", playing" : held ? ", held" : ", paused") + (loop ? ", looping" : "") + "]";
    }
}
