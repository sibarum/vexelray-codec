package dev.vexelray.codec.audio;

import sibarum.ffmpeg.Audio;
import sibarum.probe.Log;

import java.nio.file.Path;

/**
 * A sound file, playing. Made by {@link Sounds#open}, paused at its start; closed with {@link #close}, which a
 * try-with-resources does.
 *
 * <pre>{@code
 * Cued cued = Sounds.open(Path.of("song.flac"));
 * if (cued.sound()) {
 *     try (Track t = cued.track()) {
 *         t.play();
 *         long now = t.position();      // the ms being heard
 *         t.seek(12_500);               // exact, playing or paused
 *     }
 * }
 * }</pre>
 *
 * <p><b>The sound never comes into Java.</b> ffmpeg-wrapper decodes it on a thread of its own, a little ahead, and
 * hands it straight to the output device; this only steers that and reads the time back, so a garbage collection
 * cannot make it stutter.
 *
 * <p><b>It never throws once open.</b> Should the device be lost while playing, the track says so in the log, falls
 * silent and holds its position, and every call after is answered from there. A window that plays sound is never
 * taken down by it.
 *
 * <p>Every method may be called from any thread.
 */
public final class Track implements AutoCloseable {

    private static final Log LOG = Log.of("codec.audio");

    private final Audio audio;
    private final String identity;

    // Guarded by this.
    private boolean closed;
    /** The sound has failed: silent, held at {@link #lastMs}. */
    private boolean lost;
    private long lastMs;

    Track(Audio audio, String identity) {
        this.audio = audio;
        this.identity = identity;
    }

    /** The file this was opened from. */
    public Path file() {
        return audio.file();
    }

    /** What it is: "MP3 sound · stereo · 44.1 kHz". */
    public String identity() {
        return identity;
    }

    /** The codec's short name ({@code "mp3"}, {@code "flac"}), to be shown, never branched on. */
    public String codec() {
        return audio.codec();
    }

    /** Channels as stored. The speaker gets them mixed to stereo. */
    public int channels() {
        return audio.channels();
    }

    /** Samples per second as stored. The speaker gets them at its own rate. */
    public int sampleRate() {
        return audio.sampleRate();
    }

    /** Start the sound from {@link #position}. Playing already, nothing changes. */
    public synchronized void play() {
        steer(audio::play);
    }

    /** Stop the sound and hold the position, giving the device back. Paused already, nothing changes. */
    public synchronized void pause() {
        steer(audio::pause);
    }

    /** Go to {@code ms} from the start, exactly; playing or paused, it stays so. */
    public synchronized void seek(long ms) {
        long to = Math.max(0, ms);
        if (closed) {
            return;
        }
        lastMs = to;
        steer(() -> audio.seek(to * 1000));
    }

    /** Loudness from 0 (silent) to 1 (as stored); clamped to that. */
    public synchronized void volume(float volume) {
        steer(() -> audio.volume(Math.max(0f, Math.min(1f, volume))));
    }

    /**
     * Dynamic range compression, from 0 (as stored, the default) to 1; clamped to that. Loud passages are lowered,
     * quiet ones raised and peaks limited to -6 dBFS, so dialogue can be heard without the explosions waking the
     * house. The gain is worked out with the sound ahead in view, so it comes down before a loud passage or a bang
     * rather than after, and nothing is delayed for it: the {@link #position} is as it would be without. A change
     * is heard at once.
     */
    public synchronized void compression(float amount) {
        steer(() -> audio.compression(amount));
    }

    /**
     * Copy the sound last played into {@code stereo}, oldest first, left and right in turn, for a meter or a scope:
     * compressed, before the {@link #volume}. Up to a quarter of a second is kept. The sample frames copied, at most
     * {@code stereo.length / 2}; none once closed or lost.
     */
    public synchronized int heard(float[] stereo) {
        if (closed || lost) {
            return 0;
        }
        try {
            return audio.heard(stereo);
        } catch (RuntimeException | LinkageError e) {
            lose(e);
            return 0;
        }
    }

    /** The time of the sound being heard now, in ms from the start. Holds while paused, and once closed or lost. */
    public synchronized long position() {
        if (closed || lost) {
            return lastMs;
        }
        try {
            lastMs = audio.clockMicros() / 1000;
        } catch (RuntimeException | LinkageError e) {
            lose(e);
        }
        return lastMs;
    }

    /** Whether all the sound has been heard, or it was lost; true until the next {@link #seek}. */
    public synchronized boolean ended() {
        if (closed || lost) {
            return true;
        }
        try {
            return audio.ended();
        } catch (RuntimeException | LinkageError e) {
            lose(e);
            return true;
        }
    }

    public synchronized boolean closed() {
        return closed;
    }

    /** Stop the sound and give the device back. Closing twice does nothing. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        position();
        closed = true;
        try {
            audio.close();
        } catch (RuntimeException | LinkageError e) {
            // A device already gone: there is nothing left to give back, and closing does not throw.
            LOG.debug("{} did not close: {}", audio.file(), e.toString());
        }
    }

    /** Pass a call on to the sound, unless it is closed or lost; a failure loses it. Under this. */
    private void steer(Runnable call) {
        if (closed || lost) {
            return;
        }
        try {
            call.run();
        } catch (RuntimeException | LinkageError e) {
            lose(e);
        }
    }

    /** The sound has failed: silent from here, held where it was. Under this. */
    private void lose(Throwable e) {
        LOG.warn("{} lost its sound at {} ms: {}", audio.file(), lastMs, e.toString());
        lost = true;
    }

    @Override
    public synchronized String toString() {
        return "Track[" + audio.file().getFileName() + ", " + identity + (closed ? ", closed" : lost ? ", lost" : "")
                + "]";
    }
}
