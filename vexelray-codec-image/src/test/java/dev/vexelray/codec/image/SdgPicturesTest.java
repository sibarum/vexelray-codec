package dev.vexelray.codec.image;

import dev.vexelray.fields.art.Raster;
import dev.vexelray.fields.art.Svg;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.fields.sdg.SdgFile;
import dev.vexelray.fields.sdg.SdgRender;
import dev.vexelray.fields.sdg.SvgToSdg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDG drawings as pictures: both forms read, a poster at each size, and the drawing carried for a view to draw. */
class SdgPicturesTest {

    static final String BADGE = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
              <rect x="4" y="4" width="92" height="42" rx="10" fill="#2b5fd9"/>
              <circle cx="25" cy="25" r="12" fill="#ffcc33"/>
            </svg>
            """;

    static Sdg badge() {
        Svg svg = Svg.parse(BADGE);
        return SvgToSdg.convert(svg, SvgToSdg.Options.across(svg, 64));
    }

    @Test
    void bothFormsArePicturesCarryingTheirDrawing(@TempDir Path dir) throws IOException {
        Sdg sdg = badge();
        Path xml = dir.resolve("badge.sdg"), compiled = dir.resolve("badge.sdgb");
        SdgFile.write(sdg, xml);
        SdgFile.write(sdg, compiled);
        for (Path file : new Path[]{xml, compiled}) {
            assertTrue(Pictures.named(file), file + " is listed as a picture");
            Decoded d = Pictures.of(file, 200, 64);
            assertTrue(d.image(), file + ": " + d.identity());
            assertTrue(d.identity().startsWith("SDG drawing · 2 layers · 100 × 50"), d.identity());
            Picture big = d.picture(0), small = d.picture(1);
            assertNotNull(big.drawing(), "the drawing comes with it, to be drawn");
            assertSame(big.drawing(), small.drawing(), "one drawing for every size: its pages upload once");
            assertTrue(big.vector() && big.enlarges(), "it has no pixel size of its own, and fills its stage");
            assertEquals(200, big.frameWidth());
            assertEquals(100, big.frameHeight());
            assertEquals(64, small.frameWidth());
            Raster ref = SdgRender.render(sdg, 200, 100);
            assertArrayEquals(ref.rgba(), big.sheet(), "the poster is the reference drawing at that size");
        }
    }

    @Test
    void bytesInHandAreReadToo() {
        Sdg sdg = badge();
        Decoded d = Pictures.of("badge", SdgFile.bytes(sdg), 50);
        assertTrue(d.image(), d.identity());
        assertNotNull(d.picture().drawing());
        Decoded x = Pictures.of("badge-xml", SdgFile.xml(sdg).getBytes(StandardCharsets.UTF_8), 50);
        assertTrue(x.image(), "an XML drawing in hand, its pages in it: " + x.identity());
    }

    @Test
    void aBrokenDrawingIsAReasonNotAThrow(@TempDir Path dir) throws IOException {
        Path bad = dir.resolve("broken.sdgb");
        Files.write(bad, java.util.Arrays.copyOf(SdgFile.SIGNATURE, SdgFile.SIGNATURE.length + 7));
        Decoded d = Pictures.of(bad, 64);
        assertFalse(d.image());
        assertTrue(d.identity().startsWith("unreadable"), d.identity());
    }

    @Test
    void anSvgIsNotTakenForADrawing() {
        assertFalse(Sdgs.is("a.svg", BADGE.getBytes(StandardCharsets.UTF_8)));
        assertFalse(Sdgs.is("note.txt", "an <sdg> in a sentence".getBytes(StandardCharsets.UTF_8)));
        assertTrue(Sdgs.is("x", "<?xml version=\"1.0\"?>\n<!-- a drawing -->\n<sdg xmlns=\"…\">"
                .getBytes(StandardCharsets.UTF_8)));
        assertNull(Pictures.of("a.svg", BADGE.getBytes(StandardCharsets.UTF_8), 32).picture().drawing(),
                "an SVG is still rasterised, with no drawing");
    }
}
