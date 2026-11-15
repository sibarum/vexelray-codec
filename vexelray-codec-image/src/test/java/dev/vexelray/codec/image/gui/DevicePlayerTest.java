package dev.vexelray.codec.image.gui;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Playable;
import dev.vexelray.codec.image.Stamp;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Feeds;
import dev.vexelray.gui.core.Retirement;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@link Player} playing into a device feed (vexelray-gui's docs/plans/device-feed.md, step 3): it tells its frames'
 * source that no mips are wanted, and writes each frame's first level into the slot's staging, where the frame that
 * shows it will land it. On a real device; skipped where there is no Vulkan. {@code PlayerTest} holds the rest.
 */
class DevicePlayerTest {

    private static final int W = 4;
    private static final int H = 2;
    private long now;

    /** Frames of {@code W x H}, frame {@code i} filled with {@code i + 1}, level 0 only once told no mips are wanted. */
    private static final class Strip implements Playable {
        Boolean mipsWanted;

        @Override
        public int width() {
            return W;
        }

        @Override
        public int height() {
            return H;
        }

        @Override
        public int count() {
            return 3;
        }

        @Override
        public long duration() {
            return 30;
        }

        @Override
        public int delay(int i) {
            return 10;
        }

        @Override
        public long startOf(int i) {
            return i * 10L;
        }

        @Override
        public int frameAt(long ms) {
            return (int) Math.max(0, Math.min(2, ms / 10));
        }

        @Override
        public void want(int i, boolean wrap) {
        }

        @Override
        public byte[][] frame(int i) {
            byte[] px = new byte[W * H * 4];
            Arrays.fill(px, (byte) (i + 1));
            return new byte[][] {px};
        }

        @Override
        public void mips(boolean wanted) {
            mipsWanted = wanted;
        }

        @Override
        public String failure() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void itWritesLevelZeroIntoStagingAndAsksForNoMips() {
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("device player test", List.of());
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no Vulkan loader: " + e.getMessage());
            throw e;
        }
        Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
        if (selection.isEmpty()) {
            instance.close();
            assumeTrue(false, "no device with a graphics queue");
        }
        VulkanDevice device = new VulkanDevice(instance.handle(), selection.orElseThrow(),
                new VulkanDevice.Request(false, null, 1));
        Retirement retirement = new Retirement(device);
        Feeds feeds = new Feeds(device, retirement);
        try {
            Feed feed = Player.feed(feeds, W, H);
            assertTrue(feed.onDevice());
            assertEquals(Player.DEVICE_SLOTS, feed.slots());
            Strip s = new Strip();
            Motion motion = new Motion(new Stamp(Path.of("strip.gif"), 1, 1), 3, 30, 0);
            Player p = Player.forTest(feed, motion, () -> now, (m, side) -> s);
            assertTrue(p.open());
            assertNotNull(s.mipsWanted, "the source was told");
            assertFalse(s.mipsWanted, "a device feed's mips are the GPU's");

            assertEquals(0L, p.once(), "the first frame is made and handed over");
            int shown = feed.newest();
            byte[] want = new byte[W * H * 4];
            Arrays.fill(want, (byte) 1);
            assertArrayEquals(want, feed.deviceSlots().staging(shown).toArray(ValueLayout.JAVA_BYTE));
            p.shut();
        } finally {
            feeds.close();
            retirement.close();
            device.close();
            instance.close();
        }
    }

    @Test
    void withNoDeviceItIsTheFeedInTheHeap() {
        Feed feed = Player.feed(null, W, H);
        assertFalse(feed.onDevice());
        assertEquals(Player.SLOTS, feed.slots());
    }
}
