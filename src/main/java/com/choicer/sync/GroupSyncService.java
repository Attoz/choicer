package com.choicer.sync;

import com.choicer.managers.ObtainedItemsManager;
import com.choicer.managers.RolledItemsManager;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class GroupSyncService
{
    private static final String CFG_GROUP = GroupSyncConfigKeys.GROUP;
    private static final String KEY_GROUP_ID = "group_sync.group_id";
    private static final String KEY_MAX_MEMBERS = "group_sync.max_members";
    private static final String KEY_LAST_VERSION = "group_sync.last_version";
    private static final String KEY_JOIN_CODE = GroupSyncConfigKeys.JOIN_CODE;
    private static final String KEY_LAST_DISPLAY_NAME = "group_sync.last_display_name";

    private static final long POLL_INTERVAL_SECONDS = 5L;

    @Inject private SupabaseApiClient apiClient;
    @Inject private SupabaseAuthService authService;
    @Inject private SupabaseRealtimeClient realtimeClient;
    @Inject private UnlockQueue unlockQueue;
    @Inject private RolledItemsManager rolledItemsManager;
    @Inject private ObtainedItemsManager obtainedItemsManager;
    @Inject private ConfigManager configManager;
    @Inject private EventBus eventBus;
    @Inject private Client client;

    private ExecutorService ioExecutor;
    private ScheduledExecutorService scheduler;
    private volatile boolean started = false;
    private volatile long lastSeenVersion = 0L;
    private volatile Instant lastSync = null;
    private volatile GroupSyncStatus status = GroupSyncStatus.disabled();
    private volatile Consumer<GroupSyncStatus> statusListener;
    private volatile boolean polling = false;
    private volatile String lastUserId = null;
    private volatile boolean groupActive = false;
    private volatile java.util.List<GroupMember> members = java.util.Collections.emptyList();
    private volatile Consumer<java.util.List<GroupMember>> membersListener;
    private volatile long lastMembersFetchMs = 0L;
    private final AtomicInteger postFailures = new AtomicInteger(0);
    private volatile long nextPostAllowedMs = 0L;

    public void start()
    {
        if (started) return;
        started = true;

        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Choicer-GroupSync-IO");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Choicer-GroupSync-Poll");
            t.setDaemon(true);
            return t;
        });

        unlockQueue.setExecutor(ioExecutor);
        unlockQueue.loadFromDisk();
        lastSeenVersion = readLastSeenVersion();
        lastUserId = getStoredUserId();
        ensureSupabaseDefaults();
        if (isSyncEnabled())
        {
            updateStatus("starting", "Starting group sync", null, lastSeenVersion, lastSync, true);
            fetchAndApplyState(false);
            startPolling();
            startRealtimeIfReady();
            flushQueue();
        }
        else
        {
            updateStatus("disabled", "Sync disabled", null, 0L, null, false);
        }
    }

    public void shutdown()
    {
        started = false;
        stopRealtime();
        if (scheduler != null)
        {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (ioExecutor != null)
        {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
    }

    public void setStatusListener(Consumer<GroupSyncStatus> listener)
    {
        this.statusListener = listener;
        if (listener != null)
        {
            listener.accept(status);
        }
    }

    public void setMembersListener(Consumer<java.util.List<GroupMember>> listener)
    {
        this.membersListener = listener;
        if (listener != null)
        {
            listener.accept(members);
        }
    }

    public java.util.List<GroupMember> getMembers()
    {
        return members;
    }

    public GroupSyncStatus getStatus()
    {
        return status;
    }

    public boolean isGroupActive()
    {
        return groupActive;
    }

    public void setSyncEnabled(boolean enabled)
    {
        if (!started) return;
        configManager.setConfiguration(CFG_GROUP, GroupSyncConfigKeys.ENABLED, String.valueOf(enabled));
        if (enabled)
        {
            updateStatus("starting", "Starting group sync", null, lastSeenVersion, lastSync, true);
            fetchAndApplyState(false);
            startPolling();
            startRealtimeIfReady();
            flushQueue();
        }
        else
        {
            unlockQueue.clear();
            stopRealtime();
            stopPolling();
            updateStatus("disabled", "Sync disabled", null, 0L, null, false);
        }
    }

    public void joinGroup(String joinCode)
    {
        if (joinCode == null || joinCode.trim().isEmpty())
        {
            updateStatus("error", "Join code is empty", null, lastSeenVersion, lastSync, true);
            return;
        }

        if (!isSyncEnabled())
        {
            updateStatus("disabled", "Enable sync to join", null, lastSeenVersion, lastSync, false);
            return;
        }

        if (ioExecutor == null)
        {
            updateStatus("error", "Sync not ready", null, lastSeenVersion, lastSync, true);
            return;
        }

        updateStatus("joining", "Joining group...", null, lastSeenVersion, lastSync, true);
        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                JoinGroupResult result = apiClient.joinGroupSync(joinCode.trim());
                if (result == null || result.group_id == null)
                {
                    updateStatus("error", "Join failed", null, lastSeenVersion, lastSync, true);
                    return;
                }
                storeGroup(result.group_id, result.max_members);
                lastSeenVersion = 0L;
                fetchAndApplyState(true);
                refreshMembers();
                startPolling();
                startRealtimeIfReady();
            }
            catch (Exception e)
            {
                updateStatus("error", "Join failed", null, lastSeenVersion, lastSync, true);
            }
        });
    }

    public void createGroup(String name, int maxMembers, java.util.function.Consumer<CreateGroupResult> onSuccess)
    {
        if (!isSyncEnabled())
        {
            updateStatus("disabled", "Enable sync to create group", null, lastSeenVersion, lastSync, false);
            return;
        }
        if (ioExecutor == null)
        {
            updateStatus("error", "Sync not ready", null, lastSeenVersion, lastSync, true);
            return;
        }
        if (name == null || name.trim().isEmpty())
        {
            updateStatus("error", "Create fields incomplete", null, lastSeenVersion, lastSync, true);
            return;
        }

        updateStatus("creating", "Creating group...", null, lastSeenVersion, lastSync, true);
        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                CreateGroupResult result = apiClient.createGroupSync(name, maxMembers);
                if (result == null || result.group_id == null)
                {
                    updateStatus("error", "Create failed", null, lastSeenVersion, lastSync, true);
                    return;
                }
                storeGroup(result.group_id, maxMembers);
                storeJoinCode(result.join_code);
                lastSeenVersion = 0L;
                clearGroupLocalState();
                fetchAndApplyState(false);
                refreshMembers();
                startPolling();
                startRealtimeIfReady();
                if (onSuccess != null)
                {
                    onSuccess.accept(result);
                }
            }
            catch (Exception e)
            {
                updateStatus("error", "Create failed", null, lastSeenVersion, lastSync, true);
            }
        });
    }

    public void leaveGroup()
    {
        UUID groupId = getStoredGroupId();
        if (groupId == null)
        {
            updateStatus("idle", "No group joined", null, lastSeenVersion, lastSync, true);
            return;
        }
        if (ioExecutor == null)
        {
            updateStatus("error", "Sync not ready", null, lastSeenVersion, lastSync, true);
            return;
        }
        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                apiClient.leaveGroupSync(groupId);
            }
            catch (Exception e)
            {
                updateStatus("error", "Leave failed: " + errorMessage(e), groupId, lastSeenVersion, lastSync, true);
                return;
            }
            clearGroup();
            unlockQueue.clear();
            stopRealtime();
            stopPolling();
            updateStatus("idle", "Left group", null, 0L, null, true);
        });
    }

    public void postUnlock(String unlockKey)
    {
        if (!isSyncEnabled()) return;
        UUID groupId = getStoredGroupId();
        if (groupId == null) return;
        if (ioExecutor == null) return;
        unlockQueue.enqueue(groupId, unlockKey, Instant.now());
        flushQueue();
    }

    public void refreshMembers()
    {
        if (ioExecutor == null)
        {
            log.debug("Choicer group refresh: skipped (ioExecutor not ready)");
            return;
        }
        if (!isSyncEnabled())
        {
            log.debug("Choicer group refresh: skipped (sync disabled)");
            return;
        }
        UUID groupId = getStoredGroupId();
        if (groupId == null)
        {
            log.debug("Choicer group refresh: skipped (no group joined)");
            updateMembers(java.util.Collections.emptyList());
            return;
        }
        if (!isLoggedIn())
        {
            log.debug("Choicer group refresh: skipped (not logged in)");
            return;
        }
        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                java.util.List<GroupMember> list = apiClient.getGroupMembersSync(groupId);
                updateMembers(list);
                lastMembersFetchMs = System.currentTimeMillis();
            }
            catch (Exception e)
            {
                if (handleNotAuthorized(e)) return;
            }
        });
    }

    public void refreshMembersAndMaybeSync()
    {
        if (ioExecutor == null)
        {
            log.debug("Choicer group refresh+sync: skipped (ioExecutor not ready)");
            return;
        }
        if (!isSyncEnabled())
        {
            log.debug("Choicer group refresh+sync: skipped (sync disabled)");
            return;
        }
        UUID groupId = getStoredGroupId();
        if (groupId == null)
        {
            log.debug("Choicer group refresh+sync: skipped (no group joined)");
            return;
        }
        if (!isLoggedIn())
        {
            log.debug("Choicer group refresh+sync: skipped (not logged in)");
            return;
        }
        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                java.util.List<GroupMember> list = apiClient.getGroupMembersSync(groupId);
                updateMembers(list);
                lastMembersFetchMs = System.currentTimeMillis();
                if (list != null && !list.isEmpty())
                {
                    fetchAndApplyState(false);
                }
            }
            catch (Exception e)
            {
                if (handleNotAuthorized(e)) return;
            }
        });
    }

    public void kickMember(UUID memberUserId)
    {
        if (memberUserId == null) return;
        if (ioExecutor == null) return;
        if (!isSyncEnabled()) return;
        UUID groupId = getStoredGroupId();
        if (groupId == null) return;
        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                apiClient.kickMemberSync(groupId, memberUserId);
                refreshMembers();
            }
            catch (Exception e)
            {
                updateStatus("error", "Kick failed: " + errorMessage(e), groupId, lastSeenVersion, lastSync, true);
            }
        });
    }

    private void fetchAndApplyState(boolean overwriteLocal)
    {
        UUID groupId = getStoredGroupId();
        if (groupId == null)
        {
            updateStatus("idle", "No group joined", null, lastSeenVersion, lastSync, true);
            return;
        }

        if (!isLoggedIn())
        {
            updateStatus("idle", "Waiting for login", groupId, lastSeenVersion, lastSync, true);
            return;
        }

        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                GroupStateDto state = apiClient.getGroupStateSync(groupId);
                if (state == null)
                {
                    updateStatus("error", "Group state not found", groupId, lastSeenVersion, lastSync, true);
                    return;
                }
                int merged = applyGroupState(state, overwriteLocal);
                groupActive = true;
                lastSeenVersion = Math.max(lastSeenVersion, state.version);
                storeLastSeenVersion(lastSeenVersion);
                lastSync = Instant.now();
                updateStatus("synced", "Synced (+" + merged + ")", groupId, lastSeenVersion, lastSync, true);
                eventBus.post(new GroupStateUpdated(groupId, state.version, state.updatedAt));
                refreshMembers();
                startRealtimeIfReady();
            }
            catch (Exception e)
            {
                if (handleNotAuthorized(e)) return;
                updateStatus("error", "Sync failed", groupId, lastSeenVersion, lastSync, true);
            }
        });
    }

    private void startPolling()
    {
        if (scheduler == null) return;
        if (polling) return;
        polling = true;
        scheduler.scheduleAtFixedRate(this::pollOnce, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopPolling()
    {
        polling = false;
        if (scheduler != null)
        {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Choicer-GroupSync-Poll");
            t.setDaemon(true);
            return t;
        });
    }

    private void pollOnce()
    {
        if (!isSyncEnabled()) return;
        if (!isLoggedIn()) return;
        UUID groupId = getStoredGroupId();
        if (groupId == null) return;

        ioExecutor.submit(() -> {
            try
            {
                if (!ensureAuthSessionOrReset()) return;
                ensureDisplayNameSet();
                GroupStateDto state = apiClient.getGroupStateMetaSync(groupId);
                if (state == null) return;
                if (state.version > lastSeenVersion)
                {
                    GroupStateDto full = apiClient.getGroupStateSync(groupId);
                    if (full == null) return;
                    int merged = applyGroupState(full, false);
                    groupActive = true;
                    lastSeenVersion = full.version;
                    storeLastSeenVersion(lastSeenVersion);
                    lastSync = Instant.now();
                    updateStatus("synced", "Synced (+" + merged + ")", groupId, lastSeenVersion, lastSync, true);
                    eventBus.post(new GroupStateUpdated(groupId, full.version, full.updatedAt));
                    refreshMembers();
                    startRealtimeIfReady();
                }
            }
            catch (Exception e)
            {
                if (handleNotAuthorized(e)) return;
            }
        });

        if (System.currentTimeMillis() - lastMembersFetchMs > 60000L)
        {
            refreshMembers();
        }

        flushQueue();
    }

    private void requestImmediatePoll()
    {
        if (scheduler == null || !polling) return;
        scheduler.schedule(this::pollOnce, 0L, TimeUnit.SECONDS);
    }

    private void ensureDisplayNameSet()
    {
        String displayName = getDisplayName();
        if (displayName == null) return;
        String lastSent = configManager.getConfiguration(CFG_GROUP, KEY_LAST_DISPLAY_NAME);
        if (displayName.equals(lastSent)) return;

        try
        {
            JsonObject body = new JsonObject();
            body.addProperty("p_display_name", displayName);
            apiClient.callRpcSync("set_display_name", body);
            configManager.setConfiguration(CFG_GROUP, KEY_LAST_DISPLAY_NAME, displayName);
        }
        catch (Exception e)
        {
            log.debug("Choicer group: set_display_name failed", e);
        }
    }

    private String getDisplayName()
    {
        try
        {
            Player player = client.getLocalPlayer();
            if (player == null) return null;
            String name = player.getName();
            if (name == null) return null;
            String trimmed = name.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private void flushQueue()
    {
        if (ioExecutor == null) return;
        long now = System.currentTimeMillis();
        if (now < nextPostAllowedMs) return;

        ioExecutor.submit(() -> {
            if (!isSyncEnabled()) return;
            if (!isLoggedIn()) return;
            if (!ensureAuthSessionOrReset()) return;
            for (UnlockEvent event : unlockQueue.snapshot())
            {
                try
                {
                    apiClient.postUnlockSync(event.groupId, event.eventId, event.unlockKey, event.clientTs);
                    unlockQueue.removeById(event.eventId);
                    postFailures.set(0);
                    requestImmediatePoll();
                }
                catch (Exception e)
                {
                    if (handleNotAuthorized(e)) return;
                    int failures = postFailures.incrementAndGet();
                    long delayMs = (long) (15000L * Math.min(8, (1 << Math.min(3, failures))));
                    nextPostAllowedMs = System.currentTimeMillis() + delayMs;
                    updateStatus("warning", "Unlock post failed (retrying)", getStoredGroupId(), lastSeenVersion, lastSync, true);
                    return;
                }
            }
        });
    }

    private boolean handleNotAuthorized(Throwable e)
    {
        if (!isNotAuthorized(e)) return false;
        clearGroup();
        unlockQueue.clear();
        stopRealtime();
        stopPolling();
        updateMembers(java.util.Collections.emptyList());
        updateStatus("idle", "No group joined", null, 0L, null, true);
        return true;
    }

    private boolean isNotAuthorized(Throwable e)
    {
        String msg = errorMessage(e).toLowerCase();
        if (msg.contains("not authorized") || msg.contains("not authenticated"))
        {
            return true;
        }
        return msg.contains("supabase group_state failed: 401")
                || msg.contains("supabase group_state failed: 403");
    }

    private String errorMessage(Throwable e)
    {
        if (e == null) return "";
        String msg = e.getMessage();
        if (msg == null && e.getCause() != null)
        {
            msg = e.getCause().getMessage();
        }
        return msg != null ? msg : "";
    }

    private int applyGroupState(GroupStateDto state, boolean overwriteLocal)
    {
        UnlockSets sets = extractUnlocks(state);
        if (overwriteLocal)
        {
            int before = rolledItemsManager.getRolledItems().size();
            rolledItemsManager.overwriteRolledItems(sets.rolled, System.currentTimeMillis());
            if (obtainedItemsManager != null)
            {
                obtainedItemsManager.overwriteObtainedItems(sets.obtained, System.currentTimeMillis());
            }
            return Math.max(0, sets.rolled.size() - before);
        }

        int added = 0;
        for (Integer itemId : sets.rolled)
        {
            if (!rolledItemsManager.isRolled(itemId))
            {
                rolledItemsManager.markRolled(itemId);
                added++;
            }
        }
        if (obtainedItemsManager != null)
        {
            for (Integer itemId : sets.obtained)
            {
                if (!obtainedItemsManager.isObtained(itemId))
                {
                    obtainedItemsManager.markObtained(itemId);
                }
            }
        }
        return added;
    }

    private UnlockSets extractUnlocks(GroupStateDto state)
    {
        UnlockSets sets = new UnlockSets();
        if (state == null) return sets;
        for (Map.Entry<String, Boolean> entry : state.getUnlocksOrEmpty().entrySet())
        {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            UnlockKey parsed = parseUnlockKey(entry.getKey());
            if (parsed == null || parsed.itemId == null) continue;
            if (parsed.type == UnlockType.OBTAINED)
            {
                sets.obtained.add(parsed.itemId);
            }
            else
            {
                sets.rolled.add(parsed.itemId);
            }
        }
        return sets;
    }

    private UnlockKey parseUnlockKey(String key)
    {
        if (key == null) return null;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return null;
        UnlockType type = UnlockType.ROLLED;
        if (trimmed.startsWith("rolled:"))
        {
            trimmed = trimmed.substring("rolled:".length());
            type = UnlockType.ROLLED;
        }
        else if (trimmed.startsWith("obtained:"))
        {
            trimmed = trimmed.substring("obtained:".length());
            type = UnlockType.OBTAINED;
        }
        Integer itemId = parseItemId(trimmed);
        if (itemId == null) return null;
        return new UnlockKey(type, itemId);
    }

    private enum UnlockType
    {
        ROLLED,
        OBTAINED
    }

    private static final class UnlockKey
    {
        private final UnlockType type;
        private final Integer itemId;

        private UnlockKey(UnlockType type, Integer itemId)
        {
            this.type = type;
            this.itemId = itemId;
        }
    }

    private static final class UnlockSets
    {
        private final Set<Integer> rolled = new LinkedHashSet<>();
        private final Set<Integer> obtained = new LinkedHashSet<>();
    }

    private void clearGroupLocalState()
    {
        if (rolledItemsManager != null)
        {
            rolledItemsManager.clearLocalForCurrentPlayer();
        }
        if (obtainedItemsManager != null)
        {
            obtainedItemsManager.clearLocalForCurrentPlayer();
        }
    }

    private Integer parseItemId(String key)
    {
        if (key == null) return null;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("item:"))
        {
            trimmed = trimmed.substring("item:".length());
        }
        try
        {
            return Integer.parseInt(trimmed);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private UUID getStoredGroupId()
    {
        String raw = configManager.getConfiguration(CFG_GROUP, KEY_GROUP_ID);
        return parseUuid(raw);
    }

    private void storeGroup(UUID groupId, int maxMembers)
    {
        configManager.setConfiguration(CFG_GROUP, KEY_GROUP_ID, groupId.toString());
        groupActive = true;
        configManager.setConfiguration(CFG_GROUP, KEY_MAX_MEMBERS, String.valueOf(maxMembers));
        configManager.setConfiguration(CFG_GROUP, KEY_LAST_VERSION, "0");
    }

    public void clearGroup()
    {
        configManager.unsetConfiguration(CFG_GROUP, KEY_GROUP_ID);
        configManager.unsetConfiguration(CFG_GROUP, KEY_MAX_MEMBERS);
        configManager.unsetConfiguration(CFG_GROUP, KEY_LAST_VERSION);
        configManager.unsetConfiguration(CFG_GROUP, KEY_JOIN_CODE);
        lastSeenVersion = 0L;
        groupActive = false;
        clearGroupLocalState();
        stopRealtime();
    }

    private UUID parseUuid(String raw)
    {
        if (raw == null || raw.isEmpty()) return null;
        try
        {
            return UUID.fromString(raw);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private long readLastSeenVersion()
    {
        String raw = configManager.getConfiguration(CFG_GROUP, KEY_LAST_VERSION);
        if (raw == null || raw.isEmpty()) return 0L;
        try
        {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private void storeLastSeenVersion(long version)
    {
        configManager.setConfiguration(CFG_GROUP, KEY_LAST_VERSION, String.valueOf(Math.max(0L, version)));
    }

    public String getJoinCode()
    {
        String code = configManager.getConfiguration(CFG_GROUP, KEY_JOIN_CODE);
        return code != null && !code.isEmpty() ? code : null;
    }

    private void storeJoinCode(String joinCode)
    {
        if (joinCode == null || joinCode.trim().isEmpty()) return;
        configManager.setConfiguration(CFG_GROUP, KEY_JOIN_CODE, joinCode.trim());
    }

    private String getStoredUserId()
    {
        return configManager.getConfiguration(CFG_GROUP, "supabase.user_id");
    }

    private boolean ensureAuthSessionOrReset()
    {
        SupabaseAuthService.Session session = authService.ensureSessionSync();
        if (session == null)
        {
            updateStatus("error", "Auth required", getStoredGroupId(), lastSeenVersion, lastSync, true);
            return false;
        }
        String userId = session.userId;
        if (lastUserId != null && userId != null && !lastUserId.equals(userId))
        {
            clearGroup();
            updateStatus("auth_changed", "Auth changed; re-join group", null, 0L, null, true);
            lastUserId = userId;
            return false;
        }
        if (lastUserId == null && userId != null)
        {
            lastUserId = userId;
        }
        return true;
    }

    private boolean isLoggedIn()
    {
        try
        {
            return client.getGameState() == GameState.LOGGED_IN;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private boolean isSyncEnabled()
    {
        String raw = configManager.getConfiguration(CFG_GROUP, GroupSyncConfigKeys.ENABLED);
        return raw != null && raw.equalsIgnoreCase("true");
    }

    private void ensureSupabaseDefaults()
    {
        String url = configManager.getConfiguration(CFG_GROUP, GroupSyncConfigKeys.SUPABASE_URL);
        if (url == null || url.trim().isEmpty())
        {
            if (SupabaseDefaults.SUPABASE_URL != null && !SupabaseDefaults.SUPABASE_URL.trim().isEmpty())
            {
                configManager.setConfiguration(CFG_GROUP, GroupSyncConfigKeys.SUPABASE_URL, SupabaseDefaults.SUPABASE_URL.trim());
            }
        }

        String key = configManager.getConfiguration(CFG_GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY);
        if (key == null || key.trim().isEmpty())
        {
            if (SupabaseDefaults.SUPABASE_ANON_KEY != null && !SupabaseDefaults.SUPABASE_ANON_KEY.trim().isEmpty())
            {
                configManager.setConfiguration(CFG_GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY, SupabaseDefaults.SUPABASE_ANON_KEY.trim());
            }
        }
    }

    private void updateMembers(java.util.List<GroupMember> list)
    {
        if (list == null) list = java.util.Collections.emptyList();
        members = list;
        if (!list.isEmpty())
        {
            groupActive = true;
        }
        Consumer<java.util.List<GroupMember>> listener = membersListener;
        if (listener != null)
        {
            listener.accept(list);
        }
    }

    private void updateStatus(String state, String message, UUID groupId, long version, Instant lastSync, boolean enabled)
    {
        GroupSyncStatus next = new GroupSyncStatus(enabled, state, message, groupId, version, lastSync);
        status = next;
        Consumer<GroupSyncStatus> listener = statusListener;
        if (listener != null)
        {
            listener.accept(next);
        }
    }

    private void startRealtimeIfReady()
    {
        if (realtimeClient == null) return;
        if (!isSyncEnabled()) return;
        if (!isLoggedIn()) return;
        UUID groupId = getStoredGroupId();
        if (groupId == null) return;
        if (ioExecutor == null) return;
        ioExecutor.submit(() -> realtimeClient.start(
                groupId,
                () -> fetchAndApplyState(false),
                this::refreshMembers
        ));
    }

    private void stopRealtime()
    {
        if (realtimeClient != null)
        {
            realtimeClient.stop();
        }
    }
}
