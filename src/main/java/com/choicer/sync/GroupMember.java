package com.choicer.sync;

import java.time.Instant;
import java.util.UUID;

public class GroupMember
{
    public UUID userId;
    public String displayName;
    public String role;
    public Instant joinedAt;
    public Instant lastSeenAt;
}
