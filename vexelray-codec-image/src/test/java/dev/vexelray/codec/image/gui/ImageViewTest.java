package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Stamp;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a picture sits on its stage: whole, centred, and a raster never larger than its own pixels. */
class ImageViewTest {

    @Test
    void aPictureIsShownWholeAndNeverEnlarged() {
        // 400 x 200 in a 1000 x 1000 stage: its own size, so 40% by 20%.
        var small = new Picture(null, new byte[0], 400, 200, 1, 1, new int[1], 400, 200, false);
        float[] f = ImageView.fraction(small, 1000f, 1000f);
        assertEquals(0.4f, f[0], 1e-6f);
        assertEquals(0.2f, f[1], 1e-6f);
        // The same in a 200 x 200 stage: limited by its width, so all of it across and half down.
        f = ImageView.fraction(small, 200f, 200f);
        assertEquals(1f, f[0], 1e-6f);
        assertEquals(0.5f, f[1], 1e-6f);
    }

    @Test
    void aVectorFillsItsStageAtItsOwnAspect() {
        var mark = new Picture(null, new byte[0], 40, 20, 1, 1, new int[1], 40, 20, true);
        float[] f = ImageView.fraction(mark, 1000f, 1000f);
        assertEquals(1f, f[0], 1e-6f);
        assertEquals(0.5f, f[1], 1e-6f);
    }

    @Test
    void aVideoFillsItsStageButAnAnimationIsNeverEnlarged() {
        var stamp = new Stamp(Path.of("clip"), 0L, 0L);
        var video = new Picture(null, new byte[0], 128, 72, 1, 1, new int[1], 128, 72, false,
                new Motion(stamp, 10, 1000, 0, Motion.ANIMATION, true));
        float[] f = ImageView.fraction(video, 1000f, 1000f);
        assertEquals(1f, f[0], 1e-6f);
        assertEquals(0.5625f, f[1], 1e-6f);
        var gif = new Picture(null, new byte[0], 128, 72, 1, 1, new int[1], 128, 72, false,
                new Motion(stamp, 10, 1000, 0));
        f = ImageView.fraction(gif, 1000f, 1000f);
        assertEquals(0.128f, f[0], 1e-6f);
        assertEquals(0.072f, f[1], 1e-6f);
    }
}
