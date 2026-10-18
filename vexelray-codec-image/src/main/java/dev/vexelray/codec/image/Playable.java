package dev.vexelray.codec.image;

/**
 * Frames by number, each with its time, made ahead of the clock on a thread of their own: what a {@code Playback}
 * plays. A {@link Movie} is one, made from an animated GIF, WebP or APNG; a video is another, made by
 * {@code vexelray-codec-video}. Whoever shows it says which frame it wants ({@link #want}) and takes the frames that
 * are ready ({@link #frame}), and never waits for a decode.
 *
 * <p>Times are in ms from the start of a pass. Every method may be called from any thread.
 */
public interface Playable extends AutoCloseable {

    /** The size every frame is made at. */
    int width();

    int height();

    /** How many frames can be shown: as many as the file declared, or fewer if it turned out damaged after them. */
    int count();

    /** One pass, in ms. */
    long duration();

    /** How long frame {@code i} is shown, in ms. */
    int delay(int i);

    /** When frame {@code i} starts, in ms from the start of a pass. */
    long startOf(int i);

    /** The frame shown {@code ms} into a pass: the last one there is, at or past the end. */
    int frameAt(long ms);

    /**
     * Make frame {@code i} next, and a few after it: round to frame 0 past the end with {@code wrap}, or stopping at
     * the last frame without.
     */
    void want(int i, boolean wrap);

    /**
     * Frame {@code i}'s mip chain ({@code MipChain}), {@link #width} by {@link #height} at level 0, or null if it has
     * not been made. The arrays are live: do not write to them.
     */
    byte[][] frame(int i);

    /** Why the file stopped decoding early, or null if it has not. */
    String failure();

    /** Stop decoding and close the file. Closing twice does nothing. */
    @Override
    void close();
}
