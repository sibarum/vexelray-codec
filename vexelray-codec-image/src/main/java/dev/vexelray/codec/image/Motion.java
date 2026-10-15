package dev.vexelray.codec.image;

/**
 * That a picture is the first frame of a file that plays, and what the file says about its playing: what a
 * {@link Picture} carries instead of every frame, so a long or large animation costs one frame to list and show, and
 * is played from the file by a {@link Movie} when it is shown.
 *
 * @param stamp          the file, at the version the poster was made from
 * @param frames         how many frames it declares
 * @param durationMillis one pass through them, as a player times it ({@link Movie#timed}: a delay too short to mean
 *                       anything is shown at 100 ms, as browsers show it)
 * @param loops          how many times the file asks to be played: 0 for forever
 */
public record Motion(Stamp stamp, int frames, long durationMillis, int loops) {
}
