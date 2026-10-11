package dev.vexelray.codec.image.gui;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.gui.core.app.GuiApp;

/**
 * Pictures, as a starter: name it and an application has {@link Textures}, bound to its window when the window exists
 * and unbound at shutdown.
 *
 * {@snippet :
 * @VexelApp(name = "pix", title = "Pix", starters = ImageStarter.class)
 * final class PixApp { }
 * }
 *
 * <p>The part every application showing a picture otherwise writes for itself: a {@code Textures} with the tree, and a
 * main-thread binding that hands it the {@code GuiApp} and takes it back on close. A part that shows pictures takes
 * {@code Textures} and is built before the window, as a tree is.
 *
 * <p><b>A {@code @Default}, because the application may want its own:</b> a {@code @Provides Textures} of the
 * application's (with another budget, say) backs this one off, and the binding binds that one instead.
 */
@Configuration
public final class ImageStarter {

    /** The texture cache, built with the tree and bound by {@link #texturesBinding}. */
    @Default
    @Provides
    public Textures textures() {
        return new Textures();
    }

    /** Gives the textures the device, and takes it back at shutdown. */
    @Provides
    @MainThread
    public Textures.Binding texturesBinding(GuiApp app, Textures textures) {
        return textures.bound(app);
    }
}
