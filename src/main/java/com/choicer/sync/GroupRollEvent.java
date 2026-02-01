package com.choicer.sync;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class GroupRollEvent
{
    public UUID id;
    public UUID groupId;
    public String actorUserId;
    public String actorDisplayName;
    public String rollId;
    public GroupRollEventType type;
    public Instant createdAt;
    public Instant startedAt;
    public Integer triggerItemId;
    public Integer selectedItemId;
    public Integer rollDurationMs;
    public boolean manualRoll;
    public List<Integer> options;

    public List<Integer> getOptionsOrEmpty()
    {
        return options == null ? Collections.emptyList() : options;
    }

    public int getRollDurationMsOrDefault(int fallback)
    {
        if (rollDurationMs == null || rollDurationMs <= 0)
        {
            return fallback;
        }
        return rollDurationMs;
    }
}
