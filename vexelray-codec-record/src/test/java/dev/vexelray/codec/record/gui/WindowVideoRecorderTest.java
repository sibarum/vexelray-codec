package dev.vexelray.codec.record.gui;

import dev.vexelray.gui.core.WindowRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void aFileThatCannotBeMadeIsAReasonToShow() {
        IOException e = assertThrows(IOException.class,
                () -> new WindowVideoRecorder().start(dir.resolve("window.gif"), 64, 48, 0));
        assertTrue(e.getMessage().contains("window.gif"), e.getMessage());
    }
}
