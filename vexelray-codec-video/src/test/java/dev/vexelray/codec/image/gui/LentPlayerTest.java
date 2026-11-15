package dev.vexelray.codec.image.gui;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Stamp;
import dev.vexelray.codec.video.Fixtures;
import dev.vexelray.codec.video.Footage;
import dev.vexelray.codec.video.Videos;
import dev.vexelray.gui.core.Feed;
import dev.vexelray.gui.core.Feeds;
import dev.vexelray.gui.core.Retirement;
import dev.vexelray.vulkan.present.FeedSlots;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A video through the real producer onto a real device, decoded straight into memory its feed lends: the player hands
 * each frame over by its token with nothing copied on the CPU, the GPU lands the slot from where the decoder put it,
 * and the picture is the video's. Once the footage is closed, its thread lets go of the memory, and the feed's slots
 * are freed. Skipped where there is no Vulkan.
 */
class LentPlayerTest {

    private static final long DEADLINE_NS = 10_000_000_000L;

    @Test
    void aVideoIsDecodedIntoTheFeedsMemoryAndLandedFromThere() throws Exception {
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("lent player test", List.of());
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
            Path file = Fixtures.file("rgb.mp4");
            Footage footage = new Footage(Videos.open(file, 0).clip());
            int w = footage.width();
            int h = footage.height();
            Feed feed = Player.feed(feeds, w, h);
            Motion motion = new Motion(new Stamp(file, 1, 1), footage.count(), footage.duration(), 0);
            Player p = Player.forTest(feed, motion, System::nanoTime, (m, side) -> footage);
            assertTrue(p.open());
            assertTrue(feed.lent(), "an H.264 video's frames decoded into memory the feed lends");

            long end = System.nanoTime() + DEADLINE_NS;
            while (feed.newest() < 0 && System.nanoTime() < end) {
                p.once();
                Thread.sleep(1);
            }
            int slot = feed.select();
            assertTrue(slot >= 0, "a frame handed over");
            assertTrue(feed.landNow(slot));
            FeedSlots slots = feed.deviceSlots();
            FeedSlots.oneShot(device, cmd -> slots.land(cmd, slot, feed.lentChunk(slot), feed.lentOffsets(slot),
                    feed.lentStrides(slot)));
            byte[] px = slots.read(slot, 0);
            assertTrue(Fixtures.dominant(px, w, h, 0), "the first second is red");

            p.shut();
            end = System.nanoTime() + DEADLINE_NS;
            while (!feed.letGone() && System.nanoTime() < end) {
                Thread.sleep(1);
            }
            assertTrue(feed.letGone(), "the footage's thread lets go of the memory once its decoder is closed");
            feed.close();
            List<AutoCloseable> out = new ArrayList<>();
            feeds.sweep(out);
            assertEquals(1, out.size(), "and the slots, closed and let go, are freed");
            out.get(0).close();
        } finally {
            feeds.close();
            retirement.close();
            device.close();
            instance.close();
        }
    }
}
