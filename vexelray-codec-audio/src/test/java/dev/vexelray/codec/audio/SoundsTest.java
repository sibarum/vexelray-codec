package dev.vexelray.codec.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening and playing sound for real, through ffmpeg-wrapper, to no speaker ({@code vexelray.sound=nowhere}, which
 * the build sets): fails rather than skips without it. The fixtures are three seconds of a 440 Hz tone, mono, one per
 * format, made as ffmpeg-wrapper's README says; the MP3 carries a cover picture. {@code rgb.webm} is a video with no
 * sound.
 *
 * <p>Times are real, so the bounds are loose: a busy machine runs late, never early.
 */
class SoundsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "tone.wav  | PCM sound · mono · 16 kHz",
            "tone.flac | FLAC sound · mono · 48 kHz",
            "tone.mp3  | MP3 sound · mono · 48 kHz",
            "tone.ogg  | Vorbis sound · mono · 48 kHz",
            "tone.opus | Opus sound · mono · 48 kHz",
            "tone.m4a  | AAC sound · mono · 48 kHz",
    })
    void opensEachFormatAsTheSameTrack(String name, String identity) throws Exception {
        Cued c = Sounds.open(fixture(name));
        assertTrue(c.sound(), c.identity());
        assertEquals(identity, c.identity());
        try (Track t = c.track()) {
            assertEquals(identity, t.identity());
            assertEquals(0, t.position(), "paused at the start");
            assertFalse(t.ended());
        }
    }

    @Test
    void playsAndPausesAndSeeks() throws Exception {
        try (Track t = Sounds.open(fixture("tone.flac")).track()) {
            t.play();
            Thread.sleep(500);
            long played = t.position();
            assertTrue(played > 200 && played < 800, "after 0.5 s of playing: " + played);

            t.pause();
            long held = t.position();
            Thread.sleep(200);
            assertEquals(held, t.position(), "paused, it holds");

            t.seek(2_000);
            assertEquals(2_000, t.position(), "a seek is the position at once");
        }
    }

    @Test
    void endsAtTheEnd() throws Exception {
        try (Track t = Sounds.open(fixture("tone.mp3")).track()) {
            t.seek(2_700);
            t.play();
            long giveUp = System.nanoTime() + 3_000_000_000L;
            while (!t.ended() && System.nanoTime() < giveUp) {
                Thread.sleep(10);
            }
            assertTrue(t.ended(), "ended by now, at " + t.position());
            assertTrue(Math.abs(t.position() - 3_000) < 100, "at the end: " + t.position());

            t.seek(0);
            assertFalse(t.ended(), "a seek starts it again");
        }
    }

    @Test
    void aClosedTrackHoldsAndNeverThrows() throws Exception {
        Track t = Sounds.open(fixture("tone.ogg")).track();
        t.seek(1_000);
        t.close();
        t.close();
        assertTrue(t.closed());
        t.play();
        t.volume(0.5f);
        assertEquals(1_000, t.position());
        assertTrue(t.ended());
    }

    @Test
    void aFileWithoutSoundSaysSo(@TempDir Path dir) throws IOException {
        assertEquals("not sound", Sounds.open(fixture("rgb.webm")).identity());

        Path text = Files.writeString(dir.resolve("notes.mp3"), "not an mp3 at all");
        assertEquals("not sound", Sounds.open(text).identity());

        assertEquals("a folder", Sounds.open(dir).identity());
        assertEquals("not found", Sounds.open(dir.resolve("gone.flac")).identity());
    }

    @Test
    void offOpensNothing() throws IOException {
        Path tone = fixture("tone.wav");
        String was = System.getProperty(Sounds.OUTPUT);
        System.setProperty(Sounds.OUTPUT, "off");
        try {
            Cued c = Sounds.open(tone);
            assertFalse(c.sound());
            assertEquals("sound is off", c.identity());
        } finally {
            if (was == null) {
                System.clearProperty(Sounds.OUTPUT);
            } else {
                System.setProperty(Sounds.OUTPUT, was);
            }
        }
    }

    @Test
    void namesSoundFiles() {
        assertTrue(Sounds.named(Path.of("a", "Song.FLAC")));
        assertTrue(Sounds.named(Path.of("b.opus")));
        assertFalse(Sounds.named(Path.of("clip.mp4")));
        assertFalse(Sounds.named(Path.of(".mp3")));
    }

    @Test
    void describesRatesAndChannels() {
        assertEquals("AAC sound · stereo · 44.1 kHz", Sounds.describe("aac", 2, 44_100));
        assertEquals("AC3 sound · 5.1 · 48 kHz", Sounds.describe("ac3", 6, 48_000));
        assertEquals("PCM sound · 3 channels · 22.05 kHz", Sounds.describe("pcm_s24le", 3, 22_050));
    }

    /** A fixture as a file, since FFmpeg opens paths. */
    private static Path fixture(String name) throws IOException {
        Path out = Files.createTempFile("fixture", "-" + name);
        out.toFile().deleteOnExit();
        try (InputStream in = SoundsTest.class.getResourceAsStream("/" + name)) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
