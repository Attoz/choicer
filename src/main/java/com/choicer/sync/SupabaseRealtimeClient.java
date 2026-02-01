package com.choicer.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Singleton
public class SupabaseRealtimeClient
{
    private static final long HEARTBEAT_MS = 30000L;
    private static final long RECONNECT_BASE_MS = 2000L;
    private static final long RECONNECT_MAX_MS = 30000L;

    private final OkHttpClient httpClient = new OkHttpClient();

    @Inject private ConfigManager configManager;
    @Inject private SupabaseAuthService authService;
    @Inject private Gson gson;

    private final AtomicInteger refCounter = new AtomicInteger(1);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private volatile WebSocket socket;
    private volatile boolean running = false;
    private volatile UUID groupId;
    private volatile Runnable onGroupState;
    private volatile Runnable onMembers;
    private volatile Runnable onRollEvents;
    private volatile long reconnectDelayMs = RECONNECT_BASE_MS;
    private volatile String accessToken;

    public synchronized void start(UUID groupId, Runnable onGroupState, Runnable onMembers, Runnable onRollEvents)
    {
        if (groupId == null)
        {
            return;
        }
        if (running && this.groupId != null && this.groupId.equals(groupId) && socket != null)
        {
            this.onGroupState = onGroupState;
            this.onMembers = onMembers;
            this.onRollEvents = onRollEvents;
            return;
        }
        if (running && this.groupId != null && !this.groupId.equals(groupId))
        {
            closeSocket();
        }
        this.groupId = groupId;
        this.onGroupState = onGroupState;
        this.onMembers = onMembers;
        this.onRollEvents = onRollEvents;
        if (scheduler == null)
        {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Choicer-GroupSync-RT");
                t.setDaemon(true);
                return t;
            });
        }
        running = true;
        connect();
    }

    public synchronized void stop()
    {
        running = false;
        groupId = null;
        onGroupState = null;
        onMembers = null;
        onRollEvents = null;
        closeSocket();
        if (scheduler != null)
        {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private synchronized void connect()
    {
        if (!running || groupId == null) return;

        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            log.debug("Choicer realtime: Supabase URL or anon key not configured");
            scheduleReconnect();
            return;
        }

        SupabaseAuthService.Session session = authService.refreshIfNeededSync();
        if (session == null)
        {
            session = authService.ensureSessionSync();
        }
        if (session == null || session.accessToken == null)
        {
            log.debug("Choicer realtime: Supabase auth session missing");
            scheduleReconnect();
            return;
        }
        accessToken = session.accessToken;

        String wsUrl = toRealtimeWebsocketUrl(baseUrl, anonKey);
        Request request = new Request.Builder()
                .url(wsUrl)
                .build();

        closeSocket();
        socket = httpClient.newWebSocket(request, new Listener());
    }

    private void closeSocket()
    {
        if (heartbeatTask != null)
        {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        if (socket != null)
        {
            try
            {
                socket.close(1000, "closed");
            }
            catch (Exception ignored) { }
            socket = null;
        }
    }

    private void scheduleReconnect()
    {
        if (!running || scheduler == null) return;
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(RECONNECT_MAX_MS, reconnectDelayMs * 2);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void resetReconnectDelay()
    {
        reconnectDelayMs = RECONNECT_BASE_MS;
    }

    private void onSocketOpen()
    {
        resetReconnectDelay();
        subscribeToTable("group_state");
        subscribeToTable("group_members");
        subscribeToTable("group_roll_events");
        if (scheduler != null)
        {
            heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void onSocketClosed()
    {
        closeSocket();
        scheduleReconnect();
    }

    private void sendHeartbeat()
    {
        send("phoenix", "heartbeat", new JsonObject());
    }

    private void subscribeToTable(String table)
    {
        if (groupId == null || accessToken == null) return;

        JsonObject change = new JsonObject();
        change.addProperty("event", "*");
        change.addProperty("schema", "public");
        change.addProperty("table", table);
        change.addProperty("filter", "group_id=eq." + groupId);

        JsonArray changes = new JsonArray();
        changes.add(change);

        JsonObject config = new JsonObject();
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("ack", false);
        config.add("broadcast", broadcast);
        config.add("postgres_changes", changes);

        JsonObject payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("access_token", accessToken);

        send("realtime:public:" + table, "phx_join", payload);
    }

    private void send(String topic, String event, JsonObject payload)
    {
        WebSocket ws = socket;
        if (ws == null) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("topic", topic);
        msg.addProperty("event", event);
        msg.add("payload", payload);
        msg.addProperty("ref", String.valueOf(refCounter.getAndIncrement()));
        ws.send(gson.toJson(msg));
    }

    private void handleMessage(String text)
    {
        JsonObject msg;
        try
        {
            msg = gson.fromJson(text, JsonObject.class);
        }
        catch (Exception e)
        {
            return;
        }
        if (msg == null || !msg.has("event")) return;
        String event = msg.get("event").getAsString();

        if ("phx_error".equals(event) || "phx_close".equals(event))
        {
            onSocketClosed();
            return;
        }

        if (!"postgres_changes".equals(event)) return;
        if (!msg.has("payload") || !msg.get("payload").isJsonObject()) return;
        JsonObject payload = msg.getAsJsonObject("payload");
        if (!payload.has("table")) return;

        String table = payload.get("table").getAsString();
        if ("group_state".equals(table))
        {
            Runnable cb = onGroupState;
            if (cb != null) cb.run();
        }
        else if ("group_members".equals(table))
        {
            Runnable cb = onMembers;
            if (cb != null) cb.run();
        }
        else if ("group_roll_events".equals(table))
        {
            Runnable cb = onRollEvents;
            if (cb != null) cb.run();
        }
    }

    private static String toRealtimeWebsocketUrl(String baseUrl, String anonKey)
    {
        String wsBase = baseUrl;
        if (wsBase.startsWith("https://"))
        {
            wsBase = "wss://" + wsBase.substring("https://".length());
        }
        else if (wsBase.startsWith("http://"))
        {
            wsBase = "ws://" + wsBase.substring("http://".length());
        }
        return wsBase + "/realtime/v1/websocket?apikey=" + anonKey + "&vsn=1.0.0";
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

    private final class Listener extends WebSocketListener
    {
        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            onSocketOpen();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            handleMessage(text);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response)
        {
            onSocketClosed();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            onSocketClosed();
        }
    }
}
