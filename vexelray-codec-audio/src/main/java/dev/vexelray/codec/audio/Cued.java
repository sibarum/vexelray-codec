package dev.vexelray.codec.audio;

/**
 * What {@link Sounds#open} makes of a file: the track, cued at its start, or, when there is none, why not.
 *
 * @param source   the file's {@code Path}
 * @param identity what the file is ("MP3 sound · stereo · 44.1 kHz"), or why there is no track of it ("not sound",
 *                 "unreadable: AccessDeniedException")
 * @param track    the open track, which the caller now owns and closes; null when there is none
 */
public record Cued(Object source, String identity, Track track) {

    /** A source with no track, and the reason. */
    public static Cued none(Object source, String why) {
        return new Cued(source, why, null);
    }

    /** Whether there is a track. */
    public boolean sound() {
        return track != null;
    }
}
