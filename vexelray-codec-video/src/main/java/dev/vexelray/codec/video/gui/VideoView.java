package dev.vexelray.codec.video.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.gui.Player;
import dev.vexelray.codec.video.Clip;
import dev.vexelray.codec.video.Footage;
import dev.vexelray.codec.video.VideoFile;
import sibarum.ffmpeg.VideoInfo;
import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.spi.Seams;

/**
 * A place to play one {@link Clip}: whole, at its own aspect, as large as its stage allows. Its {@link #node} goes in a
 * tree like any other, and fills what it is given.
 *
 * {@snippet :
 * VideoView view = new VideoView(gui, Length.ZERO, Length.ZERO);
 * // Opening blocks, so it is a @Job: Videos.open(path, 1920), its Opened in the reply.
 * // The view that hears the reply:
 * if (opened.video()) {
 *     view.play(opened.clip());                      // the view owns the clip now
 * }
 * }
 *
 * <p><b>On the frame contract</b> (vexelray-gui's frame-contract.md, step 5). The clip's frames are made by a
 * {@link Footage}, a few ahead on its own thread, and written by a {@link Player} into a feed of three slots, each
 * stamped with its time; the plate shows the feed, and this widget's asked half ({@link #draw}, registered through
 * {@link Seams#ask}, the widget library's seam) shows the frame due at each frame's moment. Nothing paces the video on
 * the GUI thread but that choice, and nothing is uploaded but the slot shown. A frame the decoder was too late with is
 * dropped for the one due, so a slow machine shows fewer frames rather than falling behind. A {@link VideoFile} plays with
 * its sound on the speakers, the frames following what is heard; a {@link Clip} handed in plays its pictures only.
 *
 * <p><b>Sized in percent of a stage</b>, as {@code ImageView} is, and for the same reason. Unlike a picture, a video
 * is enlarged to fill its stage: a video is watched at the size of the window, whatever size it was made at.
 *
 * <p><b>Letting go.</b> {@link #clear} stops the player, which closes the footage and the clip, and closes the feed.
 */
public final class VideoView {

    /** How soon the asked half looks again while the first frame after a start is still being made: about a frame. */
    static final long FIRST_FRAME_LOOK_NANOS = 16_000_000L;

    private final Node well;
    private final Node stage;
    private final Node plate;

    private volatile NodeLayout stageBox = NodeLayout.ABSENT;
    /** The feed the plate shows, read by the asked half with no lock; null when nothing plays. */
    private volatile Feed playing;
    /** The stamp of the frame the asked half last showed, or -1 before it has shown one. */
    private volatile long drawnStamp = -1;
    /** How many distinct frames the asked half has shown, ever: written on the GUI thread only. */
    private volatile long shownCount;

    // Guarded by this.
    private Clip clip;
    /** The video's own size, which the plate is fitted at, and its length; 0 and -1 with nothing playing. */
    private int width;
    private int height;
    private long lengthMicros = -1;
    private Player player;
    private boolean loop;
    private Runnable onEnd = () -> { };
    /** Whether the end has been said for the player playing now. */
    private boolean endSaid;
    private String failure;

    public VideoView(Gui gui, Length padY, Length padX) {
        this.plate = gui.box().size(Length.ZERO, Length.ZERO).floatAt(Length.ZERO, Length.ZERO).hitInert(true)
                .visible(false);
        this.stage = gui.column().width(Length.FILL).height(Length.FILL).clip(true).scroll(false, false)
                .children(plate);
        this.well = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(padY, padX)
                .scroll(false, false).children(stage);
        Seams.of(gui).onResize(stage, box -> {
            stageBox = box;
            fit();
        });
        Seams.of(gui).ask(this::draw);
    }

    public Node node() {
        return well;
    }

    /**
     * Play {@code clip} from its start; or, with {@code start} false, show its first frame and wait for
     * {@link #resume}. The view owns the clip from here, and closes it when it plays another or is {@linkplain #clear
     * cleared}. A clip that does not say how long it is or how fast it plays is not played: {@link #failure} says so.
     */
    public synchronized void play(Clip clip, boolean start) {
        stop();
        VideoInfo info = clip.info();
        if (!Footage.plays(info)) {
            clip.close();
            failure = "a video with no length or rate";
            return;
        }
        this.clip = clip;
        width = clip.info().width();
        height = clip.info().height();
        lengthMicros = info.durationMicros();
        Motion motion = new Motion(clip.stamp(), Footage.count(info), Math.max(1, info.durationMicros() / 1000), 0,
                (m, side) -> new Footage(clip), true);
        Feed feed = Player.feed(clip.width(), clip.height());
        Player p = Player.play(feed, motion, this::changed);
        p.loop(loop);
        if (!start) {
            p.pause();
        }
        player = p;
        endSaid = false;
        playing = feed;
        plate.image(feed).visible(true);
        fit();
    }

    /** {@link #play(Clip, boolean) Play} {@code clip} from its start, now. */
    public void play(Clip clip) {
        play(clip, true);
    }

    /**
     * Play {@code video} from its start, with its sound on the speakers when it has one; or, with {@code start} false,
     * show its first frame and wait for {@link #resume}. What an application on 0.2 plays from: a job answers with the
     * video as values ({@link dev.vexelray.codec.video.Videos#probe}), and the player opens the file again on its own
     * thread, so nothing open crosses the bus.
     */
    public synchronized void play(VideoFile video, boolean start) {
        stop();
        width = video.width();
        height = video.height();
        lengthMicros = video.durationMicros();
        Feed feed = Player.feed(video.width(), video.height());
        Player p = Player.play(feed, video.motion(), this::changed);
        p.loop(loop);
        if (!start) {
            p.pause();
        }
        player = p;
        endSaid = false;
        playing = feed;
        plate.image(feed).visible(true);
        fit();
    }

    /** {@link #play(VideoFile, boolean) Play} {@code video} from its start, now. */
    public void play(VideoFile video) {
        play(video, true);
    }

    /** Hold the frame showing, and the time with it. */
    public synchronized void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /** Play on from where it was held; from the start, if it had reached the end. */
    public synchronized void resume() {
        if (player != null) {
            endSaid = false;
            player.play();
        }
    }

    /**
     * Go to {@code micros} into the clip, exactly (the frames from the keyframe before it are decoded and dropped). If
     * the view is paused, the frame there is shown and it stays held there.
     */
    public synchronized void seek(long micros) {
        if (player != null) {
            endSaid = false;
            player.seek(Math.max(0, micros) / 1000);
        }
    }

    /** Start again from the beginning whenever the end is reached. */
    public synchronized VideoView loop(boolean loop) {
        this.loop = loop;
        if (player != null) {
            player.loop(loop);
        }
        return this;
    }

    /** Run {@code r}, on the player's thread, when a clip that does not loop reaches its end and is held there. */
    public synchronized VideoView onEnd(Runnable r) {
        onEnd = r == null ? () -> { } : r;
        return this;
    }

    /** Show nothing: stop the player, close the footage, the clip and the feed. */
    public synchronized void clear() {
        stop();
        failure = null;
    }

    public synchronized boolean playing() {
        return player != null && player.wanted();
    }

    /** The clip playing or held, or null. */
    public synchronized Clip clip() {
        return clip;
    }

    /** How far into the clip the time is, in µs. */
    public synchronized long position() {
        return player == null ? 0 : player.position() * 1000;
    }

    /** The clip's length in µs, or -1 where nothing is playing. */
    public synchronized long duration() {
        return player == null ? -1 : lengthMicros;
    }

    /** Why the clip will not play or stopped decoding early, or null. */
    public synchronized String failure() {
        if (failure != null) {
            return failure;
        }
        return player == null ? null : player.failure();
    }

    /**
     * How many distinct frames have been shown since the view was made, counted at the frames a window drew, so its
     * rate is the frame rate a person watching sees: a meter's.
     */
    public long framesShown() {
        return shownCount;
    }

    /** The feed the plate shows, or null: for a test, which does a renderer's part. */
    Feed feed() {
        return playing;
    }

    /** The time in the clip of the frame on the plate, in µs, or -1 before the first has been shown. */
    public synchronized long shownMicros() {
        long ms = player == null ? -1 : player.millisOf(drawnStamp);
        return ms < 0 ? -1 : ms * 1000;
    }

    /**
     * The asked half: the frame of the clip due at the frame's moment, and when the next one is. Before the first frame
     * the plate is drawn at nothing, since an empty feed draws a placeholder. Reads only the volatile feed; GUI thread.
     */
    long draw(Frame frame, Drawn out) {
        Feed f = playing;
        if (f == null) {
            return Frame.NEVER;
        }
        int slot = f.due(frame);
        if (slot < 0) {
            out.opacity(plate, 0f);
            return frame.presentNanos() + FIRST_FRAME_LOOK_NANOS;
        }
        out.show(f, slot);
        out.opacity(plate, 1f);
        long stamp = f.stamp(slot);
        if (stamp != drawnStamp) {
            shownCount++;
        }
        drawnStamp = stamp;
        return f.next(frame);
    }

    /** What the player says has changed: the end, said once, for a clip that does not loop. The player's thread. */
    private void changed() {
        Runnable then = null;
        synchronized (this) {
            Player p = player;
            if (p != null && p.ended() && !endSaid) {
                endSaid = true;
                then = onEnd;
            }
        }
        if (then != null) {
            then.run();
        }
    }

    /** Stop the player and close the feed. Under this. */
    private void stop() {
        if (player != null) {
            player.close();
            player = null;
        }
        Feed f = playing;
        playing = null;
        drawnStamp = -1;
        plate.image((Feed) null).visible(false);
        if (f != null) {
            f.close();
        }
        clip = null;
    }

    /** Size and place the plate on the stage: whole, at the clip's aspect, centred, as large as the stage allows. */
    private synchronized void fit() {
        NodeLayout box = stageBox;
        if (player == null || !box.present()) {
            return;
        }
        float cw = box.content().w();
        float ch = box.content().h();
        if (cw <= 0f || ch <= 0f) {
            return;
        }
        float[] f = fraction(width, height, cw, ch);
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
