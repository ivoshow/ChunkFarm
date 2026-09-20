package dev.ivoshow.chunkfarm;

import dev.ivoshow.chunkfarm.util.Text;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextTest {

    @Test
    void groupsLargeAmountsTheBrazilianWay() {
        assertEquals("1.234.567", Text.number(1_234_567L).replace(' ', '.'));
    }

    @Test
    void compactFormShortensTheNumbersThatDoNotFitInLore() {
        assertEquals("999", Text.compact(999));
        assertEquals("1K", Text.compact(1_000));
        assertEquals("1,5K", Text.compact(1_500));
        assertEquals("2M", Text.compact(2_000_000));
        assertEquals("12,3M", Text.compact(12_300_000));
        assertEquals("4B", Text.compact(4_000_000_000L));
    }

    @Test
    void prettifiesEnumNames() {
        assertEquals("Wheat Seeds", Text.prettify("WHEAT_SEEDS"));
        assertEquals("Cactus", Text.prettify("CACTUS"));
    }
}
