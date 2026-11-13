package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.codec.image.Stamp;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.vulkan.present.MipChain;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Player} making frames into a feed, on a stepped clock and a test's frames, its thread replaced by the test
 * calling {@link Player#once}: which frame is handed over with which stamp, what the feed's time is, and what a part's
 * asked half would find due ({@link Feed#due(Frame)}), through a pause, a seek, a loop, the end and a late frame.
 */
class PlayerTest {

    private static final long MS = 1_000_000L;
    private static final int W = 4;
    private static final int H = 2;

    /** Frames of {@code W x H}, frame {@code i} filled with {@code i + 1}, each {@code delay} ms; made when ready. */
    private static class Strip implements Playable {
        final int count;
        final int delay;
        final boolean drops;
        final BitSet ready = new BitSet();
        Playable.Clock clock;
        boolean closed;

        Strip(int count, int delay, boolean drops) {
            this.count = count;
            this.delay = delay;
            this.drops = drops;
            ready.set(0, count);
        }

        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return H;
        }

        @Override
        public int count() {
            return count;
        }

        @Override
        public long duration() {
            return (long) count * delay;
        }

        @Override
        public int delay(int i) {
            return delay;
        }

        @Override
        public long startOf(int i) {
            return (long) i * delay;
        }

        @Override
        public int frameAt(long ms) {
            return (int) Math.max(0, Math.min(count - 1, ms / delay));
        }

        @Override
        public void want(int i, boolean wrap) {
        }

        @Override
        public byte[][] frame(int i) {
            if (i < 0 || i >= count || !ready.get(i)) {
                return null;
            }
            byte[] px = new byte[W * H * 4];
            Arrays.fill(px, (byte) (i + 1));
            return MipChain.of(px, W, H);
        }

        @Override
        public String failure() {
            return null;
        }

        @Override
        public boolean drops() {
            return drops;
        }

        @Override
        public Clock clock() {
            return clock;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A sound's clock, moved by hand: where it is, and whether it was told to run. */
    private static final class Heard implements Playable.Clock {
        long ms;
        boolean running;

        @Override
        public long now() {
            return ms;
        }

        @Override
        public void run(boolean run) {
            running = run;
        }

        @Override
        public void seek(long ms) {
            this.ms = ms;
        }
    }

    private long now;

    private Player player(Strip strip) {
        Motion motion = new Motion(new Stamp(Path.of("strip.gif"), 1, 1), strip.count, strip.duration(), 0);
        byte[] first = new byte[W * H * 4];
        Arrays.fill(first, (byte) 1);
        Feed feed = Player.feed(W, H, first);
        Player p = Player.forTest(feed, motion, () -> now, (m, side) -> strip);
        assertTrue(p.open());
        return p;
    }

    /** What the frame shown at {@code atMs} is: the value its pixels were filled with, frame {@code i} as {@code i + 1}. */
    private static int dueAt(Feed f, long atMs) {
        int slot = f.due(new Frame(atMs * MS, atMs * MS, false));
        return slot < 0 ? -1 : f.chain(slot)[0][0];
    }

    /** What the frame due now is, on the test's clock. */
    private int dueNow(Feed f) {
        int slot = f.due(new Frame(now, now, false));
        return slot < 0 ? -1 : f.chain(slot)[0][0];
    }

    /** As the window would: show the frame due, which frees the ones before it. */
    private static void draw(Feed f) {
        f.select();
    }

    @Test
    void itPlaysOnFromTheFirstFrameAlreadyInTheFeed() {
        Strip s = new Strip(4, 10, false);
        Player p = player(s);
        Feed f = p.feed();
        assertEquals(1, dueAt(f, 0), "frame 0 was the job's, at stamp 0");
        assertEquals(0L, p.once());
        assertEquals(0L, p.once());
        assertTrue(p.once() > 0, "three slots, all handed over: it waits for one to come free");
        assertEquals(2, dueAt(f, 10));
        assertEquals(3, dueAt(f, 25));
        assertTrue(f.running());
        assertEquals(10 * MS, f.next(new Frame(5 * MS, 5 * MS, false)), "the asked half is due again at frame 1's time");
        p.shut();
        assertTrue(s.closed);
    }

    @Test
    void fromAnEmptyFeedItMakesTheFirstFrameTooAndTheTimeWaitsForIt() {
        Strip s = new Strip(4, 10, false);
        s.ready.clear(0);
        Motion motion = new Motion(new Stamp(Path.of("strip.gif"), 1, 1), 4, 40, 0);
        Player p = Player.forTest(Player.feed(W, H), motion, () -> now, (m, side) -> s);
        assertTrue(p.open());
        Feed f = p.feed();
        assertEquals(-1, f.newest(), "nothing is shown until the first frame is made");
        assertFalse(f.running());
        now = 30 * MS;
        assertTrue(p.once() > 0);
        assertEquals(0L, f.now(now), "the time waits at the start");
        s.ready.set(0);
        assertEquals(0L, p.once());
        assertTrue(f.running(), "the first frame is here: the time runs from it, now");
        assertEquals(1, dueNow(f));
        assertEquals(0L, p.once());
        assertEquals(2, dueAt(f, 40));
    }

    @Test
    void roundALoopTheStampsGoOnIncreasing() {
        Strip s = new Strip(3, 10, false);
        Player p = player(s);
        Feed f = p.feed();
        p.once();                         // frame 1 at 10
        p.once();                         // frame 2 at 20
        now = 20 * MS;
        draw(f);                          // shows frame 2: 0 and 1 come free
        assertEquals(0L, p.once());       // frame 0 of the second pass at 30
        assertEquals(0L, p.once());       // frame 1 of the second pass at 40
        assertEquals(1, dueAt(f, 30));
        assertEquals(2, dueAt(f, 45));
        assertEquals(30L * MS, f.nextAfter(25L * MS));
    }

    @Test
    void aPauseHoldsTheTimeAndThePlayLetsItRunOn() {
        Strip s = new Strip(4, 10, false);
        Player p = player(s);
        Feed f = p.feed();
        p.once();
        now = 15 * MS;
        p.pause();
        assertFalse(f.running());
        assertFalse(p.playing());
        now = 500 * MS;
        assertEquals(15 * MS, f.now(now), "paused, the feed's time stays where it was");
        assertEquals(2, dueAt(f, 500), "and frame 1, due when it paused, stays up");
        assertEquals(15L, p.position());
        p.play();
        assertTrue(f.running());
        assertEquals(15 * MS + 5 * MS, f.now(505 * MS), "played, it runs on from there");
    }

    @Test
    void aSeekMakesWhatWasHandedOverStaleAndWaitsForItsFrame() {
        Strip s = new Strip(10, 10, false);
        Player p = player(s);
        Feed f = p.feed();
        p.once();                         // frame 1 at 10
        p.once();                         // frame 2 at 20
        now = 5 * MS;
        p.seek(70);
        s.ready.clear(7);
        assertTrue(p.once() > 0, "frame 7 is not made yet");
        assertFalse(f.running(), "the time waits at the seek for its frame");
        assertEquals(-1, f.due(new Frame(now, now, false)), "nothing made for where it was is due");
        draw(f);                          // the stale frames 1 and 2 come free; frame 0 stays on screen
        s.ready.set(7);
        assertEquals(0L, p.once());
        assertTrue(f.running(), "its frame is here: the time runs on");
        assertEquals(8, dueNow(f), "frame 7 is the one due");
        assertEquals(70L, p.position());
        assertEquals(0.7f, Player.positionOf(f, new Frame(now, now, false), 100), 1e-4f,
                "a seek starts on a whole pass, so the feed's time alone says where it is");
    }

    @Test
    void anAnimationWaitsForALateFrame() {
        Strip s = new Strip(4, 10, false);
        s.ready.clear(1);
        Player p = player(s);
        Feed f = p.feed();
        now = 15 * MS;
        assertTrue(p.once() > 0);
        assertFalse(f.running(), "frame 1 was due at 10 and is not made: the time holds");
        assertEquals(10 * MS, f.now(now));
        now = 40 * MS;
        s.ready.set(1);
        assertEquals(0L, p.once());
        assertTrue(f.running());
        assertEquals(10 * MS, f.now(now), "and runs on from frame 1, now, rather than skipping it");
        assertEquals(2, dueNow(f));
    }

    @Test
    void aVideoDropsALateFrameAndMakesTheOneDue() {
        Strip s = new Strip(10, 10, true);
        s.ready.clear(1);
        Player p = player(s);
        Feed f = p.feed();
        now = 35 * MS;
        assertEquals(0L, p.once(), "frame 1's time is over: frame 3, due now, is made instead");
        assertTrue(f.running(), "a video's time never waits");
        assertEquals(4, dueAt(f, 35));
    }

    /**
     * A 60 fps video on a decoder that makes the frame asked for 25 ms after it is asked, one at a time, as
     * {@code Footage} does at 1080p60 with its mip chains: every frame it is asked for when due is made late. It used to
     * make every one and have each thrown away, showing the first frame and never another; now the newest made is
     * shown, late, and once none is, a frame far enough ahead is asked for that it is made in time.
     */
    @Test
    void aVideoWhoseDecoderIsAlwaysLateStillShowsNewFrames() {
        int lagMs = 25;
        Strip s = new Strip(600, 16, true) {
            int asked = -1;
            long askedAt;

            void decode() {
                if (asked >= 0 && now - askedAt >= lagMs * MS) {
                    ready.set(asked);
                }
            }

            @Override
            public void want(int i, boolean wrap) {
                decode();
                if (i != asked && (asked < 0 || ready.get(asked))) {
                    asked = i;   // busy with one frame, it takes the next ask once that one is made
                    askedAt = now;
                }
            }

            @Override
            public byte[][] frame(int i) {
                decode();
                return super.frame(i);
            }
        };
        s.ready.clear(1, s.count);
        Player p = player(s);
        Feed f = p.feed();
        java.util.List<Integer> shown = new java.util.ArrayList<>();
        for (now = 0; now <= 1000 * MS; now += MS) {
            for (int k = 0; k < 8 && p.once() == 0; k++) {
                // made and handed over: look again at once, as the thread does
            }
            int newest = f.newest();
            if (newest >= 0 && f.stamp(newest) <= now) {
                draw(f);   // as a window whose asked half chooses the frame due: never one handed over ahead of its time
            }
            int d = dueNow(f);
            if (shown.isEmpty() || shown.get(shown.size() - 1) != d) {
                shown.add(d);
            }
        }
        assertTrue(shown.size() >= 20, "a second of a decoder a frame and a half behind shows frames: " + shown);
        for (int k = 1; k < shown.size(); k++) {
            assertTrue(shown.get(k) > shown.get(k - 1), "and only ever newer ones: " + shown);
        }
        assertTrue(shown.get(shown.size() - 1) >= 55, "and keeps up with the time: " + shown);
    }

    /**
     * A 60 fps video on a decoder that walks forward as {@code Footage} does: 12 ms a frame decoded, 8 ms more for each
     * frame of the window it keeps (the frame asked for and the four after), seeking only to go back. It keeps up while
     * it passes frames and falls behind while it keeps them. The pictures run in bursts, each a little late, with a jump
     * ahead between, and never stop for long.
     */
    @Test
    void aVideoOnADecoderThatWalksForwardNeverStopsShowingFrames() {
        Strip s = new Strip(1200, 16, true) {
            int window = 0;
            int at = 0;
            long busyUntil = 0;

            void decode() {
                while (busyUntil <= now) {
                    if (window < at) {
                        at = window;   // behind the decoder: sought, the frame there decoded next
                    } else {
                        at++;
                    }
                    if (at >= window && at <= window + 4 && at < count && !ready.get(at)) {
                        ready.set(at);
                        busyUntil += 20 * MS;
                    } else {
                        busyUntil += 12 * MS;
                    }
                }
            }

            @Override
            public void want(int i, boolean wrap) {
                decode();
                window = i;
            }

            @Override
            public byte[][] frame(int i) {
                decode();
                return super.frame(i);
            }
        };
        s.ready.clear(1, s.count);
        Player p = player(s);
        Feed f = p.feed();
        int last = 1;
        long lastNew = 0;
        long longest = 0;
        int distinct = 0;
        for (now = 0; now <= 3000 * MS; now += MS) {
            for (int k = 0; k < 8 && p.once() == 0; k++) {
                // made and handed over: look again at once, as the thread does
            }
            int newest = f.newest();
            if (newest >= 0 && f.stamp(newest) <= now) {
                draw(f);
            }
            int slot = f.due(new Frame(now, now, false));
            int d = slot < 0 ? -1 : (int) (f.stamp(slot) / (16 * MS)) + 1;   // by its stamp: a byte holds too few
            assertTrue(d >= last, "only ever newer frames: " + d + " after " + last);
            if (d != last) {
                longest = Math.max(longest, now - lastNew);
                lastNew = now;
                last = d;
                distinct++;
            }
        }
        assertTrue(distinct >= 60, "three seconds of a decoder slower than the rate show frames: " + distinct);
        assertTrue(longest <= 250 * MS, "and never stop for long: " + longest / MS + " ms without a new one");
        assertTrue(last >= 150, "and keep up with the time: at frame " + (last - 1) + " after 3 s");
    }

    @Test
    void notLoopingItStopsAtTheEndOnTheLastFrame() {
        Strip s = new Strip(3, 10, false);
        Player p = player(s);
        Feed f = p.feed();
        p.loop(false);
        p.once();
        p.once();
        now = 20 * MS;
        draw(f);
        p.once();
        now = 31 * MS;
        p.once();
        assertTrue(p.ended());
        assertFalse(p.playing());
        assertFalse(f.running());
        assertEquals(3, dueAt(f, f.now(now) / MS), "the last frame stays up");
        assertEquals(Frame.NEVER, f.next(new Frame(now, now, false)));
        p.play();
        assertFalse(p.ended(), "played again, it starts from the beginning");
    }

    @Test
    void withASoundTheFeedFollowsWhatIsHeard() {
        Strip s = new Strip(10, 10, true);
        Heard heard = new Heard();
        s.clock = heard;
        Player p = player(s);
        Feed f = p.feed();
        assertTrue(heard.running, "the sound starts with the pictures");
        now = 30 * MS;
        heard.ms = 20;                    // the speaker is 10 ms behind the machine's clock
        p.once();
        assertEquals(20 * MS, f.now(now), "the feed's time is said again from the sound");
        p.pause();
        assertFalse(heard.running, "and the sound stops with a pause");
    }

    @Test
    void framesOfAnotherSizeThanTheFirstAreRefusedWithAReason() {
        Strip s = new Strip(3, 10, false) {
            @Override
            public int width() {
                return W * 2;
            }
        };
        Motion motion = new Motion(new Stamp(Path.of("strip.gif"), 1, 1), 3, 30, 0);
        Player p = Player.forTest(Player.feed(W, H, new byte[W * H * 4]), motion, () -> now, (m, side) -> s);
        assertFalse(p.open());
        assertNotNull(p.failure());
        assertTrue(s.closed);
    }

    @Test
    void aFileThatWillNotOpenSaysWhy() {
        Motion motion = new Motion(new Stamp(Path.of("gone.gif"), 1, 1), 3, 30, 0);
        Player p = Player.forTest(Player.feed(W, H, new byte[W * H * 4]), motion, () -> now, (m, side) -> {
            throw new java.nio.file.NoSuchFileException("gone.gif");
        });
        assertFalse(p.open());
        assertNotNull(p.failure());
    }

    /**
     * A real {@code Movie} of each animated sample, red, green and blue at 30, 50 and 70 ms, decoding on its own thread:
     * the feed made from its poster, as a job makes it, plays its frames in order and round again.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"anim3.gif", "anim3.webp", "anim3.apng.png"})
    void anAnimationPlaysIntoItsPostersFeed(String name, @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path gif = dir.resolve(name);
        try (java.io.InputStream in = PlayerTest.class.getResourceAsStream("/" + name)) {
            java.nio.file.Files.copy(in, gif);
        }
        dev.vexelray.codec.image.Picture poster = dev.vexelray.codec.image.Pictures.of(gif, 1024).picture();
        assertNotNull(poster.motion());
        Feed feed = Player.feed(poster.frameWidth(), poster.frameHeight(), poster.sheet());
        Player p = Player.forTest(feed, poster.motion(), () -> now, Motion::open);
        assertTrue(p.open(), String.valueOf(p.failure()));
        try {
            int[] at = {0, 30, 80, 150, 180};
            String[] colour = {"red", "green", "blue", "red", "green"};
            for (int k = 0; k < at.length; k++) {
                now = at[k] * MS;
                long deadline = System.nanoTime() + 5_000_000_000L;
                int slot;
                while ((slot = feed.due(new Frame(now, now, false))) < 0
                        || feed.stamp(slot) < Math.max(0, at[k] - 1) * MS) {
                    // The movie decodes on its own thread: look until it has made the frame due, as the thread would.
                    if (p.once() > 0) {
                        Thread.sleep(2);
                    }
                    assertTrue(System.nanoTime() < deadline, "frame due at " + at[k] + " ms never came");
                }
                feed.select();
                byte[] px = feed.chain(slot)[0];
                String seen = (px[0] & 0xff) > 200 ? "red" : (px[1] & 0xff) > 200 ? "green" : (px[2] & 0xff) > 200 ? "blue"
                        : "rgb " + (px[0] & 0xff) + "," + (px[1] & 0xff) + "," + (px[2] & 0xff);
                assertEquals(colour[k], seen, "at " + at[k] + " ms");
            }
        } finally {
            p.shut();
        }
    }

    @Test
    void aClosedPlayerLooksNoMore() {
        Player p = player(new Strip(3, 10, false));
        assertNull(p.failure());
        p.close();
        assertEquals(-1L, p.once());
    }
}
