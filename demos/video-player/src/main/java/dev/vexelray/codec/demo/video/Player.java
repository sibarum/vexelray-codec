package dev.vexelray.codec.demo.video;

import dev.vexelray.framework.shell.VexelApplication;

/**
 * The smallest application that plays a video: {@code Player <file>} opens it at most {@link #SIDE} on its long side and
 * plays it, looping, in one {@code VideoView} filling the window.
 *
 * <pre>
 * Space        pause, or play on
 * ← / →        five seconds back or on
 * Home         the start
 * </pre>
 *
 * <p>Two readouts for a driver, by landmark: {@code player.state} says what was opened and whether it plays, and
 * {@code player.stats} how many distinct frames reached the plate in the last second and where the clock is.
 */
public final class Player {

    static final String APP = "video-player-demo";
    static final String TITLE = "Video";
    static final int W = 1280;
    static final int H = 760;

    /** The longest side a frame is decoded at: a 1080p file plays at its own size. */
    static final int SIDE = 1920;

    public static void main(String[] args) {
        String[] cleaned = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        VexelApplication.run(new PlayerAppWiring(), cleaned);
    }

    private Player() {
    }
}
