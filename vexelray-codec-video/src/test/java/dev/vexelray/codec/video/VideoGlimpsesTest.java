package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Glimpses;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Picture;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A video's frames by time, for a timeline to show under the pointer. The fixture is three seconds at 10 fps: red,
 * green, blue. Glimpses are made on a thread of their own, so these wait for them, with a deadline.
 */
class VideoGlimpsesTest {

    private static final long DEADLINE_MS = 10_000;

    @Test
    void aVideosMotionHasGlimpses() throws Exception {
        try (Glimpses g = motion().glimpses(32)) {
            assertInstanceOf(VideoGlimpses.class, g);
        }
    }

    @Test
    void givesTheFrameShowingAtATimeForwardAndBack() throws Exception {
        try (Glimpses g = motion().glimpses(32)) {
            assertColour(g, 2500, 2);
            assertColour(g, 400, 0);
            // Forward from there, a little way and then further: decoded up to, and sought.
            assertColour(g, 1500, 1);
            assertColour(g, 1700, 1);
            assertColour(g, 2900, 2);
        }
    }

    @Test
    void sizesFramesToTheSideAskedFor() throws Exception {
        try (Glimpses g = motion().glimpses(32)) {
            Picture p = await(g, 0);
            assertEquals(32, Math.max(p.frameWidth(), p.frameHeight()));
            assertEquals(64, p.sourceWidth());
            assertNull(p.motion());
        }
    }

    @Test
    void aFrameMadeOnceIsHandedOverAgainAtOnce() throws Exception {
        try (Glimpses g = motion().glimpses(32)) {
            Picture first = await(g, 1210);
            // The same frame, asked for at another time in it, is the same picture, given on the caller's thread.
            Picture[] again = new Picture[1];
            g.want(1290, p -> again[0] = p);
            assertSame(first, again[0]);
        }
    }

    @Test
    void timesPastEitherEndAreTheFirstAndLastFrames() throws Exception {
        try (Glimpses g = motion().glimpses(32)) {
            assertColour(g, -500, 0);
            assertColour(g, 60_000, 2);
        }
    }

    @Test
    void closingStopsTheThread() throws Exception {
        VideoGlimpses g = (VideoGlimpses) motion().glimpses(32);
        await(g, 0);
        g.close();
        assertTrue(g.stopped(DEADLINE_MS));
        g.want(0, p -> {
            throw new AssertionError("told after closing");
        });
    }

    private static void assertColour(Glimpses g, long ms, int channel) throws InterruptedException {
        Picture p = await(g, ms);
        assertTrue(Fixtures.dominant(p.sheet(), p.frameWidth(), p.frameHeight(), channel),
                "the frame at " + ms + " ms is channel " + channel);
    }

    private static Picture await(Glimpses g, long ms) throws InterruptedException {
        BlockingQueue<Picture> q = new ArrayBlockingQueue<>(1);
        g.want(ms, q::offer);
        Picture p = q.poll(DEADLINE_MS, TimeUnit.MILLISECONDS);
        assertNotNull(p, "a glimpse at " + ms + " ms");
        return p;
    }

    private static Motion motion() throws Exception {
        Path file = Fixtures.file("rgb.webm");
        Decoded d = Videos.poster(file, 64, true);
        Motion m = d.pictures().getFirst().motion();
        assertNotNull(m);
        return m;
    }
}
