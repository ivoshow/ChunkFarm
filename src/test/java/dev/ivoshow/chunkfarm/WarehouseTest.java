package dev.ivoshow.chunkfarm;

import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarehouseTest {

    private static final ItemKey WHEAT = ItemKey.of(Material.WHEAT);
    private static final ItemKey SEEDS = ItemKey.of(Material.WHEAT_SEEDS);

    @Test
    void holdsFarMoreThanAnInventoryCould() {
        Warehouse warehouse = new Warehouse(90, 0);

        // Roughly a week of a busy farm, well past Integer.MAX_VALUE.
        long huge = 5_000_000_000L;
        assertEquals(huge, warehouse.add(WHEAT, huge));
        assertEquals(huge, warehouse.get(WHEAT));
        assertEquals(huge, warehouse.total());
        assertEquals(1, warehouse.typeCount());
    }

    @Test
    void clipsAtThePerTypeCapAndReportsWhatFit() {
        Warehouse warehouse = new Warehouse(90, 1_000);

        assertEquals(1_000, warehouse.add(WHEAT, 4_000));
        assertEquals(0, warehouse.add(WHEAT, 1));
        assertEquals(1_000, warehouse.total());
        assertFalse(warehouse.fits(WHEAT, 1));
    }

    @Test
    void refusesNewTypesOnceTheTypeLimitIsReached() {
        Warehouse warehouse = new Warehouse(1, 0);

        assertEquals(10, warehouse.add(WHEAT, 10));
        assertEquals(0, warehouse.add(SEEDS, 10));
        // The type already stored is still accepted.
        assertEquals(5, warehouse.add(WHEAT, 5));
        assertEquals(1, warehouse.typeCount());
    }

    @Test
    void removalNeverGoesNegativeAndDropsEmptyTypes() {
        Warehouse warehouse = new Warehouse(90, 0);
        warehouse.add(WHEAT, 100);

        assertEquals(100, warehouse.remove(WHEAT, 500));
        assertEquals(0, warehouse.get(WHEAT));
        assertEquals(0, warehouse.total());
        assertEquals(0, warehouse.typeCount());
        assertTrue(warehouse.isEmpty());
    }

    @Test
    void isFullOnlyWhenEveryTypeSlotIsSaturated() {
        Warehouse warehouse = new Warehouse(2, 100);
        assertFalse(warehouse.isFull(), "vazio nao esta cheio");

        warehouse.add(WHEAT, 100);
        assertFalse(warehouse.isFull(), "ainda cabe outro tipo");

        warehouse.add(SEEDS, 100);
        assertTrue(warehouse.isFull());

        warehouse.remove(SEEDS, 1);
        assertFalse(warehouse.isFull());
    }

    @Test
    void snapshotKeepsInsertionOrderSoTheGuiDoesNotShuffle() {
        Warehouse warehouse = new Warehouse(90, 0);
        warehouse.add(SEEDS, 1);
        warehouse.add(WHEAT, 1);
        warehouse.add(ItemKey.of(Material.CARROT), 1);

        List<Warehouse.Entry> snapshot = warehouse.snapshot();
        assertEquals(List.of(Material.WHEAT_SEEDS, Material.WHEAT, Material.CARROT),
                snapshot.stream().map(entry -> entry.key().material()).toList());

        // Topping up an existing type must not move it to the end of the menu.
        warehouse.add(SEEDS, 50);
        assertEquals(Material.WHEAT_SEEDS, warehouse.snapshot().getFirst().key().material());
    }
}
