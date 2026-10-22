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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The knob from the transport down to the real sound, measured on what the speaker was given at 1.1 s into the
     * video: given before the file opens, and turned up mid-play, it is louder than as stored by about 4 dB (the
     * tone is that far under the leveller's target). Each from its own playback, at the same place, since the AAC
     * tone's level wanders along it.
     */
    @Test
    void theCompressionReachesTheVideosSound() throws Exception {
        double plain = loudnessAt(p -> { }, false);
        double before = loudnessAt(p -> p.compression(1f), false);
        double midPlay = loudnessAt(p -> { }, true);
        assertTrue(plain > 0, "it is heard: " + plain);
        double up = 20 * Math.log10(before / plain);
        assertTrue(up > 2, "given before the file opened, louder: " + up + " dB");
        double turned = 20 * Math.log10(midPlay / plain);
        assertTrue(turned > 2, "turned up mid-play, louder: " + turned + " dB");
    }

    /** The loudness at 1.1 s, with {@code before} done first, and the knob turned all the way up at 0.8 s if asked. */
    private static double loudnessAt(Consumer<Playback> before, boolean turnUp) throws Exception {
        try (Rig r = new Rig("rgb.mp4", before)) {
            if (turnUp) {
                r.until(() -> r.play.position() >= 800);
                r.play.compression(1f);
                assertEquals(1f, r.play.compression());
            }
            r.until(() -> r.play.position() >= 1100);
            return loudness(r.play);
        }
    }

    /** The loudness of the last 50 ms the playback's sound gave the speaker. */
    private static double loudness(Playback play) {
        float[] heard = new float[2205 * 2];
        int got = play.heard(heard);
        double sum = 0;
        for (int i = 0; i < got * 2; i++) {
            sum += heard[i] * (double) heard[i];
        }
        return got == 0 ? 0 : Math.sqrt(sum / (got * 2.0));
    }

    /** A headless gui, a timeline ticked with the wall, and a playback of a fixture video. */
    private static final class Rig implements AutoCloseable {

        final KronoGui krono = KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
        final Playback play;
        private final long start = System.nanoTime();

        Rig(String name) throws Exception {
            this(name, p -> { });
        }

        /** With {@code before} done to the playback before it starts, as a viewer's settings are. */
        Rig(String name, Consumer<Playback> before) throws Exception {
            Motion m = Videos.poster(Fixtures.file(name), 0, true).picture().motion();
            play = new Playback(krono, new Textures(), (frame, w, h) -> { }, m, 64, () -> { });
            before.accept(play);
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
