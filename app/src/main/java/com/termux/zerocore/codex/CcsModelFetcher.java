package com.termux.zerocore.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fetches the OpenAI-compatible model catalog used by CCS. */
final class CcsModelFetcher {
    interface Callback { void accept(Result result); }
    static final class Model {
        final String id;
        final String owner;
        Model(String id, String owner) { this.id = id; this.owner = owner; }
    }
    static final class Result {
        final List<Model> models;
        final String error;
        Result(List<Model> models, String error) { this.models = models; this.error = error; }
        boolean success() { return error == null; }
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    private CcsModelFetcher() {}

    static void fetch(CodexProviderProfile profile, Callback callback) {
        new Thread(() -> {
            try {
                List<Model> models = fetchBlocking(profile);
                callback.accept(new Result(models, null));
            } catch (Exception error) {
                callback.accept(new Result(Collections.emptyList(), error.getMessage()));
            }
        }, "CCS model fetch").start();
    }

    private static List<Model> fetchBlocking(CodexProviderProfile profile) throws Exception {
        List<String> candidates = candidates(profile.baseUrl, profile.fullUrl);
        String last = "没有可用的模型端点";
        for (String url : candidates) {
            Request.Builder builder = new Request.Builder().url(url).get()
                .header("Authorization", "Bearer " + profile.apiKey);
            if (profile.customUserAgent != null && !profile.customUserAgent.trim().isEmpty())
                builder.header("User-Agent", profile.customUserAgent.trim());
            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (response.code() == 404 || response.code() == 405) {
                    last = "HTTP " + response.code();
                    continue;
                }
                if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code() + "\n" + abbreviate(body));
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                List<Model> result = new ArrayList<>();
                if (root.has("data") && root.get("data").isJsonArray()) {
                    for (JsonElement item : root.getAsJsonArray("data")) {
                        if (!item.isJsonObject() || !item.getAsJsonObject().has("id")) continue;
                        JsonObject object = item.getAsJsonObject();
                        result.add(new Model(object.get("id").getAsString(),
                            object.has("owned_by") ? object.get("owned_by").getAsString() : ""));
                    }
                }
                result.sort((a, b) -> a.id.compareToIgnoreCase(b.id));
                return result;
            }
        }
        throw new IllegalStateException("所有模型端点均失败：" + last);
    }

    private static List<String> candidates(String base, boolean fullUrl) {
        String value = base == null ? "" : base.trim().replaceAll("/+$", "");
        Set<String> result = new LinkedHashSet<>();
        if (fullUrl) {
            int marker = value.indexOf("/v1/");
            if (marker >= 0) result.add(value.substring(0, marker) + "/v1/models");
            else {
                int slash = value.lastIndexOf('/');
                if (slash > value.indexOf("://") + 2) result.add(value.substring(0, slash) + "/v1/models");
            }
            return new ArrayList<>(result);
        }
        if (value.matches(".*\\/v\\d+$")) {
            result.add(value + "/models");
            if (!value.endsWith("/v1")) result.add(value + "/v1/models");
        } else result.add(value + "/v1/models");
        String[] suffixes = {"/api/claudecode", "/api/anthropic", "/apps/anthropic", "/api/coding",
            "/claudecode", "/anthropic", "/step_plan", "/coding", "/claude"};
        for (String suffix : suffixes) if (value.endsWith(suffix)) {
            String root = value.substring(0, value.length() - suffix.length());
            result.add(root + "/v1/models"); result.add(root + "/models"); break;
        }
        return new ArrayList<>(result);
    }

    private static String abbreviate(String value) {
        value = value == null ? "" : value.replace('\n', ' ').trim();
        return value.length() > 512 ? value.substring(0, 512) : value;
    }
}
