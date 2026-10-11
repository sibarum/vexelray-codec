package dev.vexelray.codec.image;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * A least-recently-used cache bounded by weight rather than by count, which is the bound that matters when one entry
 * is a 32x32 icon and the next is a photograph.
 *
 * <p>LRU because browsing goes back to what it saw recently: arrowing down a folder and up again should find the last
 * few pictures waiting. The entry just added is never the one evicted, even when it alone is over budget, so a single
 * large image is still shown rather than refused; it simply leaves nothing else room to stay.
 *
 * <p>{@code evicted} is told about each value as it leaves, under the cache's lock, so a cache of GPU textures can
 * hand each one back. Thread-safe, though a cache used from one thread pays nothing for it.
 */
public final class Lru<K, V> {

    private final long budget;
    private final ToLongFunction<V> weigher;
    private final Consumer<V> evicted;
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;

    public Lru(long budget, ToLongFunction<V> weigher, Consumer<V> evicted) {
        this.budget = budget;
        this.weigher = weigher;
        this.evicted = evicted;
    }

    /** The value for {@code key}, now the most recently used, or null. */
    public synchronized V get(K key) {
        return map.get(key);
    }

    /** Hold {@code value} as the most recently used, evicting the least recently used until within budget. */
    public synchronized void put(K key, V value) {
        V old = map.put(key, value);
        if (old != null) {
            weight -= weigher.applyAsLong(old);
            if (old != value) {
                evicted.accept(old);
            }
        }
        weight += weigher.applyAsLong(value);
        Iterator<Map.Entry<K, V>> eldest = map.entrySet().iterator();
        while (weight > budget && map.size() > 1) {
            Map.Entry<K, V> e = eldest.next();
            eldest.remove();
            weight -= weigher.applyAsLong(e.getValue());
            evicted.accept(e.getValue());
        }
    }

    /** Evict everything. */
    public synchronized void clear() {
        for (V v : map.values()) {
            evicted.accept(v);
        }
        map.clear();
        weight = 0;
    }

    public synchronized long weight() {
        return weight;
    }

    public synchronized int size() {
        return map.size();
    }
}
