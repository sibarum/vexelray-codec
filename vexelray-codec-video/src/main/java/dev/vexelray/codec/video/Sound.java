package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Playable;
import sibarum.ffmpeg.Audio;
import sibarum.ffmpeg.Ffmpeg;
import sibarum.ffmpeg.VideoException;
import sibarum.probe.Log;

import java.nio.file.Path;

/**
 * A video's sound, as the clock its frames keep time by: what {@link Footage#clock} gives a {@code Playback} when the
 * file has sound and a speaker to play it on.
 *
 * <p><b>The time is the sound's while there is sound.</b> It is what is being heard ({@code Audio.clockMicros}), so
 * the frames shown are the ones the sound has reached. Where the sound stops before the pictures do (a track shorter
 * than the video), or the device is lost while playing, it runs on by the wall from there, so the pictures play to
 * their end.
 *
 * <p>Owned by the {@link Footage} that opened it, and closed with it. Every method may be called from any thread.
 */
final class Sound implements Playable.Clock {

    private static final Log LOG = Log.of("codec.video");

    private final Audio audio;

    // Guarded by this.
    private boolean running;
    private boolean closed;
    /** The sound has failed: the time is kept by the wall from {@link #lastMs} on. */
    private boolean lost;
    private long lastMs;
    /** Time run past the end of the sound, banked while stopped; and since when it has been running on, or -1. */
    private long overMs;
    private long overSince = -1;

    private Sound(Audio audio) {
        this.audio = audio;
    }

    /**
     * Where videos' sound goes: {@code speakers} (the default), {@code nowhere} (decoded and drained in real time, the
     * clock running as with speakers, nothing heard: what the tests use), or {@code off} (not opened at all, the
     * pictures keeping their own time).
     */
    static final String OUTPUT = "vexelray.sound";

    /**
     * The sound of {@code path} where {@link #OUTPUT} says, or null when there is none to play: the file has no sound,
     * nothing decodes it, there is no sound device, or sound is off. The pictures then keep their own time, silently.
     */
    static Sound open(Path path) {
        String where = System.getProperty(OUTPUT, "speakers");
        if (where.equals("off")) {
            return null;
        }
        try {
            Audio audio = Ffmpeg.openAudio(path, where.equals("nowhere") ? Audio.Output.NOWHERE
                    : Audio.Output.SPEAKERS);
            LOG.debug("{} plays with its sound, {} to {}", path.getFileName(), audio, where);
            return new Sound(audio);
        } catch (VideoException e) {
            LOG.info("{} plays without sound: {}", path, e.getMessage());
        } catch (LinkageError e) {
            LOG.warn("{} plays without sound: the sound decoder is unavailable ({})", path, e.toString());
        }
        return null;
    }

    @Override
    public synchronized long now() {
        if (closed || lost) {
            return lastMs + over();
        }
        try {
            long ms = audio.clockMicros() / 1000;
            if (running && overSince < 0 && audio.ended()) {
                overSince = System.nanoTime();
            }
            lastMs = ms;
            return ms + over();
        } catch (VideoException e) {
            lose(e);
            return lastMs + over();
        }
    }

    @Override
    public synchronized void run(boolean run) {
        if (run == running || closed) {
            return;
        }
        running = run;
        if (!run) {
            bank();
        } else if (lost || overMs > 0) {
            // Past the sound's end, or without it: the wall runs on from here.
            overSince = System.nanoTime();
        }
        if (lost) {
            return;
        }
        try {
            if (run) {
                audio.play();
            } else {
                audio.pause();
            }
        } catch (VideoException e) {
            lose(e);
        }
    }

    @Override
    public synchronized void seek(long ms) {
        if (closed) {
            return;
        }
        long to = Math.max(0, ms);
        overMs = 0;
        overSince = -1;
        lastMs = to;
        if (lost) {
            overSince = running ? System.nanoTime() : -1;
            return;
        }
        try {
            audio.seek(to * 1000);
        } catch (VideoException e) {
            lose(e);
        }
    }

    @Override
    public synchronized void volume(float volume) {
        if (closed || lost) {
            return;
        }
        try {
            audio.volume(Math.max(0f, Math.min(1f, volume)));
        } catch (VideoException e) {
            lose(e);
        }
    }

    /** Stop the sound and give the device back. Closing twice does nothing. */
    synchronized void close() {
        if (closed) {
            return;
        }
        bank();
        closed = true;
        audio.close();
    }

    /** The time run on by the wall past the sound, in ms. Under this. */
    private long over() {
        return overMs + (overSince < 0 ? 0 : (System.nanoTime() - overSince) / 1_000_000);
    }

    /** Stop the wall, keeping what it ran. Under this. */
    private void bank() {
        overMs = over();
        overSince = -1;
    }

    /** The sound has failed: keep time by the wall from where it was, silently. Under this. */
    private void lose(VideoException e) {
        LOG.warn("{} lost its sound at {} ms: {}", audio.file(), lastMs, e.getMessage());
        lost = true;
        overSince = running ? System.nanoTime() : -1;
    }

    @Override
    public synchronized String toString() {
        return "Sound[" + audio + ", " + (running ? "running" : "stopped") + (lost ? ", lost" : "") + "]";
    }
}
