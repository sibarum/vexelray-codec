package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An SVG mask rasterised and made into a field: the edge where the SVG draws it. */
class MaskFieldTest {

    @Test
    void aSquareMaskHasItsEdgeAtOneHalf() {
        String svg = "<svg color=\"#e9ecf2\" width=\"16\" height=\"16\" viewBox=\"0 0 16 16\" "
                + "xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"4\" y=\"4\" width=\"8\" height=\"8\" "
                + "fill=\"currentColor\"/></svg>";
        MaskField f = MaskField.of("square", svg.getBytes(StandardCharsets.UTF_8), 32, 8);
        assertNotNull(f, "the mask rasterises");
        assertEquals(48, f.side());
        byte[] p = f.picture().sheet();
        int centre = value(p, 48, 24, 24);
        int edge = value(p, 48, 16, 24);     // the square's left edge is at texel 8 + 8 = 16 of the field
        int outside = value(p, 48, 4, 24);
        assertTrue(centre > 200, "deep inside reads high: " + centre);
        assertEquals(128, edge, 20, "the edge reads one half");
        assertTrue(outside < 60, "well outside reads low: " + outside);
    }

    private static int value(byte[] rgba, int side, int x, int y) {
        return rgba[(y * side + x) * 4] & 0xFF;
    }
}
