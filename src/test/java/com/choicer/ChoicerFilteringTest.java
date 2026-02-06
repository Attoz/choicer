package com.choicer;

import com.choicer.ChoicerConfig;
import com.choicer.filters.ItemAttributes;
import com.choicer.filters.ItemEligibility;
import net.runelite.api.ItemID;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the Free-to-Play, trade-only, and untradeable config toggles interact correctly
 * when determining if an item can participate in Choicer rolls.
 */
public class ChoicerFilteringTest
{
    private static final int UNTRADEABLE_ALLOWLIST_ID = 8844;

    private ChoicerConfig config;
    private final IntPredicate neverTracked = id -> false;

    @Before
    public void setUp()
    {
        config = mock(ChoicerConfig.class);
        when(config.enableFlatpacks()).thenReturn(true);
        when(config.enableItemSets()).thenReturn(true);
        when(config.requireWeaponPoison()).thenReturn(false);
    }

    @Test
    public void freeToPlayBlocksTradeOnlyItemsWhenNotIncluded()
    {
        when(config.freeToPlay()).thenReturn(true);
        when(config.includeF2PTradeOnlyItems()).thenReturn(false);
        when(config.includeUntradeable()).thenReturn(false);

        ItemAttributes attributes = new ItemAttributes("Test Item", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                ItemID.RING_OF_COINS,
                ItemID.RING_OF_COINS,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertFalse(eligible);
    }

    @Test
    public void freeToPlayAllowsTradeOnlyItemsWhenIncluded()
    {
        when(config.freeToPlay()).thenReturn(true);
        when(config.includeF2PTradeOnlyItems()).thenReturn(true);
        when(config.includeUntradeable()).thenReturn(false);

        ItemAttributes attributes = new ItemAttributes("Test Item", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                ItemID.RING_OF_COINS,
                ItemID.RING_OF_COINS,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertTrue(eligible);
    }

    @Test
    public void freeToPlayStillAllowsAllowlistedUntradeables()
    {
        when(config.freeToPlay()).thenReturn(true);
        when(config.includeF2PTradeOnlyItems()).thenReturn(false);
        when(config.includeUntradeable()).thenReturn(true);

        ItemAttributes attributes = new ItemAttributes("Test Item", false, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                UNTRADEABLE_ALLOWLIST_ID,
                UNTRADEABLE_ALLOWLIST_ID,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertTrue(eligible);
    }
}
