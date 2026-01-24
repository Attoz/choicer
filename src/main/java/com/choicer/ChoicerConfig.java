package com.choicer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import java.awt.Color;

@ConfigGroup("choicer")
public interface ChoicerConfig extends Config
{
    @ConfigSection(
            name = "Settings",
            description = "Choicer options and behavior. Safe to install; disable ChanceMan before enabling Choicer.",
            position = 1
    )
    String settingsSection = "settings";

    @ConfigSection(
            name = "Important",
            description = "Warning: Do not use ChanceMan and Choicer at the same time. If you are switching from " +
                    "ChanceMan, disable it first.",
            position = 99
    )
    String importantSection = "important";

    @ConfigItem(
            keyName = "freeToPlay",
            name = "Free To Play Mode",
            description = "Only allow free-to-play items",
            position = 1,
            section = settingsSection
    )
    default boolean freeToPlay()
    {
        return false;
    }

    @ConfigItem(
            keyName = "includeF2PTradeOnlyItems",
            name = "Include F2P trade-only items",
            description = "When Free-to-Play mode is enabled, also roll items that can only " +
                    "be obtained via trading or the Grand Exchange.",
            position = 2,
            section = settingsSection
    )
    default boolean includeF2PTradeOnlyItems() { return false; }

    @ConfigItem(
            keyName = "enableItemSets",
            name = "Roll Item Sets",
            description = "Include item set items in the rollable items list. Disabling this will exclude any" +
                    " item set items from random rolls.",
            position = 3,
            section = settingsSection
    )
    default boolean enableItemSets() { return false; }

    @ConfigItem(
            keyName = "enableFlatpacks",
            name = "Roll Flatpacks",
            description = "Include flatpacks in the rollable items list. Disabling this will prevent" +
                    " flatpacks from being rolled.",
            position = 4,
            section = settingsSection
    )
    default boolean enableFlatpacks() { return false; }

    @ConfigItem(
            keyName = "requireWeaponPoison",
            name = "Weapon Poison Unlock Requirements",
            description = "Force poison variants to roll only if both the base weapon and the corresponding" +
                    " weapon poison are unlocked. (Disabling this will allow poisoned variants to roll even if " +
                    "the poison is locked.)",
            position = 5,
            section = settingsSection
    )
    default boolean requireWeaponPoison() { return true; }

    @ConfigItem(
            keyName = "enableRollSounds",
            name = "Enable Roll Sounds",
            description = "Toggle Roll Sound",
            position = 6,
            section = settingsSection
    )
    default boolean enableRollSounds() { return true; }

    @net.runelite.client.config.Range(min = 0, max = 100)
    @ConfigItem(
            keyName = "rollSoundVolume",
            name = "Roll Sound Volume",
            description = "Volume of the roll sound (0–100%).",
            position = 7,
            section = settingsSection
    )
    default int rollSoundVolume() { return 50; }


    @ConfigItem(
            keyName = "requireRolledUnlockedForGe",
            name = "GE Requires Obtained and Rolled",
            description = "Only Allow Grand Exchange results for items that have been both obtained and rolled.",
            position = 8,
            section = settingsSection
    )
    default boolean requireRolledUnlockedForGe() { return true; }


    @ConfigItem(
            keyName = "sortDropsByRarity",
            name = "Sort Drops by Rarity",
            description = "Order drops in the Show Drops menu by rarity instead of item ID.",
            position = 9,
            section = settingsSection
    )
    default boolean sortDropsByRarity() { return true; }

    @ConfigItem(
            keyName = "showRareDropTable",
            name = "Show Rare Drop Table",
            description = "Include rare drop table items in the Show Drops menu.",
            position = 10,
            section = settingsSection
    )
    default boolean showRareDropTable() { return true; }

    @ConfigItem(
            keyName = "showGemDropTable",
            name = "Show Gem Drop Table",
            description = "Include gem drop table items in the Show Drops menu.",
            position = 11,
            section = settingsSection
    )
    default boolean showGemDropTable() { return true; }

    @ConfigItem(
            keyName = "showDropsAlwaysOpen",
            name = "Show Drops Always Open",
            description = "Keep the Show Drops view active when switching away from the Music tab. Use the close button to exit.",
            position = 12,
            section = settingsSection
    )
    default boolean showDropsAlwaysOpen()
    {
        return false;
    }

    @ConfigItem(
            keyName = "deprioritizeLockedOptions",
            name = "Deprioritize Locked Menu Options",
            description = "Sorts locked menu options below the Walk Here option.",
            position = 13,
            section = settingsSection
    )
    default boolean deprioritizeLockedOptions() { return true; }

    @ConfigItem(
            keyName = "unlockedItemColor",
            name = "Rolled Item Color",
            description = "Color of the rolled (unlocked) item name in chat messages.",
            position = 14,
            section = settingsSection
    )
    default Color unlockedItemColor()
    {
        return Color.decode("#267567");
    }

    @ConfigItem(
            keyName = "rolledItemColor",
            name = "Obtained Item Color",
            description = "Color of the obtained item name in chat messages.",
            position = 15,
            section = settingsSection
    )
    default Color rolledItemColor()
    {
        return Color.decode("#ff0000");
    }

    @ConfigItem(
            keyName = "dimLockedItemsEnabled",
            name = "Dim locked items",
            description = "Dim any item icons that have not been unlocked.",
            position = 16,
            section = settingsSection
    )
    default boolean dimLockedItemsEnabled()
    {
        return true;
    }

    @ConfigItem(
            keyName = "includeUntradeable",
            name = "Include Untradeable Items",
            description = "Enable to include untradeable items in the rolling system",
            position = 17,
            section = settingsSection
    )
    default boolean includeUntradeable()
    {
        return false;
    }

    @net.runelite.client.config.Range(min = 0, max = 255)
    @ConfigItem(
            keyName = "dimLockedItemsOpacity",
            name = "Dim opacity",
            description = "0 = no dim (fully visible), 255 = fully transparent.",
            position = 17,
            section = settingsSection
    )
    default int dimLockedItemsOpacity()
    {
        return 150;
    }

    @ConfigItem(
            keyName = "includeQuestItems",
            name = "Include Quest Items",
            description = "Enable to include quest items in the rolling system",
            position = 20,
            hidden = true,
            section = settingsSection
    )
    default boolean includeQuestItems()
    {
        return false;
    }

    @net.runelite.client.config.Range(min = 2, max = 5)
    @ConfigItem(
            keyName = "choicerOptionCount",
            name = "Choicer Options",
            description = "Number of choices presented when Choicer rolls.",
            position = 18,
            section = settingsSection
    )
    default int choicerOptionCount()
    {
        return 5;
    }

    @ConfigItem(
            keyName = "clearSaveCurrent",
            name = "Clear Save (Current Character)",
            description = "Delete local + cloud progress for the current character.",
            position = 99,
            section = importantSection
    )
    default boolean clearSaveCurrent()
    {
        return false;
    }
}
