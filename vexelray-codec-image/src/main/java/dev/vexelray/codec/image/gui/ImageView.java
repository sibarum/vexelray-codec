package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Picture;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.ImageFilter;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
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
import sibarum.tactroller.api.InputEvent;

import java.util.List;
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
 * texture has arrived it is thrown in across the whole stage from the side the step came from while the old one is
 * flung out the other way, each leaning as it flies; then the old layer lets go of its texture. Until the texture arrives the old picture stays up,
 * so a step never blanks. A step that lands mid-transition takes over from where things are: the half-faded-in picture
 * becomes the one going out, from its own opacity and offset, and the one before it is dropped.
 *
 * <p><b>Two boxes per layer, so the backdrop is under the picture.</b> A node draws its own drawing over its own image,
 * so the checkerboard is the drawing of a layer's frame and the picture is the image of a plate that fills it.
 *
 * <p><b>Zoomed, moved and turned by its caller.</b> The picture in front can be given a {@link Pose}: a size relative
 * to the one it fits at, an offset from the centre, a turn, and a mirror. It is drawn with the visual transforms
 * ({@code Node.translate}, {@code scale}, {@code rotate}) on the fitted frame, and a mirror by reading the texture the
 * other way ({@code ImageRegion} reversed), never by laying the frame out again: a
 * float is held inside its parent, and a pose that changes every frame should lay nothing out. The view only draws
 * the pose; the gestures that change it, and the limits on where the picture may go, are the caller's, which
 * {@link #fitted} gives the measures for. The stage clips, so a picture larger than it is cut at its edges.
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

    /**
     * Where the picture in front is, beyond fitting: how much larger than it fits, how far its centre is from the
     * stage's, how far it is turned, and whether it is mirrored. {@link #REST} is the picture fitted, centred, upright
     * and as it is.
     *
     * <p>A mirror is in the picture's own frame, before the turn: its left and right swapped ({@code mirrorX}), or its
     * top and bottom ({@code mirrorY}), and then turned {@code degrees}. It is drawn by sampling the texture the other
     * way, so it costs nothing and every filter reads it as it reads the picture.
     *
     * @param zoom    its size over the size it fits at; above 0
     * @param x       its centre's offset from the stage's, across, in layout px
     * @param y       the same, down
     * @param degrees clockwise, about its own centre
     * @param mirrorX its left and right swapped
     * @param mirrorY its top and bottom swapped
     */
    public record Pose(float zoom, float x, float y, float degrees, boolean mirrorX, boolean mirrorY) {

        public static final Pose REST = new Pose(1f, 0f, 0f, 0f);

        public Pose {
            if (!(zoom > 0f)) {
                throw new IllegalArgumentException("a picture is shown at some size: " + zoom);
            }
        }

        /** Not mirrored. */
        public Pose(float zoom, float x, float y, float degrees) {
            this(zoom, x, y, degrees, false, false);
        }
    }

    /**
     * The stage, and the size the picture in front fits at on it ({@link Pose#zoom} 1, upright), in layout px:
     * whole and at its own aspect, and a raster no larger than its own pixels.
     */
    public record Fitted(float stageW, float stageH, float pictureW, float pictureH) {
    }

    /** GIF's degenerate delays (0 and 10 ms) mean "as fast as you like"; browsers show them at 100 ms, as does this. */
    private static final int FLOOR_MS = 20;
    private static final int DEGENERATE_MS = 100;

    /**
     * A step's transition: how long, how far each picture is thrown when the stage's width is not yet known (in root
     * em; otherwise the whole width), and how far it leans, in degrees, a whole width out.
     */
    public static final int STEP_MS = 420;
    static final float SLIDE_EM = 2.5f;
    static final float THROW_DEG = 7f;

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
    /** How the picture is scaled as it is drawn, from its texture: the GPU's filter. Guarded by this. */
    private ImageFilter filter = ImageFilter.BILINEAR;
    /** Whether the backdrop is left to whatever is behind the view ({@link #bare}). */
    private volatile boolean bare;
    /** The root em in layout px: what {@code Node.translate} counts in. Measured by a ruler on the stage. */
    private volatile float emPx = 16f;
    /** Told when the stage changes size, once the layers are fitted to it. */
    private volatile Runnable onFit = () -> { };
    /** Told when the picture in front starts or stops playing, or its playing moves ({@link #onPlayback}). */
    private volatile Runnable onPlayback = () -> { };
    /** Whether the window is focused: pictures that play are held while it is not. */
    private volatile boolean focused = true;

    public ImageView(Gui gui, KronoGui krono, Textures textures, Length padY, Length padX) {
        this.gui = gui;
        this.krono = krono;
        this.textures = textures;
        this.back = new Layer();
        this.front = new Layer();
        // The em in px, which a pose's offset is drawn in (Node.translate): one em of nothing, measured.
        Node ruler = gui.box().size(Length.rem(1f), Length.ZERO).floatAt(Length.ZERO, Length.ZERO).hitInert(true);
        this.stage = gui.column().width(Length.FILL).height(Length.FILL).clip(true).scroll(false, false)
                .children(ruler, back.frame, front.frame);
        this.well = gui.column().width(Length.FILL).height(Length.grow(1f)).padding(padY, padX)
                .scroll(false, false).children(stage);
        back.paint();
        front.paint();
        gui.onResize(stage, box -> {
            stageBox = box;
            fit();
            onFit.run();
        });
        gui.onResize(ruler, box -> {
            if (box.present() && box.rect().w() > 0f) {
                emPx = box.rect().w();
                synchronized (this) {
                    front.draw();
                    back.draw();
                }
            }
        });
        // A picture that plays is held while its window is not focused, minimised included, and plays on when it is.
        gui.bus().subscribe(InputTopics.INPUT, e -> {
            if (e instanceof InputEvent.FocusChanged f) {
                focused = f.focused();
                synchronized (this) {
                    front.hold(!focused);
                    back.hold(!focused);
                }
            }
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
        boolean shown = textures.show(p, texture -> {
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
        onPlayback.run();
        return shown;
    }

    /** Show nothing: stop every animation and let go of every texture, so the cache may give them back. */
    public synchronized void clear() {
        transition.incrementAndGet();
        front.clear();
        back.clear();
        onPlayback.run();
    }

    /**
     * How the picture in front plays, if it is the first frame of a file that does ({@link Picture#plays}): play, pause,
     * seek, step and loop. Null for a still, and before the picture's texture has come. A picture that plays starts
     * playing as soon as it is shown, round and round.
     */
    public synchronized Playback playback() {
        return front.playback;
    }

    /**
     * Run {@code r} whenever {@link #playback} changes or moves: a picture that plays has come or gone, or its frame,
     * its clock's state or its loop has changed. On the timeline, the GUI thread or a decode thread; keep it cheap.
     */
    public void onPlayback(Runnable r) {
        onPlayback = r == null ? () -> { } : r;
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
    /**
     * Scale the picture with {@code f} as it is drawn from its texture, every frame, moving or not: on the GPU, so
     * the filters with a loop of taps cost a loop of taps. {@link ImageFilter#BILINEAR} by default. A {@link #detail}
     * is made at the size it is shown and is drawn as it is.
     */
    public synchronized void filter(ImageFilter f) {
        filter = f == null ? ImageFilter.BILINEAR : f;
        for (Layer l : List.of(front, back)) {
            if (l.picture != null && l.textureW > 0) {
                l.plate.imageFilter(filter, l.textureW, l.textureH);
            }
        }
    }

    public synchronized ImageFilter filter() {
        return filter;
    }

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

    /**
     * Put the picture in front at {@code pose}: larger or smaller than it fits, off centre, turned. Cheap enough to
     * call every frame. A newly {@linkplain #show shown} picture starts at {@link Pose#REST}, and the one it replaces
     * goes out at whatever pose it had. The view keeps no limits of its own: where a picture may go is the caller's.
     */
    public synchronized void pose(Pose pose) {
        front.pose = pose == null ? Pose.REST : pose;
        front.draw();
    }

    /**
     * Draw the part of {@code of} from {@code (u0, v0)} to {@code (u1, v1)}, in fractions of its width and height, from
     * {@code part} instead: the same pixels made sharper, at the size they are shown ({@code Original.detail}). It lies
     * over the picture in the picture's own frame, so it moves, scales, turns and mirrors with it, and goes with it;
     * its fractions are of the picture as it is, not mirrored. It replaces
     * the part shown before, and is dropped if {@code of} is no longer the picture in front when its texture arrives.
     * It is shown only while the picture is at {@code zoom}, the {@link Pose#zoom} it was made for: zoomed any other
     * way it is no sharper than the picture under it, so the picture is shown plain until the next part comes. False
     * if it will not be shown.
     */
    public boolean detail(Picture of, Picture part, float zoom, float u0, float v0, float u1, float v1) {
        Layer layer;
        int g;
        synchronized (this) {
            layer = front;
            if (of == null || part == null || layer.picture != of) {
                return false;
            }
            g = layer.generation.get();
        }
        return textures.upload(part, texture -> {
            synchronized (this) {
                if (layer.current(g) && layer.picture == of) {
                    layer.sharpen(texture, zoom, u0, v0, u1, v1);
                } else {
                    textures.release(texture);
                }
            }
        });
    }

    /** The pose of the picture in front. */
    public synchronized Pose pose() {
        return front.pose;
    }

    /** The stage and the size the picture in front fits at, or null with no picture or no stage yet. */
    public synchronized Fitted fitted() {
        Picture p = front.picture;
        NodeLayout box = stageBox;
        if (p == null || !box.present()) {
            return null;
        }
        float cw = box.content().w();
        float ch = box.content().h();
        if (cw <= 0f || ch <= 0f) {
            return null;
        }
        float[] f = fraction(p, cw, ch);
        return new Fitted(cw, ch, f[0] * cw, f[1] * ch);
    }

    /** Run {@code r} whenever the stage changes size, once the layers have been fitted to it; on a layout thread. */
    public void onFit(Runnable r) {
        onFit = r == null ? () -> { } : r;
    }

    /**
     * The incoming picture is thrown in across the whole stage from its side as the outgoing one is flung out the other
     * way, from where each is now, each leaning the way it travels and straightening as it lands.
     */
    private void slide(Layer incoming, Layer outgoing, int dir) {
        int t = transition.incrementAndGet();
        float fromAlpha = outgoing.alpha;
        float fromDx = outgoing.dx;
        NodeLayout box = stageBox;
        float em = emPx;
        float span = box.present() && box.content().w() > 0f ? box.content().w() / em : SLIDE_EM;
        incoming.place(0f, dir * span, dir * THROW_DEG);
        krono.ramp(Dur.ms(STEP_MS), Ease.LINEAR, p -> {
            if (transition.get() != t) {
                return;
            }
            float q = (float) p;
            float in = dir * span * (1f - Ease.OUT_QUART.at(q));
            float out = fromDx + (-dir * span - fromDx) * Ease.OUT_CUBIC.at(q);
            // The new one is solid almost at once, so it is seen flying; the old one keeps most of itself till it
            // is nearly off.
            incoming.place(Math.min(1f, 4f * q), in, THROW_DEG * in / span);
            outgoing.place(fromAlpha * (1f - q * q), out, THROW_DEG * out / span);
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
        /** How the picture plays, if it is the poster of a file that does; null otherwise and until its texture comes. */
        Playback playback;
        /** The size of the texture on the plate, which the filter is told: the sheet, or the stream's frames. */
        int textureW;
        int textureH;
        long checkered = -1;
        /** Where the transition has it: opacity, offset across in root em, and lean in degrees clockwise. */
        volatile float alpha = 1f;
        volatile float dx;
        volatile float tilt;
        /** Where the picture is beyond fitting; {@link Pose#REST} from every load. */
        Pose pose = Pose.REST;
        /** A part of the picture drawn sharper over the plate ({@link #detail}), and its texture, which this layer owns. */
        final Node detail;
        SampledImage sharp;
        /** The zoom the part was made for: at any other it is no sharper than the plate, and is hidden. */
        float sharpZoom = 1f;
        /** Where the part lies, in fractions of the picture as it is, not mirrored. */
        private float sharpU0;
        private float sharpV0;
        private float sharpU1;
        private float sharpV1;
        /** What the plate shows, as the picture is: the texture and the part of it (a sheet's cell, or the whole). */
        private SampledImage onPlate;
        private ImageRegion plateRegion = ImageRegion.WHOLE;
        /** The mirror the plate and the part were last drawn with. */
        private boolean drawnX;
        private boolean drawnY;

        Layer() {
            Length corner = Length.rem(0.3f);
            this.plate = gui.box().width(Length.FILL).height(Length.FILL).corner(corner);
            // Its corners are the plate's: where the part reaches the picture's corners they match, and its other
            // edges lie past the stage, where nothing is seen.
            this.detail = gui.box().size(Length.ZERO, Length.ZERO).corner(corner).floatAt(Length.ZERO, Length.ZERO)
                    .hitInert(true).visible(false);
            this.frame = gui.column().size(Length.ZERO, Length.ZERO).corner(corner).scroll(false, false)
                    .floatAt(Length.ZERO, Length.ZERO).hitInert(true).visible(false).children(plate, detail);
        }

        /** Make this the layer for {@code p}, showing nothing until its texture comes. The generation to expect. */
        int load(Picture p) {
            clear();
            picture = p;
            pose = Pose.REST;
            place(0f, 0f);
            frame.visible(true);
            return generation.get();
        }

        boolean current(int g) {
            return generation.get() == g && picture != null;
        }

        /**
         * The texture for what was loaded at {@code g} has arrived: show it, and step it if it is animated. The poster
         * of a file that plays is shown until the file's first frame comes, and then the frames as they fall due.
         */
        void texture(int g, Picture p, SampledImage texture) {
            showing(texture, p.sheetWidth(), p.sheetHeight());
            if (p.plays()) {
                int side = Math.max(p.frameWidth(), p.frameHeight());
                playback = new Playback(krono, textures, (frame, w, h) -> {
                    synchronized (ImageView.this) {
                        if (current(g)) {
                            showing(frame, w, h);
                        }
                    }
                }, p.motion(), side, () -> onPlayback.run());
                playback.hold(!focused);
                playback.start(gui.offload());
                return;
            }
            if (!p.animated()) {
                return;
            }
            plate(texture, ImageRegion.cell(0, p.columns(), p.rows()));
            animation = krono.spork("image-animation", () -> {
                int i = 0;
                while (generation.get() == g) {
                    int d = p.delays()[i];
                    Time.advance(Dur.ms(d < FLOOR_MS ? DEGENERATE_MS : d));
                    i = (i + 1) % p.count();
                    synchronized (ImageView.this) {
                        if (generation.get() != g) {
                            return;
                        }
                        plate(texture, ImageRegion.cell(i, p.columns(), p.rows()));
                    }
                }
            });
        }

        /** Put {@code texture}, {@code w x h}, on the plate, scaled with the view's filter. */
        void showing(SampledImage texture, int w, int h) {
            textureW = w;
            textureH = h;
            plate.imageFilter(filter, w, h);
            plate(texture, ImageRegion.WHOLE);
        }

        /** Put {@code region} of {@code texture} on the plate, mirrored as the pose has it. */
        void plate(SampledImage texture, ImageRegion region) {
            onPlate = texture;
            plateRegion = region;
            drawnX = pose.mirrorX();
            drawnY = pose.mirrorY();
            plate.image(texture, mirrored(region));
        }

        /** {@code r} read the other way along each axis the pose mirrors. */
        ImageRegion mirrored(ImageRegion r) {
            Pose at = pose;
            if (!at.mirrorX() && !at.mirrorY()) {
                return r;
            }
            return new ImageRegion(at.mirrorX() ? r.u1() : r.u0(), at.mirrorY() ? r.v1() : r.v0(),
                    at.mirrorX() ? r.u0() : r.u1(), at.mirrorY() ? r.v0() : r.v1());
        }

        /** Hold the picture's playing, or let it go: for a window that is not focused. */
        void hold(boolean hold) {
            if (playback != null) {
                playback.hold(hold);
            }
        }

        void place(float alpha, float dx) {
            place(alpha, dx, 0f);
        }

        void place(float alpha, float dx, float tilt) {
            this.alpha = alpha;
            this.dx = dx;
            this.tilt = tilt;
            draw();
        }

        /** Show nothing: stop the animation and stop naming the texture. */
        void clear() {
            generation.incrementAndGet();
            if (animation != null) {
                animation.cancel();
                animation = null;
            }
            if (playback != null) {
                playback.close();
                playback = null;
            }
            textureW = 0;
            textureH = 0;
            if (picture != null) {
                picture = null;
                onPlate = null;
                plateRegion = ImageRegion.WHOLE;
                plate.image(null);
            }
            sharpen(null, 1f, 0f, 0f, 0f, 0f);
            frame.visible(false);
            place(1f, 0f);
        }

        /**
         * Lay {@code texture} over the part of the picture from {@code (u0, v0)} to {@code (u1, v1)}, or take the one
         * there away for null, and give back the one it replaces.
         */
        void sharpen(SampledImage texture, float zoom, float u0, float v0, float u1, float v1) {
            SampledImage old = sharp;
            sharp = texture;
            sharpZoom = zoom;
            sharpU0 = u0;
            sharpV0 = v0;
            sharpU1 = u1;
            sharpV1 = v1;
            if (texture == null) {
                detail.visible(false).image(null);
            } else {
                placeDetail();
                detail.visible(sharp());
            }
            if (old != null && old != texture) {
                textures.release(old);
            }
        }

        /** Lay the part where it lies on the picture as the pose mirrors it, and read it the same way. */
        void placeDetail() {
            Pose at = pose;
            float u0 = at.mirrorX() ? 1f - sharpU1 : sharpU0;
            float v0 = at.mirrorY() ? 1f - sharpV1 : sharpV0;
            detail.floatAt(Length.percent(100f * u0), Length.percent(100f * v0))
                    .size(Length.percent(100f * (sharpU1 - sharpU0)), Length.percent(100f * (sharpV1 - sharpV0)))
                    .image(sharp, mirrored(ImageRegion.WHOLE));
        }

        /** Whether the part is shown: there is one, and the picture is at the zoom it was made for. */
        boolean sharp() {
            return sharp != null && Math.abs(pose.zoom() - sharpZoom) <= 1e-3f * sharpZoom;
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

        /**
         * Size the frame to the stage at its pose, place it, and turn it; redraw the checks if the size changed. In
         * percent of the stage, so a resize keeps the picture where it was relative to the stage.
         */
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

        /**
         * Draw the frame where its pose and the transition put it: moved, scaled and turned about its centre, all
         * visual transforms, so a pose changed every frame lays nothing out. A mirror changed since the last draw
         * reads the plate and the part the other way.
         */
        void draw() {
            Pose at = pose;
            float em = emPx;
            frame.opacity(alpha).translate(dx + at.x() / em, at.y() / em).scale(at.zoom())
                    .rotate(at.degrees() + tilt);
            if (at.mirrorX() != drawnX || at.mirrorY() != drawnY) {
                drawnX = at.mirrorX();
                drawnY = at.mirrorY();
                if (onPlate != null) {
                    plate.image(onPlate, mirrored(plateRegion));
                }
                if (sharp != null) {
                    placeDetail();
                }
            }
            detail.visible(sharp());
        }
    }
}
