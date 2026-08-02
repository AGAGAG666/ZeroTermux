package com.termux.zerocore.codex;

import android.content.Context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Loopback-only protocol router used by Codex and OpenCode. */
public final class CcsProxyEngine extends NanoHTTPD {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES).build();

    public CcsProxyEngine(Context context) {
        super("127.0.0.1", CodexProviderStore.proxyPort());
        this.context = context.getApplicationContext();
    }

    @Override public synchronized void start() throws IOException {
        if (running.get()) return;
        super.start(SOCKET_READ_TIMEOUT, false);
        running.set(true);
    }

    @Override public synchronized void stop() {
        running.set(false);
        super.stop();
        client.dispatcher().cancelAll();
    }

    public boolean isRunning() { return running.get(); }

    @Override public Response serve(IHTTPSession session) {
        if (!Method.POST.equals(session.getMethod()))
            return json(Status.METHOD_NOT_ALLOWED, error("Only POST is supported"));
        String agent = agentForPath(session.getUri());
        if (agent == null) return json(Status.NOT_FOUND, error("Unsupported endpoint: " + session.getUri()));

        JsonObject incoming;
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            incoming = JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
        } catch (Exception e) {
            return json(Status.BAD_REQUEST, error("Invalid JSON body: " + e.getMessage()));
        }
        boolean wantsStream = incoming.has("stream") && incoming.get("stream").getAsBoolean();
        List<CodexProviderProfile> candidates = candidates(agent);
        if (candidates.isEmpty()) return json(Status.SERVICE_UNAVAILABLE, error("No active " + agent + " provider"));

        String lastError = "No provider accepted the request";
        for (CodexProviderProfile provider : candidates) {
            JsonObject upstreamBody = CcsProtocolBridge.toUpstream(provider, agent, incoming);
            for (String endpoint : endpoints(provider)) {
                long started = System.currentTimeMillis();
                try (okhttp3.Response upstream = client.newCall(request(provider, endpoint, upstreamBody)).execute()) {
                    String raw = upstream.body() == null ? "{}" : upstream.body().string();
                    if (!upstream.isSuccessful()) {
                        lastError = provider.name + " (" + endpoint + "): HTTP " + upstream.code() + " " + abbreviate(raw);
                        CcsUsageStore.record(agent, provider.id, model(upstreamBody), false,
                            System.currentTimeMillis() - started, 0, 0);
                        continue;
                    }
                    JsonObject converted = CcsProtocolBridge.toClient(provider, agent,
                        JsonParser.parseString(raw).getAsJsonObject());
                    long[] usage = usage(converted);
                    CcsUsageStore.record(agent, provider.id, model(upstreamBody), true,
                        System.currentTimeMillis() - started, usage[0], usage[1]);
                    if (wantsStream) return newFixedLengthResponse(Status.OK, "text/event-stream; charset=utf-8",
                        CcsProtocolBridge.syntheticStream(agent, converted));
                    return json(Status.OK, converted);
                } catch (Exception e) {
                    lastError = provider.name + " (" + endpoint + "): " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    CcsUsageStore.record(agent, provider.id, model(incoming), false,
                        System.currentTimeMillis() - started, 0, 0);
                }
            }
        }
        return json(Status.BAD_GATEWAY, error(lastError));
    }

    private List<CodexProviderProfile> candidates(String agent) {
        List<CodexProviderProfile> all = CodexProviderStore.load(context, agent);
        List<CodexProviderProfile> result = new ArrayList<>();
        String activeId = CodexProviderStore.activeId(context, agent);
        for (CodexProviderProfile provider : all) if (provider.id.equals(activeId)) result.add(provider);
        for (CodexProviderProfile provider : all)
            if (!provider.id.equals(activeId) && provider.failoverEnabled) result.add(provider);
        return result;
    }

    private List<String> endpoints(CodexProviderProfile provider) {
        List<String> result = new ArrayList<>(); result.add(provider.baseUrl);
        if (provider.endpointAutoSelect && provider.alternateEndpoints != null)
            for (String endpoint : provider.alternateEndpoints)
                if (endpoint != null && !endpoint.trim().isEmpty() && !result.contains(endpoint.trim())) result.add(endpoint.trim());
        return result;
    }

    private Request request(CodexProviderProfile provider, String endpoint, JsonObject body) {
        String url = provider.fullUrl && provider.baseUrl.equals(endpoint) ? endpoint
            : CcsConnectionTester.join(endpoint, CcsProtocolBridge.upstreamPath(provider));
        Request.Builder builder = new Request.Builder().url(url)
            .post(RequestBody.create(JSON, body.toString()));
        if (CodexProviderProfile.FORMAT_ANTHROPIC.equals(provider.apiFormat)) {
            if ("ANTHROPIC_API_KEY".equals(provider.anthropicAuthField)) builder.header("x-api-key", provider.apiKey);
            else builder.header("Authorization", "Bearer " + provider.apiKey);
            builder.header("anthropic-version", "2023-06-01");
            if (provider.impersonateClaudeCode) {
                builder.header("anthropic-beta", "claude-code-20250219");
                builder.header("x-app", "cli");
            }
        } else builder.header("Authorization", "Bearer " + provider.apiKey);
        if (provider.customUserAgent != null && !provider.customUserAgent.trim().isEmpty())
            builder.header("User-Agent", provider.customUserAgent.trim());
        try {
            JsonObject headers = JsonParser.parseString(provider.headersJson).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : headers.entrySet())
                if (entry.getValue().isJsonPrimitive()) builder.header(entry.getKey(), entry.getValue().getAsString());
        } catch (Exception ignored) {}
        return builder.build();
    }

    static String agentForPath(String path) {
        if (path == null) return null;
        String value = path.toLowerCase();
        if (value.endsWith("/responses") || value.contains("/responses/compact")) return CodexProviderProfile.AGENT_CODEX;
        if (value.endsWith("/chat/completions")) return CodexProviderProfile.AGENT_OPENCODE;
        return null;
    }

    private static long[] usage(JsonObject body) {
        JsonObject value = body.has("usage") && body.get("usage").isJsonObject() ? body.getAsJsonObject("usage") : null;
        if (value == null) return new long[]{0, 0};
        return new long[]{number(value, "input_tokens", number(value, "prompt_tokens", 0)),
            number(value, "output_tokens", number(value, "completion_tokens", 0))};
    }

    private static long number(JsonObject value, String key, long fallback) {
        try { return value.has(key) ? value.get(key).getAsLong() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static String model(JsonObject body) {
        try { return body.get("model").getAsString(); } catch (Exception ignored) { return ""; }
    }

    private static JsonObject error(String message) {
        JsonObject detail = new JsonObject(); detail.addProperty("message", message); detail.addProperty("type", "cc_switch_proxy_error");
        JsonObject root = new JsonObject(); root.add("error", detail); return root;
    }

    private static Response json(Status status, JsonObject body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
    }

    private static String abbreviate(String value) {
        String compact = value == null ? "" : value.replace('\n', ' ').trim();
        return compact.length() > 600 ? compact.substring(0, 600) : compact;
    }
}
