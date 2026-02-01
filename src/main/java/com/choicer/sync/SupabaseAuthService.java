package com.choicer.sync;

import com.google.gson.Gson;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@Singleton
public class SupabaseAuthService
{
    private static final String CFG_GROUP = "choicer";
    private static final String KEY_ACCESS = "supabase.access_token";
    private static final String KEY_REFRESH = "supabase.refresh_token";
    private static final String KEY_EXPIRES_AT = "supabase.expires_at";
    private static final String KEY_USER_ID = "supabase.user_id";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient = new OkHttpClient();

    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    public CompletableFuture<Session> ensureSession(ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(this::ensureSessionSync, executor);
    }

    public CompletableFuture<Session> refreshIfNeeded(ExecutorService executor)
    {
        return CompletableFuture.supplyAsync(this::refreshIfNeededSync, executor);
    }

    public Session ensureSessionSync()
    {
        Session stored = readStoredSession();
        if (stored == null || stored.accessToken == null || stored.accessToken.isEmpty())
        {
            return signInAnonymously();
        }

        if (isExpiredOrExpiringSoon(stored))
        {
            Session refreshed = refreshSession(stored.refreshToken);
            return refreshed != null ? refreshed : signInAnonymously();
        }

        return stored;
    }

    public Session refreshIfNeededSync()
    {
        Session stored = readStoredSession();
        if (stored == null) return signInAnonymously();
        if (!isExpiredOrExpiringSoon(stored)) return stored;
        Session refreshed = refreshSession(stored.refreshToken);
        return refreshed != null ? refreshed : signInAnonymously();
    }

    public void clearSession()
    {
        configManager.unsetConfiguration(CFG_GROUP, KEY_ACCESS);
        configManager.unsetConfiguration(CFG_GROUP, KEY_REFRESH);
        configManager.unsetConfiguration(CFG_GROUP, KEY_EXPIRES_AT);
        configManager.unsetConfiguration(CFG_GROUP, KEY_USER_ID);
    }

    private Session signInAnonymously()
    {
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null)
        {
            throw new IllegalStateException("Supabase URL or anon key not configured");
        }

        String url = baseUrl + "/auth/v1/signup";
        JsonObject payloadV1 = new JsonObject();
        payloadV1.addProperty("anonymous", true);

        JsonObject payloadV2 = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("is_anonymous", true);
        payloadV2.add("data", data);

        try
        {
            SignupAttempt attempt = tryAnonymousSignup(url, anonKey, payloadV1);
            if (attempt.session == null)
            {
                attempt = tryAnonymousSignup(url, anonKey, payloadV2);
            }
            if (attempt.session == null)
            {
                String detail = attempt.errorBody != null ? (" - " + attempt.errorBody) : "";
                throw new IOException("Supabase anonymous sign-in failed: " + attempt.status + detail);
            }
            storeSession(attempt.session);
            return attempt.session;
        }
        catch (IOException e)
        {
            log.warn("Choicer group auth: anonymous sign-in failed", e);
            throw new RuntimeException(e);
        }
    }

    private SignupAttempt tryAnonymousSignup(String url, String anonKey, JsonObject payload) throws IOException
    {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + anonKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String body = response.body() != null ? response.body().string() : "";
            if (response.code() == 422)
            {
                return SignupAttempt.failure(422, body);
            }
            if (!response.isSuccessful())
            {
                return SignupAttempt.failure(response.code(), body);
            }
            return SignupAttempt.success(parseSession(body));
        }
    }

    private static final class SignupAttempt
    {
        final Session session;
        final int status;
        final String errorBody;

        private SignupAttempt(Session session, int status, String errorBody)
        {
            this.session = session;
            this.status = status;
            this.errorBody = errorBody;
        }

        static SignupAttempt success(Session session)
        {
            return new SignupAttempt(session, 200, null);
        }

        static SignupAttempt failure(int status, String errorBody)
        {
            String body = (errorBody != null && errorBody.length() > 200) ? errorBody.substring(0, 200) : errorBody;
            return new SignupAttempt(null, status, body);
        }
    }

    private Session refreshSession(String refreshToken)
    {
        String baseUrl = normalizeBaseUrl(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_URL));
        String anonKey = safeTrim(configManager.getConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.SUPABASE_ANON_KEY));
        if (baseUrl == null || anonKey == null || refreshToken == null || refreshToken.isEmpty())
        {
            return null;
        }

        String url = baseUrl + "/auth/v1/token?grant_type=refresh_token";
        JsonObject payload = new JsonObject();
        payload.addProperty("refresh_token", refreshToken);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(payload)))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer " + anonKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            Session session = parseSession(body);
            if (session == null)
            {
                return null;
            }
            storeSession(session);
            return session;
        }
        catch (IOException e)
        {
            log.warn("Choicer group auth: refresh failed", e);
            return null;
        }
    }

    private Session parseSession(String body)
    {
        if (body == null || body.isEmpty()) return null;
        try
        {
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            if (obj == null || !obj.has("access_token")) return null;
            String accessToken = obj.has("access_token") ? obj.get("access_token").getAsString() : null;
            String refreshToken = obj.has("refresh_token") ? obj.get("refresh_token").getAsString() : null;
            long expiresIn = obj.has("expires_in") ? obj.get("expires_in").getAsLong() : 0L;
            long expiresAt = obj.has("expires_at") ? obj.get("expires_at").getAsLong() : 0L;
            String userId = null;
            if (obj.has("user") && obj.get("user").isJsonObject())
            {
                JsonObject user = obj.get("user").getAsJsonObject();
                if (user.has("id"))
                {
                    userId = user.get("id").getAsString();
                }
            }
            if (expiresAt == 0L && expiresIn > 0L)
            {
                long now = Instant.now().getEpochSecond();
                expiresAt = now + expiresIn;
            }
            return new Session(accessToken, refreshToken, expiresAt, userId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private void storeSession(Session session)
    {
        if (session == null) return;
        configManager.setConfiguration(CFG_GROUP, KEY_ACCESS, session.accessToken);
        if (session.refreshToken != null)
        {
            configManager.setConfiguration(CFG_GROUP, KEY_REFRESH, session.refreshToken);
        }
        if (session.expiresAt > 0)
        {
            configManager.setConfiguration(CFG_GROUP, KEY_EXPIRES_AT, String.valueOf(session.expiresAt));
        }
        if (session.userId != null)
        {
            configManager.setConfiguration(CFG_GROUP, KEY_USER_ID, session.userId);
        }
    }

    public Session readStoredSession()
    {
        String access = configManager.getConfiguration(CFG_GROUP, KEY_ACCESS);
        String refresh = configManager.getConfiguration(CFG_GROUP, KEY_REFRESH);
        String expiresAtStr = configManager.getConfiguration(CFG_GROUP, KEY_EXPIRES_AT);
        String userId = configManager.getConfiguration(CFG_GROUP, KEY_USER_ID);

        long expiresAt = 0L;
        if (expiresAtStr != null)
        {
            try
            {
                expiresAt = Long.parseLong(expiresAtStr.trim());
            }
            catch (NumberFormatException ignored) { }
        }

        if (access == null || access.isEmpty())
        {
            return null;
        }
        return new Session(access, refresh, expiresAt, userId);
    }

    private boolean isExpiredOrExpiringSoon(Session session)
    {
        if (session == null || session.expiresAt <= 0) return false;
        long now = Instant.now().getEpochSecond();
        return session.expiresAt <= (now + 30);
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

    public static class Session
    {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresAt;
        public final String userId;

        public Session(String accessToken, String refreshToken, long expiresAt, String userId)
        {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
            this.userId = userId;
        }
    }
}
