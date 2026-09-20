package dev.ivoshow.chunkfarm;

import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemKeyTest {

    @Test
    void plainItemsRoundTripThroughTheSaveFile() {
        ItemKey key = ItemKey.of(Material.SUGAR_CANE);
        assertEquals("SUGAR_CANE", key.serialize());
        assertEquals(key, ItemKey.deserialize(key.serialize()));
    }

    @Test
    void serializedFormCarriesNoCharacterThatBreaksTheStorageFormat() {
        // The warehouse is written as "<key>=<amount>" lines, so '=' must not appear
        // inside the key itself.
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            String serialized = ItemKey.of(material).serialize();
            assertEquals(-1, serialized.indexOf('='), material + " serializou com '='");
            assertEquals(-1, serialized.indexOf('.'), material + " serializou com '.'");
        }
    }

    @Test
    void anUnknownMaterialIsRejectedInsteadOfCrashingTheLoad() {
        assertNull(ItemKey.deserialize("SOME_ITEM_FROM_A_REMOVED_PLUGIN"));
    }

    @Test
    void differentMaterialsNeverCollide() {
        assertNotEquals(ItemKey.of(Material.WHEAT), ItemKey.of(Material.WHEAT_SEEDS));
    }
}
