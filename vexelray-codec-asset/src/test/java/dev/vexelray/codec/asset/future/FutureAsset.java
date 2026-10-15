package dev.vexelray.codec.asset.future;

import dev.vexelray.codec.asset.Channel;
import dev.vexelray.codec.asset.DistanceField;
import dev.vexelray.codec.asset.Look;
import dev.vexelray.codec.asset.MeshData;
import dev.vexelray.codec.asset.PointCloud;
import dev.vexelray.codec.asset.Shader;
import dev.vexelray.codec.asset.Volume;
import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;
import java.util.Map;

/**
 * {@code Asset} as a later version might have it: the same schema at minor 1, with a list of a kind of thing that does
 * not exist yet on the end, and its textures at their own later minor. What CompatibilityTest reads with today's
 * {@code Asset}, and what reads today's files.
 */
@IntrabufMessage(name = "vexelray.asset.Asset", minor = 1, compat = Compat.FULL)
public record FutureAsset(String name, @Field(nonNull = true) Map<String, String> properties,
                          @Field(nonNull = true) List<MeshData> meshes,
                          @Field(nonNull = true) List<PointCloud> pointClouds,
                          @Field(nonNull = true) List<Volume> volumes,
                          @Field(nonNull = true) List<FutureTexture> textures,
                          @Field(nonNull = true) List<DistanceField> fields,
                          @Field(nonNull = true) List<Shader> shaders,
                          @Field(nonNull = true) List<Look> looks,
                          @Field(since = 1, nonNull = true) List<Channel> splats) {
}
