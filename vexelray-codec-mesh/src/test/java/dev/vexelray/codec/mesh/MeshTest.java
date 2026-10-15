package dev.vexelray.codec.mesh;

import dev.vexelray.codec.mesh.Attribute.Scope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshTest {

    /** A unit cube: eight vertices, six quads. */
    static Mesh cube() {
        return Mesh.builder()
                .positions(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1})
                .face(0, 3, 2, 1).face(4, 5, 6, 7).face(0, 1, 5, 4)
                .face(2, 3, 7, 6).face(0, 4, 7, 3).face(1, 2, 6, 5)
                .build();
    }

    @Test
    void facesKeepTheirCornersAndOrder() {
        Mesh m = cube();
        assertEquals(8, m.vertexCount());
        assertEquals(6, m.faceCount());
        assertEquals(24, m.cornerCount());
        assertEquals(4, m.faceSize(2));
        assertEquals(8, m.faceStart(2));
        assertArrayEquals(new int[]{0, 1, 5, 4}, m.face(2));
        assertFalse(m.triangles(), "quads stay quads");
        assertEquals(0, m.edgeCount(), "a face's sides are not listed edges");
    }

    @Test
    void theWireframeIsEveryDistinctEdgeOnce() {
        int[] w = cube().wireframe();
        assertEquals(24, w.length, "a cube has twelve edges, each shared by two faces");
        for (int i = 0; i < w.length; i += 2) {
            assertTrue(w[i] < w[i + 1], "lower index first");
        }
        assertArrayEquals(new int[]{0, 1, 0, 3, 0, 4}, Arrays.copyOf(w, 6), "in ascending order");
    }

    @Test
    void listedEdgesJoinTheWireframeWithoutRepeating() {
        Mesh m = Mesh.builder()
                .positions(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 5, 5, 5})
                .face(0, 1, 2)
                .edge(1, 0)                 // a side of the face, listed again
                .edge(2, 3)                 // a loose edge to a vertex no face has
                .build();
        assertEquals(2, m.edgeCount());
        assertArrayEquals(new int[]{1, 0, 2, 3}, m.edges(), "listed edges as the file gave them");
        assertArrayEquals(new int[]{0, 1, 0, 2, 1, 2, 2, 3}, m.wireframe());
        assertSame(m.wireframe(), m.wireframe(), "worked out once");
    }

    @Test
    void attributesAreFoundByNameAndScope() {
        Mesh m = Mesh.builder()
                .positions(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
                .face(0, 1, 2)
                .attribute(Attribute.UV, Scope.CORNER, 2, new float[]{0, 0, 1, 0, 0, 1})
                .attribute(Attribute.COLOR, Scope.FACE, 3, new float[]{1, 0, 0})
                .build();
        assertEquals(Attribute.POSITION, m.attributes().getFirst().name());
        assertSame(m.positions(), m.attributes().getFirst().values());
        assertEquals(Scope.CORNER, m.attribute(Attribute.UV).scope(), "a corner uv when no vertex uv");
        assertNull(m.attribute(Attribute.COLOR), "a face colour is not a vertex's");
        assertEquals(1f, m.attribute(Attribute.COLOR, Scope.FACE).get(0, 0));
        assertNull(m.attribute(Attribute.NORMAL));
    }

    @Test
    void buildRefusesWhatDoesNotFit() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Mesh.builder()
                .positions(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}).face(0, 1, 2).face(0, 2, 3).build());
        assertEquals("face 1 names vertex 3 of 3", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> Mesh.builder()
                .positions(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}).face(0, 1, 2)
                .attribute(Attribute.NORMAL, Scope.VERTEX, 3, new float[]{0, 0, 1}).build());
        assertEquals("normal has 1 values for 3 vertex elements", e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> Mesh.builder().face(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Mesh.builder().edge(0, 4)
                .positions(new float[]{0, 0, 0}).build());
        assertThrows(IllegalArgumentException.class, () -> Mesh.builder()
                .attribute(Attribute.POSITION, Scope.VERTEX, 3, new float[0]));
    }

    @Test
    void aPointCloudIsAMeshWithNoFaces() {
        Mesh m = Mesh.builder().positions(new float[]{1, 2, 3, 4, 5, 6}).build();
        assertEquals(2, m.vertexCount());
        assertEquals(0, m.faceCount());
        assertTrue(m.triangles());
        assertEquals(0, m.wireframe().length);
    }
}
