package dev.vexelray.codec.video.gui;

import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.codec.video.Clip;
import dev.vexelray.codec.video.Reel;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.vulkan.present.SampledImage;
import sibarum.ffmpeg.Frame;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Time;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A place to play one {@link Clip}: whole, at its own aspect, as large as its stage allows. Its {@link #node} goes in a
 * tree like any other, and fills what it is given.
 *
 * {@snippet :
 * VideoView view = new VideoView(gui, krono, textures, Length.ZERO, Length.ZERO);
 * gui.offload().execute(() -> {
 *     Opened o = Videos.open(path, 1920);            // blocking: off the GUI thread
 *     if (o.video()) {
 *         view.play(o.clip());                       // the view owns the clip now
 *     }
 * });
 * }
 *
 * <p><b>Paced by krono, decoded by a {@link Reel}.</b> A shred on the timeline keeps the clip's clock: it advances to
 * each frame's time and asks the reel for the frame due then, so a video runs on the same clock as every animation in
 * the window, and a frame the decoder was too late with is dropped rather than shown late. Until the first frame after
 * a start or a seek has come, the clock waits for it, so a slow open does not skip the opening.
 *
 * <p><b>A texture per frame.</b> A texture is never rewritten ({@code GuiApp.texture}), so each frame due is uploaded
 * as one ({@code Textures.upload}), the plate is pointed at it, and the one before is given back
 * ({@code Textures.release}), which closes it once no frame in flight samples it. One upload is in flight at a time: a
 * frame that falls due while one is still on its way waits, and is replaced by any later one, so a window that draws
 * slower than the video plays shows fewer frames rather than falling behind.
 *
 * <p><b>Sized in percent of a stage</b>, as {@code ImageView} is, and for the same reason. Unlike a picture, a video
 * is enlarged to fill its stage: a video is watched at the size of the window, whatever size it was made at.
 *
 * <p><b>Letting go.</b> {@link #clear} stops the clock, closes the reel and the clip, and gives the texture back.
 */
public final class VideoView {

    /** The longest the clock waits between looks at the reel, when no frame is waiting to say how long. */
    static final long WAIT_US = 5_000;
    /** The longest step to a waiting frame: a still held for minutes is still watched for a seek or a pause. */
    static final long MAX_STEP_US = 100_000;

    private final Gui gui;
    private final KronoGui krono;
    private final Textures textures;
    private final Node well;
    private final Node stage;
    private final Node plate;

    /** Bumped by every play, pause, resume, seek and clear, so a stale clock stops and a stale texture goes back. */
    private final AtomicInteger generation = new AtomicInteger();
    private volatile NodeLayout stageBox = NodeLayout.ABSENT;

    // Guarded by this.
    private Reel reel;
    private Scheduled clock;
    private boolean playing;
    private boolean loop;
    private Runnable onEnd = () -> { };
    /** The texture on the plate, and the time of the frame it holds; -1 for none. */
    private SampledImage shown;
    private long shownMicros = -1;
    /** The generation with an upload on its way, or -1; and the frame due after it, if one has fallen due since. */
    private int inFlight = -1;
    private Frame pending;

    /** Where the clock has got to, in the clip's time: written by the clock's shred, read by anyone. */
    private volatile long position;

    public VideoView(Gui gui, KronoGui krono, Textures textures, Length padY, Length padX) {
        this.gui = gui;
        this.krono = krono;
        this.textures = textures;
        this.plate = gui.box().size(Length.ZERO, Length.ZERO).floatAt(Length.ZERO, Length.ZERO).hitInert(true)
                .visible(false);
        this.stage = gui.column().width(Length.FILL).height(Length.FILL).clip(true).scroll(false, false)
                .children(plate);
        this.well = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(padY, padX)
                .scroll(false, false).children(stage);
        gui.onResize(stage, box -> {
            stageBox = box;
            fit();
        });
    }

    public Node node() {
        return well;
    }

    /**
     * Play {@code clip} from its start; or, with {@code start} false, show its first frame and wait for
     * {@link #resume}. The view owns the clip from here, and closes it when it plays another or is {@linkplain #clear
     * cleared}.
     */
    public synchronized void play(Clip clip, boolean start) {
        stop();
        reel = new Reel(clip);
        position = 0;
        playing = start;
        plate.visible(true);
        fit();
        run(0, !start);
    }

    /** {@link #play(Clip, boolean) Play} {@code clip} from its start, now. */
    public void play(Clip clip) {
        play(clip, true);
    }

    /** Hold the frame showing, and the clock with it. */
    public synchronized void pause() {
        if (!playing) {
            return;
        }
        playing = false;
        generation.incrementAndGet();
        cancelClock();
    }

    /** Play on from where the clock was held; from the start, if it had reached the end. */
    public synchronized void resume() {
        if (playing || reel == null) {
            return;
        }
        playing = true;
        if (reel.finished()) {
            reel.seek(0);
            position = 0;
        }
        run(position, false);
    }

    /**
     * Go to {@code micros} into the clip, exactly (the frames from the keyframe before it are decoded and dropped). If
     * the view is paused, the frame there is shown and the clock stays held there.
     */
    public synchronized void seek(long micros) {
        if (reel == null) {
            return;
        }
        long to = Math.max(0, micros);
        reel.seek(to);
        position = to;
        run(to, !playing);
    }

    /** Start again from the beginning whenever the end is reached. */
    public synchronized VideoView loop(boolean loop) {
        this.loop = loop;
        return this;
    }

    /** Run {@code r} on the timeline when a clip that does not loop reaches its end, and is held there. */
    public synchronized VideoView onEnd(Runnable r) {
        onEnd = r == null ? () -> { } : r;
        return this;
    }

    /** Show nothing: stop the clock, close the reel and the clip, and give the texture back. */
    public synchronized void clear() {
        stop();
        plate.visible(false);
    }

    public synchronized boolean playing() {
        return playing;
    }

    /** The clip playing or held, or null. */
    public synchronized Clip clip() {
        return reel == null ? null : reel.clip();
    }

    /** How far into the clip the clock is, in µs. */
    public long position() {
        return position;
    }

    /** The clip's length in µs, or -1 where the file does not say or nothing is playing. */
    public synchronized long duration() {
        return reel == null ? -1 : reel.clip().info().durationMicros();
    }

    /** The time in the clip of the frame on the plate, or -1 before the first has been shown. */
    public synchronized long shownMicros() {
        return shownMicros;
    }

    /**
     * Start the clock at {@code from}, as a new generation: it shows each frame as its time comes, or with {@code once}
     * shows the first frame to come and stops, which is how a paused view shows where it was sought to.
     */
    private void run(long from, boolean once) {
        int g = generation.incrementAndGet();
        cancelClock();
        Reel r = reel;
        clock = krono.spork("video", () -> {
            long now = from;
            boolean primed = false;
            while (generation.get() == g) {
                Frame f = r.due(now);
                if (f != null) {
                    primed = true;
                    present(g, f);
                    if (once) {
                        return;
                    }
                }
                if (r.finished()) {
                    if (once || !ended(g, r)) {
                        return;
                    }
                    now = 0;
                    primed = false;
                }
                long next = r.next();
                long step = next > now ? Math.min(next - now, MAX_STEP_US) : WAIT_US;
                Time.advance(Dur.us(step));
                if (generation.get() != g) {
                    return;
                }
                // Before the first frame has come the clock waits for it; after, it keeps time, and frames that come
                // too late for it are dropped by the reel.
                if (primed || next >= 0) {
                    now += step;
                    if (!once) {
                        position = now;
                    }
                }
            }
        });
    }

    /** The reel has run out: from the start again if looping (true), else held at the end, and said so (false). */
    private boolean ended(int g, Reel r) {
        Runnable then;
        synchronized (this) {
            if (generation.get() != g) {
                return false;
            }
            if (loop) {
                r.seek(0);
                position = 0;
                return true;
            }
            playing = false;
            then = onEnd;
        }
        then.run();
        return false;
    }

    /** Put {@code f} on the plate: upload it, unless one is on its way, in which case it waits its turn. */
    private void present(int g, Frame f) {
        synchronized (this) {
            if (inFlight == g) {
                pending = f;
                return;
            }
            inFlight = g;
            pending = null;
        }
        upload(g, f);
    }

    private void upload(int g, Frame f) {
        Clip c = clip();
        if (c == null) {
            return;
        }
        Picture picture = new Picture(f, f.rgba(), f.width(), f.height(), 1, 1, new int[1], c.info().width(),
                c.info().height(), false);
        boolean sent = textures.upload(picture, texture -> {
            Frame next;
            synchronized (this) {
                if (generation.get() != g) {
                    textures.release(texture);
                    if (inFlight == g) {
                        inFlight = -1;
                    }
                    return;
                }
                plate.image(texture);
                SampledImage old = shown;
                shown = texture;
                shownMicros = f.ptsMicros();
                if (old != null) {
                    textures.release(old);
                }
                next = pending;
                pending = null;
                if (next == null) {
                    inFlight = -1;
                    return;
                }
            }
            upload(g, next);
        });
        if (!sent) {
            // No window (a headless run): nothing to upload into, but the frame was due, and is the one "shown".
            synchronized (this) {
                shownMicros = f.ptsMicros();
                inFlight = -1;
                pending = null;
            }
        }
    }

    /** Stop the clock, close the reel, and give back the texture. */
    private void stop() {
        generation.incrementAndGet();
        cancelClock();
        if (reel != null) {
            reel.close();
            reel = null;
        }
        playing = false;
        plate.image(null);
        if (shown != null) {
            textures.release(shown);
            shown = null;
        }
        shownMicros = -1;
        inFlight = -1;
        pending = null;
        position = 0;
    }

    private void cancelClock() {
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
    }

    /** Size and place the plate on the stage: whole, at the clip's aspect, centred, as large as the stage allows. */
    private synchronized void fit() {
        NodeLayout box = stageBox;
        if (reel == null || !box.present()) {
            return;
        }
        float cw = box.content().w();
        float ch = box.content().h();
        if (cw <= 0f || ch <= 0f) {
            return;
        }
        float[] f = fraction(reel.clip().info().width(), reel.clip().info().height(), cw, ch);
        plate.size(Length.percent(100f * f[0]), Length.percent(100f * f[1]))
                .floatAt(Length.percent(50f * (1f - f[0])), Length.percent(50f * (1f - f[1])));
    }

    /** The size a {@code w x h} video is shown at in a {@code cw x ch} box, as a fraction of each side: filling it. */
    static float[] fraction(int w, int h, float cw, float ch) {
        float vw = Math.max(1, w);
        float vh = Math.max(1, h);
        float scale = Math.min(cw / vw, ch / vh);
        return new float[] {vw * scale / cw, vh * scale / ch};
    }
}
