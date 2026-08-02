package com.termux.zerocore.codex;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

final class CcsMcpManager {
    private CcsMcpManager() {}

    static void show(Context context, String agent) {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cc-switch/mcp-" + agent + ".json");
        String current = "{}";
        try { if (file.exists()) current = CodexProviderStore.read(file); } catch (Exception ignored) {}
        EditText editor = new EditText(context); editor.setMinLines(12); editor.setGravity(android.view.Gravity.TOP);
        editor.setText(current); editor.setHint("{\"server\":{\"command\":\"node\",\"args\":[\"server.js\"],\"enabled\":true}}");
        new AlertDialog.Builder(context).setTitle("MCP · " + agent).setView(editor)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("保存并同步", (d, w) -> {
                try {
                    JsonElement parsed = JsonParser.parseString(editor.getText().toString());
                    if (!parsed.isJsonObject()) throw new IllegalArgumentException("MCP 配置必须是 JSON 对象");
                    CodexProviderStore.writeAtomic(file, new GsonBuilder().setPrettyPrinting().create().toJson(parsed) + "\n");
                    if (CodexProviderProfile.AGENT_OPENCODE.equals(agent)) syncOpenCode(parsed.getAsJsonObject());
                    else syncCodex(parsed.getAsJsonObject());
                    Toast.makeText(context, "MCP 已同步", Toast.LENGTH_LONG).show();
                } catch (Exception error) { Toast.makeText(context, error.getMessage(), Toast.LENGTH_LONG).show(); }
            }).show();
    }

    private static void syncOpenCode(JsonObject mcp) throws Exception {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/opencode/opencode.json");
        JsonObject root = file.exists() ? JsonParser.parseString(CodexProviderStore.read(file)).getAsJsonObject() : new JsonObject();
        root.add("mcp", mcp.deepCopy());
        CodexProviderStore.writeAtomic(file, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
    }

    private static void syncCodex(JsonObject mcp) throws Exception {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex/config.toml");
        String config = file.exists() ? CodexProviderStore.read(file) : "";
        String begin = "# BEGIN ZEROTERMUX MCP", end = "# END ZEROTERMUX MCP";
        int start = config.indexOf(begin), finish = config.indexOf(end);
        if (start >= 0 && finish >= start) config = config.substring(0, start) + config.substring(finish + end.length());
        StringBuilder block = new StringBuilder(begin).append('\n');
        for (java.util.Map.Entry<String, JsonElement> entry : mcp.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject server = entry.getValue().getAsJsonObject();
            if (server.has("enabled") && !server.get("enabled").getAsBoolean()) continue;
            block.append("[mcp_servers.\"").append(toml(entry.getKey())).append("\"]\n");
            if (server.has("command")) block.append("command = \"").append(toml(server.get("command").getAsString())).append("\"\n");
            if (server.has("args") && server.get("args").isJsonArray()) {
                block.append("args = ["); boolean first = true;
                for (JsonElement arg : server.getAsJsonArray("args")) { if (!first) block.append(", "); first = false; block.append('"').append(toml(arg.getAsString())).append('"'); }
                block.append("]\n");
            }
        }
        block.append(end).append("\n");
        CodexProviderStore.writeAtomic(file, config.trim() + "\n\n" + block);
    }
    private static String toml(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
