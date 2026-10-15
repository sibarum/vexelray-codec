package dev.vexelray.codec.video.gui;

import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.codec.video.Fixtures;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playback on a driven clock, with no window: the textures are never bound, so nothing is uploaded, and the frame
 * "shown" is the one the view would have put on its plate. The reel still decodes for real, on its own thread, so each
 * step of logical time waits for the decoder to keep up before taking the next, as a window's frame would.
 */
class VideoViewTest {

    private KronoGui krono;
    private VideoView view;
    private long now;

    @BeforeEach
    void open() {
        Gui gui = new Gui(Atchung.create());
        krono = KronoGui.attach(gui, Kron.driven());
        view = new VideoView(gui, krono, new Textures(), Length.ZERO, Length.ZERO);
        now = 0;
    }

    @AfterEach
    void close() {
        view.clear();
        krono.close();
    }

    @Test
    void aVideoFillsItsStageAtItsOwnAspect() {
        // 64 x 48 in a 1000 x 1000 stage: enlarged, so all of it across and three quarters down.
        float[] f = VideoView.fraction(64, 48, 1000f, 1000f);
        assertEquals(1f, f[0], 1e-6f);
        assertEquals(0.75f, f[1], 1e-6f);
        f = VideoView.fraction(1920, 1080, 1000f, 200f);
        assertEquals(200f * 16f / 9f / 1000f, f[0], 1e-6f);
        assertEquals(1f, f[1], 1e-6f);
    }

    @Test
    void framesAreShownAsTheirTimeComes() throws Exception {
        view.play(Videos.open(Fixtures.file("rgb.webm"), 0).clip());
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        long from = now;
        run(1_000);
        // A second on the clock is a second into the video: frame 10, give or take the step it landed in.
        assertTrue(Math.abs(view.shownMicros() - 1_000_000) <= 100_000, "shown " + view.shownMicros());
        assertTrue(Math.abs(view.position() - (now - from) * 1000) <= 10_000, "position " + view.position());
        assertTrue(view.playing());
    }

    @Test
    void aPauseHoldsTheClockAndASeekWhilePausedShowsTheFrameThere() throws Exception {
        view.play(Videos.open(Fixtures.file("rgb.webm"), 0).clip());
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        run(500);
        view.pause();
        long held = view.position();
        run(500);
        assertEquals(held, view.position());
        assertFalse(view.playing());

        view.seek(2_450_000);
        until(() -> view.shownMicros() == 2_400_000, "the frame at the seek is shown");
        assertEquals(2_450_000, view.position());
        run(300);
        assertEquals(2_400_000, view.shownMicros(), "still paused");

        view.resume();
        run(300);
        assertTrue(view.shownMicros() >= 2_600_000, "plays on from the seek: " + view.shownMicros());
    }

    @Test
    void theEndIsSaidAndHeld() throws Exception {
        AtomicInteger ends = new AtomicInteger();
        view.onEnd(ends::incrementAndGet);
        view.play(Videos.open(Fixtures.file("rgb.webm"), 0).clip());
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        view.seek(2_700_000);
        until(() -> ends.get() == 1, "the end is reached");
        assertEquals(2_900_000, view.shownMicros());
        assertFalse(view.playing());
        run(300);
        assertEquals(1, ends.get());
    }

    @Test
    void aLoopStartsAgain() throws Exception {
        view.loop(true);
        view.play(Videos.open(Fixtures.file("rgb.webm"), 0).clip());
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        view.seek(2_800_000);
        until(() -> view.shownMicros() >= 0 && view.shownMicros() < 500_000 && view.position() < 500_000,
                "it comes round to the start");
        assertTrue(view.playing());
    }

    @Test
    void clearingLetsGoOfTheClip() throws Exception {
        var clip = Videos.open(Fixtures.file("rgb.webm"), 0).clip();
        view.play(clip);
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        view.clear();
        until(clip::closed, "the reel closes the clip");
        assertEquals(-1, view.shownMicros());
        assertEquals(-1, view.duration());
    }

    /** {@code ms} of logical time in 10 ms frames, each after the decoder has had a moment to keep up. */
    private void run(long ms) throws InterruptedException {
        for (long end = now + ms; now < end; ) {
            now += 10;
            krono.tick(Dur.ms(now));
            Thread.sleep(2);
        }
    }

    /** Frames until {@code condition} holds, with a deadline in real time. */
    private void until(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what + " (shown " + view.shownMicros()
                        + ", position " + view.position() + ")");
            }
            run(10);
        }
    }
}
