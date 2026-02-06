package com.choicer.filters;

import net.runelite.api.ItemComposition;

/**
 * Immutable snapshot of the minimal item metadata needed when evaluating
 * whether an item can participate in Choicer rolls.
 */
public final class ItemAttributes
{
    private final String name;
    private final boolean tradeable;
    private final boolean members;
    private final int placeholderTemplateId;

    public ItemAttributes(String name, boolean tradeable, boolean members, int placeholderTemplateId)
    {
        this.name = name;
        this.tradeable = tradeable;
        this.members = members;
        this.placeholderTemplateId = placeholderTemplateId;
    }

    public static ItemAttributes from(ItemComposition comp)
    {
        return new ItemAttributes(
                comp.getName(),
                comp.isTradeable(),
                comp.isMembers(),
                comp.getPlaceholderTemplateId()
        );
    }

    public String getName()
    {
        return name;
    }

    public boolean isTradeable()
    {
        return tradeable;
    }

    public boolean isMembers()
    {
        return members;
    }

    public int getPlaceholderTemplateId()
    {
        return placeholderTemplateId;
    }
}
