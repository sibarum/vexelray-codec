package dev.vexelray.codec.asset;

import dev.vexelray.codec.mesh.Attribute;
import dev.vexelray.codec.mesh.Mesh;

import java.util.List;

/** One of everything, made the same way every time: what the round trips and the golden file are made of. */
final class Samples {

    private Samples() {
    }

    /** A quad and a triangle sharing an edge, with a seam's uvs on the corners and a listed loose edge. */
    static Mesh mesh() {
        return Mesh.builder()
                .positions(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 2, 0.5f, 0})
                .face(0, 1, 2, 3)
                .face(1, 4, 2)
                .edge(3, 4)
                .attribute(Attribute.NORMAL, Attribute.Scope.VERTEX, 3,
                        new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1})
                .attribute(Attribute.UV, Attribute.Scope.CORNER, 2,
                        new float[]{0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0.5f, 0, 1})
                .attribute(Attribute.COLOR, Attribute.Scope.FACE, 3, new float[]{1, 0, 0, 0, 0, 1})
                .build();
    }

    /** A canvas-style field: a disc, {@code inner} 8 and {@code spread} 4, as RGBA8 with the same byte in R, G and B. */
    static byte[] discRgba() {
        int side = 16;
        byte[] rgba = new byte[side * side * 4];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double d = 4 - Math.hypot(x + 0.5 - 8, y + 0.5 - 8);
                int v = (int) Math.round(Math.clamp(0.5 + d / 8, 0, 1) * 255);
                int i = (y * side + x) * 4;
                rgba[i] = rgba[i + 1] = rgba[i + 2] = (byte) v;
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return rgba;
    }

    static int[] spirv() {
        return new int[]{ShaderStage.SPIRV_MAGIC, 0x00010500, 0, 12, 0};
    }

    static Shader shader() {
        return new Shader("ui", List.of(
                ShaderStage.of(ShaderStage.CANVAS, ShaderStage.VERTEX, "main", spirv()),
                ShaderStage.of(ShaderStage.CANVAS, ShaderStage.FRAGMENT, "main", spirv())),
                List.of(new ShadeletInfo("field", ShadeletInfo.SOURCE, 0, List.of(), 0x11L),
                        new ShadeletInfo("fill", ShadeletInfo.PAINTER, 1,
                                List.of(new StyleField("color", 4, false), new StyleField("opacity", 1, true)),
                                0x22L)));
    }

    static Look look() {
        return new Look("icon", "disc", ShadeletUse.of("field", 0x11L),
                List.of(), List.of(ShadeletUse.of("fill", 0x22L, 0.2f, 0.4f, 0.6f, 1f)));
    }

    static Asset asset() {
        float[] density = new float[2 * 3 * 4];
        for (int i = 0; i < density.length; i++) {
            density[i] = i * 0.25f;
        }
        byte[] texels = new byte[4 * 2 * 4];
        for (int i = 0; i < texels.length; i++) {
            texels[i] = (byte) (i * 7);
        }
        byte[] voxels = new byte[2 * 2 * 2];
        for (int i = 0; i < voxels.length; i++) {
            voxels[i] = (byte) i;
        }
        return Asset.builder("sampler")
                .property("units", "metres")
                .property("up", "+y")
                .mesh(MeshData.of("panel", mesh()))
                .pointCloud(PointCloud.of("scan", new float[]{1, 2, 3, 4, 5, 6},
                        Channel.f32("intensity", 1, new float[]{0.5f, 0.75f})))
                .volume(new Volume("smoke", 2, 3, 4, -1, -1.5f, -2, 1, 1, 1,
                        List.of(Channel.f32("density", 1, density))))
                .texture(Texture.of2d("checker", Texture.RGBA8_SRGB, 4, 2, texels))
                .texture(Texture.of("lut", Texture.R8, 2, 2, 2, voxels))
                .field(DistanceField.ofRgba("disc", discRgba(), 8, 4))
                .shader(shader())
                .look(look())
                .build();
    }
}
