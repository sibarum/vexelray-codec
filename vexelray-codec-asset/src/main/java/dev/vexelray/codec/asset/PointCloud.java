package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Points with no faces between them: a scan, a particle state, samples of a field. Every channel has one value per
 * point; {@code position}, three {@value Channel#F32} each, is always there.
 *
 * @param name     what the asset calls it
 * @param count    how many points
 * @param channels one value per point each, {@code position} first
 */
@IntrabufMessage(name = "vexelray.asset.PointCloud", compat = Compat.FULL)
public record PointCloud(String name, long count, @Field(nonNull = true) List<Channel> channels) {

    public PointCloud {
        channels = List.copyOf(channels);
    }

    /** Points at {@code xyz}, three floats each, and whatever else each carries. */
    public static PointCloud of(String name, float[] xyz, Channel... more) {
        if (xyz.length % 3 != 0) {
            throw new IllegalArgumentException("positions are three floats a point, got " + xyz.length);
        }
        List<Channel> all = new ArrayList<>(more.length + 1);
        all.add(Channel.f32("position", 3, xyz));
        all.addAll(List.of(more));
        return new PointCloud(name, xyz.length / 3, all);
    }

    /** The channel called {@code name}, or null. */
    public Channel channel(String name) {
        return MeshData.channel(channels, name);
    }

    /** Every point's x, y and z. */
    public float[] positions() {
        Channel p = channel("position");
        if (p == null) {
            throw new IllegalStateException("a point cloud with no position");
        }
        return p.floats();
    }
}
