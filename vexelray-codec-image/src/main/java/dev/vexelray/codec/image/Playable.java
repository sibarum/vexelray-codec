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

    /**
     * Whether a frame not made by the time it is due is passed over, so the pictures keep up with the clock: true, the
     * default, for a video, whose time is real. False for an animated image, every frame of which is shown, the clock
     * waiting for a late one, so a machine too slow for it plays it slower rather than skipping.
     */
    default boolean drops() {
        return true;
    }

    /**
     * Whether {@link #frame}'s chains need their levels below the first: false when they are shown through a device
     * feed, whose mips the GPU makes, so a frame costs only its first level. Said once, before the first {@link #want};
     * a source that cannot leave them out may ignore it, since level 0 is all such a consumer reads. True until said.
     */
    default void mips(boolean wanted) {
    }

    /**
     * Ask for the frames as a decoder's planes, which a device feed converts on the GPU (vexelray-gui's
     * docs/plans/device-feed.md B), at {@code width x height}: the layout they come in from now, each {@link #frame}
     * then one array of the planes packed ({@code PlaneLayout.frameBytes}), or null when this source cannot, and its
     * frames stay RGBA. A video can when its decoder's format is one the GPU converts and its frames are not scaled.
     * May wait for the first frame to be decoded, so the player's thread asks, once, before its first {@link #want}.
     */
    default dev.vexelray.vulkan.present.PlaneLayout planes(int width, int height) {
        return null;
    }

    /**
     * Ask for the frames decoded straight into memory {@code lender} lends (a device feed's, which the GPU copies
     * from), so no byte is copied on the way: the layout they come in from now, each frame then reached through
     * {@link #lent} rather than {@link #frame}; or null when this source cannot, and it is asked for {@link #planes}
     * instead. As {@link #planes}, it may wait for the first frame, so the player's thread asks, once, before its first
     * {@link #want}. A source that says yes calls {@link Lender#letGo} once nothing it runs writes the memory again.
     */
    default dev.vexelray.vulkan.present.PlaneLayout decodeInto(int width, int height, Lender lender) {
        return null;
    }

    /**
     * Frame {@code i} in lent memory ({@link #decodeInto}): where it is, with a new reference to it that keeps its
     * memory from being decoded into again until {@linkplain #release released}; or null if it has not been made.
     */
    default Lent lent(int i) {
        return null;
    }

    /** Let go of a reference {@link #lent} gave. Any thread, and after {@link #close} too. */
    default void release(long token) {
    }

    /** Whether frame {@code i} has been made: {@link #frame} or {@link #lent} would give it now. */
    default boolean made(int i) {
        return frame(i) != null;
    }

    /**
     * Memory lent to a source to decode into by whoever shows its frames: a device feed's chunks of host-visible
     * memory, which its GPU copies each frame from where it was decoded. Every method may be called from any thread.
     */
    interface Lender {

        /**
         * The frames will come in {@code planes}: said by {@link #decodeInto}, on the asker's thread, before anything is
         * lent, so the memory is made for them. Once.
         */
        void stage(dev.vexelray.vulkan.present.PlaneLayout planes);

        /** A new chunk of {@code bytes}, mapped: its index, or -1 when no more can be lent. */
        int lend(long bytes);

        /** Chunk {@code index}, as {@link #lend} made it. */
        java.lang.foreign.MemorySegment chunk(int index);

        /**
         * Nothing the source runs will write the chunks again (its decoder is closed): whatever references the lender
         * still holds go to {@code release}, and the chunks are its to free. Once.
         */
        void letGo(java.util.function.LongConsumer release);
    }

    /**
     * A frame in lent memory: {@code token}, a reference to it ({@link #release}), and where it is, chunk
     * {@code chunk} with plane {@code p} starting {@code offsets[p]} bytes in and its rows {@code strides[p]} apart.
     */
    record Lent(long token, int chunk, long[] offsets, int[] strides) {
    }

    /**
     * A clock this keeps for itself, which whoever shows it keeps time by instead of its own; or null, the usual
     * case, when it has none. A video with sound has one: sound is heard at the speaker's own pace, so the frames
     * follow the sound, never the other way round.
     */
    default Clock clock() {
        return null;
    }

    /**
     * The time a {@link Playable} keeps, steered by whoever shows it: run while the frames are being played, stopped
     * whenever they are not, and moved with every seek. Every method may be called from any thread, and none throws:
     * a clock that has lost its means of keeping time keeps it by the wall from where it was.
     */
    interface Clock {

        /** Where it is, in ms into a pass. */
        long now();

        /** Start it ({@code true}) from where it is, or stop it there. Each is harmless when it is so already. */
        void run(boolean run);

        /** Put it at {@code ms} into a pass, running or stopped as it was. */
        void seek(long ms);

        /**
         * How loud what it keeps time by is heard, from 0 (silent) to 1 (as the file has it), running or stopped.
         * Silence does not stop it: the time is still kept. Nothing, for a clock with nothing to hear.
         */
        default void volume(float volume) {
        }

        /**
         * Dynamic range compression on what it keeps time by, one knob from 0 (as the file has it) to 1: dialogue up
         * and explosions down, through night mode at 0.5 to deliberately too much at 1. The time is unchanged by it.
         * Nothing, for a clock with nothing to hear.
         */
        default void compression(float amount) {
        }

        /**
         * Copy what was last heard into {@code stereo}, oldest first, left and right in turn, for a meter: the sample
         * frames copied, at most {@code stereo.length / 2}. None, for a clock with nothing to hear.
         */
        default int heard(float[] stereo) {
            return 0;
        }
    }

    /** Stop decoding and close the file. Closing twice does nothing. */
    @Override
    void close();
}
