package dev.vexelray.codec.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.ffmpeg.Ffmpeg;
import sibarum.ffmpeg.Frame;
import sibarum.ffmpeg.Recording;
import sibarum.ffmpeg.Video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records for real, on the recorder's own thread, and plays every file back through ffmpeg-wrapper. */
class RecorderTest {

    private static final int RED = 0, GREEN = 1, BLUE = 2;

    @TempDir
    Path dir;

    @Test
    void recordsWhatIsOfferedAtTheTimesItWasShown() throws Exception {
        Path file = dir.resolve("window.mkv");
        Recorder.Started started = Recorder.start(file, 64, 48, Recording.Settings.DEFAULT);
        assertTrue(started.recording(), started.why());
        Recorder rec = started.recorder();
        List<Long> times = new ArrayList<>();
        // A second of red at 10 fps, idle for a second and a half, then green: offered no faster than it encodes, so
        // none is dropped.
        for (int i = 0; i < 20; i++) {
            long t = i * 100_000L + (i >= 10 ? 1_500_000L : 0);
            while (!rec.offer(solid(64, 48, i < 10 ? RED : GREEN, t))) {
                Thread.onSpinWait();
            }
            times.add(t);
        }
        rec.close();
        assertNull(rec.failure());
        assertEquals(20, rec.written());
        assertEquals(Files.size(file), rec.bytes());

        try (Video v = Ffmpeg.open(file)) {
            assertEquals("av1", v.info().codec());
            List<Frame> frames = new ArrayList<>();
            for (Frame f; (f = v.next()) != null; ) {
                frames.add(f);
            }
            assertEquals(20, frames.size());
            for (int i = 0; i < 20; i++) {
                assertEquals(times.get(i), frames.get(i).ptsMicros(), 1000, "frame " + i + "'s time");
            }
            assertTrue(Fixtures.dominant(frames.get(5).rgba(), 64, 48, RED));
            assertTrue(Fixtures.dominant(frames.get(15).rgba(), 64, 48, GREEN));
        }
    }

    @Test
    void neverWaitsAndAccountsForEveryFrame() throws Exception {
        Path file = dir.resolve("burst.mkv");
        Recorder rec = Recorder.start(file, 1280, 720, Recording.Settings.DEFAULT).recorder();
        // Offered as fast as they can be made: some may be dropped, none waited for, all accounted.
        for (int i = 0; i < 60; i++) {
            rec.offer(solid(1280, 720, i % 3, i * 16_667L));
        }
        rec.close();
        assertNull(rec.failure());
        assertEquals(60, rec.offered());
        assertEquals(60, rec.written() + rec.dropped());
        try (Video v = Ffmpeg.open(file)) {
            int n = 0;
            while (v.next() != null) {
                n++;
            }
            assertEquals(rec.written(), n);
        }
    }

    @Test
    void dropsAFrameNoLaterThanTheLast() {
        Recorder rec = Recorder.start(dir.resolve("order.mkv"), 64, 48, Recording.Settings.DEFAULT).recorder();
        try {
            assertTrue(rec.offer(solid(64, 48, BLUE, 50_000)));
            // Dropped on offer while the first still waits, or by the encoder once it has gone: either way, dropped.
            rec.offer(solid(64, 48, BLUE, 50_000));
            rec.offer(solid(64, 48, BLUE, 10_000));
        } finally {
            rec.close();
        }
        assertEquals(1, rec.written());
        assertEquals(2, rec.dropped());
    }

    @Test
    void aFailureToStartIsAReason() {
        Recorder.Started started = Recorder.start(dir.resolve("window.gif"), 64, 48, Recording.Settings.DEFAULT);
        assertFalse(started.recording());
        assertNotNull(started.why());
        assertTrue(started.why().contains("window.gif"), started.why());
    }

    @Test
    void aWindowResizedWhileRecordedStaysOnItsCanvas() throws Exception {
        Path file = dir.resolve("resized.mkv");
        Recorder rec = Recorder.start(file, 64, 48, Recording.Settings.DEFAULT).recorder();
        int[][] sizes = {{64, 48}, {32, 24}, {80, 60}};
        for (int i = 0; i < sizes.length; i++) {
            while (!rec.offer(solid(sizes[i][0], sizes[i][1], i, i * 100_000L))) {
                Thread.onSpinWait();
            }
        }
        rec.close();
        assertNull(rec.failure());
        assertEquals(3, rec.written());
        try (Video v = Ffmpeg.open(file)) {
            for (int i = 0; i < sizes.length; i++) {
                Frame f = v.next();
                assertEquals(64, f.width(), "every frame is the canvas");
                assertEquals(48, f.height());
                // The top left is the frame's colour, whatever its size.
                int p = (6 * 64 + 8) * 4;
                assertTrue((f.rgba()[p + i] & 0xff) > 200, "frame " + i + " at the top left");
            }
        }
    }

    @Test
    void closingTwiceDoesNothingAndAClosedRecorderDropsWhatItIsOffered() throws Exception {
        Path file = dir.resolve("closed.mkv");
        Recorder rec = Recorder.start(file, 64, 48, Recording.Settings.DEFAULT).recorder();
        rec.offer(solid(64, 48, RED, 0));
        rec.close();
        rec.close();
        assertFalse(rec.offer(solid(64, 48, RED, 100_000)));
        assertEquals(1, rec.written());
        try (Video v = Ffmpeg.open(file)) {
            assertNotNull(v.next());
        }
    }

    private static Frame solid(int w, int h, int channel, long micros) {
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i + channel] = (byte) 0xff;
            px[i + 3] = (byte) 0xff;
        }
        return new Frame(w, h, micros, px);
    }
}
