package dev.vexelray.codec.record.gui;

import dev.vexelray.gui.core.WindowRecorder;
import dev.vexelray.vulkan.present.TappedFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.ffmpeg.Ffmpeg;
import sibarum.ffmpeg.Frame;
import sibarum.ffmpeg.Video;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorder the GUI's camera finds, fed as a presenter feeds it: frames in mapped memory, B, G, R, A, each released
 * once it is encoded. Recorded for real and played back.
 */
class WindowVideoRecorderTest {

    @TempDir
    Path dir;

    @Test
    void theCameraFindsItOnTheClassPath() {
        assertInstanceOf(WindowVideoRecorder.class, WindowRecorder.find().orElseThrow());
    }

    @Test
    void recordsTappedFramesReleasesEachAndHoldsTheLastUntilTheEnd() throws Exception {
        Path file = dir.resolve("window.mkv");
        long start = 1_000_000_000L;
        AtomicInteger released = new AtomicInteger();
        WindowRecorder.Session session = new WindowVideoRecorder().start(file, 96, 64, start);
        try (Arena arena = Arena.ofShared()) {
            // A window of 64x48 on a 96x64 canvas, blue then red, presented 100 ms apart, as BGRA.
            for (int i = 0; i < 2; i++) {
                MemorySegment px = arena.allocate(64 * 48 * 4);
                for (long p = 0; p < 64 * 48; p++) {
                    px.set(java.lang.foreign.ValueLayout.JAVA_BYTE, p * 4 + (i == 0 ? 0 : 2), (byte) 0xff);
                    px.set(java.lang.foreign.ValueLayout.JAVA_BYTE, p * 4 + 3, (byte) 0xff);
                }
                session.frame(frame(px, 64, 48, start + i * 100_000_000L, released));
                Thread.sleep(50);   // as a window presents: not faster than the encoder takes them
            }
            session.finish(start + 2_000_000_000L);
        }
        assertNull(session.failure());
        assertEquals(2, released.get(), "every buffer given back to the presenter");
        try (Video v = Ffmpeg.open(file)) {
            assertEquals(96, v.info().width());
            assertTrue(Math.abs(v.info().durationMicros() - 2_000_000) < 100_000, "lasts until the stop");
            Frame blue = v.next();
            assertEquals(0, blue.ptsMicros(), 1000);
            assertTrue((blue.rgba()[(20 * 96 + 20) * 4 + 2] & 0xff) > 200, "BGRA read as such: blue");
            Frame red = v.next();
            assertTrue((red.rgba()[(20 * 96 + 20) * 4] & 0xff) > 200, "red");
            assertTrue((red.rgba()[(60 * 96 + 90) * 4] & 0xff) < 30, "the canvas beyond the window, black");
            Frame held = v.next();
            assertEquals(2_000_000, held.ptsMicros(), 1000);
        }
    }

    @Test
    void aFileThatCannotBeMadeIsAReasonToShow() {
        IOException e = assertThrows(IOException.class,
                () -> new WindowVideoRecorder().start(dir.resolve("window.gif"), 64, 48, 0));
        assertTrue(e.getMessage().contains("window.gif"), e.getMessage());
    }

    private static TappedFrame frame(MemorySegment px, int w, int h, long at, AtomicInteger released) {
        return new TappedFrame() {
            @Override
            public MemorySegment pixels() {
                return px;
            }

            @Override
            public int width() {
                return w;
            }

            @Override
            public int height() {
                return h;
            }

            @Override
            public boolean bgra() {
                return true;
            }

            @Override
            public long presentedNanos() {
                return at;
            }

            @Override
            public void release() {
                released.incrementAndGet();
            }
        };
    }
}
