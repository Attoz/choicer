package com.choicer.sync;

public enum GroupRollEventType
{
    STARTED("started"),
    SELECTED("selected");

    public final String wireValue;

    GroupRollEventType(String wireValue)
    {
        this.wireValue = wireValue;
    }

    public static GroupRollEventType fromWire(String value)
    {
        if (value == null)
        {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        for (GroupRollEventType type : values())
        {
            if (type.wireValue.equals(normalized))
            {
                return type;
            }
        }
        return null;
    }
}
