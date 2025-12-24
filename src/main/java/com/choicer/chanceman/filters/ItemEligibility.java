package com.choicer.chanceman.filters;

import com.choicer.chanceman.ChanceManConfig;

import java.util.Collections;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Centralizes the item eligibility logic so it can be tested without the plugin runtime.
 */
public final class ItemEligibility
{
    private ItemEligibility() { }

    public static boolean shouldInclude(
            ItemAttributes attributes,
            int itemId,
            int canonicalItemId,
            ChanceManConfig config,
            Set<Integer> unlockedItems,
            IntPredicate notTrackedPredicate
    )
    {
        if (attributes == null)
        {
            return false;
        }
        String name = attributes.getName();
        if (name == null)
        {
            return false;
        }
        name = name.trim();
        if (name.isEmpty()
                || name.equalsIgnoreCase("null")
                || name.equalsIgnoreCase("Members")
                || name.equalsIgnoreCase("(Members)")
                || name.matches("(?i)null\\s*\\(Members\\)"))
        {
            return false;
        }
        if (attributes.getPlaceholderTemplateId() != -1)
        {
            return false;
        }
        if (!attributes.isTradeable())
        {
            if (!config.includeUntradeable())
            {
                return false;
            }
            if (!ItemsFilter.isUntradeableAllowlisted(canonicalItemId))
            {
                return false;
            }
        }
        if (ItemsFilter.isQuestItem(canonicalItemId) && !config.includeQuestItems())
        {
            return false;
        }
        if (notTrackedPredicate != null && notTrackedPredicate.test(itemId))
        {
            return false;
        }
        if (ItemsFilter.isBlocked(itemId, config))
        {
            return false;
        }
        if (config.freeToPlay() && attributes.isMembers())
        {
            return false;
        }
        Set<Integer> unlockedSnapshot = unlockedItems != null ? unlockedItems : Collections.emptySet();
        return ItemsFilter.isPoisonEligible(
                itemId,
                config.requireWeaponPoison(),
                unlockedSnapshot
        );
    }
}
