package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each format read into the one Mesh. The fixtures are written here, text as text and binary through a ByteBuffer, so
 * each is exactly the bytes its format's spec describes.
 */
class FormatsTest {

    private static final float[] SQUARE = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};

    static Mesh read(String name, String text) {
        return read(name, text.getBytes(StandardCharsets.US_ASCII));
    }

    static Mesh read(String name, byte[] bytes) {
        Loaded l = Meshes.of(name, bytes);
        assertTrue(l.geometry(), () -> name + ": " + l.identity());
        return l.mesh();
    }

    // ---- OBJ ----

    @Test
    void objQuadsWithSharedUvsAreOnTheVertices() {
        Mesh m = read("square.obj", """
                # a square
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1 4/4/1
                """);
        assertArrayEquals(SQUARE, m.positions());
        assertArrayEquals(new int[]{0, 1, 2, 3}, m.face(0));
        Attribute uv = m.attribute(Attribute.UV, Scope.VERTEX);
        assertNotNull(uv, "every face agrees on each vertex's uv");
        assertArrayEquals(new float[]{0, 0, 1, 0, 1, 1, 0, 1}, uv.values());
        Attribute n = m.attribute(Attribute.NORMAL, Scope.VERTEX);
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1}, n.values());
    }

    @Test
    void objSeamsKeepTheVertexAndPutTheUvsOnTheCorners() {
        Mesh m = read("seam.obj", """
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 0.5 0
                vt 0.5 1
                vt 1 1
                f 1/1 2/2 3/3
                f 1/4 3/3 4/1
                """);
        assertEquals(4, m.vertexCount(), "vertex 1 has two uvs and is still one vertex");
        assertNull(m.attribute(Attribute.UV, Scope.VERTEX));
        Attribute uv = m.attribute(Attribute.UV, Scope.CORNER);
        assertEquals(6, uv.count());
        assertEquals(1f, uv.get(3, 0), "the second face's first corner");
        assertEquals(10, m.wireframe().length, "the diagonal is shared: five edges");
    }

    @Test
    void objNegativeIndicesCountBackAndLinesAreEdges() {
        Mesh m = read("rel.obj", """
                v 0 0 0
                v 1 0 0
                v 1 1 0
                f -3 -2 -1
                v 2 2 2
                l 3 4 1
                """);
        assertArrayEquals(new int[]{0, 1, 2}, m.face(0));
        assertArrayEquals(new int[]{2, 3, 3, 0}, m.edges(), "a polyline is its segments");
    }

    @Test
    void objVertexColoursAndMissingUvsAreNaN() {
        Mesh m = read("mixed.obj", """
                v 0 0 0 1 0 0
                v 1 0 0 0 1 0
                v 1 1 0 0 0 1
                v 0 1 0
                vt 0.25 0.75
                f 1/1 2/1 3/1
                f 1 3 4
                """);
        Attribute c = m.attribute(Attribute.COLOR, Scope.VERTEX);
        assertEquals(1f, c.get(0, 0));
        assertEquals(1f, c.get(2, 2));
        assertTrue(Float.isNaN(c.get(3, 0)), "a vertex without a colour");
        Attribute uv = m.attribute(Attribute.UV, Scope.CORNER);
        assertEquals(0.75f, uv.get(0, 1));
        assertTrue(Float.isNaN(uv.get(4, 0)), "a corner without a uv, in a file where others have one");
    }

    @Test
    void objFaultsAreNamedByLine() {
        assertEquals("malformed OBJ: line 3: a face needs three corners, this has 2",
                Meshes.of("bad.obj", "v 0 0 0\nv 1 0 0\nf 1 2\n".getBytes()).identity());
        assertEquals("malformed OBJ: line 2: a vertex index is 0; OBJ counts from 1",
                Meshes.of("bad.obj", "v 0 0 0\nf 0 1 1\n".getBytes()).identity());
        assertEquals("malformed OBJ: face 0 names vertex 5 of 3",
                Meshes.of("bad.obj", "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 6\n".getBytes()).identity());
    }

    // ---- PLY ----

    private static final String PLY_HEADER = """
            ply
            format %s 1.0
            comment a square, a quad, a loose edge
            element vertex 4
            property float x
            property float y
            property float z
            property float nx
            property float ny
            property float nz
            property uchar red
            property uchar green
            property uchar blue
            property float confidence
            element face 1
            property list uchar int vertex_indices
            property uchar red
            property uchar green
            property uchar blue
            element edge 1
            property int vertex1
            property int vertex2
            end_header
            """;

    private static void assertPlySquare(Mesh m) {
        assertArrayEquals(SQUARE, m.positions());
        assertArrayEquals(new int[]{0, 1, 2, 3}, m.face(0));
        assertArrayEquals(new int[]{0, 2}, m.edges());
        assertEquals(1f, m.attribute(Attribute.NORMAL, Scope.VERTEX).get(3, 2));
        Attribute c = m.attribute(Attribute.COLOR, Scope.VERTEX);
        assertEquals(3, c.components());
        assertEquals(1f, c.get(0, 0), "255 is 1");
        assertEquals(51 / 255f, c.get(1, 1), 1e-6);
        assertEquals(0.5f, m.attribute("confidence", Scope.VERTEX).get(2, 0), "kept under its own name");
        assertEquals(1f, m.attribute(Attribute.COLOR, Scope.FACE).get(0, 2), "a face's colour");
        assertEquals(List.of("position", "normal", "color", "confidence", "color"),
                m.attributes().stream().map(Attribute::name).toList());
    }

    @Test
    void plyAscii() {
        Mesh m = read("square.ply", PLY_HEADER.formatted("ascii") + """
                0 0 0 0 0 1 255 0 0 1
                1 0 0 0 0 1 0 51 0 0.75
                1 1 0 0 0 1 0 0 255 0.5
                0 1 0 0 0 1 255 255 255 0.25
                4 0 1 2 3 0 0 255
                0 2
                """);
        assertPlySquare(m);
    }

    @Test
    void plyBinaryEitherWayRound() {
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            String format = order == ByteOrder.LITTLE_ENDIAN ? "binary_little_endian" : "binary_big_endian";
            byte[] header = PLY_HEADER.formatted(format).getBytes(StandardCharsets.US_ASCII);
            ByteBuffer b = ByteBuffer.allocate(header.length + 4 * 31 + 1 + 16 + 3 + 8).order(order);
            b.put(header);
            int[][] rgb = {{255, 0, 0}, {0, 51, 0}, {0, 0, 255}, {255, 255, 255}};
            float[] confidence = {1, 0.75f, 0.5f, 0.25f};
            for (int v = 0; v < 4; v++) {
                b.putFloat(SQUARE[3 * v]).putFloat(SQUARE[3 * v + 1]).putFloat(SQUARE[3 * v + 2]);
                b.putFloat(0).putFloat(0).putFloat(1);
                b.put((byte) rgb[v][0]).put((byte) rgb[v][1]).put((byte) rgb[v][2]);
                b.putFloat(confidence[v]);
            }
            b.put((byte) 4).putInt(0).putInt(1).putInt(2).putInt(3).put((byte) 0).put((byte) 0).put((byte) 255);
            b.putInt(0).putInt(2);
            byte[] bytes = Arrays.copyOf(b.array(), b.position());
            assertPlySquare(read("square.ply", bytes));
        }
    }

    @Test
    void plyCornerTexcoordsAndUnknownElementsReadPast() {
        Mesh m = read("tex.ply", """
                ply
                format ascii 1.0
                element vertex 3
                property double x
                property double y
                property double z
                element material 1
                property list uchar float values
                element face 1
                property list uchar uint vertex_index
                property list uchar float texcoord
                end_header
                0 0 0
                1 0 0
                0 1 0
                3 0.1 0.2 0.3
                3 0 1 2 6 0 0 1 0 0 1
                """);
        assertArrayEquals(new int[]{0, 1, 2}, m.face(0));
        Attribute uv = m.attribute(Attribute.UV, Scope.CORNER);
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1}, uv.values());
    }

    @Test
    void aPointCloudPly() {
        Loaded l = Meshes.of("scan.ply", """
                ply
                format ascii 1.0
                element vertex 2
                property float x
                property float y
                property float z
                property float intensity
                end_header
                1 2 3 0.5
                4 5 6 0.25
                """.getBytes());
        assertEquals("PLY mesh · 2 vertices · 0 faces", l.identity());
        assertEquals(0.25f, l.mesh().attribute("intensity", Scope.VERTEX).get(1, 0));
    }

    @Test
    void plyFaults() {
        assertEquals("malformed PLY: the file ends before the elements its header declares",
                Meshes.of("short.ply", ("ply\nformat binary_little_endian 1.0\nelement vertex 2\nproperty float x\n"
                        + "property float y\nproperty float z\nend_header\n\0\0\0\0\0\0\0\0").getBytes()).identity());
        assertEquals("malformed PLY: vertex: 1000000 elements cannot fit in what is left of the file",
                Meshes.of("liar.ply", "ply\nformat ascii 1.0\nelement vertex 1000000\nproperty float x\nend_header\n0"
                        .getBytes()).identity());
        assertEquals("malformed PLY: the vertex element has no x, y and z",
                Meshes.of("noz.ply", ("ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\n"
                        + "end_header\n0 0\n").getBytes()).identity());
    }

    // ---- STL ----

    @Test
    void binaryStlWeldsIdenticalCorners() {
        ByteBuffer b = ByteBuffer.allocate(84 + 2 * 50).order(ByteOrder.LITTLE_ENDIAN);
        b.put("solid but binary all the same".getBytes(StandardCharsets.US_ASCII));
        b.position(80);
        b.putInt(2);
        float[][] tris = {{0, 0, 0, 1, 0, 0, 1, 1, 0}, {0, 0, 0, 1, 1, 0, 0, 1, -0f}};
        for (float[] t : tris) {
            b.putFloat(0).putFloat(0).putFloat(1);
            for (float f : t) {
                b.putFloat(f);
            }
            b.putShort((short) 0);
        }
        Loaded l = Meshes.of("square.stl", b.array());
        assertEquals("STL mesh · 4 vertices · 2 faces", l.identity(), "a header that says solid is still binary");
        Mesh m = l.mesh();
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, m.corners(), "the shared diagonal is shared");
        assertEquals(10, m.wireframe().length);
        assertEquals(1f, m.attribute(Attribute.NORMAL, Scope.FACE).get(1, 2));
        assertNull(m.attribute("stl_attribute", Scope.FACE), "all zero, so not kept");
    }

    @Test
    void asciiStl() {
        Mesh m = read("tri.stl", """
                solid my part
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 1 0 0
                      vertex 1 1 0
                    endloop
                  endfacet
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 1 1 0
                      vertex 0 1 0
                    endloop
                  endfacet
                endsolid my part
                """);
        assertEquals(4, m.vertexCount());
        assertEquals(2, m.faceCount());
        assertTrue(m.triangles());
    }

    @Test
    void asciiStlFaults() {
        assertEquals("malformed STL: line 3: expected vertex or endloop, found vertx",
                Meshes.of("bad.stl", "solid x\nfacet normal 0 0 1\nouter loop vertx 0 0 0\n".getBytes()).identity());
    }

    // ---- OFF ----

    @Test
    void offWithFaceColours() {
        Mesh m = read("square.off", """
                OFF
                # a square and a triangle
                5 2 0
                0 0 0
                1 0 0
                1 1 0
                0 1 0
                0.5 2 0
                4 0 1 2 3 255 0 0
                3 3 2 4
                """);
        assertArrayEquals(new int[]{0, 1, 2, 3}, m.face(0));
        assertArrayEquals(new int[]{3, 2, 4}, m.face(1));
        Attribute c = m.attribute(Attribute.COLOR, Scope.FACE);
        assertArrayEquals(new float[]{1, 0, 0, 1}, Arrays.copyOf(c.values(), 4), "0–255, alpha 1");
        assertTrue(Float.isNaN(c.get(1, 0)), "the triangle has no colour");
        assertFalse(m.edgeCount() > 0, "OFF lists no edges");
    }

    @Test
    void offVariantsCarryNormalsColoursAndUvs() {
        Mesh m = read("tri.off", """
                STCNOFF 3 1 3
                0 0 0  0 0 1  1 0 0 1  0 0
                1 0 0  0 0 1  0 1 0 1  1 0
                0 1 0  0 0 1  0 0 1 0.5  0 1
                3 0 1 2
                """);
        assertEquals(1f, m.attribute(Attribute.NORMAL, Scope.VERTEX).get(2, 2));
        assertEquals(0.5f, m.attribute(Attribute.COLOR, Scope.VERTEX).get(2, 3));
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1}, m.attribute(Attribute.UV, Scope.VERTEX).values());
    }

    @Test
    void fourDimensionalOffIsRefused() {
        assertEquals("malformed OFF: line 1: 4OFF is not three-dimensional; only 3D OFF is read",
                Meshes.of("x.off", "4OFF\n1 0 0\n0 0 0 0\n".getBytes()).identity());
    }

}
