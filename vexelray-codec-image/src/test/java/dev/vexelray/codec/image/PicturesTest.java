package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pictures across the native boundary: these decode for real, so a missing imagelib fails here rather than passing
 * quietly. The raster fixtures are encoded by ImageIO at test time, so each is a real file of the format it claims.
 */
class PicturesTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
            + "<rect width=\"40\" height=\"20\" fill=\"#0a0\"/></svg>";

    @Test
    void aPngIsAPictureWithItsSizeAndItsPixels(@TempDir Path dir) throws IOException {
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);
        Path f = dir.resolve("red.png");
        ImageIO.write(img, "png", f.toFile());

        Decoded p = Pictures.of(f, 1024);
        assertTrue(p.image());
        assertEquals(f, p.source());
        assertEquals("PNG image · 3 × 2", p.identity());
        Picture pic = p.picture();
        assertEquals(3, pic.frameWidth(), "never scaled up");
        assertEquals(255, pic.sheet()[0] & 0xFF, "the first pixel is red");
        assertEquals(0, pic.sheet()[7] & 0xFF, "the second is transparent");
        assertEquals(Stamp.of(f), pic.key());
        assertNull(p.picture(1), "one size was asked for");
    }

    @Test
    void everySizeComesFromTheSameDecode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("huge.png");
        ImageIO.write(new BufferedImage(4000, 2000, BufferedImage.TYPE_INT_ARGB), "png", f.toFile());

        Decoded both = Pictures.of(f, 1024, 2560);
        assertEquals(1024, both.picture().frameWidth());
        assertEquals(2560, both.picture(1).frameWidth());
        assertEquals(1280, both.picture(1).frameHeight());
        assertEquals(2000, both.picture(1).sourceHeight(), "the header still says what the file is");
        assertEquals(both.picture().bytes() + both.picture(1).bytes(), both.bytes());
    }

    @Test
    void noSizeIsTheSourcesOwnAsFarAsASheetHolds(@TempDir Path dir) throws IOException {
        Path small = dir.resolve("small.png");
        ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB), "png", small.toFile());
        assertEquals(300, Pictures.of(small).picture().frameWidth());

        Path huge = dir.resolve("huge.png");
        ImageIO.write(new BufferedImage(5000, 100, BufferedImage.TYPE_INT_ARGB), "png", huge.toFile());
        assertEquals(Picture.SHEET_SIDE, Pictures.of(huge).picture().frameWidth());
    }

    @Test
    void aVectorIsRasterisedOnceAtTheLargestSizeAndAtItsOwnAspect(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mark.svg");
        Files.writeString(f, SVG, StandardCharsets.UTF_8);
        Decoded p = Pictures.of(f, 1024, 2560);
        assertEquals("SVG image · 40 × 20", p.identity());
        assertTrue(p.picture().vector());
        assertEquals(2560, p.picture(1).frameWidth(), "scaled up: a vector has no size of its own");
        assertEquals(1280, p.picture(1).frameHeight());
        assertEquals(1024, p.picture().frameWidth(), "the smaller is a shrink of it");

        assertEquals(40, Pictures.of(f).picture().frameWidth(), "no size asked: its own");
    }

    @Test
    void bytesInHandAreKeyedByTheirName() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", png);

        Decoded p = Pictures.of("logo", png.toByteArray(), 64);
        assertTrue(p.image());
        assertEquals("logo", p.source());
        assertEquals("logo", p.picture().key());

        Decoded svg = Pictures.of("mark", SVG.getBytes(StandardCharsets.UTF_8), 80);
        assertEquals(80, svg.picture().frameWidth());

        assertEquals("not an image", Pictures.of("junk", new byte[] {1, 2, 3}).identity());
    }

    @Test
    void aResourceIsReadOffTheClassPath() {
        Decoded p = Pictures.resource(PicturesTest.class, "/anim.gif");
        assertTrue(p.image(), p.identity());
        assertTrue(p.picture().animated());
        assertEquals("GIF image · 4 × 3 · 2 frames, 0.1 s", p.identity());
        assertTrue(p.picture().key().toString().endsWith("anim.gif"));

        assertEquals("no such resource", Pictures.resource(PicturesTest.class, "/nowhere.png").identity());
    }

    @Test
    void whatIsNotAPictureSaysWhy(@TempDir Path dir) throws IOException {
        Path text = dir.resolve("notes.png");
        Files.writeString(text, "not a picture at all", StandardCharsets.UTF_8);
        Decoded p = Pictures.of(text, 256);
        assertFalse(p.image());
        assertNull(p.picture());
        assertEquals("not an image", p.identity());

        Path broken = dir.resolve("broken.png");
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        Files.write(broken, head);
        assertEquals("does not decode as an image", Pictures.of(broken).identity());

        assertTrue(Pictures.of(dir.resolve("gone.png")).identity().startsWith("unreadable"));
        assertEquals("a folder", Pictures.of(dir).identity());
    }

    @Test
    void anEmptyFileOrATruncatedAnimationIsAReasonNotAnException(@TempDir Path dir) throws IOException {
        Path empty = Files.createFile(dir.resolve("empty.png"));
        assertEquals("not an image", Pictures.of(empty, 64).identity());

        // The header and the start of the first frame of an animated GIF: it says it may play, and has no frame.
        byte[] gif = PicturesTest.class.getResourceAsStream("/anim3.gif").readAllBytes();
        Path cut = dir.resolve("cut.gif");
        Files.write(cut, java.util.Arrays.copyOf(gif, 20));
        Decoded d = Pictures.of(cut, 64);
        assertFalse(d.image());
        assertEquals("does not decode as an image", d.identity());
    }

    @Test
    void aSideOfNothingOrLessIsTheSourcesOwn(@TempDir Path dir) throws IOException {
        Path png = dir.resolve("small.png");
        ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        Decoded d = Pictures.of(png, 0, -5);
        assertEquals(300, d.picture().frameWidth(), "not a pixel across");
        assertEquals(300, d.picture(1).frameWidth());

        Path svg = dir.resolve("mark.svg");
        Files.writeString(svg, SVG, StandardCharsets.UTF_8);
        assertEquals(40, Pictures.of(svg, 0).picture().frameWidth());
        assertEquals(80, Pictures.of(svg, -1, 80).picture(1).frameWidth(), "the largest asked still rules");
    }

    @Test
    void namesAreMatchedByExtensionIgnoringCase() {
        assertTrue(Pictures.named(Path.of("IMG_1.JPG")));
        assertTrue(Pictures.named(Path.of("a.webp")));
        assertTrue(Pictures.named(Path.of("a.svgz")));
        assertFalse(Pictures.named(Path.of("clip.mp4")));
        assertFalse(Pictures.named(Path.of("photo.avif")), "imagelib leaves AVIF out, so a listing does too");
        assertFalse(Pictures.named(Path.of(".png")), "a dotfile is not an image named png");
        assertNotNull(Pictures.of(Path.of("nowhere", "x.png")));
    }

    @Test
    void anAnimationIsDescribedByItsFramesAndLength() {
        Picture p = new Picture(new Stamp(Path.of("a.gif"), 1, 1), new byte[4 * 4 * 4], 2, 2, 2, 2,
                new int[] {500, 500, 500}, 2, 2, false);
        assertEquals("GIF image · 2 × 2 · 3 frames, 1.5 s", Pictures.describe("GIF image", p));
    }

    @Test
    void aVectorBoxKeepsItsAspectAndIsNeverEmpty() {
        int side = 1024;
        assertEquals(side, Pictures.vectorBox(10, 40, side)[1]);
        assertEquals(side / 4, Pictures.vectorBox(10, 40, side)[0]);
        assertEquals(1, Pictures.vectorBox(1, 100000, side)[0]);
        assertEquals(side, Pictures.vectorBox(0, 0, side)[0]);
    }
}
