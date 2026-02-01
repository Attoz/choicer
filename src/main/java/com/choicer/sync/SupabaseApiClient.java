package com.choicer.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@Singleton
public class SupabaseApiClient
{
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient = new OkHttpClient();

    @Inject private SupabaseAuthService authService;
    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    public CompletableFuture<JoinGroupResult> joinGroup(String joinCode, ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(() -> joinGroupSync(joinCode), executor);
    }

    public CompletableFuture<GroupStateDto> getGroupState(UUID groupId, ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(() -> getGroupStateSync(groupId), executor);
    }

    public CompletableFuture<GroupStateDto> getGroupStateMeta(UUID groupId, ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(() -> getGroupStateMetaSync(groupId), executor);
    }

    public CompletableFuture<Void> postUnlock(UUID groupId, UUID eventId, String unlockKey, Instant clientTs, ExecutorService executor)
    {
        return CompletableFuture.runAsync(() -> postUnlockSync(groupId, eventId, unlockKey, clientTs), executor);
    }

    public CompletableFuture<CreateGroupResult> createGroup(String name, int maxMembers, ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(() -> createGroupSync(name, maxMembers), executor);
    }

    public CompletableFuture<java.util.List<GroupMember>> getGroupMembers(UUID groupId, ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(() -> getGroupMembersSync(groupId), executor);
    }

    public CompletableFuture<Void> kickMember(UUID groupId, UUID memberUserId, ExecutorService executor)
    {
        return CompletableFuture.runAsync(() -> kickMemberSync(groupId, memberUserId), executor);
    }

    public CompletableFuture<Void> leaveGroup(UUID groupId, ExecutorService executor)
    {
        return CompletableFuture.runAsync(() -> leaveGroupSync(groupId), executor);
    }

    public JsonArray callRpc(String name, JsonObject body, ExecutorService executor)
    {
        return callRpcSync(name, body);
    }

    /**
     * Persists the display name server-side.
     * Uses RPC first (existing backend contract), then falls back to direct table updates.
     */
    public void setDisplayNameSync(String displayName, UUID groupId)
    {
        String normalized = displayName != null ? displayName.trim() : "";
        if (normalized.isEmpty())
        {
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("p_display_name", normalized);
        try
        {
            callRpcSync("set_display_name", body);
            return;
        }
        catch (Exception rpcError)
        {
            log.debug("Choicer group: set_display_name RPC unavailable, trying REST fallback");
        }

        try
        {
            patchGroupMemberDisplayNameSync(groupId, normalized);
            upsertUserProfileDisplayNameSync(normalized);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to persist display name", e);
        }
    }

    /**
     * Reads the current persisted display name for the authenticated user in a group.
     */
    public String getGroupMemberDisplayNameSync(UUID groupId)
    {
        if (groupId == null)
        {
            return null;
        }
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }

        SupabaseAuthService.Session session = authService.ensureSessionSync();
        String userId = session != null ? session.userId : null;
        if (userId == null || userId.trim().isEmpty())
        {
            return null;
        }

        String url = baseUrl
                + "/rest/v1/group_members?group_id=eq." + groupId
                + "&user_id=eq." + userId
                + "&select=display_name&limit=1";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                return retryGetGroupMemberDisplayName(baseUrl, anonKey, refreshed.accessToken, groupId, userId);
            }
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_members display_name query failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonArray array = gson.fromJson(body, JsonArray.class);
            if (array == null || array.size() == 0 || !array.get(0).isJsonObject())
            {
                return null;
            }
            JsonObject obj = array.get(0).getAsJsonObject();
            if (!obj.has("display_name"))
            {
                return null;
            }
            String value = obj.get("display_name").isJsonNull() ? null : obj.get("display_name").getAsString();
            if (value == null)
            {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public JoinGroupResult joinGroupSync(String joinCode)
    {
        JsonObject body = new JsonObject();
        body.addProperty("p_join_code", joinCode);

        JsonArray result = callRpcSync("join_group", body);
        if (result == null || result.size() == 0)
        {
            throw new RuntimeException("Supabase join_group returned empty response");
        }
        return gson.fromJson(result.get(0), JoinGroupResult.class);
    }

    public GroupStateDto getGroupStateSync(UUID groupId)
    {
        return getGroupStateSync(groupId, "unlocks,version,updated_at");
    }

    public GroupStateDto getGroupStateMetaSync(UUID groupId)
    {
        return getGroupStateSync(groupId, "version,updated_at");
    }

    private GroupStateDto getGroupStateSync(UUID groupId, String select)
    {
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }

        SupabaseAuthService.Session session = authService.ensureSessionSync();
        String url = baseUrl + "/rest/v1/group_state?group_id=eq." + groupId + "&select=" + select;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                return retryGroupState(baseUrl, anonKey, refreshed.accessToken, groupId, select);
            }
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_state failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonArray array = gson.fromJson(body, JsonArray.class);
            if (array == null || array.size() == 0)
            {
                return null;
            }
            return parseGroupState(array.get(0));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void postUnlockSync(UUID groupId, UUID eventId, String unlockKey, Instant clientTs)
    {
        JsonObject body = new JsonObject();
        body.addProperty("p_group_id", groupId.toString());
        body.addProperty("p_event_id", eventId.toString());
        body.addProperty("p_unlock_key", unlockKey);
        body.addProperty("p_client_ts", clientTs.toString());

        callRpcSync("post_unlock", body);
    }

    public void postRollEventSync(GroupRollEvent event)
    {
        if (event == null || event.groupId == null || event.type == null)
        {
            return;
        }
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }

        SupabaseAuthService.Session session = authService.ensureSessionSync();
        String url = baseUrl + "/rest/v1/group_roll_events";
        JsonObject body = new JsonObject();
        body.addProperty("group_id", event.groupId.toString());
        body.addProperty("event_type", event.type.wireValue);
        if (event.rollId != null)
        {
            body.addProperty("roll_id", event.rollId);
        }
        if (event.startedAt != null)
        {
            body.addProperty("started_at", event.startedAt.toString());
        }
        if (event.triggerItemId != null)
        {
            body.addProperty("trigger_item_id", event.triggerItemId);
        }
        if (event.selectedItemId != null)
        {
            body.addProperty("selected_item_id", event.selectedItemId);
        }
        if (event.rollDurationMs != null)
        {
            body.addProperty("roll_duration_ms", event.rollDurationMs);
        }
        body.addProperty("is_manual_roll", event.manualRoll);
        if (event.actorDisplayName != null && !event.actorDisplayName.trim().isEmpty())
        {
            body.addProperty("actor_display_name", event.actorDisplayName.trim());
        }
        if (event.options != null)
        {
            JsonArray options = new JsonArray();
            for (Integer itemId : event.options)
            {
                if (itemId != null && itemId > 0)
                {
                    options.add(itemId);
                }
            }
            body.add("options", options);
        }

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                retryPostRollEvent(baseUrl, anonKey, refreshed.accessToken, body);
                return;
            }
            if (!response.isSuccessful())
            {
                String bodyStr = response.body() != null ? response.body().string() : "";
                String detail = bodyStr != null && !bodyStr.isEmpty() ? (" - " + bodyStr) : "";
                throw new IOException("Supabase group_roll_events insert failed: " + response.code() + detail);
            }
        }
        catch (IOException e)
        {
            log.warn("Choicer group roll insert failed", e);
            throw new RuntimeException(e);
        }
    }

    public List<GroupRollEvent> getRollEventsSinceSync(UUID groupId, Instant sinceExclusive, int limit)
    {
        if (groupId == null)
        {
            return Collections.emptyList();
        }
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }

        SupabaseAuthService.Session session = authService.ensureSessionSync();
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/rest/v1/group_roll_events?group_id=eq.")
                .append(groupId)
                .append("&select=id,group_id,actor_user_id,actor_display_name,roll_id,event_type,created_at,started_at,trigger_item_id,selected_item_id,roll_duration_ms,is_manual_roll,options")
                .append("&order=created_at.asc");
        if (sinceExclusive != null)
        {
            url.append("&created_at=gt.").append(sinceExclusive);
        }
        if (limit > 0)
        {
            url.append("&limit=").append(limit);
        }

        Request request = new Request.Builder()
                .url(url.toString())
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                return retryGetRollEvents(baseUrl, anonKey, refreshed.accessToken, groupId, sinceExclusive, limit);
            }
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_roll_events query failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonArray array = gson.fromJson(body, JsonArray.class);
            return parseRollEvents(array);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }


    public CreateGroupResult createGroupSync(String name, int maxMembers)
    {
        JsonObject body = new JsonObject();
        body.addProperty("p_name", name);
        body.addProperty("p_max_members", maxMembers);

        JsonArray result = callRpcSync("create_group", body);
        if (result == null || result.size() == 0)
        {
            throw new RuntimeException("Supabase create_group returned empty response");
        }
        return gson.fromJson(result.get(0), CreateGroupResult.class);
    }

    public java.util.List<GroupMember> getGroupMembersSync(UUID groupId)
    {
        JsonObject body = new JsonObject();
        body.addProperty("p_group_id", groupId.toString());
        JsonArray result = callRpcSync("get_group_members", body);
        java.util.List<GroupMember> members = new java.util.ArrayList<>();
        if (result == null) return members;
        for (JsonElement el : result)
        {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            GroupMember member = new GroupMember();
            if (obj.has("user_id"))
            {
                try
                {
                    member.userId = UUID.fromString(obj.get("user_id").getAsString());
                }
                catch (Exception ignored) { }
            }
            if (obj.has("display_name"))
            {
                try
                {
                    member.displayName = obj.get("display_name").getAsString();
                }
                catch (Exception ignored) { }
            }
            else if (obj.has("displayName"))
            {
                try
                {
                    member.displayName = obj.get("displayName").getAsString();
                }
                catch (Exception ignored) { }
            }
            member.role = obj.has("role") ? obj.get("role").getAsString() : null;
            if (obj.has("joined_at"))
            {
                try
                {
                    member.joinedAt = Instant.parse(obj.get("joined_at").getAsString());
                }
                catch (Exception ignored) { }
            }
            if (obj.has("last_seen_at"))
            {
                try
                {
                    member.lastSeenAt = Instant.parse(obj.get("last_seen_at").getAsString());
                }
                catch (Exception ignored) { }
            }
            members.add(member);
        }
        return members;
    }

    public void kickMemberSync(UUID groupId, UUID memberUserId)
    {
        JsonObject body = new JsonObject();
        body.addProperty("p_group_id", groupId.toString());
        body.addProperty("p_member_user_id", memberUserId.toString());
        callRpcSync("kick_member", body);
    }

    public void leaveGroupSync(UUID groupId)
    {
        JsonObject body = new JsonObject();
        body.addProperty("p_group_id", groupId.toString());
        callRpcSync("leave_group", body);
    }

    public JsonArray callRpcSync(String name, JsonObject body)
    {
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }

        SupabaseAuthService.Session session = authService.ensureSessionSync();
        String url = baseUrl + "/rest/v1/rpc/" + name;

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                return retryRpc(baseUrl, anonKey, refreshed.accessToken, name, body);
            }
            String bodyStr = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
            {
                String detail = bodyStr != null && !bodyStr.isEmpty() ? (" - " + bodyStr) : "";
                throw new IOException("Supabase RPC failed: " + response.code() + detail);
            }
            if (bodyStr == null || bodyStr.isEmpty())
            {
                return new JsonArray();
            }
            JsonElement json = gson.fromJson(bodyStr, JsonElement.class);
            if (json != null && json.isJsonArray())
            {
                return json.getAsJsonArray();
            }
            return new JsonArray();
        }
        catch (IOException e)
        {
            log.warn("Choicer group RPC {} failed", name, e);
            throw new RuntimeException(e);
        }
    }

    private JsonArray retryRpc(String baseUrl, String anonKey, String accessToken, String name, JsonObject body) throws IOException
    {
        String url = baseUrl + "/rest/v1/rpc/" + name;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase RPC retry failed: " + response.code());
            }
            String bodyStr = response.body() != null ? response.body().string() : "";
            if (bodyStr == null || bodyStr.isEmpty())
            {
                return new JsonArray();
            }
            JsonElement json = gson.fromJson(bodyStr, JsonElement.class);
            if (json != null && json.isJsonArray())
            {
                return json.getAsJsonArray();
            }
            return new JsonArray();
        }
    }

    private GroupStateDto retryGroupState(String baseUrl, String anonKey, String accessToken, UUID groupId, String select) throws IOException
    {
        String url = baseUrl + "/rest/v1/group_state?group_id=eq." + groupId + "&select=" + select;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_state retry failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonArray array = gson.fromJson(body, JsonArray.class);
            if (array == null || array.size() == 0)
            {
                return null;
            }
            return parseGroupState(array.get(0));
        }
    }

    private String retryGetGroupMemberDisplayName(
            String baseUrl,
            String anonKey,
            String accessToken,
            UUID groupId,
            String userId) throws IOException
    {
        String url = baseUrl
                + "/rest/v1/group_members?group_id=eq." + groupId
                + "&user_id=eq." + userId
                + "&select=display_name&limit=1";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_members display_name retry query failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonArray array = gson.fromJson(body, JsonArray.class);
            if (array == null || array.size() == 0 || !array.get(0).isJsonObject())
            {
                return null;
            }
            JsonObject obj = array.get(0).getAsJsonObject();
            if (!obj.has("display_name"))
            {
                return null;
            }
            String value = obj.get("display_name").isJsonNull() ? null : obj.get("display_name").getAsString();
            if (value == null)
            {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private void retryPostRollEvent(String baseUrl, String anonKey, String accessToken, JsonObject body) throws IOException
    {
        String url = baseUrl + "/rest/v1/group_roll_events";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_roll_events retry insert failed: " + response.code());
            }
        }
    }

    private List<GroupRollEvent> retryGetRollEvents(
            String baseUrl,
            String anonKey,
            String accessToken,
            UUID groupId,
            Instant sinceExclusive,
            int limit) throws IOException
    {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/rest/v1/group_roll_events?group_id=eq.")
                .append(groupId)
                .append("&select=id,group_id,actor_user_id,actor_display_name,roll_id,event_type,created_at,started_at,trigger_item_id,selected_item_id,roll_duration_ms,is_manual_roll,options")
                .append("&order=created_at.asc");
        if (sinceExclusive != null)
        {
            url.append("&created_at=gt.").append(sinceExclusive);
        }
        if (limit > 0)
        {
            url.append("&limit=").append(limit);
        }

        Request request = new Request.Builder()
                .url(url.toString())
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_roll_events retry query failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonArray array = gson.fromJson(body, JsonArray.class);
            return parseRollEvents(array);
        }
    }

    private List<GroupRollEvent> parseRollEvents(JsonArray array)
    {
        if (array == null || array.size() == 0)
        {
            return Collections.emptyList();
        }
        List<GroupRollEvent> events = new ArrayList<>();
        for (JsonElement element : array)
        {
            if (element == null || !element.isJsonObject())
            {
                continue;
            }
            GroupRollEvent event = parseRollEvent(element.getAsJsonObject());
            if (event != null)
            {
                events.add(event);
            }
        }
        return events;
    }

    private GroupRollEvent parseRollEvent(JsonObject obj)
    {
        GroupRollEvent event = new GroupRollEvent();
        if (obj.has("id"))
        {
            try
            {
                event.id = UUID.fromString(obj.get("id").getAsString());
            }
            catch (Exception ignored) { }
        }
        if (obj.has("group_id"))
        {
            try
            {
                event.groupId = UUID.fromString(obj.get("group_id").getAsString());
            }
            catch (Exception ignored) { }
        }
        if (obj.has("actor_user_id"))
        {
            try
            {
                event.actorUserId = obj.get("actor_user_id").getAsString();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("actor_display_name"))
        {
            try
            {
                event.actorDisplayName = obj.get("actor_display_name").getAsString();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("roll_id"))
        {
            try
            {
                event.rollId = obj.get("roll_id").getAsString();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("event_type"))
        {
            try
            {
                event.type = GroupRollEventType.fromWire(obj.get("event_type").getAsString());
            }
            catch (Exception ignored) { }
        }
        if (obj.has("created_at"))
        {
            try
            {
                event.createdAt = Instant.parse(obj.get("created_at").getAsString());
            }
            catch (Exception ignored) { }
        }
        if (obj.has("started_at"))
        {
            try
            {
                event.startedAt = Instant.parse(obj.get("started_at").getAsString());
            }
            catch (Exception ignored) { }
        }
        if (obj.has("trigger_item_id"))
        {
            try
            {
                event.triggerItemId = obj.get("trigger_item_id").getAsInt();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("selected_item_id"))
        {
            try
            {
                event.selectedItemId = obj.get("selected_item_id").getAsInt();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("roll_duration_ms"))
        {
            try
            {
                event.rollDurationMs = obj.get("roll_duration_ms").getAsInt();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("is_manual_roll"))
        {
            try
            {
                event.manualRoll = obj.get("is_manual_roll").getAsBoolean();
            }
            catch (Exception ignored) { }
        }
        if (obj.has("options") && obj.get("options").isJsonArray())
        {
            List<Integer> options = new ArrayList<>();
            for (JsonElement opt : obj.getAsJsonArray("options"))
            {
                try
                {
                    int itemId = opt.getAsInt();
                    if (itemId > 0)
                    {
                        options.add(itemId);
                    }
                }
                catch (Exception ignored) { }
            }
            event.options = options;
        }
        return event;
    }

    private void patchGroupMemberDisplayNameSync(UUID groupId, String displayName) throws IOException
    {
        if (groupId == null)
        {
            return;
        }
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }
        SupabaseAuthService.Session session = authService.ensureSessionSync();
        String userId = session != null ? session.userId : null;
        if (userId == null || userId.trim().isEmpty())
        {
            throw new IllegalStateException("Supabase user id missing");
        }

        JsonObject body = new JsonObject();
        body.addProperty("display_name", displayName);

        String url = baseUrl + "/rest/v1/group_members?group_id=eq." + groupId + "&user_id=eq." + userId;
        Request request = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                request = new Request.Builder()
                        .url(url)
                        .patch(RequestBody.create(JSON, gson.toJson(body)))
                        .addHeader("apikey", anonKey)
                        .addHeader("Authorization", "Bearer " + refreshed.accessToken)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "return=minimal")
                        .build();
                try (Response retry = httpClient.newCall(request).execute())
                {
                    if (!retry.isSuccessful())
                    {
                        throw new IOException("Supabase group_members display_name patch failed: " + retry.code());
                    }
                }
                return;
            }
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase group_members display_name patch failed: " + response.code());
            }
        }
    }

    private void upsertUserProfileDisplayNameSync(String displayName) throws IOException
    {
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }
        SupabaseAuthService.Session session = authService.ensureSessionSync();
        String userId = session != null ? session.userId : null;
        if (userId == null || userId.trim().isEmpty())
        {
            throw new IllegalStateException("Supabase user id missing");
        }

        JsonObject row = new JsonObject();
        row.addProperty("user_id", userId);
        row.addProperty("display_name", displayName);
        JsonArray payload = new JsonArray();
        payload.add(row);

        String url = baseUrl + "/rest/v1/user_profiles";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + session.accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                SupabaseAuthService.Session refreshed = authService.refreshIfNeededSync();
                if (refreshed == null)
                {
                    throw new IOException("Supabase auth refresh failed");
                }
                request = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(JSON, gson.toJson(payload)))
                        .addHeader("apikey", anonKey)
                        .addHeader("Authorization", "Bearer " + refreshed.accessToken)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                        .build();
                try (Response retry = httpClient.newCall(request).execute())
                {
                    if (!retry.isSuccessful())
                    {
                        throw new IOException("Supabase user_profiles upsert failed: " + retry.code());
                    }
                }
                return;
            }
            if (!response.isSuccessful())
            {
                throw new IOException("Supabase user_profiles upsert failed: " + response.code());
            }
        }
    }

    private GroupStateDto parseGroupState(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        GroupStateDto dto = new GroupStateDto();
        dto.version = obj.has("version") ? obj.get("version").getAsLong() : 0L;
        if (obj.has("updated_at"))
        {
            try
            {
                dto.updatedAt = Instant.parse(obj.get("updated_at").getAsString());
            }
            catch (Exception ignored) { }
        }
        if (obj.has("unlocks") && obj.get("unlocks").isJsonObject())
        {
            JsonObject unlocksObj = obj.get("unlocks").getAsJsonObject();
            dto.unlocks = new java.util.HashMap<>();
            for (Map.Entry<String, JsonElement> entry : unlocksObj.entrySet())
            {
                boolean value = false;
                try
                {
                    value = entry.getValue().getAsBoolean();
                }
                catch (Exception ignored) { }
                dto.unlocks.put(entry.getKey(), value);
            }
        }
        return dto;
    }

    private static String normalizeBaseUrl(String base)
    {
        if (base == null) return null;
        String trimmed = base.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String safeTrim(String value)
    {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
