package com.choicer.chanceman.filters;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks quest item IDs that require the Quest Items toggle to participate in rolls.
 * The list is intentionally empty for now and can be populated as IDs become available.
 */
public final class QuestItemAllowlist
{
    private static final Set<Integer> QUEST_ITEM_IDS = new HashSet<>();
    private static final Map<Integer, String> QUEST_NAME_BY_ITEM = new HashMap<>();
    private static final Map<String, Set<Integer>> QUEST_ITEMS_BY_QUEST = new LinkedHashMap<>();

    @Getter
    private static final Set<Integer> ALLOWED_QUEST_ITEMS;

    static
    {
        // Example usage for future quest items:
        // registerQuestItems("Cook's Assistant", ItemID.POT_OF_FLOUR);
        ALLOWED_QUEST_ITEMS = Collections.unmodifiableSet(QUEST_ITEM_IDS);
    }

    private QuestItemAllowlist()
    {
        // utility class
    }

    static void registerQuestItemForTesting(int itemId)
    {
        registerQuestItems("Test Quest", itemId);
    }

    private static void registerQuestItems(String questName, int... itemIds)
    {
        if (questName == null || questName.trim().isEmpty() || itemIds == null)
        {
            return;
        }

        final String normalizedQuestName = questName.trim();
        Set<Integer> questItems = QUEST_ITEMS_BY_QUEST.computeIfAbsent(normalizedQuestName, q -> new HashSet<>());
        for (int itemId : itemIds)
        {
            if (itemId <= 0)
            {
                continue;
            }
            QUEST_ITEM_IDS.add(itemId);
            QUEST_NAME_BY_ITEM.put(itemId, normalizedQuestName);
            questItems.add(itemId);
        }
    }

    public static String getQuestNameForItem(int itemId)
    {
        return QUEST_NAME_BY_ITEM.get(itemId);
    }

    static void clearForTesting()
    {
        QUEST_ITEM_IDS.clear();
        QUEST_NAME_BY_ITEM.clear();
        QUEST_ITEMS_BY_QUEST.clear();
    }
}
