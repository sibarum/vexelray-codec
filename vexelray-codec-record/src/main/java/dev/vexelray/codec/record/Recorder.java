package dev.vexelray.codec.record;

import sibarum.ffmpeg.record.Recording;
import sibarum.probe.Log;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * A video file recorded from frames as they are shown, encoded to AV1 on a thread of its own: whoever shows them hands
 * each one over ({@link #offer}) and never waits for an encode. What a window's frame tap gives its frames to.
 *
 * <p><b>A few frames queued, and no more.</b> At most {@link #QUEUE} wait to be encoded. A 1080p frame is 8 MB, so the
 * queue is kept small, and a frame offered while it is full is dropped and counted ({@link #dropped}), never waited
 * for: a recorder must not stall what it records. SVT-AV1 encodes a 1080p frame in a fraction of a 60 fps frame's
 * time, so a full queue means a machine far slower than a laptop, or a far larger window.
 *
 * <p><b>Each frame keeps the time it was shown</b> (in µs from the recording's start), and the file
 * keeps it too, so a window that draws nothing while idle is recorded with its pauses as long as they were.
 *
 * <p><b>Frames of any size, on one canvas.</b> The file's frames are all the canvas's size, given at {@link #start};
 * a frame offered smaller (a window made smaller) goes at its top left, unscaled, on black, and a larger one is cut
 * to it. One stream, which every player plays, however often the window is resized.
 *
 * <p><b>A failure ends the recording, never the caller.</b> A full disk or a lost encoder stops the thread, which
 * finishes what it can of the file; {@link #failure} says why, and frames offered after it are dropped.
 *
 * <p>Every method may be called from any thread.
 */
public final class Recorder implements AutoCloseable {

    /** Frames waiting to be encoded, at most. */
    public static final int QUEUE = 4;

    private static final Log LOG = Log.of("codec.record");

    /**
     * What {@link #start} makes of a file: the recorder, or, when there is none, why not.
     *
     * @param file     where the recording goes
     * @param why      null while recording; why there is no recorder ("could not record to x.mkv: ...")
     * @param recorder the recorder, which the caller now owns and closes; null when there is none
     */
    public record Started(Path file, String why, Recorder recorder) {

        /** Whether there is a recorder. */
        public boolean recording() {
            return recorder != null;
        }
    }

    /**
     * A frame waiting: an array of RGBA, or native memory in either order with what gives it back once it is encoded
     * or dropped (a presenter's readback buffer).
     */
    private record Shot(byte[] array, MemorySegment memory, int width, int height, Recording.Pixels order,
                        long micros, Runnable release) {

        boolean encode(Recording recording) {
            return array != null
                    ? recording.frame(array, width, height, micros)
                    : recording.frame(memory, width, height, order, micros);
        }

        void done() {
            if (release != null) {
                release.run();
            }
        }
    }

    private final Recording recording;
    private final ArrayDeque<Shot> queue = new ArrayDeque<>(QUEUE);
    private final Thread thread;

    // Guarded by queue.
    private boolean closing;
    /** Where the last frame is held until at the end, in µs, or -1: what {@link #close(long)} asks for. */
    private long endMicros = -1;
    private String failure;
    private long offered;
    private long dropped;
    private long written;
    private long bytes;

    private Recorder(Recording recording) {
        this.recording = recording;
        this.thread = Thread.ofPlatform().daemon().name("video-encode " + recording.file().getFileName())
                .start(this::run);
    }

    /**
     * Create {@code file} ({@code .mkv} or {@code .webm}; one already there is replaced) and start recording into it,
     * on a canvas of {@code width x height}: the largest a frame will be, such as the screen's, so a window resized
     * while it is recorded stays in one stream, every pixel unscaled. Blocking: it opens the encoder and creates the file, so it is run off a
     * render loop ({@code Gui.offload()}). A failure is a reason, never an exception.
     */
    public static Started start(Path file, int width, int height, Recording.Settings settings) {
        Recording recording;
        try {
            recording = Recording.create(file, width, height, settings);
        } catch (RuntimeException e) {
            return new Started(file, e.getMessage() == null ? e.toString() : e.getMessage(), null);
        } catch (LinkageError e) {
            return new Started(file, "no encoder: " + e.getMessage(), null);
        }
        LOG.info("recording {} at {}x{} with {}", file, width, height, recording.encoder());
        return new Started(file, null, new Recorder(recording));
    }

    /** The file being written. */
    public Path file() {
        return recording.file();
    }

    /** The canvas's width: every frame in the file is this wide. */
    public int width() {
        return recording.width();
    }

    /** The canvas's height. */
    public int height() {
        return recording.height();
    }

    /** The encoder recording: {@code "libsvtav1"}, or a GPU's, such as {@code "av1_nvenc"}. To be shown. */
    public String encoder() {
        return recording.encoder();
    }

    /**
     * Queue the {@code width x height} frame {@code rgba} (straight RGBA8, top row first), shown at {@code micros}
     * from the recording's start, to be encoded, and return at once. The array is the recorder's now: the caller
     * makes a new one for the next frame rather than writing over this. True if it was queued; false if it was
     * dropped, because the queue is full, the recorder is closing or has failed, or its time does not come after
     * the last one queued. The frame may be any size: it goes at the canvas's top left, unscaled, on black, and
     * whatever falls outside the canvas is cut off.
     */
    public boolean offer(byte[] rgba, int width, int height, long micros) {
        return offer(new Shot(rgba, null, width, height, Recording.Pixels.RGBA, micros, null));
    }

    /**
     * Queue the {@code width x height} frame at {@code pixels}, native memory in {@code order} (a presented frame where
     * the GPU left it), shown at {@code micros} from the recording's start, and return at once. It is encoded from
     * there, with no copy, and {@code release} is run once it has been, or at once if it is dropped, from whichever
     * thread that happens on. Otherwise as {@link #offer(byte[], int, int, long)}.
     */
    public boolean offer(MemorySegment pixels, int width, int height, Recording.Pixels order, long micros,
                         Runnable release) {
        return offer(new Shot(null, pixels, width, height, order, micros, release));
    }

    private boolean offer(Shot shot) {
        synchronized (queue) {
            offered++;
            Shot last = queue.peekLast();
            if (closing || failure != null || queue.size() >= QUEUE || shot.micros() < 0
                    || (last != null && shot.micros() <= last.micros())) {
                dropped++;
                shot.done();
                return false;
            }
            queue.addLast(shot);
            queue.notifyAll();
            return true;
        }
    }

    /** Frames offered so far, queued or not. */
    public long offered() {
        synchronized (queue) {
            return offered;
        }
    }

    /** Frames offered and not recorded: dropped for a full queue, a closed or failed recorder, or a time too soon. */
    public long dropped() {
        synchronized (queue) {
            return dropped;
        }
    }

    /** Frames encoded into the file so far. */
    public long written() {
        synchronized (queue) {
            return written;
        }
    }

    /** About how many bytes the file holds: as of the last frame encoded, and exactly once it is finished. */
    public long bytes() {
        synchronized (queue) {
            return bytes;
        }
    }

    /** Why the recording stopped early, or null if it has not. */
    public String failure() {
        synchronized (queue) {
            return failure;
        }
    }

    /**
     * Stop taking frames, encode those still queued, and finish the file. Blocks until it is whole, which is a few
     * frames' encoding, so it too is run off a render loop. Closing twice does nothing. {@link #failure} then says
     * whether the file was finished.
     */
    @Override
    public void close() {
        close(-1);
    }

    /**
     * As {@link #close()}, the last frame lasting until {@code endMicros} from the recording's start: a window that sat
     * still at the end is recorded sitting still, rather than the file ending at its last change. A time no later than
     * the last frame's changes nothing.
     */
    public void close(long endMicros) {
        synchronized (queue) {
            if (!closing) {
                this.endMicros = endMicros;
            }
            closing = true;
            queue.notifyAll();
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        try {
            while (true) {
                Shot shot;
                long end;
                synchronized (queue) {
                    while (!closing && queue.isEmpty()) {
                        queue.wait();
                    }
                    shot = queue.pollFirst();
                    end = endMicros;
                }
                if (shot == null) {
                    // Closing, and everything queued is in the file: the last frame held until the end, if asked.
                    if (end >= 0 && recording.repeat(end)) {
                        synchronized (queue) {
                            written++;
                        }
                    }
                    return;
                }
                boolean encoded;
                try {
                    encoded = shot.encode(recording);
                } finally {
                    shot.done();
                }
                long size = recording.bytes();
                synchronized (queue) {
                    if (encoded) {
                        written++;
                    } else {
                        dropped++;  // the same millisecond as the last: the file could not tell them apart
                    }
                    bytes = size;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            fail(e);
        } finally {
            finish();
        }
    }

    /** Finish the file, saying why if it cannot be. */
    private void finish() {
        try {
            recording.close();
        } catch (RuntimeException | Error e) {
            fail(e);
        }
        long size;
        try {
            size = Files.size(recording.file());
        } catch (IOException e) {
            size = -1;
        }
        synchronized (queue) {
            if (size >= 0) {
                bytes = size;
            }
            dropped += queue.size();
            for (Shot left : queue) {
                left.done();
            }
            queue.clear();
            closing = true;
            if (failure == null) {
                LOG.info("recorded {}: {} frames, {} dropped", recording.file(), written, dropped);
            }
        }
    }

    private void fail(Throwable e) {
        String why = e.getMessage() == null ? e.toString() : e.getMessage();
        synchronized (queue) {
            if (failure == null) {
                failure = why;
            }
        }
        LOG.warn("{} stopped recording: {}", recording.file(), why);
    }
}
