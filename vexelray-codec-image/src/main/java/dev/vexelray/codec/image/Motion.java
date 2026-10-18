package dev.vexelray.codec.image;

import java.io.IOException;

/**
 * That a picture is the first frame of a file that plays, and what the file says about its playing: what a
 * {@link Picture} carries instead of every frame, so a long or large animation costs one frame to list and show, and
 * is played from the file when it is shown, by what {@link #open} makes of it.
 *
 * @param stamp          the file, at the version the poster was made from
 * @param frames         how many frames it declares
 * @param durationMillis one pass through them, as a player times it ({@link Movie#timed}: a delay too short to mean
 *                       anything is shown at 100 ms, as browsers show it)
 * @param loops          how many times the file asks to be played: 0 for forever
 * @param source         what plays the file: {@link #ANIMATION}, a {@link Movie}, for an animated image; a video's
 *                       own, from {@code vexelray-codec-video}, for a video
 */
public record Motion(Stamp stamp, int frames, long durationMillis, int loops, Source source) {

    /** How a file that plays is opened, its frames at most {@code side} on their long side. */
    @FunctionalInterface
    public interface Source {
        Playable open(Motion motion, int side) throws IOException;
    }

    /** An animated GIF, WebP or APNG, played by a {@link Movie}. One instance, so two motions of a file are equal. */
    public static final Source ANIMATION = Movie::open;

    /** An animated image's motion, played by a {@link Movie}. */
    public Motion(Stamp stamp, int frames, long durationMillis, int loops) {
        this(stamp, frames, durationMillis, loops, ANIMATION);
    }

    /**
     * Open the file, its frames at most {@code side} on their long side. Blocking: run it on {@code Gui.offload()}.
     * Throws {@link IOException} if the file cannot be read, and a {@link RuntimeException} if it does not decode.
     * The caller closes what it gets.
     */
    public Playable open(int side) throws IOException {
        return source.open(this, side);
    }
}
