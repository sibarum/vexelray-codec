package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Playable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A video played by number, as a {@code Playback} plays it. The fixture is three seconds at 10 fps: red, green, blue.
 * Footage decodes on its own thread, so these wait for it, with a deadline rather than a sleep.
 */
class FootageTest {

    private static final long DEADLINE_MS = 10_000;

    @Test
    void framesAreNumberedAtTheStreamsRate() throws Exception {
        try (Footage f = footage()) {
            assertEquals(30, f.count());
            assertEquals(3000, f.duration());
            assertEquals(0, f.startOf(0));
            assertEquals(100, f.startOf(1));
            assertEquals(100, f.delay(7));
            assertEquals(0, f.frameAt(99));
            assertEquals(1, f.frameAt(100));
            assertEquals(29, f.frameAt(10_000));
            for (int i = 0; i < f.count(); i++) {
                assertEquals(i, f.frameAt(f.startOf(i)), "frame " + i + " starts where it is found");
            }
        }
    }

    @Test
    void makesTheWantedFrameAndTheFewAfter() throws Exception {
        try (Footage f = footage()) {
            f.want(0, true);
            byte[][] first = await(f, 0);
            assertEquals(f.width() * f.height() * 4, first[0].length);
            assertTrue(Fixtures.dominant(first[0], f.width(), f.height(), 0));
            for (int k = 1; k <= Footage.AHEAD; k++) {
                assertNotNull(await(f, k));
            }
        }
    }

    @Test
    void seeksToAFrameFarAheadAndBack() throws Exception {
        try (Footage f = footage()) {
            f.want(25, false);
            byte[][] blue = await(f, 25);
            assertTrue(Fixtures.dominant(blue[0], f.width(), f.height(), 2));
            f.want(12, false);
            byte[][] green = await(f, 12);
            assertTrue(Fixtures.dominant(green[0], f.width(), f.height(), 1));
            assertNull(f.failure());
        }
    }

    @Test
    void wrapsRoundToTheStartWhenLooping() throws Exception {
        try (Footage f = footage()) {
            f.want(28, true);
            assertNotNull(await(f, 29));
            byte[][] red = await(f, 1);
            assertTrue(Fixtures.dominant(red[0], f.width(), f.height(), 0));
        }
    }

    @Test
    void closingStopsTheThreadAndClosesTheClip() throws Exception {
        Footage f = footage();
        f.want(0, true);
        await(f, 0);
        f.close();
        assertTrue(f.stopped(DEADLINE_MS));
        assertTrue(f.clip().closed());
        assertNull(f.frame(0));
    }

    @Test
    void aPosterThatPlaysIsTheFirstFrameAndOpensFootage() throws Exception {
        Path file = Fixtures.file("rgb.webm");
        Decoded d = Videos.poster(file, 0, true);
        Picture p = d.picture();
        assertTrue(p.plays());
        assertTrue(Fixtures.dominant(p.sheet(), p.frameWidth(), p.frameHeight(), 0), "the first frame, red");
        Motion m = p.motion();
        assertEquals(30, m.frames());
        assertEquals(3000, m.durationMillis());
        assertSame(Footage.SOURCE, m.source());
        assertTrue(p.enlarges(), "fitted, a video fills its stage");
        try (Playable played = m.open(32)) {
            Footage footage = assertInstanceOf(Footage.class, played);
            assertEquals(32, footage.width());
            assertEquals(24, footage.height());
        }
    }

    @Test
    void aPlainPosterDoesNotPlay() throws Exception {
        assertFalse(Videos.poster(Fixtures.file("rgb.webm"), 0).picture().plays());
    }

    private static Footage footage() throws Exception {
        return new Footage(Videos.open(Fixtures.file("rgb.webm"), 0).clip());
    }

    /** Frame {@code i}'s chain, once the footage has made it. */
    private static byte[][] await(Footage f, int i) throws InterruptedException {
        long end = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < end) {
            byte[][] chain = f.frame(i);
            if (chain != null) {
                return chain;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("frame " + i + " was not made within " + DEADLINE_MS + " ms");
    }
}
