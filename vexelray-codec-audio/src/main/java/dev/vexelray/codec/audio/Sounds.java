package dev.vexelray.codec.audio;

import sibarum.ffmpeg.Audio;
import sibarum.ffmpeg.Ffmpeg;
import sibarum.probe.Log;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sound files, each as the same {@link Track} whatever its format: MP3, AAC, FLAC, Vorbis, Opus, WAV, ALAC and
 * whatever else FFmpeg decodes, in whatever file they come in.
 *
 * <p><b>The caller never learns a codec.</b> Every file opens the same way and plays the same way; the codec's name is
 * in the {@link Cued#identity identity}, to be shown, never branched on. A file that also has pictures (a video, or an
 * MP3 with its cover) opens as its sound.
 *
 * <p><b>Every failure is a reason, not an exception.</b> A file that is not sound, one that is damaged, a machine with
 * no sound device: each comes back as a {@link Cued} with no track and a line saying why, so the caller says so and
 * the next file is unaffected.
 *
 * <p>Blocking: {@link #open} reads enough of the file to know its streams and opens the device. Run it on
 * {@code Gui.offload()}.
 */
public final class Sounds {

    /**
     * Where sound goes: {@code speakers} (the default), {@code nowhere} (decoded and drained in real time, the clock
     * running as with speakers, nothing heard: what the tests use), or {@code off} (nothing opened). The same property
     * vexelray-codec-video's sound follows.
     */
    public static final String OUTPUT = "vexelray.sound";

    /** The extensions a folder is listed for. A file is opened by its bytes, whatever it is named. */
    static final Set<String> EXTENSIONS = Set.of("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "aif", "aiff", "alac", "ape", "wv", "mka", "ac3", "mp2");

    private static final Log LOG = Log.of("codec.audio");

    private static volatile boolean decoderReported;

    private Sounds() {
    }

    /** The extensions a folder is listed for, sorted. */
    public static List<String> extensions() {
        return EXTENSIONS.stream().sorted().toList();
    }

    /** Whether {@code path} is named as a sound file. */
    public static boolean named(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(s.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * {@code path}'s sound as a {@link Track}, paused at its start and played to where {@link #OUTPUT} says, or why
     * there is none. The track is the caller's to close. Blocking.
     */
    public static Cued open(Path path) {
        String where = System.getProperty(OUTPUT, "speakers");
        if (where.equals("off")) {
            return Cued.none(path, "sound is off");
        }
        try {
            if (Files.isDirectory(path)) {
                return Cued.none(path, "a folder");
            }
            if (!Files.isReadable(path)) {
                return Cued.none(path, Files.exists(path) ? "unreadable" : "not found");
            }
        } catch (RuntimeException e) {
            return Cued.none(path, "unreadable: " + e.getClass().getSimpleName());
        }
        Audio audio;
        try {
            audio = Ffmpeg.openAudio(path, where.equals("nowhere") ? Audio.Output.NOWHERE : Audio.Output.SPEAKERS);
        } catch (RuntimeException e) {
            LOG.debug("{} did not open as sound: {}", path, e.getMessage());
            return Cued.none(path, unopened(e.getMessage()));
        } catch (LinkageError e) {
            unavailable(e);
            return Cued.none(path, "the sound decoder is unavailable");
        }
        String identity = describe(audio.codec(), audio.channels(), audio.sampleRate());
        return new Cued(path, identity, new Track(audio, identity));
    }

    /** The decoder, for a log line or an about box. */
    public static String decoder() {
        try {
            return "FFmpeg " + Ffmpeg.version();
        } catch (RuntimeException | LinkageError e) {
            unavailable(e);
            return "no sound decoder";
        }
    }

    /** What a file is: "MP3 sound · stereo · 44.1 kHz". */
    static String describe(String codec, int channels, int rate) {
        return name(codec) + " sound · " + channels(channels) + " · " + rate(rate);
    }

    /** Why a file did not open, from the wrapper's message, in the caller's words. */
    static String unopened(String message) {
        String m = message == null ? "" : message;
        if (m.contains("no decoder for the sound")) {
            return "no decoder for its sound";
        }
        if (m.contains("no sound output") || m.contains("could not open the sound output")
                || m.contains("the sound output has no rate")) {
            return "no sound device";
        }
        if (m.contains("could not read the streams") || m.contains("could not set the decoder up")
                || m.contains("could not open the decoder")) {
            return "damaged sound";
        }
        return "not sound";
    }

    private static String name(String codec) {
        if (codec.startsWith("pcm_")) {
            return "PCM";
        }
        return switch (codec) {
            case "vorbis" -> "Vorbis";
            case "opus" -> "Opus";
            case "wmav1", "wmav2" -> "WMA";
            case "wavpack" -> "WavPack";
            default -> codec.toUpperCase(Locale.ROOT);
        };
    }

    private static String channels(int n) {
        return switch (n) {
            case 1 -> "mono";
            case 2 -> "stereo";
            case 6 -> "5.1";
            case 8 -> "7.1";
            default -> n + " channels";
        };
    }

    private static String rate(int hz) {
        return BigDecimal.valueOf(hz, 3).stripTrailingZeros().toPlainString() + " kHz";
    }

    private static void unavailable(Throwable e) {
        if (!decoderReported) {
            decoderReported = true;
            LOG.warn("no sound: the sound decoder is unavailable ({})", e.toString());
        }
    }
}
