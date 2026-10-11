package dev.vexelray.codec.image;

import java.util.List;

/**
 * One source's pixels, decided once and read-only: what it is, and its picture at each size asked for; or, when there
 * is no picture, why not. Produced by {@link Pictures}; nothing in the source is ever executed.
 *
 * @param source   what was decoded: the file's {@code Path}, or the name of bytes in hand
 * @param identity what the source is ("PNG image · 1920 × 1080"), or why there is no picture of it ("not an image",
 *                 "unreadable: AccessDeniedException")
 * @param pictures one per size asked for, in the order asked, all from the same decode; empty when there is none
 */
public record Decoded(Object source, String identity, List<Picture> pictures) {

    public Decoded {
        pictures = List.copyOf(pictures);
    }

    /** A source with no picture, and the reason. */
    public static Decoded none(Object source, String why) {
        return new Decoded(source, why, List.of());
    }

    /** Whether there is a picture. */
    public boolean image() {
        return !pictures.isEmpty();
    }

    /** The picture at the first size asked for, or null when there is none. */
    public Picture picture() {
        return pictures.isEmpty() ? null : pictures.getFirst();
    }

    /** The picture at the {@code i}th size asked for, or null when there is none. */
    public Picture picture(int i) {
        return i < pictures.size() ? pictures.get(i) : null;
    }

    /** What this holds in decoded pixels: the weight a cache is bounded by. */
    public long bytes() {
        long total = 0;
        for (Picture p : pictures) {
            total += p.bytes();
        }
        return total;
    }
}
