package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * Values on a regular 3D grid: densities, temperatures, a fluid's velocity, or a signed distance to a solid's surface
 * (a channel called {@value #DISTANCE}, positive inside, in the volume's own units). Every channel has one value per
 * voxel, x fastest, then y, then z.
 *
 * <p>Voxel {@code (i, j, k)}'s centre is at {@code origin + (i + 0.5, j + 0.5, k + 0.5) * voxel size}.
 *
 * @param name     what the asset calls it
 * @param width    voxels along x
 * @param height   voxels along y
 * @param depth    voxels along z
 * @param originX  the grid's corner, x
 * @param originY  the grid's corner, y
 * @param originZ  the grid's corner, z
 * @param voxelX   one voxel's size along x
 * @param voxelY   one voxel's size along y
 * @param voxelZ   one voxel's size along z
 * @param channels one value per voxel each
 */
@IntrabufMessage(name = "vexelray.asset.Volume", compat = Compat.FULL)
public record Volume(String name, int width, int height, int depth,
                     float originX, float originY, float originZ,
                     float voxelX, float voxelY, float voxelZ,
                     @Field(nonNull = true) List<Channel> channels) {

    /** The conventional name of a signed distance channel. */
    public static final String DISTANCE = "distance";

    public Volume {
        channels = List.copyOf(channels);
    }

    /** A grid of unit voxels from the origin, carrying {@code channels}. */
    public static Volume of(String name, int width, int height, int depth, Channel... channels) {
        return new Volume(name, width, height, depth, 0, 0, 0, 1, 1, 1, List.of(channels));
    }

    /** How many voxels. */
    public long voxels() {
        return (long) width * height * depth;
    }

    /** The channel called {@code name}, or null. */
    public Channel channel(String name) {
        return MeshData.channel(channels, name);
    }
}
