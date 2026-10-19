package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A video's sound as its clock, played for real to no speaker (the build sets {@code vexelray.sound=nowhere}), so its
 * times are real times and the bounds are loose: a busy machine runs late, never early. {@code rgb.mp4} has three
 * seconds of a tone; {@code rgb.webm} has no sound.
 */
class SoundTest {

    @Test
    void footageWithSoundKeepsTimeByItAndWithoutKeepsNone() throws Exception {
        try (Playable withSound = motion("rgb.mp4").open(0); Playable silent = motion("rgb.webm").open(0)) {
            assertInstanceOf(Sound.class, withSound.clock());
            assertNull(silent.clock());
        }
    }

    @Test
    void itRunsStopsAndSeeks() throws Exception {
        Sound s = Sound.open(Fixtures.file("rgb.mp4"));
        assertNotNull(s);
        try {
            assertEquals(0, s.now());
            s.run(true);
            Thread.sleep(500);
            long ran = s.now();
            assertTrue(ran > 200 && ran < 800, "after half a second: " + ran);

            s.run(false);
            long held = s.now();
            Thread.sleep(200);
            assertEquals(held, s.now(), "stopped, it holds");

            s.seek(2000);
            assertEquals(2000, s.now(), "a seek is the clock at once");
        } finally {
            s.close();
        }
    }

    @Test
    void pastTheEndOfTheSoundTheWallRunsOn() throws Exception {
        Sound s = Sound.open(Fixtures.file("rgb.mp4"));
        try {
            s.seek(2800);
            s.run(true);
            long give = System.nanoTime() + 3_000_000_000L;
            while (s.now() < 3300 && System.nanoTime() < give) {
                Thread.sleep(10);
            }
            assertTrue(s.now() >= 3300, "on past the tone's 3 s: " + s.now());
            s.run(false);
            long held = s.now();
            Thread.sleep(200);
            assertEquals(held, s.now(), "and holds when stopped");
            s.seek(0);
            assertEquals(0, s.now(), "a seek forgets the wall");
        } finally {
            s.close();
        }
    }

    @Test
    void offIsNoSound() throws Exception {
        String was = System.getProperty(Sound.OUTPUT);
        System.setProperty(Sound.OUTPUT, "off");
        try {
            assertNull(Sound.open(Fixtures.file("rgb.mp4")));
        } finally {
            System.setProperty(Sound.OUTPUT, was == null ? "nowhere" : was);
        }
    }

    @Test
    void aClosedSoundKeepsAnswering() throws Exception {
        Sound s = Sound.open(Fixtures.file("rgb.mp4"));
        s.seek(1000);
        s.close();
        s.close();
        assertEquals(1000, s.now());
        s.run(true);
        s.seek(0);
    }

    private static Motion motion(String name) throws Exception {
        Path file = Fixtures.file(name);
        return Videos.poster(file, 0, true).picture().motion();
    }
}
