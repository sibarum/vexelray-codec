package dev.vexelray.codec.image.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The checkerboard behind a transparent picture: only the dark squares are drawn, and none spill past the box. */
class CheckerTest {

    @Test
    void halfTheSquaresAreDrawnAndTheEdgesAreCut() {
        int c = ImageView.CHECK_PX;
        // Four squares across, three down: twelve squares, six of them dark.
        assertEquals(6, ImageView.checks(4 * c, 3 * c).size());
        // A box that ends mid-square still gets the squares that start inside it.
        assertEquals(3, ImageView.checks(2 * c + 1, 2 * c).size());
        assertEquals(0, ImageView.checks(0, 0).size());
    }
}
