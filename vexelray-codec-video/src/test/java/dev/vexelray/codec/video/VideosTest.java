package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Stamp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opening a video and taking its poster, for real, through ffmpeg-wrapper: fails rather than skips without it. */
class VideosTest {

    @Test
    void opensAtItsOwnSizeAndSaysWhatItIs() throws IOException {
        Opened o = Videos.open(Fixtures.file("rgb.mp4"), 0);
        assertTrue(o.video(), o.identity());
        try (Clip c = o.clip()) {
            assertEquals("H.264 video · 64 × 48 · 0:03", o.identity());
            assertEquals(64, c.width());
            assertEquals(48, c.height());
            assertTrue(c.info().audio());
        }
    }

    @Test
    void opensSmallerForASmallerSideAndNeverLarger() throws IOException {
        try (Clip c = Videos.open(Fixtures.file("rgb.webm"), 32).clip()) {
            assertEquals(32, c.width());
            assertEquals(24, c.height());
        }
        try (Clip c = Videos.open(Fixtures.file("rgb.webm"), 4096).clip()) {
            assertEquals(64, c.width());
        }
    }

    @Test
    void thePosterIsATenthOfTheWayIn() throws IOException {
        Path file = Fixtures.file("rgb.webm");
        Decoded d = Videos.poster(file, 0);
        assertTrue(d.image(), d.identity());
        assertEquals("VP9 video · 64 × 48 · 0:03", d.identity());
        Picture p = d.picture();
        assertEquals(Stamp.of(file), p.key());
        assertEquals(64, p.sheetWidth());
        assertFalse(p.animated());
        // 0.3 s in: still the red second.
        assertTrue(Fixtures.dominant(p.sheet(), 64, 48, 0));
    }

    @Test
    void whatIsNotAVideoIsAReason() throws IOException {
        Path text = Files.createTempFile("not-a-video", ".mp4");
        Files.writeString(text, "plain text");
        Opened o = Videos.open(text, 0);
        assertFalse(o.video());
        assertNull(o.clip());
        assertEquals("not a video", o.identity());
        assertEquals("not a video", Videos.poster(text, 0).identity());
        assertEquals("a folder", Videos.open(text.getParent(), 0).identity());
        assertTrue(Videos.open(text.resolveSibling("no-such.mp4"), 0).identity().startsWith("unreadable"));
    }

    @Test
    void namesClocksAndSizes() {
        assertTrue(Videos.named(Path.of("a/b/Holiday.MKV")));
        assertFalse(Videos.named(Path.of("a/b/holiday.png")));
        assertEquals("0:00", Videos.clock(0));
        assertEquals("0:42", Videos.clock(42_900_000));
        assertEquals("1:02:07", Videos.clock(3_727_000_000L));
        assertEquals("H.264", Videos.codec("h264"));
        assertEquals("AV1", Videos.codec("av1"));
        assertArrayEquals(new int[] {1920, 1080}, Videos.fit(1920, 1080, 0));
        assertArrayEquals(new int[] {640, 360}, Videos.fit(1920, 1080, 640));
        assertTrue(Videos.decoder().startsWith("FFmpeg "));
    }

    @Test
    void slidesStepThroughTheVideoAFrameFromEachPart() throws IOException {
        Path file = Fixtures.file("rgb.mp4");
        Decoded d = Videos.slides(file, 3, 32, 700);
        assertTrue(d.image(), d.identity());
        Picture p = d.picture();
        assertEquals(Stamp.of(file), p.key());
        assertEquals(3, p.count());
        assertTrue(p.animated());
        assertFalse(p.plays(), "a slideshow is a sheet, not a video");
        assertArrayEquals(new int[] {700, 700, 700}, p.delays());
        assertEquals(32, p.frameWidth());
        assertEquals(24, p.frameHeight());
        assertEquals(64, p.sourceWidth());
        // Two across, two down: the middle of each second, red, green, then blue.
        assertEquals(2, p.columns());
        for (int k = 0; k < 3; k++) {
            assertTrue(Fixtures.dominant(cell(p, k), 32, 24, k), "frame " + k);
        }
    }

    @Test
    void theFirstFrameIsHandedOverBeforeTheRest() throws IOException {
        Path file = Fixtures.file("rgb.mp4");
        List<Picture> early = new ArrayList<>();
        List<String> said = new ArrayList<>();
        Decoded d = Videos.slides(file, 3, 32, 700, (identity, p) -> {
            said.add(identity);
            early.add(p);
        });
        assertEquals(1, early.size(), "once");
        assertEquals(List.of(d.identity()), said);
        Picture still = early.get(0);
        assertFalse(still.animated());
        assertEquals(Stamp.of(file), still.key());
        assertTrue(Fixtures.dominant(still.sheet(), 32, 24, 0), "the red second, as the slideshow starts");
        assertEquals(3, d.picture().count());
    }

    @Test
    void oneSlideIsAStillFromTheMiddle() throws IOException {
        Picture p = Videos.slides(Fixtures.file("rgb.webm"), 1, 0, 500).picture();
        assertFalse(p.animated());
        assertEquals(64, p.frameWidth());
        assertTrue(Fixtures.dominant(p.sheet(), 64, 48, 1), "the middle of the video is the green second");
    }

    @Test
    void whatIsNotAVideoHasNoSlides() throws IOException {
        Path text = Files.createTempFile("not-a-video", ".mkv");
        Files.writeString(text, "plain text");
        Decoded d = Videos.slides(text, 8, 640, 1000);
        assertFalse(d.image());
        assertEquals("not a video", d.identity());
    }

    /** Frame {@code k} of {@code p}'s sheet, on its own. */
    private static byte[] cell(Picture p, int k) {
        int fw = p.frameWidth();
        int fh = p.frameHeight();
        int x = (k % p.columns()) * fw;
        int y = (k / p.columns()) * fh;
        byte[] out = new byte[fw * fh * 4];
        for (int r = 0; r < fh; r++) {
            System.arraycopy(p.sheet(), ((y + r) * p.sheetWidth() + x) * 4, out, r * fw * 4, fw * 4);
        }
        return out;
    }
}
