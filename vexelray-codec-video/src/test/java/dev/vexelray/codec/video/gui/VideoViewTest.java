package dev.vexelray.codec.video.gui;

import dev.vexelray.codec.video.Fixtures;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playing on the machine's clock, with no window: the test runs the tree's frames headless every few milliseconds, so
 * the view's asked half is asked as a window would ask it and chooses through the real {@code Drawn}, and then does
 * the renderer's part, settling the slot shown. The footage decodes for real, on its own thread. {@code rgb.webm} is
 * 3 s at 10 frames a second.
 */
class VideoViewTest {

    private Gui gui;
    private VideoView view;

    @BeforeEach
    void open() {
        gui = new Gui(Atchung.create());
        view = new VideoView(gui, Length.ZERO, Length.ZERO);
        gui.root().children(view.node());
    }

    @AfterEach
    void close() {
        view.clear();
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
        run(1_000);
        // A second on the clock is a second into the video: frame 10, give or take a frame and the test's own steps.
        assertTrue(Math.abs(view.shownMicros() - 1_000_000) <= 150_000, "shown " + view.shownMicros());
        assertTrue(Math.abs(view.position() - 1_000_000) <= 150_000, "position " + view.position());
        assertTrue(view.playing());
    }

    @Test
    void aVideoProbedByAJobPlaysWithItsSound() throws Exception {
        var probed = Videos.probe(Fixtures.file("rgb.mp4"), 0);
        assertTrue(probed.video().isPresent(), probed.identity());
        var video = probed.video().get();
        assertTrue(video.sound(), "rgb.mp4 has a tone");
        view.play(video);
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        run(600);
        assertTrue(view.shownMicros() >= 400_000, "it plays on: " + view.shownMicros());
        assertEquals(video.durationMicros(), view.duration());
    }

    @Test
    void aFileThatIsNotAVideoIsProbedAsNoneWithAReason() throws Exception {
        var probed = Videos.probe(Fixtures.file("rgb.mp4").resolveSibling("no-such.mp4"), 0);
        assertTrue(probed.video().isEmpty());
        assertFalse(probed.identity().isBlank());
    }

    @Test
    void aPauseHoldsTheTimeAndASeekWhilePausedShowsTheFrameThere() throws Exception {
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
        run(50);
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
    void aVideoWithNoFramesStopsRatherThanSpinning() throws Exception {
        AtomicInteger ends = new AtomicInteger();
        view.loop(true).onEnd(ends::incrementAndGet);
        var o = Videos.open(Fixtures.noFrames(), 0);
        assertTrue(o.video(), o.identity());
        view.play(o.clip());
        until(() -> ends.get() == 1 || view.failure() != null, "it stops, ended or failed");
        run(300);
        assertTrue(ends.get() <= 1, "said once at most, not looped round for ever");
        assertEquals(-1, view.shownMicros());
    }

    @Test
    void clearingLetsGoOfTheClip() throws Exception {
        var clip = Videos.open(Fixtures.file("rgb.webm"), 0).clip();
        view.play(clip);
        until(() -> view.shownMicros() == 0, "the first frame is shown");
        view.clear();
        until(clip::closed, "the footage closes the clip");
        assertEquals(-1, view.shownMicros());
        assertEquals(-1, view.duration());
    }

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** One frame of the tree, headless: the asked half chooses, and the renderer's settling of the slot shown. */
    private void frame() {
        Seams.of(gui).frame(400f, 300f, VideoViewTest::noText);
        Feed f = view.feed();
        if (f != null) {
            f.select();
        }
    }

    /** {@code ms} of frames, one every few milliseconds. */
    private void run(long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            frame();
            Thread.sleep(4);
        }
    }

    /** Frames until {@code condition} holds, with a deadline. */
    private void until(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what + " (shown " + view.shownMicros()
                        + ", position " + view.position() + ", failure " + view.failure() + ")");
            }
            frame();
            Thread.sleep(4);
        }
    }
}
