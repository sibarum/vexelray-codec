package dev.vexelray.codec.video;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What {@link Videos#probe} makes of a file, as values: the video, or none and why. A job's answer.
 *
 * @param path     the file looked at
 * @param video    the video, when it is one that plays
 * @param identity what the file is, or why there is no video of it
 */
public record Probed(Path path, Optional<VideoFile> video, String identity) {
}
