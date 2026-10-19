package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.video.Fixtures;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;

import java.util.function.BooleanSupplier;

/**
 * A video with sound through the real transport: {@code Playback} (in this package for its constructor), the real
 * {@code Footage} and its sound, played to no speaker. The sound keeps real time, so the test ticks the timeline in
 * step with the wall.
 */
class SoundPlaybackTest {

    @Test
    void quickPausePlayStepPlayPlaysOn() throws Exception {
        try (Rig r = new Rig("rgb.mp4")) {
            r.until(() -> r.play.frame() >= 10);
            r.play.pause();
            r.play.play();
            r.play.step(1);
            r.play.play();
            int at = r.play.frame();
            r.until(() -> r.play.frame() >= at + 5);
        }
    }

    /** A headless gui, a timeline ticked with the wall, and a playback of a fixture video. */
    private static final class Rig implements AutoCloseable {

        final KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        final Playback play;
        private final long start = System.nanoTime();

        Rig(String name) throws Exception {
            Motion m = Videos.poster(Fixtures.file(name), 0, true).picture().motion();
            play = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, () -> { });
            play.start(Runnable::run);
        }

        void until(BooleanSupplier done) throws InterruptedException {
            long give = System.nanoTime() + 5_000_000_000L;
            while (!done.getAsBoolean()) {
                if (System.nanoTime() > give) {
                    throw new AssertionError("never came: " + play);
                }
                krono.tick(Dur.ms((System.nanoTime() - start) / 1_000_000));
                Thread.sleep(1);
            }
        }

        @Override
        public void close() {
            play.close();
            krono.close();
        }
    }
}
