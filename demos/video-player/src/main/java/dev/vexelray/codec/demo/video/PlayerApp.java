package dev.vexelray.codec.demo.video;

import dev.vexelray.codec.image.gui.ImageStarter;
import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/** {@link Player}'s facts, the textures ({@link ImageStarter}) and the driving socket. A demo has one edition. */
@VexelApp(name = Player.APP, title = Player.TITLE, width = Player.W, height = Player.H,
        starters = {ImageStarter.class, AutomationStarter.class})
final class PlayerApp {

    private PlayerApp() {
    }
}
