package com.choicer.sync;

import java.time.Instant;
import java.util.UUID;

public class GroupStateUpdated
{
    private final UUID groupId;
    private final long version;
    private final Instant updatedAt;

    public GroupStateUpdated(UUID groupId, long version, Instant updatedAt)
    {
        this.groupId = groupId;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public UUID getGroupId()
    {
        return groupId;
    }

    public long getVersion()
    {
        return version;
    }

    public Instant getUpdatedAt()
    {
        return updatedAt;
    }
}
