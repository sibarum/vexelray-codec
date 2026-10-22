package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.codec.image.Pictures;
import dev.vexelray.codec.image.Stamp;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport, headless: a driven clock the test ticks, a movie decoding for real on its own thread, and no window,
 * so a frame due is the frame "shown". {@code anim3.gif} is red, green and blue at 30, 50 and 70 ms: frame 0 from 0,
 * frame 1 from 30, frame 2 from 80, and round again at 150.
 */
class PlaybackTest {

    @Test
    void itPlaysAsSoonAsItOpensAndRoundAndRound(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.until(() -> r.play.frame() == 0);
            assertTrue(r.play.playing());
            assertTrue(r.play.loop(), "looping until told otherwise");
            assertFalse(r.play.sounds(), "a GIF has nothing to hear");
            r.until(() -> r.play.frame() == 2);
            r.until(() -> r.play.frame() == 0);
            assertTrue(r.play.playing(), "the end is the start again");
        }
    }

    @Test
    void pausedItHoldsItsFrameAndPlaysOnFromThere(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.until(() -> r.play.frame() == 1);
            r.play.pause();
            long at = r.play.position();
            r.tick(500);
            assertEquals(1, r.play.frame());
            assertEquals(at, r.play.position(), "the clock stopped where it was");
            r.play.play();
            r.until(() -> r.play.frame() == 2);
        }
    }

    @Test
    void aSeekWhilePausedShowsTheFrameThereAndStaysPaused(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.until(() -> r.play.frame() == 0);
            r.play.pause();
            r.play.seek(90);
            r.until(() -> r.play.frame() == 2);
            assertEquals(90, r.play.position());
            assertFalse(r.play.playing());
            r.play.seek(0);
            r.until(() -> r.play.frame() == 0);
        }
    }

    @Test
    void steppingPausesAndGoesAFrameAtATimeRoundTheEnds(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.until(() -> r.play.frame() == 0);
            r.play.step(1);
            assertFalse(r.play.wanted(), "a step pauses");
            r.until(() -> r.play.frame() == 1);
            assertEquals(30, r.play.position());
            r.play.step(-2);
            r.until(() -> r.play.frame() == 2);
            assertEquals(80, r.play.position(), "back from the first is the last, while looping");
        }
    }

    @Test
    void withoutLoopingItStopsOnTheLastFrameAndPlayingAgainStartsOver(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.play.loop(false);
            r.until(() -> !r.play.wanted());
            assertEquals(2, r.play.frame());
            assertEquals(150, r.play.position());
            r.play.play();
            r.until(() -> r.play.frame() == 0);
        }
    }

    @Test
    void heldItStopsAndLettingGoPlaysOnButNotAPauseOfItsOwn(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.until(() -> r.play.frame() == 1);
            r.play.hold(true);
            assertFalse(r.play.playing());
            assertTrue(r.play.wanted(), "held is not paused");
            long at = r.play.position();
            r.tick(500);
            assertEquals(at, r.play.position());
            r.play.hold(false);
            assertTrue(r.play.playing());

            r.play.hold(true);
            r.play.pause();
            r.play.hold(false);
            assertFalse(r.play.playing(), "a pause made while held stays");
        }
    }

    @Test
    void everyMoveIsSaid(@TempDir Path dir) throws IOException {
        try (Rig r = new Rig(dir)) {
            r.until(() -> r.play.frame() == 1);
            int before = r.changes.get();
            r.play.pause();
            r.play.loop(false);
            r.play.volume(0.5f);
            r.play.muted(true);
            assertEquals(before + 4, r.changes.get());
        }
    }

    @Test
    void withAClockOfItsOwnTheFramesFollowThatClock(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        try (Rig r = new Rig(dir, own)) {
            r.until(() -> r.play.frame() == 0);
            r.until(() -> own.running);
            assertTrue(own.running, "it runs from the first frame");
            r.tick(500);
            assertEquals(0, r.play.frame(), "the timeline alone does not move it");
            assertEquals(0, r.play.position());
            own.now = 90;
            r.until(() -> r.play.frame() == 2);
            assertEquals(90, r.play.position());
        }
    }

    @Test
    void theOwnClockStopsWithThePicturesAndGoesWhereASeekSendsThem(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        try (Rig r = new Rig(dir, own)) {
            r.until(() -> own.running);
            r.play.pause();
            assertFalse(own.running, "a pause stops it");
            r.play.seek(90);
            assertEquals(90, own.now, "a seek moves it, stopped");
            assertFalse(own.running);
            r.play.play();
            r.until(() -> own.running);
            r.play.hold(true);
            assertFalse(own.running, "so does a hold");
            r.play.hold(false);
            r.until(() -> own.running);
            assertEquals(1, own.seeks.get(), "letting go is not a seek");
            r.play.step(1);
            assertFalse(own.running, "and a step");
        }
    }

    @Test
    void loopingSendsTheOwnClockBackToTheStart(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        try (Rig r = new Rig(dir, own)) {
            r.until(() -> r.play.frame() == 0);
            own.now = 160;
            r.until(() -> own.seeks.get() == 1);
            assertEquals(0, own.now);
            assertTrue(own.running, "and it plays on");

            r.play.loop(false);
            r.until(() -> {
                own.now = 160;      // again, should a pass already under way have looped it once more
                return !r.play.wanted();
            });
            assertFalse(own.running, "the end stops it");
        }
    }

    @Test
    void quickPausePlayStepPlayPlaysOn(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        own.slow = true;
        try (Rig r = new Rig(dir, own)) {
            r.until(() -> own.running);
            own.now = 40;
            r.until(() -> r.play.frame() == 1);
            r.play.pause();
            r.play.play();
            r.play.step(1);
            r.play.play();
            r.until(() -> own.running);
            own.now = 100;
            r.until(() -> r.play.frame() == 2);
        }
    }

    @Test
    void theVolumeAndTheMuteGoToTheOwnClockWhichKeepsTimeSilenced(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        try (Rig r = new Rig(dir, own)) {
            r.until(() -> own.running);
            assertTrue(r.play.sounds());
            assertEquals(1f, own.volume, "as the file has it, until told otherwise");
            r.play.volume(0.4f);
            assertEquals(0.4f, own.volume);
            r.play.muted(true);
            assertEquals(0f, own.volume, "muted is silent");
            assertTrue(own.running, "and still keeps time");
            r.play.volume(0.7f);
            assertEquals(0f, own.volume, "a level given while muted is kept, not heard");
            assertEquals(0.7f, r.play.volume());
            r.play.muted(false);
            assertEquals(0.7f, own.volume);
            r.play.volume(3f);
            assertEquals(1f, r.play.volume(), "no louder than the file");
        }
    }

    @Test
    void theCompressionGoesToTheOwnClockMutedOrNot(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        try (Rig r = new Rig(dir, own, p -> p.compression(0.5f))) {
            r.until(() -> own.running);
            assertEquals(0.5f, own.compression, "given before the file opened, heard once it has");
            r.play.compression(1f);
            assertEquals(1f, own.compression);
            r.play.muted(true);
            assertEquals(1f, own.compression, "a mute is not a compression");
            r.play.compression(7f);
            assertEquals(1f, r.play.compression(), "no more than all the way up");
            r.play.compression(Float.NaN);
            assertEquals(0f, r.play.compression(), "and nonsense is none");
            assertEquals(0f, own.compression);
            assertTrue(own.running, "the clock keeps time throughout");
        }
    }

    @Test
    void aVolumeGivenBeforeTheFileOpensIsHeardOnceItHas(@TempDir Path dir) throws IOException {
        FakeClock own = new FakeClock();
        try (Rig r = new Rig(dir, own, p -> {
            p.volume(0.25f);
            p.muted(true);
        })) {
            r.until(() -> own.running);
            assertEquals(0f, own.volume);
            r.play.muted(false);
            assertEquals(0.25f, own.volume);
        }
    }

    @Test
    void aDecoderThatWillNotLoadIsAFailureNotAThrow() {
        Motion m = new Motion(new Stamp(Path.of("gone.gif"), 1, 1), 3, 150, 0, (motion, side) -> {
            throw new UnsatisfiedLinkError("no imagelib here");
        }, false);
        KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        AtomicInteger changes = new AtomicInteger();
        Playback p = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, changes::incrementAndGet);
        p.start(Runnable::run);
        assertEquals("no imagelib here", p.failure());
        assertEquals(1, changes.get(), "and said");
        p.close();
        krono.close();
    }

    @Test
    void nowhereToOpenItIsAFailureNotAThrow(@TempDir Path dir) throws IOException {
        Motion m = Pictures.of(gif(dir), 1024).picture().motion();
        KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        Playback p = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, () -> { });
        p.start(task -> {
            throw new RejectedExecutionException("shut down");
        });
        assertEquals("nowhere to open it", p.failure());
        p.close();
        krono.close();
    }

    @Test
    void aFileWithNoFramesToShowStopsRatherThanWaitingForEver(@TempDir Path dir) throws IOException {
        Motion gif = Pictures.of(gif(dir), 1024).picture().motion();
        Motion m = new Motion(gif.stamp(), 3, 150, 0, (motion, side) -> new Empty(), false);
        KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        AtomicInteger changes = new AtomicInteger();
        Playback p = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, changes::incrementAndGet);
        p.start(Runnable::run);
        for (int t = 1; t <= 100 && p.wanted(); t++) {
            krono.tick(Dur.ms(t));
        }
        assertFalse(p.wanted(), "stopped, as at the end");
        assertEquals("no frames to show", p.failure());
        assertEquals(-1, p.frame(), "the poster stays up");
        p.step(1);
        p.seek(-5);
        p.close();
        p.close();
        krono.close();
    }

    @Test
    void steppingBeforeTheFileHasOpenedDoesNotThrow() {
        Motion m = new Motion(new Stamp(Path.of("slow.gif"), 1, 1), 0, 0, 0, (motion, side) -> new Empty(), false);
        KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        Playback p = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, () -> { });
        p.step(1);
        p.step(-3);
        assertEquals(0, p.position());
        p.close();
        krono.close();
    }

    @Test
    void aVideoDecoderSlowerThanItsFramesShowsWhatItMakesDroppingTheRest() {
        Laggard slow = new Laggard(true);
        Motion m = new Motion(new Stamp(Path.of("slow.gif"), 1, 1), Laggard.FRAMES, Laggard.FRAMES * 10L, 0,
                (motion, side) -> slow, false);
        KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        Playback p = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, () -> { });
        p.start(Runnable::run);
        // Each frame is up for 10 ms and takes 50 to make: the decoder makes whatever was wanted when it started, and
        // by the time it lands the clock has moved five frames on.
        int started = 0;
        for (int t = 1; t <= 1000; t++) {
            if (t % 50 == 0) {
                slow.make(started);
                started = slow.wanted;
            }
            krono.tick(Dur.ms(t));
        }
        assertTrue(p.frame() > 20, "the frames it made were shown, not passed over: at frame " + p.frame());
        p.close();
        krono.close();
    }

    @Test
    void anAnimatedImageTooSlowToDecodeShowsEveryFrameSlower() {
        Laggard slow = new Laggard(false);
        Motion m = new Motion(new Stamp(Path.of("slow.webp"), 1, 1), Laggard.FRAMES, Laggard.FRAMES * 10L, 0,
                (motion, side) -> slow, false);
        KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        List<Integer> shown = new CopyOnWriteArrayList<>();
        AtomicReference<Playback> play = new AtomicReference<>();
        Playback p = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, () -> {
            Playback q = play.get();
            if (q != null && q.frame() >= 0 && (shown.isEmpty() || shown.getLast() != q.frame())) {
                shown.add(q.frame());
            }
        });
        play.set(p);
        p.start(Runnable::run);
        // 50 ms to make each 10 ms frame: the clock waits for each, so a second plays a fifth of a second.
        for (int t = 1; t <= 1000; t++) {
            if (t % 50 == 0) {
                slow.make(slow.wanted);
            }
            krono.tick(Dur.ms(t));
        }
        assertTrue(shown.size() > 15, "it played on: " + shown);
        for (int i = 0; i < shown.size(); i++) {
            assertEquals(i, shown.get(i), "every frame, in order: " + shown);
        }
        assertTrue(p.position() < 300, "slowed to the decoder's pace, not the clock's: at " + p.position() + " ms");
        p.close();
        krono.close();
    }

    /** Ten-millisecond frames, made one at a time only when the test says which; dropped when late, or not. */
    private static final class Laggard implements Playable {
        static final int FRAMES = 200;
        private final byte[][][] made = new byte[FRAMES][][];
        private final boolean drops;
        volatile int wanted;

        Laggard(boolean drops) {
            this.drops = drops;
        }

        public boolean drops() { return drops; }

        synchronized void make(int i) {
            made[i] = new byte[][] {new byte[4 * 3 * 4]};
        }

        public int width() { return 4; }
        public int height() { return 3; }
        public int count() { return FRAMES; }
        public long duration() { return FRAMES * 10L; }
        public int delay(int i) { return 10; }
        public long startOf(int i) { return i * 10L; }
        public int frameAt(long ms) { return (int) Math.min(FRAMES - 1, Math.max(0, ms) / 10); }
        public void want(int i, boolean wrap) { wanted = i; }
        public synchronized byte[][] frame(int i) { return made[i]; }
        public String failure() { return null; }
        public void close() { }
    }

    /** A playable whose file turned out to have nothing in it: no frames, and says so. */
    private static final class Empty implements Playable {
        public int width() { return 4; }
        public int height() { return 3; }
        public int count() { return 0; }
        public long duration() { return 150; }
        public int delay(int i) { return 50; }
        public long startOf(int i) { return 0; }
        public int frameAt(long ms) { return 0; }
        public void want(int i, boolean wrap) { }
        public byte[][] frame(int i) { return null; }
        public String failure() { return "no frames to show"; }
        public void close() { }
    }

    private static Path gif(Path dir) throws IOException {
        Path gif = dir.resolve("anim3.gif");
        try (InputStream in = PlaybackTest.class.getResourceAsStream("/anim3.gif")) {
            Files.copy(in, gif);
        }
        return gif;
    }

    /** A clock the test sets, and what was asked of it. */
    private static final class FakeClock implements Playable.Clock {
        volatile long now;
        volatile boolean running;
        /** Start and stop take a while, as a sound device's do. */
        volatile boolean slow;
        final AtomicInteger seeks = new AtomicInteger();
        volatile float volume = 1f;
        volatile float compression;

        @Override
        public long now() {
            return now;
        }

        @Override
        public void run(boolean run) {
            if (slow) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            running = run;
        }

        @Override
        public void seek(long ms) {
            now = ms;
            seeks.incrementAndGet();
        }

        @Override
        public void volume(float volume) {
            this.volume = volume;
        }

        @Override
        public void compression(float amount) {
            this.compression = amount;
        }
    }

    /** A movie that keeps the given clock: a stand-in for a video with sound. */
    private record Clocked(Playable movie, Playable.Clock clock) implements Playable {
        public int width() { return movie.width(); }
        public int height() { return movie.height(); }
        public int count() { return movie.count(); }
        public long duration() { return movie.duration(); }
        public int delay(int i) { return movie.delay(i); }
        public long startOf(int i) { return movie.startOf(i); }
        public int frameAt(long ms) { return movie.frameAt(ms); }
        public void want(int i, boolean wrap) { movie.want(i, wrap); }
        public byte[][] frame(int i) { return movie.frame(i); }
        public String failure() { return movie.failure(); }
        public void close() { movie.close(); }
    }

    /** A headless gui, a driven clock, and a playback of {@code anim3.gif}. */
    private static final class Rig implements AutoCloseable {

        final KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        final AtomicInteger changes = new AtomicInteger();
        final Playback play;
        private long now;

        Rig(Path dir) throws IOException {
            this(dir, null);
        }

        /** With {@code own} not null, the movie keeps that clock. */
        Rig(Path dir, Playable.Clock own) throws IOException {
            this(dir, own, p -> { });
        }

        /** With {@code before} done to the playback before it opens the file. */
        Rig(Path dir, Playable.Clock own, Consumer<Playback> before) throws IOException {
            Path gif = dir.resolve("anim3.gif");
            try (InputStream in = PlaybackTest.class.getResourceAsStream("/anim3.gif")) {
                Files.copy(in, gif);
            }
            Motion m = Pictures.of(gif, 1024).picture().motion();
            if (own != null) {
                m = new Motion(m.stamp(), m.frames(), m.durationMillis(), m.loops(),
                        (motion, side) -> new Clocked(Motion.ANIMATION.open(motion, side), own), false);
            }
            play = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 1024, changes::incrementAndGet);
            before.accept(play);
            play.start(Runnable::run);
        }

        /** Move the clock on {@code ms}, a millisecond at a time, as frames would. */
        void tick(long ms) {
            for (int i = 0; i < ms; i++) {
                now++;
                krono.tick(Dur.ms(now));
            }
        }

        /** Tick until {@code done}, giving the movie's thread real time to make its frames. */
        void until(BooleanSupplier done) {
            long give = System.nanoTime() + 5_000_000_000L;
            while (!done.getAsBoolean()) {
                if (System.nanoTime() > give) {
                    throw new AssertionError("never came: " + play);
                }
                tick(1);
                try {
                    Thread.sleep(0, 200_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
            play.close();
            krono.close();
        }
    }
}
