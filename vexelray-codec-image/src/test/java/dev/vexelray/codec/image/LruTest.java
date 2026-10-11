package dev.vexelray.codec.image;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Recent first, bounded by weight, and the newest is never the one turned away. */
class LruTest {

    private final List<String> gone = new ArrayList<>();
    private final Lru<String, String> lru = new Lru<>(10, String::length, gone::add);

    @Test
    void theLeastRecentlyUsedLeavesFirst() {
        lru.put("a", "aaaa");
        lru.put("b", "bbbb");
        lru.get("a");                 // a is now the recent one
        lru.put("c", "cccc");         // 12 > 10: b goes, not a
        assertEquals(List.of("bbbb"), gone);
        assertNull(lru.get("b"));
        assertEquals("aaaa", lru.get("a"));
        assertEquals(8, lru.weight());
    }

    @Test
    void anEntryOverBudgetAloneIsKeptAndEverythingElseGoes() {
        lru.put("a", "aa");
        lru.put("big", "x".repeat(25));
        assertEquals(List.of("aa"), gone);
        assertEquals(1, lru.size());
        assertEquals(25, lru.weight());
    }

    @Test
    void replacingAKeyHandsBackTheOldValue() {
        lru.put("a", "one");
        lru.put("a", "two");
        assertEquals(List.of("one"), gone);
        assertEquals(3, lru.weight());
    }

    @Test
    void clearHandsBackEverything() {
        lru.put("a", "aa");
        lru.put("b", "bb");
        lru.clear();
        assertEquals(List.of("aa", "bb"), gone);
        assertEquals(0, lru.weight());
    }
}
