package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Stamp;
import sibarum.ffmpeg.Video;
import sibarum.ffmpeg.VideoInfo;

/**
 * An open video file, set to come out at the size it will be shown: what {@link Videos#open} makes and a {@link Reel}
 * plays. It holds the file and its decoder open, so it is closed, by the reel that plays it or by whoever opened it.
 *
 * <p>One thread at a time, as the decoder under it is: the thread that opened it until it is handed to a reel, and the
 * reel's own thread after.
 */
public final class Clip implements AutoCloseable {

    private final Stamp stamp;
    private final Video video;
    private final String identity;

    Clip(Stamp stamp, Video video, String identity) {
        this.stamp = stamp;
        this.video = video;
        this.identity = identity;
    }

    /** Which version of which file this is. */
    public Stamp stamp() {
        return stamp;
    }

    /** What the file said about itself: its own size, length, rate and codec. */
    public VideoInfo info() {
        return video.info();
    }

    /** "H.264 video · 1920 × 1080 · 0:42". */
    public String identity() {
        return identity;
    }

    /** The size its frames come out at: the file's own, or smaller to fit the side it was opened for. */
    public int width() {
        return video.outputWidth();
    }

    public int height() {
        return video.outputHeight();
    }

    /** The decoder, for the reel that plays this and for {@link Videos#poster}. */
    Video video() {
        return video;
    }

    public boolean closed() {
        return video.closed();
    }

    /** Close the file. Closing twice does nothing. */
    @Override
    public void close() {
        video.close();
    }

    @Override
    public String toString() {
        return "Clip[" + stamp.path().getFileName() + ", " + identity + ", at " + width() + "x" + height() + "]";
    }
}
