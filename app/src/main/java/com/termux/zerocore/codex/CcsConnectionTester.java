package com.termux.zerocore.codex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class CcsConnectionTester {
    interface ResultCallback { void accept(Result result); }
    static final class Result {
        final boolean success; final String message;
        Result(boolean success, String message) { this.success = success; this.message = message; }
    }
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

    private CcsConnectionTester() {}

    static void test(CodexProviderProfile profile, ResultCallback callback) {
        test(profile, false, callback);
    }

    static void test(CodexProviderProfile profile, boolean stream, ResultCallback callback) {
        long started = System.currentTimeMillis();
        try {
            Request request = request(profile, profile.baseUrl, stream);
            CLIENT.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException error) {
                    callback.accept(new Result(false, error.getClass().getSimpleName() + ": " + error.getMessage()));
                }
                @Override public void onResponse(Call call, Response response) {
                    long elapsed = System.currentTimeMillis() - started;
                    String body = "";
                    try { if (response.body() != null) body = response.body().string(); } catch (Exception ignored) {}
                    boolean ok = response.isSuccessful();
                    String detail = body.length() > 600 ? body.substring(0, 600) : body;
                    callback.accept(new Result(ok, "HTTP " + response.code() + " · " + elapsed + "ms\n" + detail));
                    response.close();
                }
            });
        } catch (Exception error) {
            callback.accept(new Result(false, error.getMessage()));
        }
    }

    static void speedTest(CodexProviderProfile profile, ResultCallback callback) {
        new Thread(() -> {
            StringBuilder result = new StringBuilder(); boolean any = false;
            java.util.List<String> endpoints = new java.util.ArrayList<>(); endpoints.add(profile.baseUrl);
            if (profile.alternateEndpoints != null) endpoints.addAll(profile.alternateEndpoints);
            for (String endpoint : endpoints) {
                if (endpoint == null || endpoint.trim().isEmpty()) continue;
                long started = System.currentTimeMillis();
                try (Response response = CLIENT.newCall(request(profile, endpoint.trim(), false)).execute()) {
                    long elapsed = System.currentTimeMillis() - started;
                    result.append(endpoint.trim()).append(" · HTTP ").append(response.code()).append(" · ").append(elapsed).append("ms\n");
                    any |= response.isSuccessful();
                } catch (Exception error) {
                    result.append(endpoint.trim()).append(" · ").append(error.getClass().getSimpleName()).append("\n");
                }
            }
            callback.accept(new Result(any, result.length() == 0 ? "没有可测速端点" : result.toString()));
        }, "CCS endpoint speed test").start();
    }

    private static Request request(CodexProviderProfile profile, String endpoint, boolean stream) {
        String model = profile.model == null || profile.model.trim().isEmpty() ? "test" : profile.model.trim();
        JsonObject body = new JsonObject();
        String suffix;
        if (CodexProviderProfile.FORMAT_ANTHROPIC.equals(profile.apiFormat)) {
            suffix = "/v1/messages";
            body.addProperty("model", model); body.addProperty("max_tokens", 1);
            com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
            JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", "ping");
            messages.add(message); body.add("messages", messages);
        } else if (CodexProviderProfile.FORMAT_CHAT.equals(profile.apiFormat)) {
            suffix = "/chat/completions";
            body.addProperty("model", model); body.addProperty("max_tokens", 1); body.addProperty("stream", stream);
            com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
            JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", "ping");
            messages.add(message); body.add("messages", messages);
        } else {
            suffix = "/responses";
            body.addProperty("model", model); body.addProperty("input", "ping"); body.addProperty("max_output_tokens", 1); body.addProperty("stream", stream);
        }
        try {
            JsonObject override = JsonParser.parseString(profile.bodyJson).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : override.entrySet()) body.add(entry.getKey(), entry.getValue());
        } catch (Exception ignored) {}
        String url = profile.fullUrl && endpoint.equals(profile.baseUrl) ? endpoint : join(endpoint, suffix);
        Request.Builder request = new Request.Builder().url(url)
            .post(RequestBody.create(MediaType.parse("application/json"), body.toString()));
        if (CodexProviderProfile.FORMAT_ANTHROPIC.equals(profile.apiFormat)) {
            if ("ANTHROPIC_API_KEY".equals(profile.anthropicAuthField)) request.header("x-api-key", profile.apiKey);
            else request.header("Authorization", "Bearer " + profile.apiKey);
            request.header("anthropic-version", "2023-06-01");
        } else request.header("Authorization", "Bearer " + profile.apiKey);
        if (profile.customUserAgent != null && !profile.customUserAgent.isEmpty()) request.header("User-Agent", profile.customUserAgent);
        try {
            JsonObject headers = JsonParser.parseString(profile.headersJson).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : headers.entrySet())
                if (entry.getValue().isJsonPrimitive()) request.header(entry.getKey(), entry.getValue().getAsString());
        } catch (Exception ignored) {}
        return request.build();
    }

    static String join(String base, String suffix) {
        String value = base == null ? "" : base.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/v1") && suffix.startsWith("/v1/")) return value + suffix.substring(3);
        if (!value.endsWith("/v1") && !suffix.startsWith("/v1/") && (value.matches("https?://[^/]+"))) value += "/v1";
        return value + suffix;
    }
}
