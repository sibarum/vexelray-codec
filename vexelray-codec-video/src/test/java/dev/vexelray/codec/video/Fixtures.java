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

    /** {@code name}'s first {@code keep} bytes, as a file: a download cut short. */
    public static Path cut(String name, int keep) throws IOException {
        Path out = Files.createTempFile("fixture-cut", "-" + name);
        out.toFile().deleteOnExit();
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + name)) {
            Files.write(out, in.readNBytes(keep));
        }
        return out;
    }

    /** {@code rgb.webm} cut just into its first cluster: it opens, says what is coming, and has not one frame. */
    public static Path noFrames() throws IOException {
        byte[] webm;
        try (InputStream in = Fixtures.class.getResourceAsStream("/rgb.webm")) {
            webm = in.readAllBytes();
        }
        for (int i = 0; i + 4 <= webm.length; i++) {
            if ((webm[i] & 0xFF) == 0x1F && (webm[i + 1] & 0xFF) == 0x43 && (webm[i + 2] & 0xFF) == 0xB6
                    && (webm[i + 3] & 0xFF) == 0x75) {
                return cut("rgb.webm", i + 5);   // the cluster begun, and none of it there
            }
        }
        throw new IllegalStateException("rgb.webm has no cluster");
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
