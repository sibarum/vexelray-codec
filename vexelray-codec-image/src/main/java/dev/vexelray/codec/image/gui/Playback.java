package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.StreamTexture;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Time;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A picture that plays, as a movie does: play, pause, seek, step a frame, and loop. What {@link ImageView} shows a
 * {@link dev.vexelray.codec.image.Picture} with a {@link Motion} through, and what a transport bar drives
 * ({@link ImageView#playback}).
 *
 * <p><b>Paced by krono, made by a {@link Playable}, shown through a {@link StreamTexture}.</b> The playable is what
 * the motion opens ({@link Motion#open}): a {@code Movie} for an animated image, a video's frames for a video, the one
 * played the same way as the other. A shred on the timeline
 * keeps the picture's clock: it advances to each frame's time, asks the movie for the frame due, and hands it to the
 * stream, whose slot the plate is then pointed at. So a picture plays on the same clock as every animation in the
 * window, its frames are made ahead off the GUI thread, and a frame on screen costs a copy into a slot made once. A
 * frame the movie was too late with is looked for again a few milliseconds on, the newest frame it has made since the
 * one up being shown meanwhile, so a file that cannot be decoded as fast as it plays shows fewer frames rather than
 * falling behind, or stopping. Until the first frame after a start or a seek has come, the clock waits for it.
 *
 * <p><b>A playable with a clock of its own is followed by it.</b> A video's sound ({@link Playable#clock}) is heard at
 * the speaker's pace, so the shred shows the frame the sound has reached rather than counting time itself. The sound
 * starts with the first frame, stops whenever the clock does (a pause, a hold, a step, the end, a close), and goes
 * wherever a seek or a loop sends the pictures.
 *
 * <p><b>Held, not stopped.</b> {@link #hold} pauses it without forgetting that it was playing: the movie keeps the
 * frames it made and its place in the file, the stream keeps the frame on screen, and nothing is decoded or copied
 * until it is let go, when it plays on if it was playing. A view holds its pictures while its window is not focused.
 * A pause of the viewer's own is not undone by letting go.
 *
 * <p><b>Its sound has a {@link #volume}, can be {@link #muted} and has a {@link #compression}</b>, kept while the file
 * is still opening and given to the sound once it is, and again whenever any changes. Silence is only silence, and
 * compression only loudness: the clock keeps time as before.
 *
 * <p>Plays as soon as it opens, round and round: {@link #loop} is on until it is turned off. Every method may be
 * called from any thread; {@code changed} is told on the timeline or the GUI thread whenever what a transport bar
 * shows has moved: the frame, the clock's state, the loop, the volume, the mute and the compression.
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
    /**
     * How far a playable's own clock may be from where the pictures start again before it is sought there: less, and
     * playing on is letting go of a pause, which the sound picks up where it stopped rather than starting afresh.
     */
    static final long SEEK_SLACK_MS = 40;

    private final KronoGui krono;
    private final Textures textures;
    private final Screen screen;
    private final Motion motion;
    private final int side;
    private final Runnable changed;

    /** Bumped by every play, pause, seek and close, so a stale clock stops. */
    private final AtomicInteger generation = new AtomicInteger();

    // Guarded by this.
    private Playable movie;
    private StreamTexture stream;
    private Scheduled clock;
    private boolean wanted = true;
    private boolean held;
    private boolean loop = true;
    private boolean closed;
    private String failure;
    /** How loud the sound is, whether it is silenced, and how compressed: given to the movie's clock whenever it has one. */
    private float volume = 1f;
    private boolean muted;
    private float compression;
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
        try {
            offload.execute(this::open);
        } catch (RejectedExecutionException e) {
            // Shown as the application shuts down: there is no thread to open it on, and nothing will play.
            fail("nowhere to open it");
        }
    }

    /** Open the file, make the stream, and play from where the clock is. Blocking: on the offload thread. */
    private void open() {
        Playable m;
        try {
            m = motion.open(side);
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
            // A file that will not read or decode, or a decoder that will not load: said, never thrown, so the poster
            // stays up with a reason instead of waiting for a movie that is not coming.
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return;
        }
        synchronized (this) {
            if (closed) {
                m.close();
                return;
            }
            movie = m;
            sound();
        }
        boolean windowed = textures.stream(m.width(), m.height(), s -> {
            if (s == null) {
                fail("no room on the GPU for its frames");
                return;
            }
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
                if (!closed) {
                    run(position, !playing());
                }
            }
        }
    }

    /** It will not play, and why: the poster stays up. */
    private void fail(String why) {
        synchronized (this) {
            failure = why;
        }
        changed.run();
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

    /**
     * How loud its sound is heard, from 0 to 1 (as the file has it, the default); a video's, a picture with no sound
     * keeping it for nothing. Kept, muted or not, and given to the sound whenever the file is opened.
     */
    public void volume(float volume) {
        synchronized (this) {
            this.volume = Math.max(0f, Math.min(1f, volume));
            sound();
        }
        changed.run();
    }

    public synchronized float volume() {
        return volume;
    }

    /** Silence the sound ({@code true}) or let it be heard at its {@link #volume}. The clock keeps time either way. */
    public void muted(boolean muted) {
        synchronized (this) {
            this.muted = muted;
            sound();
        }
        changed.run();
    }

    public synchronized boolean muted() {
        return muted;
    }

    /**
     * Dynamic range compression on its sound, one knob from 0 (as the file has it, the default) to 1: dialogue up and
     * explosions down, through night mode at 0.5 to deliberately too much at 1. Kept, and given to the sound whenever
     * the file is opened. The clock keeps time as before.
     */
    public void compression(float amount) {
        synchronized (this) {
            this.compression = amount > 0f ? Math.min(1f, amount) : 0f;     // NaN is none
            sound();
        }
        changed.run();
    }

    public synchronized float compression() {
        return compression;
    }

    /**
     * Copy what its sound last gave the speaker into {@code stereo}, oldest first, left and right in turn, for a meter:
     * the sample frames copied, at most a quarter of a second's. None when there is no sound to hear.
     */
    public int heard(float[] stereo) {
        Playable.Clock own;
        synchronized (this) {
            own = movie == null ? null : movie.clock();
        }
        return own == null ? 0 : own.heard(stereo);
    }

    /** Whether there is sound to hear: a video with sound, once its file is open. What a volume control is shown for. */
    public synchronized boolean sounds() {
        return movie != null && movie.clock() != null;
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
        return Math.max(1, movie != null ? movie.count() : motion.frames());
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
        Playable m;
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
        try {
            if (m != null) {
                m.close();
            }
        } finally {
            if (s != null) {
                textures.release(s);
            }
        }
    }

    private void stopClock() {
        generation.incrementAndGet();
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
        Playable.Clock own = movie == null ? null : movie.clock();
        if (own != null) {
            own.run(false);
        }
    }

    /** Tell the movie's clock how loud to be and how compressed, if there is a movie and it keeps one. Under this. */
    private void sound() {
        Playable.Clock own = movie == null ? null : movie.clock();
        if (own != null) {
            own.volume(muted ? 0f : volume);
            own.compression(compression);
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
        Playable m = movie;
        Playable.Clock own = m.clock();
        if (own != null && Math.abs(own.now() - from) > SEEK_SLACK_MS) {
            // Moved, not merely let go: the sound goes where the pictures go. Running, it stays so.
            own.seek(from);
        }
        clock = krono.spork("picture-movie", () -> {
            long pos = from;
            int shown = -1;
            boolean sounding = false;
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
                        if (own != null && !sounding) {
                            // The sound starts with the first frame, as the clock does without it.
                            own.run(true);
                            sounding = true;
                        }
                    } else if (i >= m.count()) {
                        // The file ended, or stopped decoding, before this frame (frameAt clamps, so only a file
                        // with none at all): it is never coming. Stopped where it is, as at the end, rather than
                        // looking for it every few milliseconds for ever.
                        synchronized (this) {
                            if (generation.get() == g) {
                                wanted = false;
                                if (own != null) {
                                    own.run(false);
                                }
                            }
                        }
                        changed.run();
                        return;
                    } else if (shown < 0) {
                        // The clock waits for the first frame, so a seek or a slow open does not skip the start.
                        Time.advance(Dur.ms(POLL_MS));
                        continue;
                    } else {
                        // Late. A movie slower than the clock lands each frame after it is due, so waiting for the
                        // frame due would wait for ever: the newest it has made since the one up goes up meanwhile.
                        for (int j = i - 1, lo = shown < i ? shown + 1 : 0; j >= lo; j--) {
                            byte[][] made = m.frame(j);
                            if (made != null) {
                                present(j, made);
                                shown = j;
                                break;
                            }
                        }
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
                // With a clock of its own (a video's sound), the time is the clock's: the frames follow what is heard.
                pos = sounding ? own.now() : pos + step;
                if (pos >= m.duration()) {
                    if (looping && own != null) {
                        pos = 0;
                        own.seek(0);
                    } else if (looping) {
                        pos %= Math.max(1, m.duration());
                    } else {
                        position = m.duration();
                        synchronized (this) {
                            if (generation.get() == g) {
                                wanted = false;
                                if (own != null) {
                                    own.run(false);
                                }
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
