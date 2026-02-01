package com.choicer.sync;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public class GroupStateDto
{
    public Map<String, Boolean> unlocks;
    public long version;
    public Instant updatedAt;

    public Map<String, Boolean> getUnlocksOrEmpty()
    {
        return unlocks != null ? unlocks : Collections.emptyMap();
    }
}
