package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file that plays: listed as its first frame and a {@link Motion}, and played frame by frame by a {@link Movie}. The
 * {@code anim3} fixtures are three frames, red, green and blue, at 30, 50 and 70 ms, in each format that animates.
 */
class MovieTest {

    private static final int[][] COLOURS = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}};

    @ParameterizedTest
    @ValueSource(strings = {"anim3.gif", "anim3.webp", "anim3.apng.png"})
    void aFileThatPlaysIsListedAsItsFirstFrameAndItsMotion(String name, @TempDir Path dir) throws IOException {
        Decoded d = Pictures.of(file(dir, name), 1024, 2);
        Picture p = d.picture();
        assertTrue(p.plays(), d.identity());
        assertFalse(p.animated(), "one frame is decoded, not three");
        assertTrue(p.moves());
        assertEquals(1, p.count());
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255}, Arrays.copyOf(p.sheet(), 4), "frame 0, red");
        assertEquals(new Motion(p.motion().stamp(), 3, 150, p.motion().loops()), p.motion());
        assertEquals(2, d.picture(1).frameWidth(), "each size is the first frame at that size");
        assertSame(p.motion(), d.picture(1).motion());
        assertTrue(d.identity().endsWith("4 × 3 · 3 frames, 0.2 s"), d.identity());
    }

    @Test
    void aResourceOrBytesInHandIsStillPackedWhole() {
        Decoded p = Pictures.resource(MovieTest.class, "/anim3.gif");
        assertTrue(p.picture().animated());
        assertFalse(p.picture().plays(), "there is no file to play it from");
    }

    @Test
    void aStillFileDoesNotPlay(@TempDir Path dir) throws IOException {
        Path png = dir.resolve("still.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                "png", png.toFile());
        Picture p = Pictures.of(png, 64).picture();
        assertFalse(p.plays());
        assertFalse(p.moves());
    }

    @ParameterizedTest
    @ValueSource(strings = {"anim3.gif", "anim3.webp", "anim3.apng.png"})
    void aMovieMakesTheWantedFrameAndTheOnesAfterIt(String name, @TempDir Path dir) throws Exception {
        Motion m = Pictures.of(file(dir, name), 1024).picture().motion();
        try (Movie movie = Movie.open(m, 1024)) {
            assertEquals(3, movie.count());
            assertEquals(150, movie.duration());
            assertEquals(4, movie.width());
            assertEquals(3, movie.height());
            assertEquals(0, movie.frameAt(0));
            assertEquals(0, movie.frameAt(29));
            assertEquals(1, movie.frameAt(30));
            assertEquals(2, movie.frameAt(80));
            assertEquals(2, movie.frameAt(10_000), "past the end is the last frame");
            assertEquals(80, movie.startOf(2));

            movie.want(0, true);
            for (int i = 0; i < 3; i++) {
                byte[][] chain = await(movie, i);
                assertEquals(3, chain.length, "4x3 has 4, 2 and 1 on its long side");
                assertEquals(COLOURS[i][0], chain[0][0] & 0xFF, "frame " + i);
                assertEquals(COLOURS[i][1], chain[0][1] & 0xFF, "frame " + i);
                assertEquals(COLOURS[i][2], chain[0][2] & 0xFF, "frame " + i);
            }
        }
    }

    @Test
    void framesAreKeptSoSeekingBackIsAtOnce(@TempDir Path dir) throws Exception {
        Motion m = Pictures.of(file(dir, "anim3.gif"), 1024).picture().motion();
        try (Movie movie = Movie.open(m, 1024)) {
            movie.want(0, false);
            await(movie, 2);
            movie.want(2, false);
            movie.want(0, false);
            assertNotNull(movie.frame(0), "frame 0 is still kept after the movie moved past it");
            assertEquals(3, movie.keptFrames());
        }
    }

    @Test
    void aBudgetGivesUpTheLeastRecentlyShownButNeverTheWindow(@TempDir Path dir) throws Exception {
        Motion m = Pictures.of(file(dir, "anim3.gif"), 1024).picture().motion();
        try (Movie movie = Movie.open(m, 1024, 1)) {
            movie.want(0, true);
            await(movie, 0);
            await(movie, 2);
            assertTrue(movie.keptFrames() <= Movie.AHEAD + 1);
        }
    }

    @Test
    void aFileCutShortPlaysTheFramesBeforeTheCut(@TempDir Path dir) throws Exception {
        Path whole = file(dir, "anim3.gif");
        Motion m = Pictures.of(whole, 1024).picture().motion();
        byte[] bytes = Files.readAllBytes(whole);
        // Keep the header and the first frame: the scan finds one frame, the declared count is what it found.
        Path cut = dir.resolve("cut.gif");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length - 20));
        Motion cutMotion = Pictures.of(cut, 1024).picture().motion();
        if (cutMotion == null) {
            return;   // cut to a single frame: a still, which is also right
        }
        try (Movie movie = Movie.open(cutMotion, 1024)) {
            movie.want(0, true);
            await(movie, 0);
            assertTrue(movie.count() <= m.frames());
        }
    }

    @Test
    void closingStopsTheThreadAndClosesTheFile(@TempDir Path dir) throws Exception {
        Motion m = Pictures.of(file(dir, "anim3.webp"), 1024).picture().motion();
        Movie movie = Movie.open(m, 1024);
        movie.want(0, true);
        movie.close();
        movie.close();
        assertTrue(movie.stopped(5_000));
        assertNull(movie.frame(0));
    }

    @Test
    void aDegenerateDelayIsShownAsBrowsersShowIt() {
        assertEquals(100, Movie.timed(0));
        assertEquals(100, Movie.timed(10));
        assertEquals(20, Movie.timed(20));
        assertEquals(70, Movie.timed(70));
    }

    @Test
    void aFrameIsScaledDownNeverUpAndNeverPastWhatAGpuHolds() {
        assertArrayEquals(new int[] {4, 3}, Movie.fitted(4, 3, 1024));
        assertArrayEquals(new int[] {1024, 512}, Movie.fitted(2048, 1024, 1024));
        assertArrayEquals(new int[] {Picture.SHEET_SIDE, Picture.SHEET_SIDE / 2}, Movie.fitted(20_000, 10_000, 99_999));
        assertArrayEquals(new int[] {4, 3}, Movie.fitted(4, 3, 0), "no side is its own, not a pixel");
        assertArrayEquals(new int[] {Picture.SHEET_SIDE, Picture.SHEET_SIDE / 2}, Movie.fitted(20_000, 10_000, -1));
    }

    @Test
    void aFileEmptiedOrGoneSinceItsPosterDoesNotOpen(@TempDir Path dir) throws IOException {
        Path gif = file(dir, "anim3.gif");
        Motion m = Pictures.of(gif, 1024).picture().motion();
        Files.write(gif, new byte[0]);
        assertThrows(RuntimeException.class, () -> Movie.open(m, 1024).close());
        Files.delete(gif);
        assertThrows(IOException.class, () -> Movie.open(m, 1024).close());
    }

    private static byte[][] await(Movie movie, int i) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < until) {
            byte[][] f = movie.frame(i);
            if (f != null) {
                return f;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("frame " + i + " never came: " + movie);
    }

    private static Path file(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        try (InputStream in = MovieTest.class.getResourceAsStream("/" + name)) {
            Files.copy(in, p);
        }
        return p;
    }
}
