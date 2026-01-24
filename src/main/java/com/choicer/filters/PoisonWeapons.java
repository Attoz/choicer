package com.choicer.filters;

import lombok.Getter;
import net.runelite.api.gameval.ItemID;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Enum representing every poisonable weapon and its four variants:
 *   - Base (unpoisoned)
 *   - (p)
 *   - (p+)
 *   - (p++)
 */
@Getter
public enum PoisonWeapons {
    // Global prerequisites for poisonable weapons
    WEAPON_POISON(ItemID.WEAPON_POISON),
    WEAPON_POISON_(ItemID.WEAPON_POISON_),
    WEAPON_POISON__(ItemID.WEAPON_POISON__),

    // --- Daggers ---
    BRONZE_DAGGER(ItemID.BRONZE_DAGGER, ItemID.BRONZE_DAGGER_P, ItemID.BRONZE_DAGGER_P_, ItemID.BRONZE_DAGGER_P__),
    IRON_DAGGER(ItemID.IRON_DAGGER, ItemID.IRON_DAGGER_P, ItemID.IRON_DAGGER_P_, ItemID.IRON_DAGGER_P__),
    BONE_DAGGER(ItemID.DTTD_BONE_DAGGER, ItemID.DTTD_BONE_DAGGER_P, ItemID.DTTD_BONE_DAGGER_P_,
            ItemID.DTTD_BONE_DAGGER_P__),
    BLACK_DAGGER(ItemID.BLACK_DAGGER, ItemID.BLACK_DAGGER_P, ItemID.BLACK_DAGGER_P_, ItemID.BLACK_DAGGER_P__),
    WHITE_DAGGER(ItemID.WHITE_DAGGER, ItemID.WHITE_DAGGER_P, ItemID.WHITE_DAGGER_P_, ItemID.WHITE_DAGGER_P__),
    STEEL_DAGGER(ItemID.STEEL_DAGGER, ItemID.STEEL_DAGGER_P, ItemID.STEEL_DAGGER_P_, ItemID.STEEL_DAGGER_P__),
    MITHRIL_DAGGER(ItemID.MITHRIL_DAGGER, ItemID.MITHRIL_DAGGER_P, ItemID.MITHRIL_DAGGER_P_, ItemID.MITHRIL_DAGGER_P__),
    ADAMANT_DAGGER(ItemID.ADAMANT_DAGGER, ItemID.ADAMANT_DAGGER_P, ItemID.ADAMANT_DAGGER_P_, ItemID.ADAMANT_DAGGER_P__),
    RUNE_DAGGER(ItemID.RUNE_DAGGER, ItemID.RUNE_DAGGER_P, ItemID.RUNE_DAGGER_P_, ItemID.RUNE_DAGGER_P__),
    DRAGON_DAGGER(ItemID.DRAGON_DAGGER, ItemID.DRAGON_DAGGER_P, ItemID.DRAGON_DAGGER_P_, ItemID.DRAGON_DAGGER_P__),
    ABYSSAL_DAGGER(ItemID.ABYSSAL_DAGGER, ItemID.ABYSSAL_DAGGER_P, ItemID.ABYSSAL_DAGGER_P_, ItemID.ABYSSAL_DAGGER_P__),

    // --- Javelins ---
    BRONZE_JAVELIN(ItemID.BRONZE_JAVELIN, ItemID.BRONZE_JAVELIN_P, ItemID.BRONZE_JAVELIN_P_, ItemID.BRONZE_JAVELIN_P__),
    IRON_JAVELIN(ItemID.IRON_JAVELIN, ItemID.IRON_JAVELIN_P, ItemID.IRON_JAVELIN_P_, ItemID.IRON_JAVELIN_P__),
    STEEL_JAVELIN(ItemID.STEEL_JAVELIN, ItemID.STEEL_JAVELIN_P, ItemID.STEEL_JAVELIN_P_, ItemID.STEEL_JAVELIN_P__),
    MITHRIL_JAVELIN(ItemID.MITHRIL_JAVELIN, ItemID.MITHRIL_JAVELIN_P, ItemID.MITHRIL_JAVELIN_P_,
            ItemID.MITHRIL_JAVELIN_P__),
    ADAMANT_JAVELIN(ItemID.ADAMANT_JAVELIN, ItemID.ADAMANT_JAVELIN_P, ItemID.ADAMANT_JAVELIN_P_,
            ItemID.ADAMANT_JAVELIN_P__),
    RUNE_JAVELIN(ItemID.RUNE_JAVELIN, ItemID.RUNE_JAVELIN_P, ItemID.RUNE_JAVELIN_P_, ItemID.RUNE_JAVELIN_P__),
    DRAGON_JAVELIN(ItemID.DRAGON_JAVELIN, ItemID.DRAGON_JAVELIN_P, ItemID.DRAGON_JAVELIN_P_, ItemID.DRAGON_JAVELIN_P__),
    AMETHYST_JAVELIN(ItemID.AMETHYST_JAVELIN, ItemID.AMETHYST_JAVELIN_P, ItemID.AMETHYST_JAVELIN_P_,
            ItemID.AMETHYST_JAVELIN_P__),

    // --- Spears ---
    BRONZE_SPEAR(ItemID.BRONZE_SPEAR, ItemID.BRONZE_SPEAR_P, ItemID.BRONZE_SPEAR_P_, ItemID.BRONZE_SPEAR_P__),
    IRON_SPEAR(ItemID.IRON_SPEAR, ItemID.IRON_SPEAR_P, ItemID.IRON_SPEAR_P_, ItemID.IRON_SPEAR_P__),
    BLACK_SPEAR(ItemID.BLACK_SPEAR, ItemID.BLACK_SPEAR_P, ItemID.BLACK_SPEAR_P_, ItemID.BLACK_SPEAR_P__),
    STEEL_SPEAR(ItemID.STEEL_SPEAR, ItemID.STEEL_SPEAR_P, ItemID.STEEL_SPEAR_P_, ItemID.STEEL_SPEAR_P__),
    MITHRIL_SPEAR(ItemID.MITHRIL_SPEAR, ItemID.MITHRIL_SPEAR_P, ItemID.MITHRIL_SPEAR_P_, ItemID.MITHRIL_SPEAR_P__),
    ADAMANT_SPEAR(ItemID.ADAMANT_SPEAR, ItemID.ADAMANT_SPEAR_P, ItemID.ADAMANT_SPEAR_P_, ItemID.ADAMANT_SPEAR_P__),
    RUNE_SPEAR(ItemID.RUNE_SPEAR, ItemID.RUNE_SPEAR_P, ItemID.RUNE_SPEAR_P_, ItemID.RUNE_SPEAR_P__),
    DRAGON_SPEAR(ItemID.DRAGON_SPEAR, ItemID.DRAGON_SPEAR_P, ItemID.DRAGON_SPEAR_P_, ItemID.DRAGON_SPEAR_P__),

    // --- Hastas ---
    BRONZE_HASTA(ItemID.BRUT_BRONZE_SPEAR, ItemID.BRUT_BRONZE_SPEAR_P, ItemID.BRUT_BRONZE_SPEAR_P_,
            ItemID.BRUT_BRONZE_SPEAR_P__),
    IRON_HASTA(ItemID.BRUT_IRON_SPEAR, ItemID.BRUT_IRON_SPEAR_P, ItemID.BRUT_IRON_SPEAR_P_,
            ItemID.BRUT_IRON_SPEAR_P__),
    STEEL_HASTA(ItemID.BRUT_STEEL_SPEAR, ItemID.BRUT_STEEL_SPEAR_P, ItemID.BRUT_STEEL_SPEAR_P_,
            ItemID.BRUT_STEEL_SPEAR_P__),
    MITHRIL_HASTA(ItemID.BRUT_MITHRIL_SPEAR, ItemID.BRUT_MITHRIL_SPEAR_P, ItemID.BRUT_MITHRIL_SPEAR_P_,
            ItemID.BRUT_MITHRIL_SPEAR_P__),
    ADAMANT_HASTA(ItemID.BRUT_ADAMANT_SPEAR, ItemID.BRUT_ADAMANT_SPEAR_P, ItemID.BRUT_ADAMANT_SPEAR_P_,
            ItemID.BRUT_ADAMANT_SPEAR_P__),
    RUNE_HASTA(ItemID.BRUT_RUNE_SPEAR, ItemID.BRUT_RUNE_SPEAR_P, ItemID.BRUT_RUNE_SPEAR_P_,
            ItemID.BRUT_RUNE_SPEAR_P__),
    DRAGON_HASTA(ItemID.BRUT_DRAGON_SPEAR, ItemID.BRUT_DRAGON_SPEAR_P, ItemID.BRUT_DRAGON_SPEAR_P_,
            ItemID.BRUT_DRAGON_SPEAR_P__),

    // --- Darts ---
    BRONZE_DART(ItemID.BRONZE_DART, ItemID.BRONZE_DART_P, ItemID.BRONZE_DART_P_, ItemID.BRONZE_DART_P__),
    IRON_DART(ItemID.IRON_DART, ItemID.IRON_DART_P, ItemID.IRON_DART_P_, ItemID.IRON_DART_P__),
    BLACK_DART(ItemID.BLACK_DART, ItemID.BLACK_DART_P, ItemID.BLACK_DART_P_, ItemID.BLACK_DART_P__),
    STEEL_DART(ItemID.STEEL_DART, ItemID.STEEL_DART_P, ItemID.STEEL_DART_P_, ItemID.STEEL_DART_P__),
    MITHRIL_DART(ItemID.MITHRIL_DART, ItemID.MITHRIL_DART_P, ItemID.MITHRIL_DART_P_, ItemID.MITHRIL_DART_P__),
    ADAMANT_DART(ItemID.ADAMANT_DART, ItemID.ADAMANT_DART_P, ItemID.ADAMANT_DART_P_, ItemID.ADAMANT_DART_P__),
    RUNE_DART(ItemID.RUNE_DART, ItemID.RUNE_DART_P, ItemID.RUNE_DART_P_, ItemID.RUNE_DART_P__),
    DRAGON_DART(ItemID.DRAGON_DART, ItemID.DRAGON_DART_P, ItemID.DRAGON_DART_P_, ItemID.DRAGON_DART_P__),
    AMETHYST_DART(ItemID.AMETHYST_DART, ItemID.AMETHYST_DART_P, ItemID.AMETHYST_DART_P_, ItemID.AMETHYST_DART_P__),

    // --- Knives ---
    BRONZE_KNIFE(ItemID.BRONZE_KNIFE, ItemID.BRONZE_KNIFE_P, ItemID.BRONZE_KNIFE_P_, ItemID.BRONZE_KNIFE_P__),
    IRON_KNIFE(ItemID.IRON_KNIFE, ItemID.IRON_KNIFE_P, ItemID.IRON_KNIFE_P_, ItemID.IRON_KNIFE_P__),
    BLACK_KNIFE(ItemID.BLACK_KNIFE, ItemID.BLACK_KNIFE_P, ItemID.BLACK_KNIFE_P_, ItemID.BLACK_KNIFE_P__),
    STEEL_KNIFE(ItemID.STEEL_KNIFE, ItemID.STEEL_KNIFE_P, ItemID.STEEL_KNIFE_P_, ItemID.STEEL_KNIFE_P__),
    MITHRIL_KNIFE(ItemID.MITHRIL_KNIFE, ItemID.MITHRIL_KNIFE_P, ItemID.MITHRIL_KNIFE_P_, ItemID.MITHRIL_KNIFE_P__),
    ADAMANT_KNIFE(ItemID.ADAMANT_KNIFE, ItemID.ADAMANT_KNIFE_P, ItemID.ADAMANT_KNIFE_P_, ItemID.ADAMANT_KNIFE_P__),
    RUNE_KNIFE(ItemID.RUNE_KNIFE, ItemID.RUNE_KNIFE_P, ItemID.RUNE_KNIFE_P_, ItemID.RUNE_KNIFE_P__),
    DRAGON_KNIFE(ItemID.DRAGON_KNIFE, ItemID.DRAGON_KNIFE_P, ItemID.DRAGON_KNIFE_P_, ItemID.DRAGON_KNIFE_P__),

    // --- Arrows ---
    BRONZE_ARROW(ItemID.BRONZE_ARROW, ItemID.BRONZE_ARROW_P, ItemID.BRONZE_ARROW_P_, ItemID.BRONZE_ARROW_P__),
    IRON_ARROW(ItemID.IRON_ARROW, ItemID.IRON_ARROW_P, ItemID.IRON_ARROW_P_, ItemID.IRON_ARROW_P__),
    STEEL_ARROW(ItemID.STEEL_ARROW, ItemID.STEEL_ARROW_P, ItemID.STEEL_ARROW_P_, ItemID.STEEL_ARROW_P__),
    MITHRIL_ARROW(ItemID.MITHRIL_ARROW, ItemID.MITHRIL_ARROW_P, ItemID.MITHRIL_ARROW_P_, ItemID.MITHRIL_ARROW_P__),
    ADAMANT_ARROW(ItemID.ADAMANT_ARROW, ItemID.ADAMANT_ARROW_P, ItemID.ADAMANT_ARROW_P_, ItemID.ADAMANT_ARROW_P__),
    RUNE_ARROW(ItemID.RUNE_ARROW, ItemID.RUNE_ARROW_P, ItemID.RUNE_ARROW_P_, ItemID.RUNE_ARROW_P__),
    DRAGON_ARROW(ItemID.DRAGON_ARROW, ItemID.DRAGON_ARROW_P, ItemID.DRAGON_ARROW_P_, ItemID.DRAGON_ARROW_P__),
    AMETHYST_ARROW(ItemID.AMETHYST_ARROW, ItemID.AMETHYST_ARROW_P, ItemID.AMETHYST_ARROW_P_, ItemID.AMETHYST_ARROW_P__),

    // --- Bolts ---
    BRONZE_BOLTS(ItemID.BOLT, ItemID.POISON_BOLT, ItemID.POISON_BOLT_, ItemID.POISON_BOLT__),
    IRON_BOLTS(ItemID.XBOWS_CROSSBOW_BOLTS_IRON, ItemID.XBOWS_CROSSBOW_BOLTS_IRON_POISONED,
            ItemID.XBOWS_CROSSBOW_BOLTS_IRON_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_IRON_POISONED__),
    SILVER_BOLTS(ItemID.XBOWS_CROSSBOW_BOLTS_SILVER, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_POISONED,
            ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_POISONED__),
    STEEL_BOLTS(ItemID.XBOWS_CROSSBOW_BOLTS_STEEL, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_POISONED,
            ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_POISONED__),
    MITHRIL_BOLTS(ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_POISONED,
            ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_POISONED__),
    ADAMANTITE_BOLTS(ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_POISONED,
            ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_POISONED__),
    RUNITE_BOLTS(ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_POISONED,
            ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_POISONED_, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_POISONED__),
    DRAGON_BOLTS(ItemID.DRAGON_BOLTS, ItemID.DRAGON_BOLTS_P, ItemID.DRAGON_BOLTS_P_, ItemID.DRAGON_BOLTS_P__);

    private final int baseId;
    private final int poisonId;
    private final int poisonPlusId;
    private final int poisonPlusPlusId;

    // Overloaded constructor for global prerequisites
    PoisonWeapons(int id) {
        this.baseId = id;
        this.poisonId = id;
        this.poisonPlusId = id;
        this.poisonPlusPlusId = id;
    }

    // Constructor for weapons with distinct variant IDs.
    PoisonWeapons(int baseId, int poisonId, int poisonPlusId, int poisonPlusPlusId) {
        this.baseId = baseId;
        this.poisonId = poisonId;
        this.poisonPlusId = poisonPlusId;
        this.poisonPlusPlusId = poisonPlusPlusId;
    }

    /**
     * Checks whether the given item id matches any variant of this weapon.
     *
     * @param id the item id to check.
     * @return true if the id matches base, poison, poison+ or poison++.
     */
    public boolean matches(int id) {
        return id == baseId || id == poisonId || id == poisonPlusId || id == poisonPlusPlusId;
    }

    /**
     * Returns an unmodifiable set of all base weapon ids from poisonable weapons, excluding poison constants.
     */
    public static Set<Integer> getAllBaseWeaponIds() {
        Set<Integer> ids = new HashSet<>();
        for (PoisonWeapons weapon : values()) {
            if (!weapon.name().startsWith("WEAPON_POISON")) {
                ids.add(weapon.getBaseId());
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Checks if a given item id is a poisonable weapon variant.
     *
     * @param itemId the item id to check.
     * @return true if it matches any poisonable weapon variant.
     */
    public static boolean isPoisonableWeapon(int itemId) {
        for (PoisonWeapons weapon : values()) {
            if (weapon.name().startsWith("WEAPON_POISON")) continue;
            if (weapon.matches(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a poisonable weapon variant is eligible.
     * Base weapons are always eligible.
     * For poisoned variants:
     *   - (p) requires WEAPON_POISON,
     *   - (p+) requires WEAPON_POISON_,
     *   - (p++) requires WEAPON_POISON__,
     * to be unlocked along with the base weapon.
     * @param itemId the item ID to check
     * @param requireWeaponPoison if true, the corresponding global poison must be unlocked
     * @param unlockedItems the set of unlocked item IDs
     * @return true if eligible; false otherwise
     */
    public static boolean isPoisonVariantEligible(int itemId, boolean requireWeaponPoison, Set<Integer> unlockedItems) {
        PoisonWeapons matchingWeapon = null;
        for (PoisonWeapons weapon : values()) {
            if (weapon.name().startsWith("WEAPON_POISON")) continue;
            if (weapon.matches(itemId)) {
                matchingWeapon = weapon;
                break;
            }
        }
        if (matchingWeapon == null) {
            return true;
        }
        // Base weapon is always eligible.
        if (itemId == matchingWeapon.getBaseId()) {
            return true;
        }
        if (!requireWeaponPoison) {
            return true;
        }
        int requiredGlobalId;
        if (itemId == matchingWeapon.getPoisonId()) {
            requiredGlobalId = PoisonWeapons.WEAPON_POISON.getBaseId();
        } else if (itemId == matchingWeapon.getPoisonPlusId()) {
            requiredGlobalId = PoisonWeapons.WEAPON_POISON_.getBaseId();
        } else if (itemId == matchingWeapon.getPoisonPlusPlusId()) {
            requiredGlobalId = PoisonWeapons.WEAPON_POISON__.getBaseId();
        } else {
            return true;
        }
        boolean eligible = unlockedItems.contains(matchingWeapon.getBaseId()) && unlockedItems.contains(requiredGlobalId);
        return eligible;
    }

}
