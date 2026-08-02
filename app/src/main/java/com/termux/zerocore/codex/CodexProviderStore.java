package com.termux.zerocore.codex;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local CC Switch store and live-config projector for Codex and OpenCode. */
public final class CodexProviderStore {
    public static final String PREFS = "cc_switch_android";
    private static final String LEGACY_PREFS = "codex_provider_switch";
    private static final String KEY_ROUTE = "route_enabled";
    private static final int PROXY_PORT = 15721;
    private static final String BEGIN = "# BEGIN ZEROTERMUX CC SWITCH";
    private static final String END = "# END ZEROTERMUX CC SWITCH";
    private static final String LEGACY_BEGIN = "# BEGIN ZEROTERMUX CODEX PROVIDER";
    private static final String LEGACY_END = "# END ZEROTERMUX CODEX PROVIDER";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CodexProviderStore() {}

    public static List<CodexProviderProfile> load(Context context, String agent) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(providerKey(agent), null);
        if (json == null && CodexProviderProfile.AGENT_CODEX.equals(agent)) {
            json = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString("providers", null);
        }
        List<CodexProviderProfile> providers = null;
        try {
            providers = GSON.fromJson(json, new TypeToken<List<CodexProviderProfile>>() {}.getType());
        } catch (Exception ignored) {}
        if (providers == null) providers = new ArrayList<>();
        providers.removeIf(profile -> profile == null || profile.official);
        for (CodexProviderProfile profile : providers) profile.normalize(agent);
        providers.sort(Comparator.comparingInt(profile -> profile.failoverPriority));
        if (json != null) save(context, agent, providers);
        return providers;
    }

    public static List<CodexProviderProfile> load(Context context) {
        return load(context, CodexProviderProfile.AGENT_CODEX);
    }

    public static void save(Context context, String agent, List<CodexProviderProfile> providers) {
        for (CodexProviderProfile profile : providers) profile.normalize(agent);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(providerKey(agent), GSON.toJson(providers)).apply();
    }

    public static void save(Context context, List<CodexProviderProfile> providers) {
        save(context, CodexProviderProfile.AGENT_CODEX, providers);
    }

    public static String activeId(Context context, String agent) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(activeKey(agent), "");
    }

    public static String activeId(Context context) {
        return activeId(context, CodexProviderProfile.AGENT_CODEX);
    }

    public static CodexProviderProfile active(Context context, String agent) {
        String id = activeId(context, agent);
        for (CodexProviderProfile profile : load(context, agent)) if (profile.id.equals(id)) return profile;
        return null;
    }

    public static boolean isRouteEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROUTE, false);
    }

    public static void setRouteEnabled(Context context, boolean enabled) throws Exception {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROUTE, enabled).commit();
        if (enabled) ContextCompat.startForegroundService(context,
            new Intent(context, CcsProxyService.class).setAction(CcsProxyService.ACTION_START));
        else context.startService(new Intent(context, CcsProxyService.class).setAction(CcsProxyService.ACTION_STOP));
        reapplyActive(context, CodexProviderProfile.AGENT_CODEX);
        reapplyActive(context, CodexProviderProfile.AGENT_OPENCODE);
    }

    public static void ensureProxyRunning(Context context) {
        if (isRouteEnabled(context)) ContextCompat.startForegroundService(context,
            new Intent(context, CcsProxyService.class).setAction(CcsProxyService.ACTION_START));
    }

    public static void reapplyActive(Context context, String agent) throws Exception {
        CodexProviderProfile active = active(context, agent);
        if (active != null) apply(context, agent, active);
    }

    public static void apply(Context context, String agent, CodexProviderProfile profile) throws Exception {
        profile.normalize(agent);
        if (TextUtils.isEmpty(profile.name) || TextUtils.isEmpty(profile.baseUrl))
            throw new IllegalArgumentException("供应商名称和 Base URL 必填");
        if (CodexProviderProfile.AGENT_OPENCODE.equals(agent)) writeOpenCodeConfig(context, profile);
        else {
            CodexHistoryMigrator.migrateToCustom();
            writeCodexConfig(context, profile);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(activeKey(agent), profile.id).commit();
    }

    public static void apply(Context context, CodexProviderProfile profile) throws Exception {
        apply(context, CodexProviderProfile.AGENT_CODEX, profile);
    }

    public static void delete(Context context, String agent, CodexProviderProfile profile,
                              List<CodexProviderProfile> providers) {
        providers.remove(profile);
        save(context, agent, providers);
        if (profile.id.equals(activeId(context, agent))) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(activeKey(agent)).apply();
        }
    }

    public static void delete(Context context, CodexProviderProfile profile, List<CodexProviderProfile> providers) {
        delete(context, CodexProviderProfile.AGENT_CODEX, profile, providers);
    }

    private static void writeCodexConfig(Context context, CodexProviderProfile profile) throws Exception {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建 " + dir);
        File config = new File(dir, "config.toml");
        backupOnce(config);
        String unmanaged = removeRootSelection(removeManagedBlock(config.exists() ? read(config) : ""));
        boolean routed = isRouteEnabled(context);
        String baseUrl = routed ? "http://127.0.0.1:" + PROXY_PORT + "/v1" : trimSlash(profile.baseUrl);
        String token = routed ? "cc-switch-local" : profile.apiKey.trim();

        StringBuilder block = new StringBuilder(BEGIN).append('\n');
        block.append("model_provider = \"custom\"\n");
        if (!TextUtils.isEmpty(profile.model)) block.append("model = \"").append(toml(profile.model.trim())).append("\"\n");
        if (!profile.modelCatalog.isEmpty() || !TextUtils.isEmpty(profile.model))
            block.append("model_catalog_json = \"cc-switch-model-catalog.json\"\n");
        block.append("\n[model_providers.custom]\n");
        block.append("name = \"CC Switch\"\n");
        block.append("base_url = \"").append(toml(baseUrl)).append("\"\n");
        block.append("wire_api = \"").append(routed ? "responses" : codexWireApi(profile.apiFormat)).append("\"\n");
        block.append("requires_openai_auth = false\n");
        if (!TextUtils.isEmpty(token)) block.append("experimental_bearer_token = \"").append(toml(token)).append("\"\n");
        if (!TextUtils.isEmpty(profile.customUserAgent))
            block.append("http_headers = { User-Agent = \"").append(toml(profile.customUserAgent)).append("\" }\n");
        block.append(END).append("\n\n");
        writeAtomic(config, block + unmanaged.trim() + (unmanaged.trim().isEmpty() ? "" : "\n"));
        CodexModelCatalogWriter.write(profile);
    }

    private static void writeOpenCodeConfig(Context context, CodexProviderProfile profile) throws Exception {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/opencode");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建 " + dir);
        File config = new File(dir, "opencode.json");
        backupOnce(config);
        JsonObject root = new JsonObject();
        if (config.exists()) {
            try {
                JsonElement parsed = JsonParser.parseString(read(config));
                if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
            } catch (Exception ignored) {}
        }
        if (!root.has("$schema")) root.addProperty("$schema", "https://opencode.ai/config.json");
        JsonObject providers = root.has("provider") && root.get("provider").isJsonObject()
            ? root.getAsJsonObject("provider") : new JsonObject();
        JsonObject provider = new JsonObject();
        provider.addProperty("npm", "@ai-sdk/openai-compatible");
        JsonObject options = new JsonObject();
        boolean routed = isRouteEnabled(context);
        options.addProperty("baseURL", routed ? "http://127.0.0.1:" + PROXY_PORT + "/v1" : trimSlash(profile.baseUrl));
        options.addProperty("apiKey", routed ? "cc-switch-local" : profile.apiKey);
        provider.add("options", options);
        JsonObject models = new JsonObject();
        for (CodexProviderProfile.ModelMapping mapping : profile.modelCatalog) {
            if (mapping != null && !TextUtils.isEmpty(mapping.model)) {
                JsonObject entry = new JsonObject();
                if (!TextUtils.isEmpty(mapping.displayName)) entry.addProperty("name", mapping.displayName);
                models.add(mapping.model, entry);
            }
        }
        if (models.size() > 0) provider.add("models", models);
        providers.add("cc-switch", provider);
        root.add("provider", providers);
        if (!TextUtils.isEmpty(profile.model)) root.addProperty("model", "cc-switch/" + profile.model);
        writeAtomic(config, GSON.toJson(root) + "\n");
    }

    public static String exportJson(Context context) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("routeEnabled", isRouteEnabled(context));
        root.addProperty("activeCodex", activeId(context, CodexProviderProfile.AGENT_CODEX));
        root.addProperty("activeOpenCode", activeId(context, CodexProviderProfile.AGENT_OPENCODE));
        root.add("codex", GSON.toJsonTree(load(context, CodexProviderProfile.AGENT_CODEX)));
        root.add("opencode", GSON.toJsonTree(load(context, CodexProviderProfile.AGENT_OPENCODE)));
        return GSON.toJson(root);
    }

    public static void importJson(Context context, String json) throws Exception {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<CodexProviderProfile> codex = GSON.fromJson(root.get("codex"), new TypeToken<List<CodexProviderProfile>>() {}.getType());
        List<CodexProviderProfile> opencode = GSON.fromJson(root.get("opencode"), new TypeToken<List<CodexProviderProfile>>() {}.getType());
        save(context, CodexProviderProfile.AGENT_CODEX, codex == null ? new ArrayList<>() : codex);
        save(context, CodexProviderProfile.AGENT_OPENCODE, opencode == null ? new ArrayList<>() : opencode);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (root.has("activeCodex")) editor.putString(activeKey(CodexProviderProfile.AGENT_CODEX), root.get("activeCodex").getAsString());
        if (root.has("activeOpenCode")) editor.putString(activeKey(CodexProviderProfile.AGENT_OPENCODE), root.get("activeOpenCode").getAsString());
        editor.commit();
        if (root.has("routeEnabled")) setRouteEnabled(context, root.get("routeEnabled").getAsBoolean());
    }

    public static int proxyPort() { return PROXY_PORT; }

    private static String providerKey(String agent) { return "providers_" + agent; }
    private static String activeKey(String agent) { return "active_" + agent; }
    private static String codexWireApi(String format) {
        if (CodexProviderProfile.FORMAT_CHAT.equals(format)) return "chat";
        if (CodexProviderProfile.FORMAT_ANTHROPIC.equals(format)) return "responses";
        return "responses";
    }
    private static String removeManagedBlock(String value) {
        String current = removeBlock(value, BEGIN, END);
        return removeBlock(current, LEGACY_BEGIN, LEGACY_END);
    }
    private static String removeBlock(String value, String begin, String endMarker) {
        int start = value.indexOf(begin), end = value.indexOf(endMarker);
        return start >= 0 && end >= start ? value.substring(0, start) + value.substring(end + endMarker.length()) : value;
    }
    private static String removeRootSelection(String value) {
        StringBuilder result = new StringBuilder();
        boolean inTable = false;
        for (String line : value.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) inTable = true;
            if (!inTable && (trimmed.startsWith("model_provider =") || trimmed.startsWith("model ="))) continue;
            result.append(line).append('\n');
        }
        return result.toString();
    }
    private static void backupOnce(File file) throws Exception {
        if (!file.exists()) return;
        File backup = new File(file.getParentFile(), file.getName() + ".zerotermux-backup");
        if (!backup.exists()) writeAtomic(backup, read(file));
    }
    static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0, count;
            while (offset < data.length && (count = input.read(data, offset, data.length - offset)) >= 0) offset += count;
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }
    static void writeAtomic(File target, String value) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建 " + parent);
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(value.getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        temp.setReadable(false, false); temp.setWritable(false, false);
        temp.setReadable(true, true); temp.setWritable(true, true);
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换 " + target);
        if (!temp.renameTo(target)) throw new IllegalStateException("无法写入 " + target);
    }
    private static String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
    private static String toml(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
