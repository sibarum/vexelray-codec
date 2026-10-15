package dev.vexelray.codec.asset;

import dev.vexelray.codec.asset.future.FutureAsset;
import dev.vexelray.codec.asset.future.FutureAssetIntrabuf;
import dev.vexelray.codec.asset.future.FutureTexture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promise the format makes: files outlive the build that wrote them, in both directions. A later version of the
 * schema is stood in for by {@link FutureAsset}; an earlier one by a file committed when the format was first written.
 */
class CompatibilityTest {

    /** The file the first version wrote. Never regenerated once committed: it is what later versions must open. */
    private static final String GOLDEN = "/golden/sampler-1.0.vxa";

    @Test
    void todaysReaderOpensAFileFromALaterVersion() {
        Asset now = Samples.asset();
        List<FutureTexture> textures = now.textures().stream()
                .map(t -> new FutureTexture(t.name(), t.format(), t.width(), t.height(), t.depth(), t.levels(), 6))
                .toList();
        FutureAsset later = new FutureAsset(now.name(), now.properties(), now.meshes(), now.pointClouds(),
                now.volumes(), textures, now.fields(), now.shaders(), now.looks(),
                List.of(Channel.f32("opacity", 1, new float[]{0.5f, 1})));
        byte[] payload = FutureAssetIntrabuf.toBytes(later);
        byte[] file = Arrays.copyOf(Assets.SIGNATURE, Assets.SIGNATURE.length + payload.length);
        System.arraycopy(payload, 0, file, Assets.SIGNATURE.length, payload.length);

        Unpacked u = Assets.read("later.vxa", file);
        assertTrue(u.readable(), u.identity());
        AssetsTest.assertSampler(u.asset());
    }

    @Test
    void aLaterReaderOpensTodaysFile() {
        byte[] file = Assets.toBytes(Samples.asset());
        FutureAsset later = FutureAssetIntrabuf.CODEC.decode(file, Assets.SIGNATURE.length,
                file.length - Assets.SIGNATURE.length);
        assertEquals(List.of(), later.splats(), "a list this file predates is empty");
        assertNull(later.textures().getFirst().layers(), "and a field it predates is absent");
        assertEquals("checker", later.textures().getFirst().name());
    }

    @Test
    void theFirstVersionsFileStillOpens() {
        Unpacked u = Assets.resource(CompatibilityTest.class, GOLDEN);
        assertTrue(u.readable(), u.identity());
        AssetsTest.assertSampler(u.asset());
    }

    /**
     * Writes the golden file, with {@code -Dvxa.golden=write}. Only ever for a new golden file beside the old ones: an
     * existing one is what an older version wrote, and rewriting it would test nothing.
     */
    @Test
    @EnabledIfSystemProperty(named = "vxa.golden", matches = "write")
    void writeTheGoldenFile() throws IOException {
        Path f = Path.of("src/test/resources" + GOLDEN);
        if (Files.exists(f)) {
            throw new IllegalStateException(f + " exists; name a new golden file instead");
        }
        Files.createDirectories(f.getParent());
        Assets.write(Samples.asset(), f);
    }
}
