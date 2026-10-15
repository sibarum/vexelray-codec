package dev.vexelray.codec.asset;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one asset file holds: any number of each kind of thing the stack draws, side by side, each with a name the
 * others can refer to it by. {@link Assets} reads and writes it.
 *
 * <p><b>It only ever grows.</b> Every schema here is intrabuf FULL: a field is added at the end with
 * {@code @Field(since = n)} and the minor bumped, never removed, reordered or retyped. So a newer reader opens an older
 * file, the new fields empty, and an older reader opens a newer one, skipping what it does not know. A new kind of
 * thing is a new list on the end of this record. Where a value has an open-ended vocabulary (a codec, a texel format,
 * a channel type) it is a name rather than an enum, so a reader that meets a newer one cannot read that one item and
 * can still read the rest.
 *
 * @param name        what the asset is, or null
 * @param properties  anything else the writer wants said: the tool that made it, its units, which way is up
 * @param meshes      polygon meshes
 * @param pointClouds points with no faces
 * @param volumes     values on 3D grids, signed distances among them
 * @param textures    2D and 3D texture maps
 * @param fields      2D distance fields, compressed
 * @param shaders     compiled shadelet compositions
 * @param looks       how things are drawn, by shadelets
 */
@IntrabufMessage(name = "vexelray.asset.Asset", compat = Compat.FULL)
public record Asset(String name, @Field(nonNull = true) Map<String, String> properties,
                    @Field(nonNull = true) List<MeshData> meshes,
                    @Field(nonNull = true) List<PointCloud> pointClouds,
                    @Field(nonNull = true) List<Volume> volumes,
                    @Field(nonNull = true) List<Texture> textures,
                    @Field(nonNull = true) List<DistanceField> fields,
                    @Field(nonNull = true) List<Shader> shaders,
                    @Field(nonNull = true) List<Look> looks) {

    public Asset {
        properties = Map.copyOf(properties);
        meshes = List.copyOf(meshes);
        pointClouds = List.copyOf(pointClouds);
        volumes = List.copyOf(volumes);
        textures = List.copyOf(textures);
        fields = List.copyOf(fields);
        shaders = List.copyOf(shaders);
        looks = List.copyOf(looks);
    }

    /** A builder for an asset called {@code name}, or with no name when null. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** How many items it holds, of every kind. */
    public int items() {
        return meshes.size() + pointClouds.size() + volumes.size() + textures.size() + fields.size()
                + shaders.size() + looks.size();
    }

    /** Collects items. Not thread-safe. */
    public static final class Builder {
        private final String name;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final List<MeshData> meshes = new ArrayList<>();
        private final List<PointCloud> pointClouds = new ArrayList<>();
        private final List<Volume> volumes = new ArrayList<>();
        private final List<Texture> textures = new ArrayList<>();
        private final List<DistanceField> fields = new ArrayList<>();
        private final List<Shader> shaders = new ArrayList<>();
        private final List<Look> looks = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public Builder mesh(MeshData m) {
            meshes.add(m);
            return this;
        }

        public Builder pointCloud(PointCloud p) {
            pointClouds.add(p);
            return this;
        }

        public Builder volume(Volume v) {
            volumes.add(v);
            return this;
        }

        public Builder texture(Texture t) {
            textures.add(t);
            return this;
        }

        public Builder field(DistanceField f) {
            fields.add(f);
            return this;
        }

        public Builder shader(Shader s) {
            shaders.add(s);
            return this;
        }

        public Builder look(Look l) {
            looks.add(l);
            return this;
        }

        public Asset build() {
            return new Asset(name, properties, meshes, pointClouds, volumes, textures, fields, shaders, looks);
        }
    }
}
