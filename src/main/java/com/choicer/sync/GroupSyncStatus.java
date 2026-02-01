package com.choicer.sync;

import java.time.Instant;
import java.util.UUID;

public class GroupSyncStatus
{
    public final boolean enabled;
    public final String state;
    public final String message;
    public final UUID groupId;
    public final long version;
    public final Instant lastSync;

    public GroupSyncStatus(boolean enabled, String state, String message, UUID groupId, long version, Instant lastSync)
    {
        this.enabled = enabled;
        this.state = state;
        this.message = message;
        this.groupId = groupId;
        this.version = version;
        this.lastSync = lastSync;
    }

    public static GroupSyncStatus disabled()
    {
        return new GroupSyncStatus(false, "disabled", "Sync disabled", null, 0L, null);
    }
}
