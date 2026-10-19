package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.codec.image.Pictures;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
            assertEquals(before + 2, r.changes.get());
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

    /** A clock the test sets, and what was asked of it. */
    private static final class FakeClock implements Playable.Clock {
        volatile long now;
        volatile boolean running;
        /** Start and stop take a while, as a sound device's do. */
        volatile boolean slow;
        final AtomicInteger seeks = new AtomicInteger();

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
