package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Picture;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.vulkan.present.SampledImage;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Time;
import sibarum.kronometer.anim.Ease;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A place to show one {@link Picture}: whole, at its own aspect, a raster never larger than its own pixels, animated
 * if it is, over a chosen {@link Backdrop}. Its {@link #node} goes in a tree like any other, and fills what it is given.
 *
 * {@snippet :
 * ImageView view = new ImageView(gui, krono, textures, Length.ZERO, Length.ZERO);
 * gui.offload().execute(() -> {
 *     Decoded d = Pictures.of(path, 2048);           // blocking: off the GUI thread
 *     if (d.image()) {
 *         view.show(d.picture());
 *     }
 * });
 * }
 *
 * <p><b>Sized in percent of a stage.</b> The layout has no aspect-ratio length, so the picture sits on a stage that
 * takes whatever room the well gives it, {@link Gui#onResize} reports the stage's box, and the picture is sized and
 * placed in percent of it: the one unit that needs neither the density nor a pixel length. A vector has no size of its
 * own and fills the stage.
 *
 * <p><b>Two layers, so one picture can give way to the next.</b> Each layer is a frame floating on the stage, centred
 * by its offset. A {@linkplain #show(Picture, int) step} puts the new picture on the layer in front, and once its
 * texture has arrived it fades in and slides in from the side the step came from while the old one fades and slides
 * out the other way; then the old layer lets go of its texture. Until the texture arrives the old picture stays up,
 * so a step never blanks. A step that lands mid-transition takes over from where things are: the half-faded-in picture
 * becomes the one going out, from its own opacity and offset, and the one before it is dropped.
 *
 * <p><b>Two boxes per layer, so the backdrop is under the picture.</b> A node draws its own drawing over its own image,
 * so the checkerboard is the drawing of a layer's frame and the picture is the image of a plate that fills it.
 *
 * <p><b>Letting go.</b> {@link #clear} stops every animation and stops naming every texture, which is all
 * {@code GuiApp.release} asks before it may close one. Everything here is made once per view and shown or hidden.
 */
public final class ImageView {

    /** What shows through a transparent pixel. */
    public enum Backdrop {
        DARK("Dark"),
        LIGHT("Light"),
        CHECKER("Checker"),
        MAGENTA("Magenta"),
        GREEN("Green");

        private final String label;

        Backdrop(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** GIF's degenerate delays (0 and 10 ms) mean "as fast as you like"; browsers show them at 100 ms, as does this. */
    private static final int FLOOR_MS = 20;
    private static final int DEGENERATE_MS = 100;

    /** A step's transition: how long, and how far each picture slides, in root em. */
    public static final int STEP_MS = 260;
    static final float SLIDE_EM = 2.5f;

    /** A checker square, in px: big enough to read as a pattern, small enough to sit behind an icon. */
    static final int CHECK_PX = 12;
    private static final Color CHECK_LIGHT = Color.rgb(0xF4F4F4);
    private static final Color CHECK_DARK = Color.rgb(0xCDCDCD);

    private final Gui gui;
    private final KronoGui krono;
    private final Textures textures;
    private final Node well;
    private final Node stage;

    /** The layer showing the newest picture, and the one it is replacing. Swapped by {@link #show}; guarded by this. */
    private Layer front;
    private Layer back;

    /** Bumped by every transition and by {@link #clear}, so a stale ramp stops writing. */
    private final AtomicInteger transition = new AtomicInteger();
    private volatile NodeLayout stageBox = NodeLayout.ABSENT;
    private volatile Backdrop backdrop = Backdrop.DARK;
    /** Whether the backdrop is left to whatever is behind the view ({@link #bare}). */
    private volatile boolean bare;

    public ImageView(Gui gui, KronoGui krono, Textures textures, Length padY, Length padX) {
        this.gui = gui;
        this.krono = krono;
        this.textures = textures;
        this.back = new Layer();
        this.front = new Layer();
        this.stage = gui.column().width(Length.FILL).height(Length.FILL).clip(true).scroll(false, false)
                .children(back.frame, front.frame);
        this.well = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(padY, padX)
                .scroll(false, false).children(stage);
        back.paint();
        front.paint();
        gui.onResize(stage, box -> {
            stageBox = box;
            fit();
        });
    }

    public Node node() {
        return well;
    }

    /** Show {@code p} at once, with no transition. */
    public boolean show(Picture p) {
        return show(p, 0);
    }

    /**
     * Show {@code p}, arriving from the side {@code direction} says: 1 for the next picture (in from the right), -1 for
     * the previous, 0 for none (it replaces what is up the moment it is ready). The texture arrives later, on the GUI
     * thread, and is dropped if something else has been shown by then. False if there is no window to upload into (a
     * headless capture), in which case nothing is shown.
     */
    public synchronized boolean show(Picture p, int direction) {
        // Whatever was moving stops where it is: the picture fading out is finished with, and the one in front
        // starts going out from wherever it has got to.
        transition.incrementAndGet();
        back.clear();
        Layer outgoing = front;
        front = back;
        back = outgoing;
        // Keep the incoming layer above the outgoing one: the newest picture is drawn last.
        stage.append(front.frame);
        Layer incoming = front;
        int g = incoming.load(p);
        fit();
        int dir = Integer.signum(direction);
        return textures.show(p, texture -> {
            synchronized (this) {
                if (!incoming.current(g)) {
                    return;
                }
                incoming.texture(g, p, texture);
                if (outgoing.picture == null || dir == 0) {
                    incoming.place(1f, 0f);
                    outgoing.clear();
                    return;
                }
                slide(incoming, outgoing, dir);
            }
        });
    }

    /** Show nothing: stop every animation and let go of every texture, so the cache may give them back. */
    public synchronized void clear() {
        transition.incrementAndGet();
        front.clear();
        back.clear();
    }

    /**
     * Leave the backdrop to whatever is behind the view: the frames are bare, so a transparent pixel shows the window
     * itself, and {@link #backdrop} is only remembered. For a viewer that paints its backdrop across the whole window, so a
     * checkerboard runs on unbroken past the picture's edge.
     */
    public synchronized ImageView bare() {
        bare = true;
        front.paint();
        back.paint();
        return this;
    }

    public Backdrop backdrop() {
        return backdrop;
    }

    /** Change what shows through a transparent pixel. Takes effect at once, picture or no picture. */
    public void backdrop(Backdrop b) {
        backdrop = b == null ? Backdrop.DARK : b;
        synchronized (this) {
            front.checkered = -1;
            back.checkered = -1;
            front.paint();
            back.paint();
        }
        fit();
    }

    /** The incoming picture fades and slides in as the outgoing one fades and slides out, from where each is now. */
    private void slide(Layer incoming, Layer outgoing, int dir) {
        int t = transition.incrementAndGet();
        float fromAlpha = outgoing.alpha;
        float fromDx = outgoing.dx;
        incoming.place(0f, dir * SLIDE_EM);
        krono.ramp(Dur.ms(STEP_MS), Ease.LINEAR, p -> {
            if (transition.get() != t) {
                return;
            }
            float e = Ease.OUT_CUBIC.at((float) p);
            // Opacity in time, position eased: a fade that eases spends too long nearly-there (see Ease).
            incoming.place((float) p, dir * SLIDE_EM * (1f - e));
            outgoing.place(fromAlpha * (1f - (float) p), fromDx + (-dir * SLIDE_EM - fromDx) * e);
        }, () -> {
            synchronized (this) {
                if (transition.get() == t) {
                    outgoing.clear();
                }
            }
        });
    }

    /** Size and place each layer's frame on the stage: whole, at its picture's aspect, centred. */
    private synchronized void fit() {
        front.fit();
        back.fit();
    }

    /** The colour a backdrop shows; for the checkerboard, its light squares. A swatch of it uses the same. */
    public static Color color(Theme theme, Backdrop b) {
        return switch (b) {
            case DARK -> theme.color(Role.WELL);
            case LIGHT -> Color.rgb(0xF4F4F4);
            case CHECKER -> CHECK_LIGHT;
            case MAGENTA -> Color.rgb(0xFF00FF);
            case GREEN -> Color.rgb(0x00FF00);
        };
    }

    /** The dark squares of a {@code w x h} checkerboard; the light ones are the frame's background. */
    public static Sketch checks(int w, int h) {
        return checks(w, h, CHECK_PX);
    }

    /** The same with squares of {@code side} px: a swatch of the checkerboard wants smaller ones. */
    public static Sketch checks(int w, int h, int side) {
        Sketch s = new Sketch().tag("checker");
        for (int y = 0, row = 0; y < h; y += side, row++) {
            for (int x = (row & 1) * side; x < w; x += 2 * side) {
                s.fill(x, y, Math.min(side, w - x), Math.min(side, h - y), CHECK_DARK);
            }
        }
        return s;
    }

    /** The size a picture is shown at in a {@code cw x ch} box, as a fraction of each side: whole and centred. */
    static float[] fraction(Picture p, float cw, float ch) {
        float w = Math.max(1, p.sourceWidth());
        float h = Math.max(1, p.sourceHeight());
        float scale = Math.min(cw / w, ch / h);
        if (!p.vector()) {
            scale = Math.min(scale, 1f);
        }
        return new float[] {w * scale / cw, h * scale / ch};
    }

    /**
     * One picture on the stage: a frame floating where {@link #fit} puts it, its backdrop, and a plate showing the
     * picture. Its fields are guarded by the view.
     */
    private final class Layer {

        final Node frame;
        final Node plate;
        /** Bumped by every load and clear, so a late texture or a stale animation step is dropped. */
        private final AtomicInteger generation = new AtomicInteger();
        volatile Picture picture;
        private Scheduled animation;
        long checkered = -1;
        /** Where the transition has it: opacity, and offset across in root em. */
        volatile float alpha = 1f;
        volatile float dx;

        Layer() {
            Length corner = Length.rem(0.3f);
            this.plate = gui.box().width(Length.FILL).height(Length.FILL).corner(corner);
            this.frame = gui.column().size(Length.ZERO, Length.ZERO).corner(corner).scroll(false, false)
                    .floatAt(Length.ZERO, Length.ZERO).hitInert(true).visible(false).children(plate);
        }

        /** Make this the layer for {@code p}, showing nothing until its texture comes. The generation to expect. */
        int load(Picture p) {
            clear();
            picture = p;
            place(0f, 0f);
            frame.visible(true);
            return generation.get();
        }

        boolean current(int g) {
            return generation.get() == g && picture != null;
        }

        /** The texture for what was loaded at {@code g} has arrived: show it, and step it if it is animated. */
        void texture(int g, Picture p, SampledImage texture) {
            if (!p.animated()) {
                plate.image(texture);
                return;
            }
            plate.image(texture, ImageRegion.cell(0, p.columns(), p.rows()));
            animation = krono.spork("image-animation", () -> {
                int i = 0;
                while (generation.get() == g) {
                    int d = p.delays()[i];
                    Time.advance(Dur.ms(d < FLOOR_MS ? DEGENERATE_MS : d));
                    if (generation.get() != g) {
                        return;
                    }
                    i = (i + 1) % p.count();
                    plate.image(texture, ImageRegion.cell(i, p.columns(), p.rows()));
                }
            });
        }

        void place(float alpha, float dx) {
            this.alpha = alpha;
            this.dx = dx;
            frame.opacity(alpha).translate(dx, 0f);
        }

        /** Show nothing: stop the animation and stop naming the texture. */
        void clear() {
            generation.incrementAndGet();
            if (animation != null) {
                animation.cancel();
                animation = null;
            }
            if (picture != null) {
                picture = null;
                plate.image(null);
            }
            frame.visible(false);
            place(1f, 0f);
        }

        void paint() {
            if (bare) {
                frame.background(Color.TRANSPARENT).picture(null);
                return;
            }
            frame.background(color(gui.theme(), backdrop));
            if (backdrop != Backdrop.CHECKER) {
                frame.picture(null);
            }
        }

        /** Size the frame to the stage and centre it; redraw the checks if the size changed. */
        void fit() {
            Picture p = picture;
            NodeLayout box = stageBox;
            if (p == null || !box.present()) {
                return;
            }
            float cw = box.content().w();
            float ch = box.content().h();
            if (cw <= 0f || ch <= 0f) {
                return;
            }
            float[] f = fraction(p, cw, ch);
            frame.size(Length.percent(100f * f[0]), Length.percent(100f * f[1]))
                    .floatAt(Length.percent(50f * (1f - f[0])), Length.percent(50f * (1f - f[1])));
            if (!bare && backdrop == Backdrop.CHECKER) {
                int w = Math.round(f[0] * cw);
                int h = Math.round(f[1] * ch);
                long size = (long) w << 32 | h;
                if (size != checkered) {
                    checkered = size;
                    frame.picture(checks(w, h).picture());
                }
            }
        }
    }
}
