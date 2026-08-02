package com.termux.zerocore.codex;

import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the Codex-owned session index and supplements it with rollout workspace metadata. */
public final class CodexSessionRepository {
    private CodexSessionRepository() {}

    public static List<CodexSessionInfo> loadSessions() {
        Map<String, IndexEntry> indexed = readIndex();
        Map<String, RolloutMeta> rollouts = new HashMap<>();
        collectRollouts(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex/sessions"), rollouts);
        collectRollouts(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex/archived_sessions"), rollouts);

        // Older Codex versions may not have session_index.jsonl. Keep one UUID per rollout as fallback.
        for (Map.Entry<String, RolloutMeta> entry : rollouts.entrySet()) {
            if (!indexed.containsKey(entry.getKey())) {
                RolloutMeta meta = entry.getValue();
                indexed.put(entry.getKey(), new IndexEntry(entry.getKey(), "Codex · " + shortId(entry.getKey()), meta.updatedAt));
            }
        }

        List<CodexSessionInfo> result = new ArrayList<>();
        for (IndexEntry entry : indexed.values()) {
            RolloutMeta meta = rollouts.get(entry.id);
            CodexSessionRegistry.Binding binding = CodexSessionRegistry.forConversation(entry.id);
            result.add(new CodexSessionInfo(entry.id, entry.title,
                meta == null ? null : meta.cwd, Math.max(entry.updatedAt, meta == null ? 0 : meta.updatedAt),
                meta == null ? null : meta.file, binding != null, binding == null ? null : binding.terminalHandle));
        }
        result.sort(Comparator.comparingLong(CodexSessionInfo::getUpdatedAt).reversed());
        return result;
    }

    private static Map<String, IndexEntry> readIndex() {
        Map<String, IndexEntry> result = new LinkedHashMap<>();
        File index = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex/session_index.jsonl");
        if (!index.isFile()) return result;
        try (BufferedReader reader = new BufferedReader(new FileReader(index))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                    String id = string(row, "id");
                    if (TextUtils.isEmpty(id)) continue;
                    String title = string(row, "thread_name");
                    if (TextUtils.isEmpty(title)) title = "Codex · " + shortId(id);
                    result.put(id, new IndexEntry(id, title, timestamp(string(row, "updated_at"), index.lastModified())));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void collectRollouts(File file, Map<String, RolloutMeta> result) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) collectRollouts(child, result);
            return;
        }
        if (!file.getName().endsWith(".jsonl")) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            for (int i = 0; i < 8 && (line = reader.readLine()) != null; i++) {
                JsonObject root = JsonParser.parseString(line).getAsJsonObject();
                if (!"session_meta".equals(string(root, "type"))) continue;
                JsonObject payload = root.getAsJsonObject("payload");
                String id = first(string(payload, "id"), string(payload, "session_id"));
                if (TextUtils.isEmpty(id)) return;
                RolloutMeta existing = result.get(id);
                if (existing == null || file.lastModified() >= existing.updatedAt)
                    result.put(id, new RolloutMeta(string(payload, "cwd"), file.lastModified(), file));
                return;
            }
        } catch (Exception ignored) {}
    }

    private static long timestamp(String value, long fallback) {
        try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) { return fallback; }
    }
    private static String string(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null; }
        catch (Exception ignored) { return null; }
    }
    private static String first(String first, String second) { return TextUtils.isEmpty(first) ? second : first; }
    private static String shortId(String id) { return id.length() <= 8 ? id : id.substring(0, 8); }

    private static final class IndexEntry {
        final String id, title; final long updatedAt;
        IndexEntry(String id, String title, long updatedAt) { this.id = id; this.title = title; this.updatedAt = updatedAt; }
    }
    private static final class RolloutMeta {
        final String cwd; final long updatedAt; final File file;
        RolloutMeta(String cwd, long updatedAt, File file) { this.cwd = cwd; this.updatedAt = updatedAt; this.file = file; }
    }
}
