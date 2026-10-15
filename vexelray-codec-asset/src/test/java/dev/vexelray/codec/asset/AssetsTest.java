package dev.vexelray.codec.asset;

import dev.vexelray.codec.mesh.Attribute;
import dev.vexelray.codec.mesh.Mesh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every kind of item through a file and back, and every failure a reason. */
class AssetsTest {

    @Test
    void oneOfEverythingRoundTripsThroughAFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sampler.vxa");
        Assets.write(Samples.asset(), f);
        assertArrayEquals(Assets.SIGNATURE, Arrays.copyOf(Files.readAllBytes(f), 8));
        assertEquals(List.of(f), Files.list(dir).toList(), "no temporary file left beside it");

        Unpacked u = Assets.read(f);
        assertTrue(u.readable(), u.identity());
        assertEquals("sampler · 1 mesh · 1 point cloud · 1 volume · 2 textures · 1 distance field · 1 shader · 1 look",
                u.identity());
        assertSampler(u.asset());
    }

    @Test
    void theTextFormatReadsBackTheSame() {
        String text = Assets.toText(Samples.asset());
        assertTrue(text.startsWith("#intrabuf"), text.substring(0, 40));
        Unpacked u = Assets.read("sampler.txt", text.getBytes(StandardCharsets.UTF_8));
        assertTrue(u.readable(), u.identity());
        assertSampler(u.asset());
    }

    static void assertSampler(Asset a) {
        assertEquals("sampler", a.name());
        assertEquals(Map.of("units", "metres", "up", "+y"), a.properties());

        Mesh want = Samples.mesh();
        Mesh m = a.meshes().getFirst().toMesh();
        assertEquals("panel", a.meshes().getFirst().name());
        assertArrayEquals(want.positions(), m.positions());
        assertArrayEquals(want.corners(), m.corners());
        assertArrayEquals(new int[]{1, 4, 2}, m.face(1), "a quad and a triangle keep their sizes");
        assertArrayEquals(want.edges(), m.edges());
        for (Attribute w : want.attributes()) {
            Attribute got = m.attribute(w.name(), w.scope());
            assertArrayEquals(w.values(), got.values(), w.toString());
        }

        PointCloud p = a.pointClouds().getFirst();
        assertEquals(2, p.count());
        assertArrayEquals(new float[]{1, 2, 3, 4, 5, 6}, p.positions());
        assertArrayEquals(new float[]{0.5f, 0.75f}, p.channel("intensity").floats());

        Volume v = a.volumes().getFirst();
        assertEquals(24, v.voxels());
        assertEquals(-1.5f, v.originY());
        assertEquals(5.75f, v.channel("density").floats()[23]);

        Texture t = a.textures().getFirst();
        assertFalse(t.volumetric());
        assertEquals((byte) 7, t.texels(0)[1]);
        Texture lut = a.textures().get(1);
        assertTrue(lut.volumetric());
        assertEquals(8, lut.texels(0).length);

        DistanceField d = a.fields().getFirst();
        assertEquals(8, d.inner());
        assertArrayEquals(Samples.discRgba(), d.rgba(), "the canvas's texture again, exactly");

        Shader s = a.shaders().getFirst();
        assertArrayEquals(Samples.spirv(), s.stage(ShaderStage.CANVAS, ShaderStage.FRAGMENT).words());
        assertEquals(4, s.shadelet("fill").sharedFloats());
        assertEquals(1, s.shadelet("fill").instanceFloats());

        Look l = a.looks().getFirst();
        assertEquals("disc", l.subject());
        assertArrayEquals(new float[]{0.2f, 0.4f, 0.6f, 1f}, l.painters().getFirst().floats());
        assertNull(s.mismatch(l), "the look is drawn by the shader in the same file");
    }

    @Test
    void aDistanceFieldKeepsOneChannelOfFour() {
        byte[] rgba = Samples.discRgba();
        DistanceField d = DistanceField.ofRgba("disc", rgba, 8, 4);
        assertEquals(DistanceField.SDF, d.kind());
        assertEquals(rgba.length / 4, d.texels().size());
        assertTrue(d.texels().bytes().length < rgba.length / 4, "and packs that smaller still");
    }

    @Test
    void aLookIsCheckedAgainstTheShader() {
        Shader s = Samples.shader();
        assertEquals("no shadelet glow", s.mismatch(new Look(null, null, ShadeletUse.of("field", 0x11L), List.of(),
                List.of(ShadeletUse.of("glow", 0)))));
        assertEquals("fill is a painter, used as a source",
                s.mismatch(new Look(null, null, ShadeletUse.of("fill", 0x22L), List.of(), List.of())));
        assertEquals("fill's style was laid out otherwise when the look was written",
                s.mismatch(new Look(null, null, ShadeletUse.of("field", 0x11L), List.of(),
                        List.of(ShadeletUse.of("fill", 0x99L, 1, 1, 1, 1)))));
        assertEquals("fill takes 4 shared floats, given 1",
                s.mismatch(new Look(null, null, ShadeletUse.of("field", 0x11L), List.of(),
                        List.of(ShadeletUse.of("fill", 0x22L, 1)))));
    }

    @Test
    void anEmptyAsset() {
        Unpacked u = Assets.read("empty", Assets.toBytes(Asset.builder(null).build()));
        assertEquals("asset · empty", u.identity());
        assertEquals(0, u.asset().items());
    }

    @Test
    void failuresAreReasons(@TempDir Path dir) {
        assertEquals("a folder", Assets.read(dir).identity());
        assertEquals("unreadable: NoSuchFileException", Assets.read(dir.resolve("gone.vxa")).identity());
        assertEquals("not an asset file", Assets.read("x", "hello".getBytes()).identity());
        assertEquals("no such resource", Assets.resource(AssetsTest.class, "/nowhere.vxa").identity());

        byte[] whole = Assets.toBytes(Samples.asset());
        Unpacked cut = Assets.read("cut", Arrays.copyOf(whole, whole.length / 2));
        assertFalse(cut.readable());
        assertTrue(cut.identity().startsWith("malformed: "), cut.identity());

        byte[] notOurs = Arrays.copyOf(Assets.SIGNATURE, 20);
        notOurs[8] = 0x42;
        assertEquals("written by an incompatible version: NOT_INTRABUF", Assets.read("x", notOurs).identity());
    }

    @Test
    void anItemPackedByANewerWriterLeavesTheRestReadable() {
        Asset a = Asset.builder("mixed")
                .texture(new Texture("future", Texture.RGBA8, 1, 1, 1, List.of(new Packed("zstd", 4, new byte[3]))))
                .texture(new Texture("hdr", "rgb9e5", 1, 1, 1, List.of(Packed.raw(new byte[4]))))
                .texture(Texture.of2d("now", Texture.R8, 1, 1, new byte[]{9}))
                .build();
        Unpacked u = Assets.read("mixed", Assets.toBytes(a));
        assertTrue(u.readable());
        List<Texture> t = u.asset().textures();
        assertFalse(t.get(0).known(), "a codec it does not know");
        assertFalse(t.get(1).known(), "a format it does not know");
        assertTrue(t.get(2).known());
        assertEquals(9, t.get(2).texels(0)[0]);
    }

    @Test
    void names() {
        assertTrue(Assets.named(Path.of("models", "Ship.VXA")));
        assertFalse(Assets.named(Path.of("ship.obj")));
    }
}
