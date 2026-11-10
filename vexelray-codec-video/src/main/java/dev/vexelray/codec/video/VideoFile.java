package dev.vexelray.codec.video;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Stamp;

/**
 * A video that plays, as values: what a {@code @Job} that looked at a file answers with ({@link Videos#probe}), so it
 * can cross the bus where an open {@link Clip} cannot, and what a {@code VideoView} or a {@code Player} plays it from,
 * opening the file again on a thread of its own. Nothing here is open.
 *
 * @param stamp          the file, at the version looked at
 * @param side           the longest side its frames were asked for (0: its own size); it plays at that again
 * @param width          its frames' size at that side
 * @param height         the same, down
 * @param durationMicros its length
 * @param frameRate      frames a second
 * @param sound          whether it has a sound to play with it
 * @param identity       what it is ("H.264 video · 1920 × 1080 · 0:42")
 */
public record VideoFile(Stamp stamp, int side, int width, int height, long durationMicros, double frameRate,
                    boolean sound, String identity) {

    /** How many frames it has: its length at its rate, at least one. */
    public int frames() {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(durationMicros * frameRate / 1e6)));
    }

    /**
     * What a {@code Player} plays it by: a motion that opens the file as {@link Footage}, with its sound on the
     * speakers when it has one, its frames {@link #width} by {@link #height}.
     */
    public Motion motion() {
        return new Motion(stamp, frames(), Math.max(1, durationMicros / 1000), 0, Footage.SOURCE, true);
    }
}
