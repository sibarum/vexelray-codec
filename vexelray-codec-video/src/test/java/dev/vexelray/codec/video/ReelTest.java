package dev.vexelray.codec.video;

import org.junit.jupiter.api.Test;
import sibarum.ffmpeg.Frame;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reel decodes ahead on its own thread, so these wait for it, with a deadline rather than a sleep. */
class ReelTest {

    private static final long DEADLINE_MS = 10_000;

    @Test
    void handsOverEveryFrameAsItsTimeComes() throws Exception {
        try (Reel reel = new Reel(clip())) {
            for (int i = 0; i < 30; i++) {
                long t = i * 100_000L;
                assertEquals(t, await(reel, t).ptsMicros());
            }
            awaitThat(reel::finished, "the reel finishes after the last frame");
            assertNull(reel.failure());
        }
    }

    @Test
    void dropsTheFramesALateClockMissed() throws Exception {
        try (Reel reel = new Reel(clip())) {
            // AHEAD frames waiting: 0 to 300 ms.
            awaitThat(() -> reel.waiting() == Reel.AHEAD, "the reel fills");
            Frame f = reel.due(350_000);
            // All four were due; only the newest is handed over.
            assertEquals(300_000, f.ptsMicros());
        }
    }

    @Test
    void aSeekReplacesTheQueue() throws Exception {
        try (Reel reel = new Reel(clip())) {
            awaitThat(() -> reel.next() == 0, "the first frame comes");
            reel.seek(2_450_000);
            Frame f = await(reel, 2_450_000);
            assertEquals(2_400_000, f.ptsMicros());
            assertTrue(Fixtures.dominant(f.rgba(), f.width(), f.height(), 2));
        }
    }

    @Test
    void closingStopsTheThreadAndClosesTheClip() throws Exception {
        Clip clip = clip();
        Reel reel = new Reel(clip);
        awaitThat(() -> reel.next() == 0, "the first frame comes");
        reel.close();
        assertTrue(reel.stopped(DEADLINE_MS));
        assertTrue(clip.closed());
        assertNull(reel.due(Long.MAX_VALUE));
    }

    private static Clip clip() throws IOException {
        return Videos.open(Fixtures.file("rgb.webm"), 0).clip();
    }

    /** The frame due at {@code micros}, once the reel has decoded it. */
    private static Frame await(Reel reel, long micros) throws InterruptedException {
        long end = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < end) {
            Frame f = reel.due(micros);
            if (f != null) {
                return f;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("no frame due at " + micros + "us within " + DEADLINE_MS + " ms");
    }

    private static void awaitThat(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + DEADLINE_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting: " + what);
            }
            Thread.sleep(1);
        }
    }
}
