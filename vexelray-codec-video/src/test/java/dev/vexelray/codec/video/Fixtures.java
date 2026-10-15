package dev.vexelray.codec.video;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The test videos, as files, since FFmpeg opens paths. Each is three seconds at 64x48 and 10 fps — one second red, one
 * green, one blue, a keyframe every ten frames — made by the ffmpeg that ffmpeg-wrapper's build fetches: {@code rgb.mp4}
 * is H.264 with AAC sound, {@code rgb.webm} VP9 with none.
 */
public final class Fixtures {

    public static Path file(String name) throws IOException {
        Path out = Files.createTempFile("fixture", "-" + name);
        out.toFile().deleteOnExit();
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + name)) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    /** Whether the centre of a {@code w x h} RGBA8 frame is mostly channel {@code c}: 0 red, 1 green, 2 blue. */
    public static boolean dominant(byte[] rgba, int w, int h, int c) {
        int p = ((h / 2) * w + w / 2) * 4;
        for (int other = 0; other < 3; other++) {
            if (other != c && !((rgba[p + c] & 0xFF) > 200 && (rgba[p + other] & 0xFF) < 60)) {
                return false;
            }
        }
        return true;
    }

    private Fixtures() {
    }
}
