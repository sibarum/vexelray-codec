package dev.vexelray.codec.image.gui;

import dev.vexelray.codec.image.Lru;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.StreamTexture;

import java.util.function.Consumer;

/**
 * Pictures on the GPU: each one uploaded once, the recent ones kept, and the rest given back.
 *
 * <p>Late-bound: a texture belongs to the window's device, which does not exist when the tree is built, and a part
 * taking {@code GuiApp} directly would pull the tree into the window's phase. The application {@linkplain #bind binds}
 * it when its window exists, and until then {@link #show} uploads nothing. Under the framework, {@link ImageStarter}
 * does the binding.
 *
 * <p><b>Release, not close.</b> An evicted texture may still be drawn by a frame in flight, or still be named by a node
 * for a frame, so it goes to {@code GuiApp.release}, which closes it once no frame samples it. Closing it here would be
 * a use-after-free on the GPU.
 *
 * <p>Everything but {@link #show} runs on the GUI thread, where the cache lives; {@code show} may be called from
 * anywhere and posts there.
 */
public final class Textures {

    /** GPU memory for recent pictures, by default. A 1024 px still is 4 MB, so this keeps a folder's worth. */
    public static final long BUDGET = 192L * 1024 * 1024;

    /** Slots in a picture that plays: one shown, one landing, one being written. */
    static final int SLOTS = 3;

    private final long budget;
    private volatile GuiApp app;
    private Lru<Key, Uploaded> cache;

    /** One source at one size: a thumbnail and a full view of the same file are two textures. */
    private record Key(Object key, int width, int height) {
        static Key of(Picture p) {
            return new Key(p.key(), p.sheetWidth(), p.sheetHeight());
        }
    }

    /** A texture and what it costs, which the texture itself does not say. */
    private record Uploaded(AtlasTexture texture, long bytes) {
    }

    /** Textures within {@link #BUDGET}. */
    public Textures() {
        this(BUDGET);
    }

    /** Textures within {@code budget} bytes of GPU memory, past which the least recently shown go back. */
    public Textures(long budget) {
        this.budget = budget;
    }

    /** Main thread. Null on the way out, after which nothing is uploaded; the application closes what is left. */
    public void bind(GuiApp app) {
        this.app = app;
        this.cache = app == null ? null : new Lru<>(budget, Uploaded::bytes, u -> app.release(u.texture()));
    }

    /** {@link #bind} {@code app} now, and unbind when the result is closed. Main thread, both. */
    public Binding bound(GuiApp app) {
        bind(app);
        return new Binding(this);
    }

    /**
     * Hand {@code picture}'s texture to {@code then} on the GUI thread, uploading it if it is not already there.
     * False if there is no window to upload into, in which case {@code then} is never called.
     *
     * <p>A still is uploaded as a picture ({@code GuiApp.picture}): with its mip chain, and read in linear light, so
     * drawn small it is averaged rather than skipped. An animation's sheet is not, since a level would blend one
     * frame's edge into the next frame's.
     */
    public boolean show(Picture picture, Consumer<SampledImage> then) {
        return show(picture, !picture.animated(), then);
    }

    /**
     * Hand {@code picture}'s texture to {@code then} as {@link #show} does, but as data: its texels stored and read
     * as they are, with no mip chain and no decoding. For a picture whose values are not colours, such as a
     * {@code MaskField}.
     */
    public boolean showData(Picture picture, Consumer<SampledImage> then) {
        return show(picture, false, then);
    }

    private boolean show(Picture picture, boolean asPicture, Consumer<SampledImage> then) {
        GuiApp window = app;
        if (window == null) {
            return false;
        }
        window.post(() -> {
            Lru<Key, Uploaded> textures = cache;
            if (textures == null || app != window) {
                return;
            }
            Key key = Key.of(picture);
            Uploaded u = textures.get(key);
            if (u == null || u.texture().isClosed()) {
                AtlasTexture t = asPicture
                        ? window.picture(picture.sheet(), picture.sheetWidth(), picture.sheetHeight())
                        : window.texture(picture.sheet(), picture.sheetWidth(), picture.sheetHeight());
                // A chain is a third again of the image's own bytes.
                u = new Uploaded(t, asPicture ? picture.bytes() * 4 / 3 : picture.bytes());
                textures.put(key, u);
            }
            then.accept(u.texture());
        });
        return true;
    }

    /**
     * Upload {@code picture} as a texture of its own, outside the cache, and hand it to {@code then} on the GUI thread:
     * for a picture shown once and replaced, like a detail, which would only push the folder's pictures out. The caller
     * owns it and gives it back with {@link #release}. False if there is no window to upload into.
     */
    public boolean upload(Picture picture, Consumer<SampledImage> then) {
        GuiApp window = app;
        if (window == null) {
            return false;
        }
        window.post(() -> {
            if (app != window) {
                return;
            }
            then.accept(window.texture(picture.sheet(), picture.sheetWidth(), picture.sheetHeight()));
        });
        return true;
    }

    /**
     * Give back a texture from {@link #upload}, once no node names it: closed when no frame in flight still samples it.
     * From any thread; nothing, if the window has gone, which closes what is left.
     */
    public void release(SampledImage texture) {
        GuiApp window = app;
        if (window == null || !(texture instanceof AtlasTexture t)) {
            return;
        }
        window.post(() -> {
            if (app == window) {
                window.release(t);
            }
        });
    }

    /**
     * Make a stream of {@code width x height} pictures ({@code GuiApp.stream}), with a mip chain per frame, and hand it
     * to {@code then} on the GUI thread: what a picture that plays is shown through. Outside the cache; the caller owns
     * it and gives it back with {@link #release(StreamTexture)}. False if there is no window to make it in.
     */
    public boolean stream(int width, int height, Consumer<StreamTexture> then) {
        GuiApp window = app;
        if (window == null) {
            return false;
        }
        window.post(() -> {
            if (app == window) {
                then.accept(window.stream(width, height, SLOTS, true));
            }
        });
        return true;
    }

    /**
     * Copy {@code chain}, one frame's mip chain, into a free slot of {@code stream} and hand the slot to {@code then} on
     * the GUI thread, which points a node at it; the slot shown before is then free again. {@code then} is given null
     * if every slot is still in use, and the frame is not shown. False if there is no window.
     */
    public boolean present(StreamTexture stream, byte[][] chain, Consumer<SampledImage> then) {
        GuiApp window = app;
        if (window == null) {
            return false;
        }
        window.post(() -> {
            if (app != window || stream.isClosed()) {
                return;
            }
            StreamTexture.Slot slot = stream.take();
            if (slot == null) {
                then.accept(null);
                return;
            }
            slot.write(chain);
            stream.submit(slot);
            then.accept(slot);
            stream.show(slot);
        });
        return true;
    }

    /**
     * Give back a stream from {@link #stream}, once no node names any of its slots: closed when no frame in flight
     * still samples one. From any thread; nothing, if the window has gone, which closes what is left.
     */
    public void release(StreamTexture stream) {
        GuiApp window = app;
        if (window == null || stream == null) {
            return;
        }
        window.post(() -> {
            if (app == window) {
                window.release(stream);
            }
        });
    }

    /**
     * Run {@code then} on the GUI thread after every {@link #show} already asked for has handed over its texture, so a
     * change that must land in the same frame as some pictures (a strip of thumbnails starting to slide) does. At once
     * if there is no window, since nothing is coming.
     */
    public void then(Runnable then) {
        GuiApp window = app;
        if (window == null) {
            then.run();
            return;
        }
        window.post(then);
    }

    /** {@link Textures} bound to a window until this is closed: what {@link ImageStarter} hands the shutdown. */
    public static final class Binding implements AutoCloseable {

        private final Textures textures;

        private Binding(Textures textures) {
            this.textures = textures;
        }

        public Textures textures() {
            return textures;
        }

        /** Unbind. Main thread. */
        @Override
        public void close() {
            textures.bind(null);
        }
    }
}
