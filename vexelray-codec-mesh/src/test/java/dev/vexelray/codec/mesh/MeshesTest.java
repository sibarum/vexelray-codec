package dev.vexelray.codec.mesh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The front door: a format told from the bytes, and every failure a reason. */
class MeshesTest {

    private static final String TRIANGLE_OBJ = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
    private static final String TRIANGLE_OFF = "OFF\n3 1 0\n0 0 0\n1 0 0\n0 1 0\n3 0 1 2\n";

    @Test
    void aFileIsReadWithItsIdentity(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("tri.obj");
        Files.writeString(f, TRIANGLE_OBJ);
        Loaded l = Meshes.of(f);
        assertTrue(l.geometry());
        assertEquals(f, l.source());
        assertEquals("OBJ mesh · 3 vertices · 1 face", l.identity());
    }

    @Test
    void theBytesDecideNotTheName() {
        assertEquals(Meshes.Format.OFF, Meshes.probe(bytes(TRIANGLE_OFF), "lies.stl"));
        assertEquals(Meshes.Format.PLY, Meshes.probe(bytes("ply\nformat ascii 1.0\n"), "lies.obj"));
        assertEquals(Meshes.Format.OBJ, Meshes.probe(bytes("# exported\n\nmtllib a.mtl\n"), "untitled"),
                "an OBJ statement with no .obj name");
        assertEquals(Meshes.Format.OBJ, Meshes.probe(bytes(""), "empty.obj"), "a name is enough for OBJ alone");
        assertNull(Meshes.probe(bytes("hello world\n"), "notes.txt"));
    }

    @Test
    void failuresAreReasons(@TempDir Path dir) throws IOException {
        assertEquals("a folder", Meshes.of(dir).identity());
        assertEquals("unreadable: NoSuchFileException", Meshes.of(dir.resolve("gone.ply")).identity());
        assertEquals("not a mesh", Meshes.of("notes.txt", bytes("hello world\n")).identity());
        assertEquals("no such resource", Meshes.resource(MeshesTest.class, "/nowhere.obj").identity());
        Loaded none = Meshes.of("x.ply", bytes("ply\n"));
        assertFalse(none.geometry());
        assertNull(none.mesh());
        assertEquals("malformed PLY: the header has no end_header", none.identity());
    }

    @Test
    void anEmptyObjIsAnEmptyMesh() {
        Loaded l = Meshes.of("empty.obj", new byte[0]);
        assertEquals("OBJ mesh · 0 vertices · 0 faces", l.identity());
    }

    @Test
    void namesAndExtensions() {
        assertEquals(List.of("obj", "off", "ply", "stl"), Meshes.extensions());
        assertTrue(Meshes.named(Path.of("models", "Bunny.PLY")));
        assertFalse(Meshes.named(Path.of("bunny.png")));
        assertFalse(Meshes.named(Path.of("obj")));
    }

    @Test
    void binaryJunkIsNotAMesh() {
        byte[] junk = new byte[1000];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (i * 37);
        }
        assertEquals("not a mesh", Meshes.of("junk.bin", junk).identity());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
