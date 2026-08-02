package com.termux.zerocore.codex;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class CcsPresetManager {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build();
    private CcsPresetManager() {}

    static void importOnline(Context context, Runnable onImported) {
        EditText url = new EditText(context); url.setHint("https://example.com/cc-switch-export.json"); url.setSingleLine(true);
        new AlertDialog.Builder(context).setTitle("在线预设 JSON").setView(url)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("导入", (dialog, which) -> {
                String value = url.getText().toString().trim();
                new Thread(() -> {
                    try (Response response = CLIENT.newCall(new Request.Builder().url(value).get().build()).execute()) {
                        if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("HTTP " + response.code());
                        CodexProviderStore.importJson(context, response.body().string());
                        android.os.Handler handler = new android.os.Handler(context.getMainLooper());
                        handler.post(() -> { onImported.run(); Toast.makeText(context, "在线预设已导入", Toast.LENGTH_LONG).show(); });
                    } catch (Exception error) {
                        new android.os.Handler(context.getMainLooper()).post(() -> Toast.makeText(context,
                            error.getMessage() == null ? error.toString() : error.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }, "CCS online preset").start();
            }).show();
    }
}
