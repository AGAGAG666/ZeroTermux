package com.termux.zerocore.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Writes the model catalog consumed by Codex's model picker. */
public final class CodexModelCatalogWriter {
    private CodexModelCatalogWriter() {}

    public static void write(CodexProviderProfile profile) throws Exception {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex");
        File catalog = new File(dir, "cc-switch-model-catalog.json");
        JsonArray models = new JsonArray();
        int priority = 1000;
        for (CodexProviderProfile.ModelMapping mapping : profile.modelCatalog) {
            if (mapping == null || mapping.model == null || mapping.model.trim().isEmpty()) continue;
            JsonObject model = new JsonObject();
            String id = mapping.model.trim();
            model.addProperty("slug", id);
            model.addProperty("display_name", mapping.displayName == null || mapping.displayName.trim().isEmpty()
                ? id : mapping.displayName.trim());
            model.addProperty("description", model.get("display_name").getAsString());
            model.add("supported_reasoning_levels", new JsonArray());
            model.addProperty("visibility", "list");
            model.addProperty("supported_in_api", true);
            model.add("availability_nux", JsonNull.INSTANCE);
            model.add("upgrade", JsonNull.INSTANCE);
            model.addProperty("support_verbosity", false);
            model.add("default_verbosity", JsonNull.INSTANCE);
            model.add("apply_patch_tool_type", JsonNull.INSTANCE);
            model.addProperty("context_window", mapping.contextWindow > 0 ? mapping.contextWindow : 128000);
            model.addProperty("max_context_window", mapping.contextWindow > 0 ? mapping.contextWindow : 128000);
            model.addProperty("priority", priority++);
            model.add("input_modalities", arrayOfTextAndImage());
            model.addProperty("shell_type", "shell_command");
            JsonObject truncation = new JsonObject(); truncation.addProperty("mode", "tokens"); truncation.addProperty("limit", 10000);
            model.add("truncation_policy", truncation);
            model.add("model_messages", JsonNull.INSTANCE);
            model.addProperty("base_instructions", mapping.baseInstructions == null ? "" : mapping.baseInstructions.trim());
            model.addProperty("supports_parallel_tool_calls", mapping.supportsParallelToolCalls != null && mapping.supportsParallelToolCalls);
            model.addProperty("supports_image_detail_original", false);
            model.add("experimental_supported_tools", new JsonArray());
            models.add(model);
        }
        if (models.size() == 0 && profile.model != null && !profile.model.trim().isEmpty()) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("slug", profile.model.trim());
            fallback.addProperty("display_name", profile.model.trim());
            fallback.addProperty("description", profile.model.trim());
            fallback.add("supported_reasoning_levels", new JsonArray());
            fallback.addProperty("visibility", "list");
            fallback.addProperty("supported_in_api", true);
            fallback.add("availability_nux", JsonNull.INSTANCE);
            fallback.add("upgrade", JsonNull.INSTANCE);
            fallback.addProperty("base_instructions", "");
            fallback.add("model_messages", JsonNull.INSTANCE);
            fallback.addProperty("support_verbosity", false);
            fallback.add("default_verbosity", JsonNull.INSTANCE);
            fallback.add("apply_patch_tool_type", JsonNull.INSTANCE);
            fallback.addProperty("context_window", 128000);
            fallback.addProperty("max_context_window", 128000);
            fallback.addProperty("priority", 1000);
            fallback.add("input_modalities", arrayOfTextAndImage());
            fallback.addProperty("shell_type", "shell_command");
            JsonObject truncation = new JsonObject(); truncation.addProperty("mode", "tokens"); truncation.addProperty("limit", 10000);
            fallback.add("truncation_policy", truncation);
            fallback.addProperty("supports_parallel_tool_calls", false);
            fallback.addProperty("supports_image_detail_original", false);
            fallback.add("experimental_supported_tools", new JsonArray());
            models.add(fallback);
        }
        if (models.size() == 0) {
            if (catalog.exists() && !catalog.delete()) throw new IllegalStateException("无法删除旧模型目录");
            return;
        }
        JsonObject root = new JsonObject();
        root.add("models", models);
        writeAtomic(catalog, root.toString() + "\n");
    }

    private static JsonArray arrayOfTextAndImage() {
        JsonArray array = new JsonArray();
        array.add("text");
        array.add("image");
        return array;
    }

    private static void writeAtomic(File target, String value) throws Exception {
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换模型目录");
        if (!temp.renameTo(target)) throw new IllegalStateException("无法写入模型目录");
    }
}
