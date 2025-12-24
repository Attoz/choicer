package com.choicer.chanceman.filters;

import com.choicer.chanceman.ChanceManConfig;
import com.choicer.chanceman.filters.ItemAttributes;
import com.choicer.chanceman.filters.ItemEligibility;
import com.choicer.chanceman.filters.QuestItemAllowlist;

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
 * Verifies that quest items are only eligible when the Include Quest Items toggle is enabled.
 */
public class QuestItemFilteringTest
{
    private static final int QUEST_ITEM_ID = ItemID.ABYSSAL_WHIP;

    private ChanceManConfig config;
    private final IntPredicate neverTracked = id -> false;

    @Before
    public void setUp()
    {
        config = mock(ChanceManConfig.class);
        when(config.enableFlatpacks()).thenReturn(true);
        when(config.enableItemSets()).thenReturn(true);
        when(config.requireWeaponPoison()).thenReturn(false);
        when(config.freeToPlay()).thenReturn(false);
        when(config.includeF2PTradeOnlyItems()).thenReturn(false);
        when(config.includeUntradeable()).thenReturn(false);

        QuestItemAllowlist.clearForTesting();
        QuestItemAllowlist.registerQuestItemForTesting(QUEST_ITEM_ID);
    }

    @Test
    public void questItemsBlockedWhenToggleDisabled()
    {
        when(config.includeQuestItems()).thenReturn(false);

        ItemAttributes attributes = new ItemAttributes("Quest Item", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                QUEST_ITEM_ID,
                QUEST_ITEM_ID,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertFalse(eligible);
    }

    @Test
    public void questItemsAllowedWhenToggleEnabled()
    {
        when(config.includeQuestItems()).thenReturn(true);

        ItemAttributes attributes = new ItemAttributes("Quest Item", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                QUEST_ITEM_ID,
                QUEST_ITEM_ID,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertTrue(eligible);
    }
}
