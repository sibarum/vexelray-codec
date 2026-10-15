package dev.vexelray.codec.video;

/**
 * What {@link Videos#open} makes of a file: the clip, or, when there is none, why not.
 *
 * @param source   the file's {@code Path}
 * @param identity what the file is ("H.264 video · 1920 × 1080 · 0:42"), or why there is no clip of it ("not a
 *                 video", "unreadable: AccessDeniedException")
 * @param clip     the open clip, which the caller now owns and closes; null when there is none
 */
public record Opened(Object source, String identity, Clip clip) {

    /** A source with no clip, and the reason. */
    public static Opened none(Object source, String why) {
        return new Opened(source, why, null);
    }

    /** Whether there is a clip. */
    public boolean video() {
        return clip != null;
    }
}
