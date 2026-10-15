package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Stamp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
