package dev.vexelray.codec.video.gui;

import dev.vexelray.codec.video.Recorder;
import dev.vexelray.gui.core.WindowRecorder;
import dev.vexelray.vulkan.present.TappedFrame;
import sibarum.ffmpeg.Recording;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The camera's <i>Record video to file…</i>, in AV1: what {@code vexelray-gui}'s {@link WindowRecorder#find} finds when
 * this module is on the class path ({@code META-INF/services}). An application that wants its windows recordable
 * depends on {@code vexelray-codec-video} and does nothing else.
 *
 * <p>Each frame a window presents comes from its presenter's readback buffer ({@link TappedFrame}) and goes to a
 * {@link Recorder}, which encodes it from there, with no copy, on its own thread, and gives the buffer back. The
 * encoder is SVT-AV1 by default; {@code -Dvexelray.record.encoder=hardware} asks for the GPU's.
 */
public final class WindowVideoRecorder implements WindowRecorder {

    /** The system property that picks the encoder: {@code software} (the default) or {@code hardware}. */
    public static final String ENCODER = "vexelray.record.encoder";

    @Override
    public String fileType() {
        return "Matroska video";
    }

    @Override
    public String extension() {
        return "mkv";
    }

    @Override
    public Session start(Path file, int width, int height, long startNanos) throws IOException {
        Recording.Encoder encoder = "hardware".equalsIgnoreCase(System.getProperty(ENCODER))
                ? Recording.Encoder.HARDWARE : Recording.Encoder.SOFTWARE;
        Recorder.Started started = Recorder.start(file, width, height,
                Recording.Settings.DEFAULT.withEncoder(encoder));
        if (!started.recording()) {
            throw new IOException(started.why());
        }
        return new Tapped(started.recorder(), startNanos);
    }

    /** One recording: the window's frames, timed from its start, into the recorder. */
    private record Tapped(Recorder recorder, long startNanos) implements Session {

        @Override
        public void frame(TappedFrame frame) {
            recorder.offer(frame.pixels(), frame.width(), frame.height(),
                    frame.bgra() ? Recording.Pixels.BGRA : Recording.Pixels.RGBA,
                    (frame.presentedNanos() - startNanos) / 1_000L, frame::release);
        }

        @Override
        public void finish(long endNanos) throws IOException {
            recorder.close((endNanos - startNanos) / 1_000L);
            String why = recorder.failure();
            if (why != null) {
                throw new IOException(why);
            }
        }

        @Override
        public Path file() {
            return recorder.file();
        }

        @Override
        public String failure() {
            return recorder.failure();
        }
    }
}
