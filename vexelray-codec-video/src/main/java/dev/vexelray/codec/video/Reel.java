package dev.vexelray.codec.video;

import sibarum.ffmpeg.Frame;
import sibarum.ffmpeg.Video;
import sibarum.probe.Log;

import java.util.ArrayDeque;

/**
 * A {@link Clip} decoded ahead of the clock, on a thread of its own: whoever shows it asks for the frame due at a time
 * ({@link #due}) and never waits for a decode.
 *
 * <p><b>A few frames ahead, and no more.</b> The thread decodes until {@link #AHEAD} frames are waiting, then sleeps
 * until one is taken. A 1080p frame is 8 MB, so the queue is the only buffer worth having, and it is small.
 *
 * <p><b>Late frames are dropped, not shown late.</b> {@link #due} hands over the newest frame whose time has come and
 * drops the ones before it, so a decoder that falls behind the clock skips frames to catch up instead of drifting.
 *
 * <p><b>A seek replaces the queue.</b> {@link #seek} empties it at once; the thread seeks the clip before its next
 * decode and drops a frame it was in the middle of making for the old position.
 *
 * <p>Owns the clip: the thread closes it when the reel is {@linkplain #close closed}. Every method here may be called
 * from any thread.
 */
public final class Reel implements AutoCloseable {

    /** Frames decoded ahead of the one shown. */
    public static final int AHEAD = 4;

    private static final Log LOG = Log.of("codec.video");

    private final Clip clip;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>(AHEAD);
    private final Thread thread;

    // Guarded by queue.
    /** Where the thread is to seek before its next decode, or -1. */
    private long seekTo = -1;
    /** The clip has no more frames after those queued. */
    private boolean ended;
    private boolean closed;
    /** The thread has stopped for good: a seek finds nothing to decode it, so it stays finished. */
    private boolean dead;
    private String failure;

    /** Start decoding {@code clip} from where it is (the start, for a clip just opened). */
    public Reel(Clip clip) {
        this.clip = clip;
        this.thread = Thread.ofPlatform().daemon().name("video-decode " + clip.stamp().path().getFileName())
                .start(this::run);
    }

    public Clip clip() {
        return clip;
    }

    /**
     * The newest frame shown at or before {@code micros}, dropping any older ones still waiting; null if none has come
     * yet, or the next frame is later than that.
     */
    public Frame due(long micros) {
        synchronized (queue) {
            Frame due = null;
            while (!queue.isEmpty() && queue.peekFirst().ptsMicros() <= micros) {
                due = queue.pollFirst();
            }
            if (due != null) {
                queue.notifyAll();
            }
            return due;
        }
    }

    /** When the next waiting frame is shown, or -1 if none is waiting. */
    public long next() {
        synchronized (queue) {
            Frame f = queue.peekFirst();
            return f == null ? -1 : f.ptsMicros();
        }
    }

    /** How many frames wait; for a test. */
    int waiting() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** Whether every frame has been handed over: the clip has ended, and nothing waits. */
    public boolean finished() {
        synchronized (queue) {
            return ended && queue.isEmpty() && seekTo < 0;
        }
    }

    /** Why decoding stopped early, or null if it has not. A damaged file ends where it fails, rather than throwing. */
    public String failure() {
        synchronized (queue) {
            return failure;
        }
    }

    /** Decode from {@code micros} on: the next frame due is the one shown then. */
    public void seek(long micros) {
        synchronized (queue) {
            if (dead) {
                return;
            }
            seekTo = Math.max(0, micros);
            queue.clear();
            ended = false;
            failure = null;
            queue.notifyAll();
        }
    }

    /** Stop decoding and close the clip, on the reel's own thread. Closing twice does nothing. */
    @Override
    public void close() {
        synchronized (queue) {
            closed = true;
            queue.clear();
            queue.notifyAll();
        }
    }

    /** Whether the thread has stopped and closed the clip; for a test, which can wait on it. */
    boolean stopped(long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    private void run() {
        Video video = clip.video();
        try {
            while (true) {
                long target;
                synchronized (queue) {
                    while (!closed && seekTo < 0 && (ended || queue.size() >= AHEAD)) {
                        queue.wait();
                    }
                    if (closed) {
                        return;
                    }
                    target = seekTo;
                    seekTo = -1;
                }
                Frame frame;
                String why = null;
                try {
                    if (target >= 0) {
                        video.seek(target);
                    }
                    frame = video.next();
                } catch (RuntimeException e) {
                    // A damaged stream (VideoException), or anything else the decoder threw: it ends here.
                    frame = null;
                    why = e.getMessage() == null ? e.toString() : e.getMessage();
                } catch (OutOfMemoryError e) {
                    frame = null;
                    why = "too large to decode";
                }
                synchronized (queue) {
                    if (closed) {
                        return;
                    }
                    if (seekTo >= 0) {
                        continue;   // made for a position that has since been left
                    }
                    if (frame == null) {
                        ended = true;
                        if (why != null) {
                            failure = why;
                            LOG.warn("{} stopped decoding: {}", clip.stamp().path(), why);
                        }
                    } else {
                        queue.addLast(frame);
                    }
                    queue.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Error e) {
            // The decoder itself has gone: finished, and said, so whoever plays this is not left waiting on a thread
            // that is no longer there.
            synchronized (queue) {
                ended = true;
                dead = true;
                seekTo = -1;
                failure = e.toString();
                queue.notifyAll();
            }
            LOG.warn("{} stopped decoding: {}", clip.stamp().path(), e.toString());
        } finally {
            clip.close();
        }
    }
}
