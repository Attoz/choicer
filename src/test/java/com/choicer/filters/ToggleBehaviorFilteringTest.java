package com.choicer.filters;

import com.choicer.ChoicerConfig;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that config toggles affecting item eligibility are enforced.
 */
public class ToggleBehaviorFilteringTest
{
    private ChoicerConfig config;
    private final IntPredicate neverTracked = id -> false;

    @Before
    public void setUp()
    {
        config = mock(ChoicerConfig.class);
        when(config.enableFlatpacks()).thenReturn(true);
        when(config.enableItemSets()).thenReturn(true);
        when(config.requireWeaponPoison()).thenReturn(false);
        when(config.freeToPlay()).thenReturn(false);
        when(config.includeF2PTradeOnlyItems()).thenReturn(false);
        when(config.includeUntradeable()).thenReturn(false);
        when(config.includeQuestItems()).thenReturn(false);
    }

    @Test
    public void flatpacksBlockedWhenToggleDisabled()
    {
        when(config.enableFlatpacks()).thenReturn(false);

        int flatpackId = Flatpacks.Crudechair.getId();
        ItemAttributes attributes = new ItemAttributes("Flatpack", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                flatpackId,
                flatpackId,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertFalse(eligible);
    }

    @Test
    public void flatpacksAllowedWhenToggleEnabled()
    {
        when(config.enableFlatpacks()).thenReturn(true);

        int flatpackId = Flatpacks.Crudechair.getId();
        ItemAttributes attributes = new ItemAttributes("Flatpack", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                flatpackId,
                flatpackId,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertTrue(eligible);
    }

    @Test
    public void itemSetsBlockedWhenToggleDisabled()
    {
        when(config.enableItemSets()).thenReturn(false);

        int itemSetId = ItemSets.DwarfCannon.getId();
        ItemAttributes attributes = new ItemAttributes("Item Set", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                itemSetId,
                itemSetId,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertFalse(eligible);
    }

    @Test
    public void itemSetsAllowedWhenToggleEnabled()
    {
        when(config.enableItemSets()).thenReturn(true);

        int itemSetId = ItemSets.DwarfCannon.getId();
        ItemAttributes attributes = new ItemAttributes("Item Set", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                itemSetId,
                itemSetId,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertTrue(eligible);
    }

    @Test
    public void poisonVariantsRequireGlobalPoisonWhenEnabled()
    {
        when(config.requireWeaponPoison()).thenReturn(true);

        int poisonVariantId = PoisonWeapons.BRONZE_DAGGER.getPoisonId();
        int baseWeaponId = PoisonWeapons.BRONZE_DAGGER.getBaseId();
        int requiredPoisonId = PoisonWeapons.WEAPON_POISON.getBaseId();
        ItemAttributes attributes = new ItemAttributes("Poisoned Dagger", true, false, -1);

        Set<Integer> unlockedMissingGlobal = new HashSet<>();
        unlockedMissingGlobal.add(baseWeaponId);

        boolean blockedWithoutGlobalPoison = ItemEligibility.shouldInclude(
                attributes,
                poisonVariantId,
                poisonVariantId,
                config,
                unlockedMissingGlobal,
                neverTracked
        );

        assertFalse(blockedWithoutGlobalPoison);

        Set<Integer> unlockedWithGlobal = new HashSet<>();
        unlockedWithGlobal.add(baseWeaponId);
        unlockedWithGlobal.add(requiredPoisonId);

        boolean allowedWithGlobalPoison = ItemEligibility.shouldInclude(
                attributes,
                poisonVariantId,
                poisonVariantId,
                config,
                unlockedWithGlobal,
                neverTracked
        );

        assertTrue(allowedWithGlobalPoison);
    }

    @Test
    public void poisonVariantsIgnoreGlobalPoisonWhenToggleDisabled()
    {
        when(config.requireWeaponPoison()).thenReturn(false);

        int poisonVariantId = PoisonWeapons.BRONZE_DAGGER.getPoisonId();
        ItemAttributes attributes = new ItemAttributes("Poisoned Dagger", true, false, -1);

        boolean eligible = ItemEligibility.shouldInclude(
                attributes,
                poisonVariantId,
                poisonVariantId,
                config,
                Collections.emptySet(),
                neverTracked
        );

        assertTrue(eligible);
    }
}
