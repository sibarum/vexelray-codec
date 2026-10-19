package dev.vexelray.codec.image;

import java.util.function.Consumer;

/**
 * Single frames of a file that plays, asked for by time, one at a time: what a timeline shows under the pointer before
 * it is clicked. Made from a file of their own, never from the {@link Playable} being played, so looking ahead does not
 * move what is heard and seen. What a {@link Motion} opens with {@link Motion#glimpses}, for a source that has them.
 *
 * <p><b>The latest ask wins.</b> A pointer moving along a timeline asks many times a second; frames are made one at a
 * time on a thread of their own, and an ask not yet started when a newer one comes is dropped. A frame made recently is
 * handed over again at once.
 *
 * <p>Every method may be called from any thread. The caller closes it.
 */
public interface Glimpses extends AutoCloseable {

    /**
     * Make the frame showing {@code ms} into a pass and hand it to {@code then}, on the glimpses' own thread: a still
     * {@link Picture}, the same instance for the same frame while it is kept. Not called at all if a newer ask
     * overtakes this one, or the file stops decoding.
     */
    void want(long ms, Consumer<Picture> then);

    /** Stop making frames and close the file. Closing twice does nothing. */
    @Override
    void close();
}
