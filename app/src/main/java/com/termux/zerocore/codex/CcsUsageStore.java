package com.termux.zerocore.codex;

import android.app.AlertDialog;
import android.content.Context;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

final class CcsUsageStore {
    private CcsUsageStore() {}

    static synchronized void record(String agent, String providerId, String model, boolean success,
                                    long latencyMs, long input, long output) {
        try {
            File file = file();
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            JsonObject row = new JsonObject();
            row.addProperty("timestamp", System.currentTimeMillis()); row.addProperty("agent", agent);
            row.addProperty("providerId", providerId); row.addProperty("model", model);
            row.addProperty("success", success); row.addProperty("latencyMs", latencyMs);
            row.addProperty("inputTokens", input); row.addProperty("outputTokens", output);
            try (FileWriter writer = new FileWriter(file, true)) { writer.write(row + "\n"); }
        } catch (Exception ignored) {}
    }

    static void show(Context context) {
        Map<String, long[]> totals = new HashMap<>();
        if (file().exists()) try (BufferedReader reader = new BufferedReader(new FileReader(file()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String key = row.has("agent") ? row.get("agent").getAsString() : "unknown";
                long[] value = totals.computeIfAbsent(key, ignored -> new long[5]);
                value[0]++; if (row.has("success") && row.get("success").getAsBoolean()) value[1]++;
                if (row.has("inputTokens")) value[2] += row.get("inputTokens").getAsLong();
                if (row.has("outputTokens")) value[3] += row.get("outputTokens").getAsLong();
                if (row.has("latencyMs")) value[4] += row.get("latencyMs").getAsLong();
            }
        } catch (Exception ignored) {}
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            long[] v = entry.getValue();
            text.append(entry.getKey()).append("\n请求 ").append(v[0]).append(" · 成功 ").append(v[1])
                .append(" · 输入 ").append(v[2]).append(" · 输出 ").append(v[3])
                .append(" · 平均延迟 ").append(v[0] == 0 ? 0 : v[4] / v[0]).append("ms\n\n");
        }
        if (text.length() == 0) text.append("暂无代理统计。路由关闭时可从 Codex rollout 日志补充。 ");
        new AlertDialog.Builder(context).setTitle("使用统计").setMessage(text.toString())
            .setNegativeButton("清空", (d, w) -> { if (file().exists()) file().delete(); })
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private static File file() { return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cc-switch/usage.jsonl"); }
}
