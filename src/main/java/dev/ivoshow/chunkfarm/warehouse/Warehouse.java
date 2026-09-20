package dev.ivoshow.chunkfarm.warehouse;

import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.ArrayList;
import java.util.List;

/**
 * A quantity ledger, not an inventory.
 *
 * <p>Instead of an {@code ItemStack[]} (which caps out at 54 slots x 64 items), the
 * warehouse keeps one {@code long} counter per {@link ItemKey}. A farm can therefore
 * hold billions of a crop while using a few dozen bytes, and a harvest cycle costs one
 * map increment per crop instead of allocating and spawning item entities.
 *
 * <p>Every method is synchronized: harvesting runs on a region thread, the GUI runs on
 * the main thread and persistence snapshots run async.
 */
public final class Warehouse {

    /** Hard ceiling for a single type; keeps totals from overflowing a {@code long}. */
    public static final long ABSOLUTE_MAX = 1_000_000_000_000_000L;

    private final Object2LongLinkedOpenHashMap<ItemKey> contents = new Object2LongLinkedOpenHashMap<>();
    private long total;

    private int maxTypes;
    private long maxPerType;

    public Warehouse(int maxTypes, long maxPerType) {
        this.contents.defaultReturnValue(0L);
        this.maxTypes = maxTypes;
        this.maxPerType = maxPerType;
    }

    /**
     * @return how much of {@code amount} was actually stored; the remainder did not fit
     *         and the caller must decide whether to drop it or abort the harvest
     */
    public synchronized long add(ItemKey key, long amount) {
        if (amount <= 0) {
            return 0;
        }
        long current = this.contents.getLong(key);
        if (current == 0 && this.maxTypes > 0 && this.contents.size() >= this.maxTypes) {
            return 0;
        }
        long ceiling = capacity();
        long accepted = Math.min(amount, ceiling - current);
        if (accepted <= 0) {
            return 0;
        }
        this.contents.put(key, current + accepted);
        this.total += accepted;
        return accepted;
    }

    /** @return how much was actually removed */
    public synchronized long remove(ItemKey key, long amount) {
        if (amount <= 0) {
            return 0;
        }
        long current = this.contents.getLong(key);
        long removed = Math.min(amount, current);
        if (removed <= 0) {
            return 0;
        }
        if (removed == current) {
            this.contents.removeLong(key);
        } else {
            this.contents.put(key, current - removed);
        }
        this.total -= removed;
        return removed;
    }

    public synchronized long get(ItemKey key) {
        return this.contents.getLong(key);
    }

    /** True when {@code amount} of {@code key} would fit without being clipped. */
    public synchronized boolean fits(ItemKey key, long amount) {
        long current = this.contents.getLong(key);
        if (current == 0 && this.maxTypes > 0 && this.contents.size() >= this.maxTypes) {
            return false;
        }
        return current + amount <= capacity();
    }

    public synchronized boolean isFull() {
        if (this.maxTypes > 0 && this.contents.size() < this.maxTypes) {
            return false;
        }
        for (Object2LongMap.Entry<ItemKey> entry : this.contents.object2LongEntrySet()) {
            if (entry.getLongValue() < capacity()) {
                return false;
            }
        }
        return !this.contents.isEmpty();
    }

    public synchronized long total() {
        return this.total;
    }

    public synchronized int typeCount() {
        return this.contents.size();
    }

    public synchronized boolean isEmpty() {
        return this.contents.isEmpty();
    }

    public synchronized void clear() {
        this.contents.clear();
        this.total = 0;
    }

    /** Insertion-ordered snapshot, safe to iterate off-thread (GUI paging, saving). */
    public synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(this.contents.size());
        for (Object2LongMap.Entry<ItemKey> entry : this.contents.object2LongEntrySet()) {
            out.add(new Entry(entry.getKey(), entry.getLongValue()));
        }
        return out;
    }

    public synchronized void resize(int maxTypes, long maxPerType) {
        this.maxTypes = maxTypes;
        this.maxPerType = maxPerType;
    }

    public synchronized int maxTypes() {
        return this.maxTypes;
    }

    public synchronized long maxPerType() {
        return this.maxPerType;
    }

    private long capacity() {
        return this.maxPerType <= 0 ? ABSOLUTE_MAX : Math.min(this.maxPerType, ABSOLUTE_MAX);
    }

    public record Entry(ItemKey key, long amount) {
    }
}
