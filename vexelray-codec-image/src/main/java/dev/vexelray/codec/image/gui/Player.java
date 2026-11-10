package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.codec.image.Reasons;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.vulkan.present.MipChain;
import sibarum.probe.Log;

import java.io.IOException;

/**
 * A picture that plays, made into a {@link Feed}: the producer half of vexelray-gui's frame contract
 * (docs/plans/frame-contract.md, step 5). It owns what plays (the {@link Playable} its {@link Motion} opens: a
 * {@code Movie} for an animated GIF, WebP or APNG, a video's frames for a video) and one thread, which writes the frames
 * into the feed ahead of when they are due, each stamped with its time, and says the feed's time as it goes
 * ({@link Feed#time}). Whoever shows the feed asks it which frame is due ({@link Feed#due(dev.vexelray.gui.core.Frame)})
 * in its asked half, and when the next one will be; it holds no clock, and nothing of this runs on the GUI thread.
 *
 * <p><b>What it replaces.</b> 0.1's {@code Playback} paced a picture on a krono shred that polled every few milliseconds,
 * and met the decoder and the transport in one locked object, then pushed each frame into a {@code StreamTexture}.
 * Here the pacing is the feed's time, the asked half's choice is a search over three stamps, and the thread only makes:
 * it sleeps until a slot comes free or a frame it waits for is made, and wakes at once when told something.
 *
 * <p><b>Its stamps are the feed's time, in nanoseconds,</b> and only ever increase: round a loop the next pass is
 * stamped after the last, and a seek starts after everything handed over before it, whose frames it makes
 * {@linkplain Feed#stale stale}. So the frame due is always the latest stamped at or before the feed's time, and a frame
 * made for where it was is never shown after a seek. A pass always starts on a whole number of passes, a seek's too, so
 * a stamp is so many passes and a time into one: {@link #positionOf} reads where in the file the feed's time is from
 * the time alone, which is all an asked half drawing a seek bar has.
 *
 * <p><b>A late frame is dropped from a video, and waited for in an animated image</b> ({@link Playable#drops}). Waited
 * for, the feed's time is held until it is made, so a machine too slow for an animation plays it slower; dropped, the
 * frame made next is the one due at the feed's time, and a slow decoder shows fewer frames rather than falling behind.
 *
 * <p><b>With a clock of its own</b> ({@link Playable#clock}, a video's sound) the time is the clock's: it starts with
 * the first frame, stops with every pause and hold, goes wherever a seek or a loop goes, and the feed is re-timed from
 * it whenever the two have drifted apart, so the pictures follow what is heard.
 *
 * <p><b>Begun from a feed already showing the first frame.</b> The feed is made where the picture was first decoded,
 * a job's, by {@link #feed}: its first frame handed over at stamp 0, so it is a picture before anything plays. A
 * {@code Player} made on it plays from there, round and round until {@link #loop} is turned off. Every method may be
 * called from any thread, and none waits for the decoder. {@link #close} stops it; the feed is its owner's to close.
 */
public final class Player implements AutoCloseable {

    /** A feed of three slots: one shown, one landing, one being written. */
    public static final int SLOTS = 3;

    /** How soon a frame the decoder has not made yet is looked for again: the producer's wait, never the GUI's. */
    static final long LOOK_AGAIN_NANOS = 4_000_000L;
    /** The longest the thread sleeps with nothing to do but keep time: a clock of its own is looked at this often. */
    static final long MAX_SLEEP_NANOS = 100_000_000L;
    /** How far the feed's time may be from a clock of its own before it is said again from the clock. */
    static final long DRIFT_NANOS = 2_000_000L;
    /** How far a clock of its own may be from where the pictures start again before it is sought there (Playback's). */
    static final long SEEK_SLACK_MS = 40;

    private static final long MS = 1_000_000L;
    private static final Log LOG = Log.of("codec.image");

    /** The machine's clock in nanoseconds; {@code System::nanoTime}, or a test's. */
    @FunctionalInterface
    interface Nanos {
        long now();
    }

    /** What the frames come from, once open; {@link Motion#open} by default, a test's otherwise. */
    @FunctionalInterface
    interface Opener {
        Playable open(Motion motion, int side) throws IOException;
    }

    private final Feed feed;
    private final Motion motion;
    private final Nanos nanos;
    private final Opener opener;
    private final Thread thread;

    private final Object lock = new Object();
    // Guarded by lock.
    private Playable movie;
    private boolean wanted = true;
    private boolean held;
    private boolean loop = true;
    private boolean closed;
    private boolean ended;
    private String failure;
    private float volume = 1f;
    private boolean muted;
    private float compression;
    /** Told something since the thread last looked: it looks again at once. */
    private boolean told;
    /** The frame to make next, and the stamp its pass starts at. */
    private int next = 1;
    private long makePass;
    /** The stamp the pass being seen starts at: the pass the feed's time is in. */
    private long seenPass;
    /** The latest stamp handed over: every stamp after a seek is later than it. */
    private long lastStamp;
    /** Waiting for the first frame after a seek, or for a late frame of a movie that drops none: the time is held. */
    private boolean waiting;
    /** Where a seek is waiting to go, in ms into a pass, or -1. */
    private long seekTo = -1;
    /** Bumped by every seek, so a frame copied across one is dropped rather than handed over. */
    private int generation;
    /** A frame to copy into a slot outside the lock, as {@link #turn} chose it: its slot, or -1, stamp and generation. */
    private int copySlot = -1;
    private long copyStamp;
    private byte[][] copyChain;
    private int copyGeneration;
    /** Something a transport bar shows has changed since {@link #changed} was last run. */
    private boolean owed;
    /** Run on the player's thread, outside its lock, whenever what a transport bar shows has changed. */
    private final Runnable changed;

    private Player(Feed feed, Motion motion, Nanos nanos, Opener opener, Runnable changed, boolean start) {
        if (feed.slots() < 2) {
            throw new IllegalArgumentException("a picture that plays needs a feed of " + SLOTS + " slots: " + feed);
        }
        this.feed = feed;
        this.motion = motion;
        this.nanos = nanos;
        this.opener = opener;
        this.changed = changed == null ? () -> { } : changed;
        Thread t = Thread.ofPlatform().daemon().name("player " + motion.stamp().path().getFileName()).unstarted(this::run);
        this.thread = t;
        if (start) {
            t.start();
        }
    }

    /**
     * Play {@code motion}'s frames into {@code feed}, now: a feed {@link #feed} made, showing the first frame already, or
     * an empty one, which shows nothing until the first frame is made. Opens the file on the player's own thread
     * ({@link Motion#open}, so a motion's own {@code Source} says what plays), so this returns at once.
     */
    public static Player play(Feed feed, Motion motion) {
        return play(feed, motion, null);
    }

    /**
     * {@link #play(Feed, Motion)}, running {@code changed} whenever what a transport bar shows has changed: the frame
     * handed over, playing or not, the loop, the volume, the end, a failure. On the player's own thread, outside its
     * lock; keep it cheap. For a widget, which tells its own tree; an application hears its widget by message.
     */
    public static Player play(Feed feed, Motion motion, Runnable changed) {
        return new Player(feed, motion, System::nanoTime, Motion::open, changed, true);
    }

    /** A player on a test's clock and frames, its thread not started: {@link #run} is called by the test. */
    static Player forTest(Feed feed, Motion motion, Nanos nanos, Opener opener) {
        return new Player(feed, motion, nanos, opener, null, false);
    }

    /**
     * An empty feed for a picture that plays, of {@link #SLOTS} slots of {@code width x height}, nothing handed over: a
     * player makes its first frame too. Cheap to make anywhere, a slot's memory being made when it is first written.
     */
    public static Feed feed(int width, int height) {
        return Feed.of(width, height, SLOTS, true);
    }

    /**
     * The feed a picture that plays is shown through: {@link #SLOTS} slots of {@code width x height} pictures, its first
     * frame's {@code pixels} (RGBA8) handed over at stamp 0 with their mip chain. Made where the first frame was decoded
     * (a job), it is shown as that frame until a {@code Player} plays the rest into it.
     */
    public static Feed feed(int width, int height, byte[] pixels) {
        Feed f = Feed.of(width, height, SLOTS, true);
        f.write(MipChain.of(pixels, width, height), 0L);
        return f;
    }

    // ------------------------------------------------------------------ the transport, any thread

    /** Play from where it is; from the start, if it stopped at the end. */
    public void play() {
        synchronized (lock) {
            if (wanted || closed) {
                return;
            }
            wanted = true;
            if (ended) {
                ended = false;
                seekTo = 0;
            }
            retime();
            tell();
        }
    }

    /** Stop where it is, the frame showing staying up. */
    public void pause() {
        synchronized (lock) {
            if (!wanted || closed) {
                return;
            }
            wanted = false;
            retime();
            tell();
        }
    }

    /** {@link #play} if paused, else {@link #pause}. */
    public void toggle() {
        boolean w;
        synchronized (lock) {
            w = wanted;
        }
        if (w) {
            pause();
        } else {
            play();
        }
    }

    /**
     * Pause without being paused, for a window that is not focused: the time stops, and runs on when let go if it was
     * running. Nothing is made meanwhile, and nothing is forgotten.
     */
    public void hold(boolean hold) {
        synchronized (lock) {
            if (held == hold || closed) {
                return;
            }
            held = hold;
            retime();
            tell();
        }
    }

    /** Go to {@code ms} into a pass. Paused, the frame there is shown and it stays there. */
    public void seek(long ms) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            seekTo = Math.max(0, Math.min(ms, durationLocked()));
            ended = false;
            tell();
        }
    }

    /**
     * Pause, and show the frame {@code frames} on from the one due now: back for a negative count. Round from the last
     * frame to the first and back when looping. Nothing, until the file is open.
     */
    public void step(int frames) {
        synchronized (lock) {
            if (movie == null || closed) {
                return;
            }
            wanted = false;
            int n = movie.count();
            int at = movie.frameAt(positionLocked());
            int to = loop ? Math.floorMod(at + frames, n) : Math.max(0, Math.min(n - 1, at + frames));
            retime();
            seekTo = movie.startOf(to);
            ended = false;
            tell();
        }
    }

    /** Start again from the beginning whenever the end is reached: on by default. */
    public void loop(boolean on) {
        synchronized (lock) {
            loop = on;
            tell();
        }
    }

    /** How loud its sound is heard, from 0 to 1 (as the file has it, the default); kept for a picture with none. */
    public void volume(float volume) {
        synchronized (lock) {
            this.volume = Math.max(0f, Math.min(1f, volume));
            sound();
            tell();
        }
    }

    /** Silence its sound, or let it be heard at its {@link #volume}. The time is kept either way. */
    public void muted(boolean muted) {
        synchronized (lock) {
            this.muted = muted;
            sound();
            tell();
        }
    }

    /** Dynamic range compression on its sound, from 0 (none, the default) to 1 (Playback's knob). */
    public void compression(float amount) {
        synchronized (lock) {
            this.compression = amount > 0f ? Math.min(1f, amount) : 0f;   // NaN is none
            sound();
            tell();
        }
    }

    // ------------------------------------------------------------------ what a transport bar reads, any thread

    /** Whether its time runs: asked to play, not held, not ended, and not closed. */
    public boolean playing() {
        synchronized (lock) {
            return runsLocked();
        }
    }

    /** Whether it has been asked to play, held or not: what a play button shows. */
    public boolean wanted() {
        synchronized (lock) {
            return wanted;
        }
    }

    /** Whether it stopped at the end, not looping. */
    public boolean ended() {
        synchronized (lock) {
            return ended;
        }
    }

    public boolean loop() {
        synchronized (lock) {
            return loop;
        }
    }

    /** How far into a pass the feed's time is, in ms. */
    public long position() {
        synchronized (lock) {
            return positionLocked();
        }
    }

    /** One pass, in ms: the file's once open, what the motion said until then. */
    public long duration() {
        synchronized (lock) {
            return durationLocked();
        }
    }

    /** Whether there is sound to hear: a video with sound, once its file is open. */
    public boolean sounds() {
        synchronized (lock) {
            return movie != null && movie.clock() != null;
        }
    }

    /** Why it will not play, or stopped playing, or null: the frame on screen stays. */
    public String failure() {
        synchronized (lock) {
            if (failure != null) {
                return failure;
            }
            return movie == null ? null : movie.failure();
        }
    }

    /** Whether it is held ({@link #hold}). */
    public boolean held() {
        synchronized (lock) {
            return held;
        }
    }

    /** How many frames: the file's once open, what the motion said until then; at least one. */
    public int count() {
        synchronized (lock) {
            return Math.max(1, movie != null ? movie.count() : motion.frames());
        }
    }

    /** The frame due at the feed's time, from 0; 0 until the file is open. */
    public int frame() {
        synchronized (lock) {
            return movie == null ? 0 : movie.frameAt(positionLocked());
        }
    }

    /**
     * Where in a pass the frame on screen is, in ms, as the renderer last chose it ({@link Feed#shown}), or -1 before
     * any is shown: what a meter of the frames that reached the screen counts. Read from the GUI thread's last choice,
     * so from another thread it may be a frame behind.
     */
    public long shown() {
        int slot = feed.shown();
        if (slot < 0) {
            return -1;
        }
        return millisOf(feed.stamp(slot));
    }

    /**
     * How far through a pass of {@code passMillis} the feed's time is at {@code frame}'s moment, from 0 to 1: a seek
     * bar's place, for an asked half, which holds the feed and the length and nothing of the player. Exact because a
     * pass always starts on a whole number of passes. 0 before the feed has a time. Static and allocating nothing.
     */
    public static float positionOf(Feed feed, dev.vexelray.gui.core.Frame frame, long passMillis) {
        long now = feed.now(frame.monoNanos());
        if (now >= Long.MAX_VALUE / 2 || passMillis <= 0) {
            return 0f;
        }
        long pass = passMillis * MS;
        return (float) Math.floorMod(now, pass) / pass;
    }

    /**
     * Where in a pass a frame stamped {@code stamp} is, in ms: what an asked half that chose a slot ({@link Feed#due})
     * says it showed. -1 for a negative stamp, which no frame has.
     */
    public long millisOf(long stamp) {
        if (stamp < 0) {
            return -1;
        }
        synchronized (lock) {
            long d = durationLocked();
            long ms = (stamp - seenPass) / MS;
            return d > 0 ? Math.floorMod(ms, d) : Math.max(0, ms);
        }
    }

    /** When frame {@code i} starts, in ms into a pass; 0 until the file is open. */
    public long startOf(int i) {
        synchronized (lock) {
            return movie == null ? 0 : movie.startOf(Math.max(0, Math.min(i, movie.count() - 1)));
        }
    }

    /**
     * Copy what its sound last gave the speaker into {@code stereo}, oldest first, left and right in turn, for a meter:
     * the sample frames copied. None when there is no sound to hear.
     */
    public int heard(float[] stereo) {
        Playable.Clock own;
        synchronized (lock) {
            own = movie == null ? null : movie.clock();
        }
        return own == null ? 0 : own.heard(stereo);
    }

    public Feed feed() {
        return feed;
    }

    public Motion motion() {
        return motion;
    }

    /** Stop for good: the thread, the decoder and its file. The feed stays its owner's, showing the frame it showed. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            tell();
        }
    }

    // ------------------------------------------------------------------ the thread

    /** Open the file, then make frames until closed. The player's own thread. */
    void run() {
        boolean opened = open();
        changed.run();
        if (!opened) {
            return;
        }
        try {
            while (true) {
                long sleep = once();
                if (sleep < 0) {
                    break;
                }
                boolean fire;
                synchronized (lock) {
                    fire = owed;
                    owed = false;
                }
                if (fire) {
                    changed.run();
                }
                synchronized (lock) {
                    if (sleep > 0 && !told) {
                        lock.wait(sleep / MS, (int) (sleep % MS));
                    }
                    told = false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            LOG.warn("{} stopped playing: {}", motion.stamp().path(), e.toString());
            synchronized (lock) {
                failure = Reasons.failed(e);
                wanted = false;
                retime();
            }
        } finally {
            shut();
            changed.run();
        }
    }

    /**
     * One look and, if a frame is ready and a slot free, that frame copied and handed over: how long to sleep before the
     * next look, in ns, or -1 when closed. The copy is outside the lock, so a transport called from the GUI thread never
     * waits on one. The thread's, or a test's in its place.
     */
    long once() {
        synchronized (lock) {
            if (closed || feed.closed()) {
                return -1;
            }
            long sleep = turn();
            if (copySlot < 0) {
                return sleep;
            }
        }
        for (int l = 0; l < feed.levels(); l++) {
            System.arraycopy(copyChain[l], 0, feed.level(copySlot, l), 0, feed.levelBytes(l));
        }
        synchronized (lock) {
            handOver();
        }
        return 0;
    }

    /**
     * Open the file and start the time from the first frame, on screen already: whether it opened. A file that will not
     * open, or opens at another size than its first frame was made at, says why ({@link #failure}) and plays nothing.
     */
    boolean open() {
        Playable m;
        try {
            m = opener.open(motion, Math.max(feed.width(), feed.height()));
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
            LOG.warn("{} will not play: {}", motion.stamp().path(), e.toString());
            synchronized (lock) {
                failure = Reasons.failed(e);
            }
            return false;
        }
        if (m.width() != feed.width() || m.height() != feed.height()) {
            // The poster was made at another size than the frames: a slot cannot hold them. Said, not drawn wrong.
            LOG.warn("{} plays at {}x{} into a feed of {}x{}", motion.stamp().path(), m.width(), m.height(),
                    feed.width(), feed.height());
            m.close();
            synchronized (lock) {
                failure = "its frames are not the size of its first";
            }
            return false;
        }
        synchronized (lock) {
            if (closed) {
                m.close();
                return false;
            }
            movie = m;
            sound();
            Playable.Clock own = m.clock();
            if (own != null) {
                own.seek(0);
            }
            makePass = 0;
            seenPass = 0;
            if (feed.newest() >= 0) {
                // The first frame is on screen already, at stamp 0 (a job's): the time starts there, now.
                lastStamp = 0;
                next = m.count() > 1 ? 1 : 0;
                boolean runs = runsLocked();
                feed.time(nanos.now(), 0L, runs);
                clockRuns(m, runs);
            } else {
                // An empty feed: the first frame is made here too, and the time waits at 0 until it is.
                lastStamp = -1;
                next = 0;
                waiting = true;
                feed.time(nanos.now(), 0L, false);
            }
            return true;
        }
    }

    /** Let go of the decoder and its file, and stop its sound: closed for good. The feed stays its owner's. */
    void shut() {
        Playable own;
        synchronized (lock) {
            own = movie;
            movie = null;
            closed = true;
            Playable.Clock c = own == null ? null : own.clock();
            if (c != null) {
                c.run(false);
            }
        }
        if (own != null) {
            own.close();
        }
    }

    /**
     * One look: go where a seek asked, follow the clock, notice the end, and make the next frame if a slot is free and
     * it is made. How long to sleep before looking again, in ns: 0 to look again at once. Under the lock.
     */
    long turn() {
        Playable m = movie;
        if (m == null) {
            return MAX_SLEEP_NANOS;
        }
        if (seekTo >= 0) {
            goTo(m, seekTo);
            seekTo = -1;
        }
        long now = nanos.now();
        followClock(m, now);
        long at = feed.now(now);
        if (ended(m, at)) {
            return Long.MAX_VALUE / 2;
        }
        if (!runsLocked() && !waiting) {
            // Paused or held, with the frame due on screen: nothing to make until told.
            return Long.MAX_VALUE / 2;
        }
        int count = m.count();
        if (next >= count) {
            // Every frame of the last pass is handed over, and it does not loop: wait for the end to come.
            return untilNext(at);
        }
        long stamp = makePass + m.startOf(next) * MS;
        if (m.drops() && !waiting && stamp + m.delay(next) * MS <= at) {
            // Late: its time on screen is already over. Make the frame due instead, so the pictures keep up.
            int due = m.frameAt((at - makePass) / MS);
            if (due > next) {
                next = due;
                stamp = makePass + m.startOf(next) * MS;
            }
        }
        m.want(next, loop);
        byte[][] chain = m.frame(next);
        if (chain == null) {
            if (next >= m.count()) {
                // The file stopped decoding before this frame: it is never coming. Stopped where it is, as at the end.
                ended = true;
                owed = true;
                wanted = false;
                retime();
                return Long.MAX_VALUE / 2;
            }
            if (!m.drops() && !waiting && stamp <= at) {
                // An animation every frame of which is shown: the time waits for this one.
                waiting = true;
                feed.time(now, stamp, false);
                clockRuns(m, false);
            }
            return LOOK_AGAIN_NANOS;
        }
        int slot = feed.take();
        if (slot < 0) {
            // The ring is full: a slot comes free when the next frame handed over is shown.
            return untilNext(at);
        }
        copySlot = slot;
        copyStamp = stamp;
        copyChain = chain;
        copyGeneration = generation;
        return 0;
    }

    /**
     * Hand over the frame {@link #turn} chose and the thread has copied, and go on to the one after; or, if a seek came
     * meanwhile, give its slot back unwritten, since it was made for where the picture was. Under the lock.
     */
    void handOver() {
        int slot = copySlot;
        long stamp = copyStamp;
        copySlot = -1;
        copyChain = null;
        Playable m = movie;
        if (m == null || closed || copyGeneration != generation) {
            feed.drop(slot);
            return;
        }
        feed.handOver(slot, stamp);
        owed = true;
        lastStamp = stamp;
        if (waiting) {
            // The frame the time waited for is here: it runs on from it, now.
            waiting = false;
            long now = nanos.now();
            long at = Math.max(stamp, feed.now(now));
            boolean runs = runsLocked();
            feed.time(now, at, runs);
            clockRuns(m, runs);
        }
        next++;
        if (next >= m.count() && loop) {
            next = 0;
            makePass += m.duration() * MS;
        }
    }

    /** Go to {@code ms} into a pass: everything handed over is stale, and the time waits there for its frame. */
    private void goTo(Playable m, long ms) {
        generation++;
        int to = m.frameAt(ms);
        // A new pass, after everything handed over, starting on a whole number of passes: so a stamp is always so many
        // passes and a time into one, and the feed's time alone says where in the file it is (a seek bar's).
        long pass = Math.max(1, m.duration()) * MS;
        makePass = (Math.floorDiv(Math.max(lastStamp, 0L), pass) + 1) * pass;
        seenPass = makePass;
        long first = makePass + m.startOf(to) * MS;
        next = to;
        feed.stale(first);
        long at = makePass + ms * MS;
        feed.time(nanos.now(), at, false);
        waiting = true;
        Playable.Clock own = m.clock();
        if (own != null) {
            own.run(false);
            if (Math.abs(own.now() - ms) > SEEK_SLACK_MS) {
                own.seek(ms);
            }
        }
    }

    /**
     * A clock of its own keeps the time: say it again on the feed when the two have drifted apart, and take it round a
     * loop when it passes the end. Nothing for a playable with none.
     */
    private void followClock(Playable m, long now) {
        Playable.Clock own = m.clock();
        if (own == null || !runsLocked() || waiting) {
            return;
        }
        long ms = own.now();
        if (ms >= m.duration() && loop) {
            seenPass += m.duration() * MS;
            own.seek(0);
            ms = 0;
        }
        long stamp = seenPass + ms * MS;
        if (Math.abs(feed.now(now) - stamp) > DRIFT_NANOS) {
            feed.time(now, stamp, true);
        }
    }

    /**
     * Whether it has reached the end of a pass that does not loop, the feed's time at {@code at}: then it stops there,
     * the last frame up. With a loop, whether the time has gone into the next pass, which is all that is noticed.
     */
    private boolean ended(Playable m, long at) {
        long end = seenPass + m.duration() * MS;
        if (at < end || waiting || m.duration() <= 0) {
            return ended;
        }
        if (loop) {
            if (m.clock() == null) {
                seenPass += ((at - seenPass) / (m.duration() * MS)) * m.duration() * MS;
            }
            return false;
        }
        if (!ended) {
            ended = true;
            owed = true;
            wanted = false;
            feed.time(nanos.now(), end - 1, false);
            clockRuns(m, false);
        }
        return true;
    }

    /** How long until the next frame handed over comes due, and so a slot comes free: at least a little, at most the cap. */
    private long untilNext(long at) {
        if (waiting) {
            // After a seek the stale slots come free at the window's next frame, which tells nobody: look again soon.
            return LOOK_AGAIN_NANOS;
        }
        if (!feed.running()) {
            return Long.MAX_VALUE / 2;
        }
        long s = feed.nextAfter(at);
        if (s == Long.MAX_VALUE) {
            // Everything handed over is due and the window has not drawn it yet (it may be hidden): look again a frame on.
            return 4 * LOOK_AGAIN_NANOS;
        }
        return Math.max(LOOK_AGAIN_NANOS / 4, Math.min(MAX_SLEEP_NANOS, s - at + LOOK_AGAIN_NANOS / 4));
    }

    /**
     * Say the feed's time again as it is now, running or held as the transport says. Nothing until the file is open: the
     * time starts with it. Under the lock.
     */
    private void retime() {
        if (movie == null) {
            return;
        }
        long now = nanos.now();
        boolean runs = runsLocked() && !waiting;
        feed.time(now, feed.now(now), runs);
        clockRuns(movie, runs);
    }

    private void clockRuns(Playable m, boolean run) {
        Playable.Clock own = m.clock();
        if (own != null) {
            own.run(run);
        }
    }

    private boolean runsLocked() {
        return wanted && !held && !closed && !ended;
    }

    private long positionLocked() {
        long d = durationLocked();
        long at = feed.now(nanos.now());
        if (at >= Long.MAX_VALUE / 2) {
            return 0;
        }
        long ms = (at - seenPass) / MS;
        if (d > 0 && ms < 0) {
            ms = Math.floorMod(ms, d);
        }
        return Math.max(0, Math.min(ms, d));
    }

    private long durationLocked() {
        return movie != null ? movie.duration() : motion.durationMillis();
    }

    /** Give the clock of its own its volume and compression, if it has one. Under the lock. */
    private void sound() {
        Playable.Clock own = movie == null ? null : movie.clock();
        if (own != null) {
            own.volume(muted ? 0f : volume);
            own.compression(compression);
        }
    }

    /** Wake the thread to look again. Under the lock. */
    private void tell() {
        told = true;
        owed = true;
        lock.notifyAll();
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "Player[" + motion.stamp().path().getFileName() + " at " + positionLocked() + "ms"
                    + (runsLocked() ? ", playing" : held ? ", held" : ended ? ", ended" : ", paused")
                    + (loop ? ", looping" : "") + "]";
        }
    }
}
