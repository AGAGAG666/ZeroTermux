package com.termux.zerocore.codex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

final class CcsJson {
    private static final Gson GSON = new Gson();
    private CcsJson() {}

    static CodexProviderProfile copy(CodexProviderProfile value) {
        CodexProviderProfile copy = GSON.fromJson(GSON.toJson(value), CodexProviderProfile.class);
        copy.normalize(value.agent);
        return copy;
    }

    static void requireObject(String value) {
        JsonElement element = JsonParser.parseString(value == null || value.trim().isEmpty() ? "{}" : value);
        if (!element.isJsonObject()) throw new IllegalArgumentException("请求覆盖必须是 JSON 对象");
    }

    static void copyToClipboard(Context context, String value) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("CC Switch", value));
    }
}
