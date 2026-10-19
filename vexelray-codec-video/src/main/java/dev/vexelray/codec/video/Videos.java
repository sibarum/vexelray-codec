package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Stamp;
import sibarum.ffmpeg.Ffmpeg;
import sibarum.ffmpeg.Frame;
import sibarum.ffmpeg.Video;
import sibarum.ffmpeg.VideoException;
import sibarum.ffmpeg.VideoInfo;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Videos from files: anything FFmpeg decodes (H.264, HEVC, VP9, AV1 and the rest, in MP4, MKV, WebM, MOV and the rest),
 * opened at the size they will be shown as a {@link Clip} for a {@link Reel} to play, or one frame of them as a
 * {@link Picture}, the poster a folder shows.
 *
 * <p><b>Sizes are the caller's.</b> A side is the longest side a frame keeps: a video is scaled down to it and never
 * up, by FFmpeg as each frame is converted, so a thumbnail never holds a frame at full size. No side, or 0, is the
 * file's own size.
 *
 * <p><b>Every failure is a reason, not an exception</b>, as in {@code Pictures}: a file that is not a video, one with no
 * decoder for its codec, or a machine where FFmpeg will not load comes back with no clip and a line saying why.
 *
 * <p>Blocking: run it on {@code Gui.offload()}.
 */
public final class Videos {

    /**
     * What a folder is listed for: the extensions of the containers people keep video in. A guess from the name, which
     * is what a listing can afford; FFmpeg decides when the file is opened.
     */
    static final Set<String> EXTENSIONS = Set.of("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpg",
            "mpeg", "ts", "m2ts", "mts", "3gp", "ogv");

    /** How far into a video its poster is taken: a tenth of the way, so not the fade up from black. */
    static final double POSTER_AT = 0.1;
    /** And never further than this, so a two-hour film does not decode its way to minute twelve. */
    static final long POSTER_MAX_US = 5_000_000;

    private static final Log LOG = Log.of("codec.video");

    /** Said once: a decoder that will not load fails the same way for every file after the first. */
    private static volatile boolean decoderReported;

    private Videos() {
    }

    /** The extensions {@link #named} lists, without the dot, in order: an open dialog's filter. */
    public static List<String> extensions() {
        return EXTENSIONS.stream().sorted().toList();
    }

    /** Whether {@code path} is named like a video: what a folder of videos is listed for. */
    public static boolean named(Path path) {
        Path n = path.getFileName();
        if (n == null) {
            return false;
        }
        String s = n.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(s.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** FFmpeg's version, or why there is none: for an about box or a log line. */
    public static String decoder() {
        try {
            return "FFmpeg " + Ffmpeg.version();
        } catch (RuntimeException | LinkageError e) {
            unavailable(e);
            return "no video decoder";
        }
    }

    /**
     * {@code path}, opened with its frames at most {@code side} on their long side (0 for its own size), ready to play
     * from the start. The clip is the caller's to close, or to hand to a {@link Reel}, which closes it.
     */
    public static Opened open(Path path, int side) {
        Stamp stamp;
        try {
            if (Files.isDirectory(path)) {
                return Opened.none(path, "a folder");
            }
            stamp = Stamp.of(path);
        } catch (IOException | RuntimeException e) {
            return Opened.none(path, "unreadable: " + e.getClass().getSimpleName());
        }
        Video video;
        try {
            video = Ffmpeg.open(path);
        } catch (VideoException e) {
            LOG.debug("{} did not open as a video: {}", path, e.getMessage());
            return Opened.none(path, "not a video");
        } catch (LinkageError e) {
            unavailable(e);
            return Opened.none(path, "the video decoder is unavailable");
        }
        try {
            VideoInfo info = video.info();
            int[] out = fit(info.width(), info.height(), side);
            video.output(out[0], out[1]);
            return new Opened(path, describe(info), new Clip(stamp, video, describe(info)));
        } catch (RuntimeException e) {
            video.close();
            return Opened.none(path, "does not decode as a video");
        }
    }

    /**
     * A frame of {@code path} as a picture at most {@code side} on its long side: a tenth of the way in, no further
     * than {@link #POSTER_MAX_US}, or the first frame of a video too short or too odd to seek. What a folder shows for
     * a video, and what a viewer shows while the video itself loads. Keyed by the file's {@link Stamp}.
     */
    public static Decoded poster(Path path, int side) {
        return poster(path, side, false);
    }

    /**
     * {@link #poster(Path, int)}, or with {@code plays} the video as a picture that plays: its first frame, carrying a
     * {@link Motion} that a {@link Footage} plays, so an {@code ImageView} plays it as it plays an animated GIF, except
     * that fitted it fills its stage, as a player shows it, where a GIF is never enlarged ({@link Motion#fills}). A video
     * that does not say how long it is or how fast it plays ({@link Footage#plays}) is a poster either way.
     */
    public static Decoded poster(Path path, int side, boolean plays) {
        Opened opened = open(path, side);
        if (!opened.video()) {
            return Decoded.none(path, opened.identity());
        }
        try (Clip clip = opened.clip()) {
            Video video = clip.video();
            VideoInfo info = video.info();
            boolean moves = plays && Footage.plays(info);
            Frame frame = null;
            if (!moves && info.timed() && info.durationMicros() > 0) {
                try {
                    video.seek(Math.min(POSTER_MAX_US, (long) (info.durationMicros() * POSTER_AT)));
                    frame = video.next();
                } catch (VideoException e) {
                    frame = null;
                }
            }
            if (frame == null) {
                video.seek(0);
                frame = video.next();
            }
            if (frame == null) {
                return Decoded.none(path, "a video with no frames");
            }
            Motion motion = moves ? new Motion(clip.stamp(), Footage.count(info), info.durationMicros() / 1000, 0,
                    Footage.SOURCE, true) : null;
            Picture p = new Picture(clip.stamp(), frame.rgba(), frame.width(), frame.height(), 1, 1, new int[1],
                    info.width(), info.height(), false, motion);
            return new Decoded(path, opened.identity(), List.of(p));
        } catch (VideoException e) {
            return Decoded.none(path, "does not decode as a video");
        } catch (OutOfMemoryError e) {
            LOG.warn("{} is too large to decode", path);
            return Decoded.none(path, "too large to decode");
        }
    }

    /**
     * {@code count} frames from across {@code path} as a picture that steps through them, {@code millis} each, as an
     * animated GIF does: a slideshow of the video for a preview, where playing it would be too much. The frames are
     * taken from the middle of {@code count} equal parts, so neither the fade up from black nor the credits, and
     * packed into one sheet at most {@code side} on a frame's long side, smaller if the sheet would not fit
     * {@link Picture#SHEET_SIDE}. Keyed by the file's {@link Stamp}, as a poster is.
     *
     * <p>A video that does not say how long it is gives its first frame, a still; so does one where only one of the
     * frames asked for decodes. A frame that will not decode is left out rather than failing the rest.
     */
    public static Decoded slides(Path path, int count, int side, int millis) {
        return slides(path, count, side, millis, (identity, first) -> { });
    }

    /**
     * {@link #slides(Path, int, int, int)}, handing {@code first} the first frame that decodes, as a still, the moment
     * it does, with what the file is: something to show while the rest are found, which in a long HD video is most of
     * the wait. Called at most once, on the calling thread, and not at all for a slideshow of one frame.
     */
    public static Decoded slides(Path path, int count, int side, int millis, BiConsumer<String, Picture> first) {
        if (count < 1 || millis < 1) {
            throw new IllegalArgumentException("count " + count + ", millis " + millis);
        }
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        int fits = Picture.SHEET_SIDE / Math.max(columns, rows);
        Opened opened = open(path, side <= 0 ? fits : Math.min(side, fits));
        if (!opened.video()) {
            return Decoded.none(path, opened.identity());
        }
        try (Clip clip = opened.clip()) {
            Video video = clip.video();
            VideoInfo info = video.info();
            List<Frame> frames = new ArrayList<>(count);
            if (info.timed() && info.durationMicros() > 0) {
                for (int k = 0; k < count; k++) {
                    try {
                        video.seek((long) (info.durationMicros() * (k + 0.5) / count));
                        Frame f = video.next();
                        if (f != null) {
                            frames.add(f);
                            if (frames.size() == 1 && count > 1) {
                                first.accept(opened.identity(), sheet(clip.stamp(), frames, millis, info));
                            }
                        }
                    } catch (VideoException e) {
                        LOG.debug("{} gave no frame {} of {}: {}", path, k, count, e.getMessage());
                    }
                }
            }
            if (frames.isEmpty()) {
                video.seek(0);
                Frame f = video.next();
                if (f == null) {
                    return Decoded.none(path, "a video with no frames");
                }
                frames.add(f);
            }
            return new Decoded(path, opened.identity(), List.of(sheet(clip.stamp(), frames, millis, info)));
        } catch (VideoException e) {
            return Decoded.none(path, "does not decode as a video");
        } catch (OutOfMemoryError e) {
            LOG.warn("{} is too large to decode", path);
            return Decoded.none(path, "too large to decode");
        }
    }

    /** {@code frames}, all one size, packed as {@link Picture#of} packs an animation: a square-ish grid, row by row. */
    static Picture sheet(Stamp stamp, List<Frame> frames, int millis, VideoInfo info) {
        int n = frames.size();
        int fw = frames.get(0).width();
        int fh = frames.get(0).height();
        int columns = (int) Math.ceil(Math.sqrt(n));
        int rows = (n + columns - 1) / columns;
        int sheetW = fw * columns;
        byte[] sheet = new byte[sheetW * fh * rows * 4];
        int[] delays = new int[n];
        for (int k = 0; k < n; k++) {
            byte[] rgba = frames.get(k).rgba();
            int x = (k % columns) * fw;
            int y = (k / columns) * fh;
            for (int r = 0; r < fh; r++) {
                System.arraycopy(rgba, r * fw * 4, sheet, ((y + r) * sheetW + x) * 4, fw * 4);
            }
            delays[k] = n > 1 ? millis : 0;
        }
        return new Picture(stamp, sheet, fw, fh, columns, rows, delays, info.width(), info.height(), false);
    }

    /** A frame's size for a {@code w x h} video with the long side at most {@code side}: never larger, never zero. */
    static int[] fit(int w, int h, int side) {
        if (side <= 0 || Math.max(w, h) <= side) {
            return new int[] {w, h};
        }
        double scale = (double) side / Math.max(w, h);
        return new int[] {Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    /** "H.264 video · 1920 × 1080 · 1:02:07". */
    static String describe(VideoInfo info) {
        StringBuilder s = new StringBuilder(codec(info.codec())).append(" video · ").append(info.width())
                .append(" × ").append(info.height());
        if (info.timed()) {
            s.append(" · ").append(clock(info.durationMicros()));
        }
        return s.toString();
    }

    /** A codec as people write it: FFmpeg's short names are lower case, and H.264's has lost its dot. */
    static String codec(String name) {
        return switch (name) {
            case "h264" -> "H.264";
            case "hevc" -> "HEVC";
            case "mpeg4" -> "MPEG-4";
            case "mpeg2video" -> "MPEG-2";
            case "mpeg1video" -> "MPEG-1";
            case "prores" -> "ProRes";
            case "" -> "Unknown";
            default -> name.toUpperCase(Locale.ROOT);
        };
    }

    /** {@code micros} as a clock reads it: "0:42", "12:03", "1:02:07". */
    public static String clock(long micros) {
        long seconds = Math.max(0, micros) / 1_000_000;
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long sec = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static void unavailable(Throwable e) {
        if (!decoderReported) {
            decoderReported = true;
            LOG.warn("no videos: the video decoder is unavailable ({})", e.toString());
        }
    }
}
