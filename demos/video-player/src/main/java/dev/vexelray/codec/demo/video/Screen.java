package dev.vexelray.codec.demo.video;

import dev.vexelray.codec.demo.video.Messages.Beat;
import dev.vexelray.codec.demo.video.Messages.Look;
import dev.vexelray.codec.demo.video.Messages.Looked;
import dev.vexelray.codec.demo.video.Messages.Seek;
import dev.vexelray.codec.demo.video.Messages.Toggle;
import dev.vexelray.codec.video.Probed;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.codec.video.gui.VideoView;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.framework.core.Timer;
import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;

import java.time.Duration;
import java.util.Locale;

/**
 * The window: a title bar, the video filling the rest, and a line under it saying what is playing and how fast. A
 * {@code @View}, so it runs on the GUI thread in each frame's widget tick: it asks the {@link Probe} job what the
 * command line named once the window is open, plays the answer in a {@link VideoView}, and hears its keys and the
 * meter's beat as messages. The video itself is paced by the view's asked half, which the widget registers on its
 * own; nothing of this runs per frame.
 */
@View
final class Screen {

    /** How far ← and → move the time. */
    static final long STEP_US = 5_000_000;

    private final VideoView view;
    private final Node state;
    private final Node stats;
    private final Sender<Look> look;
    private String opened = "nothing open";
    /** The meter: frames the view had shown when the last beat came, and when that was. */
    private long counted;
    private long countedAt = System.nanoTime();

    Screen(Gui gui, Keymap keys, TitleBar titleBar, Timer timer, Sender<Look> look, Sender<Toggle> toggle,
           Sender<Seek> seek, Sender<Beat> beat) {
        this.look = look;
        this.view = new VideoView(gui, Length.ZERO, Length.ZERO).loop(true);
        this.state = gui.text(opened).textColor(gui.theme().color(Role.INK));
        this.stats = gui.text("").textColor(gui.theme().color(Role.INK));
        gui.landmark("player.state", state);
        gui.landmark("player.stats", stats);
        gui.landmark("player.view", view.node());
        Node bar = gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(2))
                .padding(Length.rem(0.4f), Length.rem(1)).background(gui.theme().color(Role.PANEL))
                .children(state, stats);
        gui.root().direction(Direction.COLUMN).background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), view.node(), bar);
        keys.action("play.toggle", "Pause, or play on", Shortcut.of(Key.SPACE)).sends(toggle, new Toggle());
        keys.action("play.back", "Five seconds back", Shortcut.of(Key.LEFT)).sends(seek, new Seek(-STEP_US, false));
        keys.action("play.on", "Five seconds on", Shortcut.of(Key.RIGHT)).sends(seek, new Seek(STEP_US, false));
        keys.action("play.start", "The start", Shortcut.of(Key.HOME)).sends(seek, new Seek(0, true));
        timer.every(Duration.ofSeconds(1), Messages.BEAT_TOPIC, new Beat());
    }

    /** The main window is up: look at what the command line named. */
    @Subscribe
    public void opened(WindowOpened m) {
        look.send(new Look());
    }

    @Subscribe
    public void probed(Looked l) {
        Probed m = l.probed();
        opened = m.identity();
        m.video().ifPresent(view::play);
        say();
    }

    @Subscribe
    public void toggle(Toggle m) {
        if (view.playing()) {
            view.pause();
        } else {
            view.resume();
        }
        say();
    }

    @Subscribe
    public void seek(Seek m) {
        view.seek(m.toStart() ? 0 : view.position() + m.byMicros());
    }

    /**
     * The meter: how many distinct frames the view showed since the last beat, over the time since, which is what a
     * person watching would call the frame rate; and where the time is.
     */
    @Subscribe
    public void beat(Beat m) {
        long now = System.nanoTime();
        long shown = view.framesShown();
        double fps = (shown - counted) * 1e9 / Math.max(1, now - countedAt);
        counted = shown;
        countedAt = now;
        stats.text(String.format(Locale.ROOT, "shown %.1f fps · %s / %s", fps, Videos.clock(view.position()),
                Videos.clock(view.duration())));
        say();
    }

    private void say() {
        String failure = view.failure();
        state.text(opened + " · " + (failure != null ? failure : view.playing() ? "playing" : "paused"));
    }
}
