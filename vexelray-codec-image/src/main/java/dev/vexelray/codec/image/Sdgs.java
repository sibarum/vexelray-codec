package dev.vexelray.codec.image;

import dev.vexelray.fields.art.Raster;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.fields.sdg.SdgFile;
import dev.vexelray.fields.sdg.SdgRender;
import dev.vexelray.gui.core.ImageSdg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SDG drawings as pictures ({@code .sdg} XML, {@code .sdgb} compiled; vexelray-fields' {@code docs/sdg.md}). A
 * drawing is decoded to {@link Picture}s like any image, so a cache, a thumbnail and a filmstrip take it as they take
 * any other; each carries the drawing itself ({@link Picture#drawing}), which a view draws natively, and a poster drawn
 * by {@link SdgRender}, the format's reference renderer, for whatever shows pixels.
 */
final class Sdgs {

    /**
     * The largest poster side: a drawing is shown by drawing it, so its poster is for thumbnails and the like, and
     * drawing a 4096 px one on the CPU for nothing would cost a second. {@code -Dcodec.sdg.poster=<px>} sets it
     * otherwise: a small one shows at once whether a view draws the drawing or its poster.
     */
    static final int POSTER_SIDE = Integer.getInteger("codec.sdg.poster", 1024);

    private Sdgs() {
    }

    /**
     * Whether {@code name} and {@code sample}, a file's first bytes, are an SDG drawing: the compiled form's signature,
     * or an {@code <sdg} element in the first bytes of a file named {@code .sdg}, or of any file that has one before
     * anything else of note (an XML declaration and comments aside).
     */
    static boolean is(String name, byte[] sample) {
        if (startsWith(sample, SdgFile.SIGNATURE)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String head = new String(sample, 0, Math.min(sample.length, 4096), StandardCharsets.UTF_8);
        int root = head.indexOf("<sdg");
        if (root < 0) {
            return false;
        }
        if (lower.endsWith("." + SdgFile.XML)) {
            return true;
        }
        // An SVG with an element named sdg in it is still an SVG: only a document whose root is <sdg> is one.
        String before = head.substring(0, root).replaceAll("(?s)<\\?.*?\\?>|<!--.*?-->", "").strip();
        return before.isEmpty();
    }

    /** {@code sdg} as pictures at each of {@code sides} (0 or none for its own size, up to {@link #POSTER_SIDE}). */
    static Decoded decode(Object source, Object key, Sdg sdg, int[] sides) {
        ImageSdg drawing = ImageSdg.of(sdg);   // one for every size: its pages upload once
        int ownW = (int) Math.max(1, Math.round(sdg.width())), ownH = (int) Math.max(1, Math.round(sdg.height()));
        List<Picture> pictures = new ArrayList<>();
        int[] asked = sides.length == 0 ? new int[]{0} : sides;
        for (int s : asked) {
            int side = Math.min(POSTER_SIDE, s > 0 ? s : Math.max(ownW, ownH));
            int[] box = Pictures.vectorBox((float) sdg.width(), (float) sdg.height(), side);
            Raster poster = SdgRender.render(sdg, box[0], box[1]);
            pictures.add(new Picture(key, poster.rgba(), box[0], box[1], 1, 1, new int[1], ownW, ownH, true, null,
                    drawing));
        }
        int layers = sdg.layers().size();
        String what = "SDG drawing · " + layers + " layer" + (layers == 1 ? "" : "s");
        return new Decoded(source, Pictures.describe(what, pictures.getFirst()), pictures);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
