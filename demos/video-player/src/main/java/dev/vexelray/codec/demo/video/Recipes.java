package dev.vexelray.codec.demo.video;

import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.codec.video.Opened;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.codec.video.gui.VideoView;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.tactroller.api.Key;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/** The window: a title bar, the view, and a line under it saying what is playing and how fast. */
@Configuration
final class Recipes {

    /** How far ← and → move the clock. */
    static final long STEP_US = 5_000_000;

    /** The view, and the line that says what it is playing. */
    static final class Screen {
        private final VideoView view;
        private final Node state;

        Screen(VideoView view, Node state) {
            this.view = view;
            this.state = state;
        }

        VideoView view() {
            return view;
        }

        Node state() {
            return state;
        }
    }

    private static volatile String opened = "nothing open";

    @Provides
    Screen screen(Gui gui, KronoGui krono, Textures textures, TitleBar titleBar) {
        VideoView view = new VideoView(gui, krono, textures, Length.ZERO, Length.ZERO).loop(true);
        Node state = gui.text(opened).textColor(gui.theme().color(Role.INK));
        Node stats = gui.text("").textColor(gui.theme().color(Role.INK));
        gui.landmark("player.state", state);
        gui.landmark("player.stats", stats);
        gui.landmark("player.view", view.node());
        Node bar = gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(2))
                .padding(Length.rem(0.4f), Length.rem(1)).background(gui.theme().color(Role.PANEL))
                .children(state, stats);
        gui.root().direction(Direction.COLUMN).background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), view.node(), bar);

        gui.shortcut(Key.SPACE, () -> {
            if (view.playing()) {
                view.pause();
            } else {
                view.resume();
            }
            state.text(described(view));
        });
        gui.shortcut(Key.LEFT, () -> view.seek(view.position() - STEP_US));
        gui.shortcut(Key.RIGHT, () -> view.seek(view.position() + STEP_US));
        gui.shortcut(Key.HOME, () -> view.seek(0));
        meter(view, stats);
        return new Screen(view, state);
    }

    /**
     * Opens the file once the textures have the device: a view that played before then would have nowhere to upload,
     * and would count frames that were never drawn.
     */
    @Provides
    @MainThread
    AutoCloseable playing(Textures.Binding textures, Screen screen, Gui gui, Launch launch) {
        VideoView view = screen.view();
        if (!launch.rest().isEmpty()) {
            Path path;
            try {
                path = Path.of(launch.rest().getFirst());
            } catch (InvalidPathException e) {
                // Said in the window, as any file that will not play is, rather than stopping it opening.
                opened = "not a file name: " + launch.rest().getFirst();
                screen.state().text(described(view));
                return view::clear;
            }
            gui.offload().execute(() -> {
                Opened o = Videos.open(path, Player.SIDE);
                if (o.video()) {
                    view.play(o.clip());
                }
                opened = o.identity();
                screen.state().text(described(view));
            });
        }
        return view::clear;
    }

    private static String described(VideoView view) {
        String failure = view.failure();
        return opened + " · " + (failure != null ? failure : view.playing() ? "playing" : "paused");
    }

    /**
     * Counts the frames that reached the plate, as changes of {@link VideoView#shownMicros}, each second: what a
     * person watching would call the frame rate. Polled, because the view says nothing when a frame lands.
     */
    private static void meter(VideoView view, Node stats) {
        Thread.ofPlatform().daemon().name("player-meter").start(() -> {
            long last = -2;
            int frames = 0;
            long since = System.nanoTime();
            while (true) {
                long shown = view.shownMicros();
                if (shown != last) {
                    last = shown;
                    frames++;
                }
                long now = System.nanoTime();
                if (now - since >= 1_000_000_000L) {
                    double fps = frames * 1e9 / (now - since);
                    stats.text(String.format(Locale.ROOT, "shown %.1f fps · %s / %s", fps, Videos.clock(view.position()),
                            Videos.clock(view.duration())));
                    frames = 0;
                    since = now;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }
}
