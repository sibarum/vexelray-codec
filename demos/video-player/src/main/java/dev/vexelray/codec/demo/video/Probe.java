package dev.vexelray.codec.demo.video;

import dev.vexelray.codec.demo.video.Messages.Look;
import dev.vexelray.codec.demo.video.Messages.Looked;
import dev.vexelray.codec.video.Probed;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.core.Launch;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What the command line named, looked at on the offload lane: opened, measured and closed again
 * ({@link Videos#probe}), so the answer is values. The view plays it, and its player opens the file again on a thread of
 * its own. Nothing named, or a name that is not a path, is an answer with no video and the reason.
 */
@Job
final class Probe {

    private final Launch launch;

    Probe(Launch launch) {
        this.launch = launch;
    }

    public Looked run(Look l) {
        if (launch.rest().isEmpty()) {
            return new Looked(new Probed(Path.of(""), Optional.empty(), "nothing open"));
        }
        String named = launch.rest().getFirst();
        Path path;
        try {
            path = Path.of(named).toAbsolutePath();
        } catch (InvalidPathException e) {
            return new Looked(new Probed(Path.of(""), Optional.empty(), "not a file name: " + named));
        }
        return new Looked(Videos.probe(path, Player.SIDE));
    }
}
