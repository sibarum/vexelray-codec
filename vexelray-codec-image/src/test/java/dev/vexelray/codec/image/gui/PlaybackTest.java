package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
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

    /** A headless gui, a driven clock, and a playback of {@code anim3.gif}. */
    private static final class Rig implements AutoCloseable {

        final KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        final AtomicInteger changes = new AtomicInteger();
        final Playback play;
        private long now;

        Rig(Path dir) throws IOException {
            Path gif = dir.resolve("anim3.gif");
            try (InputStream in = PlaybackTest.class.getResourceAsStream("/anim3.gif")) {
                Files.copy(in, gif);
            }
            Motion m = Pictures.of(gif, 1024).picture().motion();
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
