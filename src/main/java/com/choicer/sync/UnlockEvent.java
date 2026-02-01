package com.choicer.sync;

import java.time.Instant;
import java.util.UUID;

public class UnlockEvent
{
    public UUID eventId;
    public UUID groupId;
    public String unlockKey;
    public Instant clientTs;

    public UnlockEvent(UUID eventId, UUID groupId, String unlockKey, Instant clientTs)
    {
        this.eventId = eventId;
        this.groupId = groupId;
        this.unlockKey = unlockKey;
        this.clientTs = clientTs;
    }
}
