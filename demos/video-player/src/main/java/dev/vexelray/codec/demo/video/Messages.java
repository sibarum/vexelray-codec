package dev.vexelray.codec.demo.video;

import dev.vexelray.codec.video.Probed;
import sibarum.atchung.Topic;

/**
 * Every message the player's parts send each other: the view asks the {@link Probe} job what the command line named,
 * the job answers with the video as values, and the keys and the meter's beat tell the view what to do.
 */
final class Messages {

    private Messages() {
    }

    /** Look at the file the command line named: sent once the main window is open. */
    static final Topic<Look> LOOK_TOPIC = Topic.edge(Look.class);

    record Look() {
    }

    /** The video, as values, or none and why: the job's answer. */
    static final Topic<Looked> LOOKED_TOPIC = Topic.edge(Looked.class);

    record Looked(Probed probed) {
    }

    /** Space: pause, or play on. */
    static final Topic<Toggle> TOGGLE_TOPIC = Topic.edge(Toggle.class);

    record Toggle() {
    }

    /** ← and →: this far back or on, in µs; Home: to the start ({@code toStart}). */
    static final Topic<Seek> SEEK_TOPIC = Topic.edge(Seek.class);

    record Seek(long byMicros, boolean toStart) {
    }

    /** The meter's beat, once a second: how many frames reached the screen since the last. */
    static final Topic<Beat> BEAT_TOPIC = Topic.edge(Beat.class);

    record Beat() {
    }
}
