package com.termux.zerocore.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.UUID;

/** Protocol transformations for the Codex Responses and OpenCode Chat clients. */
final class CcsProtocolBridge {
    private CcsProtocolBridge() {}

    static JsonObject toUpstream(CodexProviderProfile provider, String agent, JsonObject input) {
        JsonObject portableInput = input.deepCopy();
        removeEncryptedContent(portableInput);
        JsonObject result;
        boolean codex = CodexProviderProfile.AGENT_CODEX.equals(agent);
        if (codex && CodexProviderProfile.FORMAT_CHAT.equals(provider.apiFormat)) result = responsesToChat(portableInput, provider);
        else if (codex && CodexProviderProfile.FORMAT_ANTHROPIC.equals(provider.apiFormat)) result = responsesToAnthropic(portableInput, provider);
        else if (!codex && CodexProviderProfile.FORMAT_RESPONSES.equals(provider.apiFormat)) result = chatToResponses(portableInput);
        else if (!codex && CodexProviderProfile.FORMAT_ANTHROPIC.equals(provider.apiFormat)) result = chatToAnthropic(portableInput, provider);
        else result = portableInput;
        if (provider.model != null && !provider.model.trim().isEmpty() && !catalogContains(provider, string(result, "model")))
            result.addProperty("model", provider.model.trim());
        try {
            JsonObject override = JsonParser.parseString(provider.bodyJson).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : override.entrySet()) result.add(entry.getKey(), entry.getValue());
        } catch (Exception ignored) {}
        // Converted streams are buffered then emitted with the client's event protocol.
        result.addProperty("stream", false);
        return result;
    }

    static JsonObject toClient(CodexProviderProfile provider, String agent, JsonObject upstream) {
        boolean codex = CodexProviderProfile.AGENT_CODEX.equals(agent);
        if (codex && CodexProviderProfile.FORMAT_CHAT.equals(provider.apiFormat)) return chatResponseToResponses(upstream);
        if (codex && CodexProviderProfile.FORMAT_ANTHROPIC.equals(provider.apiFormat)) return anthropicResponseToResponses(upstream);
        if (!codex && CodexProviderProfile.FORMAT_RESPONSES.equals(provider.apiFormat)) return responsesResponseToChat(upstream);
        if (!codex && CodexProviderProfile.FORMAT_ANTHROPIC.equals(provider.apiFormat)) return anthropicResponseToChat(upstream);
        return upstream;
    }

    static String syntheticStream(String agent, JsonObject response) {
        if (CodexProviderProfile.AGENT_CODEX.equals(agent)) return responsesSse(response);
        JsonObject chunk = new JsonObject(); chunk.addProperty("id", string(response, "id", "chatcmpl-ccs"));
        chunk.addProperty("object", "chat.completion.chunk");
        JsonArray choices = new JsonArray(); JsonObject choice = new JsonObject(); choice.addProperty("index", 0);
        JsonObject delta = new JsonObject();
        JsonObject message = response.has("choices") ? response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message") : null;
        delta.addProperty("content", message == null ? "" : string(message, "content", ""));
        choice.add("delta", delta); choice.addProperty("finish_reason", "stop"); choices.add(choice); chunk.add("choices", choices);
        return "data: " + chunk + "\n\ndata: [DONE]\n\n";
    }

    static String upstreamPath(CodexProviderProfile provider) {
        if (CodexProviderProfile.FORMAT_CHAT.equals(provider.apiFormat)) return "/chat/completions";
        if (CodexProviderProfile.FORMAT_ANTHROPIC.equals(provider.apiFormat)) return "/v1/messages";
        return "/responses";
    }

    private static void removeEncryptedContent(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            object.remove("encrypted_content");
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) removeEncryptedContent(entry.getValue());
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) removeEncryptedContent(child);
        }
    }

    private static JsonObject responsesToChat(JsonObject source, CodexProviderProfile provider) {
        JsonObject result = new JsonObject(); copy(source, result, "model");
        result.add("messages", responsesInputToMessages(source.get("input")));
        if (source.has("tools")) result.add("tools", responsesToolsToChat(source.getAsJsonArray("tools")));
        if (source.has("tool_choice")) result.add("tool_choice", source.get("tool_choice"));
        boolean reasoning = source.has("reasoning") && !source.get("reasoning").isJsonNull();
        if (provider.supportsThinking) {
            if ("enable_thinking".equals(provider.thinkingParam)) result.addProperty("enable_thinking", reasoning);
            else if ("reasoning_split".equals(provider.thinkingParam)) result.addProperty("reasoning_split", reasoning);
            else { JsonObject thinking = new JsonObject(); thinking.addProperty("type", reasoning ? "enabled" : "disabled"); result.add("thinking", thinking); }
        }
        if (provider.supportsEffort && source.has("reasoning") && source.get("reasoning").isJsonObject()) {
            String effort = string(source.getAsJsonObject("reasoning"), "effort");
            if (effort != null) {
                if ("reasoning.effort".equals(provider.effortParam)) { JsonObject r = new JsonObject(); r.addProperty("effort", effort); result.add("reasoning", r); }
                else result.addProperty("reasoning_effort", effort);
            }
        }
        if (!"disabled".equals(provider.promptCacheRouting)) {
            String cache = sessionId(source);
            if (cache != null && ("enabled".equals(provider.promptCacheRouting) || knownCacheEndpoint(provider.baseUrl)))
                result.addProperty("prompt_cache_key", cache);
        }
        return result;
    }

    private static JsonObject responsesToAnthropic(JsonObject source, CodexProviderProfile provider) {
        JsonObject result = new JsonObject(); copy(source, result, "model");
        result.addProperty("max_tokens", provider.maxOutputTokens > 0 ? provider.maxOutputTokens : 8192);
        result.add("messages", responsesInputToAnthropic(source.get("input")));
        if (source.has("tools")) result.add("tools", responsesToolsToAnthropic(source.getAsJsonArray("tools")));
        if (source.has("instructions")) result.addProperty("system", source.get("instructions").getAsString());
        if (provider.impersonateClaudeCode) {
            String system = string(result, "system", "");
            result.addProperty("system", "You are Claude Code, Anthropic's official CLI for Claude.\n" + system);
        }
        return result;
    }

    private static JsonObject chatToResponses(JsonObject source) {
        JsonObject result = new JsonObject(); copy(source, result, "model");
        result.add("input", source.has("messages") ? source.get("messages") : new JsonArray());
        if (source.has("tools")) result.add("tools", chatToolsToResponses(source.getAsJsonArray("tools")));
        if (source.has("tool_choice")) result.add("tool_choice", source.get("tool_choice"));
        if (source.has("reasoning_effort")) { JsonObject reasoning = new JsonObject(); reasoning.add("effort", source.get("reasoning_effort")); result.add("reasoning", reasoning); }
        return result;
    }

    private static JsonObject chatToAnthropic(JsonObject source, CodexProviderProfile provider) {
        JsonObject result = new JsonObject(); copy(source, result, "model");
        result.addProperty("max_tokens", provider.maxOutputTokens > 0 ? provider.maxOutputTokens : 8192);
        JsonArray messages = new JsonArray(); StringBuilder system = new StringBuilder();
        if (source.has("messages")) for (JsonElement element : source.getAsJsonArray("messages")) {
            JsonObject message = element.getAsJsonObject();
            if ("system".equals(string(message, "role"))) { if (system.length() > 0) system.append('\n'); system.append(string(message, "content", "")); }
            else messages.add(message.deepCopy());
        }
        result.add("messages", messages); if (system.length() > 0) result.addProperty("system", system.toString());
        if (source.has("tools")) result.add("tools", chatToolsToAnthropic(source.getAsJsonArray("tools")));
        return result;
    }

    private static JsonArray responsesInputToMessages(JsonElement input) {
        JsonArray result = new JsonArray();
        if (input == null || input.isJsonNull()) return result;
        if (input.isJsonPrimitive()) { result.add(message("user", input.getAsString())); return result; }
        if (!input.isJsonArray()) return result;
        for (JsonElement element : input.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject(); String type = string(item, "type", "message");
            if ("message".equals(type) || item.has("role")) result.add(message(string(item, "role", "user"), contentText(item.get("content"))));
            else if ("function_call_output".equals(type)) {
                JsonObject tool = message("tool", contentText(item.get("output"))); tool.addProperty("tool_call_id", string(item, "call_id", "")); result.add(tool);
            }
        }
        return result;
    }

    private static JsonArray responsesInputToAnthropic(JsonElement input) { return responsesInputToMessages(input); }
    private static JsonObject message(String role, String content) { JsonObject value = new JsonObject(); value.addProperty("role", role); value.addProperty("content", content); return value; }
    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return content.toString();
        StringBuilder result = new StringBuilder();
        for (JsonElement element : content.getAsJsonArray()) {
            if (element.isJsonPrimitive()) result.append(element.getAsString());
            else if (element.isJsonObject()) {
                JsonObject item = element.getAsJsonObject();
                String text = string(item, "text"); if (text != null) result.append(text);
            }
        }
        return result.toString();
    }

    private static JsonArray responsesToolsToChat(JsonArray tools) {
        JsonArray result = new JsonArray();
        for (JsonElement element : tools) {
            if (!element.isJsonObject()) continue; JsonObject tool = element.getAsJsonObject();
            if (!"function".equals(string(tool, "type"))) continue;
            JsonObject function = new JsonObject(); copy(tool, function, "name"); copy(tool, function, "description");
            if (tool.has("parameters")) function.add("parameters", tool.get("parameters"));
            JsonObject wrapper = new JsonObject(); wrapper.addProperty("type", "function"); wrapper.add("function", function); result.add(wrapper);
        }
        return result;
    }
    private static JsonArray responsesToolsToAnthropic(JsonArray tools) {
        JsonArray result = new JsonArray();
        for (JsonElement element : tools) {
            if (!element.isJsonObject()) continue; JsonObject tool = element.getAsJsonObject();
            if (!"function".equals(string(tool, "type"))) continue;
            JsonObject converted = new JsonObject(); copy(tool, converted, "name"); copy(tool, converted, "description");
            converted.add("input_schema", tool.has("parameters") ? tool.get("parameters") : new JsonObject()); result.add(converted);
        }
        return result;
    }
    private static JsonArray chatToolsToResponses(JsonArray tools) {
        JsonArray result = new JsonArray();
        for (JsonElement element : tools) {
            JsonObject wrapper = element.getAsJsonObject(); JsonObject function = wrapper.has("function") ? wrapper.getAsJsonObject("function") : wrapper;
            JsonObject tool = function.deepCopy(); tool.addProperty("type", "function"); result.add(tool);
        }
        return result;
    }
    private static JsonArray chatToolsToAnthropic(JsonArray tools) {
        JsonArray result = new JsonArray();
        for (JsonElement element : tools) {
            JsonObject wrapper = element.getAsJsonObject(); JsonObject function = wrapper.has("function") ? wrapper.getAsJsonObject("function") : wrapper;
            JsonObject tool = new JsonObject(); copy(function, tool, "name"); copy(function, tool, "description");
            tool.add("input_schema", function.has("parameters") ? function.get("parameters") : new JsonObject()); result.add(tool);
        }
        return result;
    }

    private static JsonObject chatResponseToResponses(JsonObject source) {
        JsonObject result = responsesBase(source);
        JsonArray output = new JsonArray(); JsonObject message = new JsonObject(); message.addProperty("id", "msg_" + UUID.randomUUID());
        message.addProperty("type", "message"); message.addProperty("role", "assistant"); message.addProperty("status", "completed");
        JsonArray content = new JsonArray(); JsonObject text = new JsonObject(); text.addProperty("type", "output_text"); text.addProperty("text", chatText(source)); text.add("annotations", new JsonArray()); content.add(text); message.add("content", content); output.add(message);
        JsonObject upstreamMessage = chatMessage(source);
        if (upstreamMessage != null && upstreamMessage.has("tool_calls")) for (JsonElement element : upstreamMessage.getAsJsonArray("tool_calls")) {
            JsonObject call = element.getAsJsonObject(); JsonObject function = call.getAsJsonObject("function"); JsonObject item = new JsonObject();
            item.addProperty("type", "function_call"); item.addProperty("id", string(call, "id", "call_" + UUID.randomUUID()));
            item.addProperty("call_id", string(call, "id", "call_" + UUID.randomUUID())); item.addProperty("name", string(function, "name", ""));
            item.addProperty("arguments", string(function, "arguments", "{}")); item.addProperty("status", "completed"); output.add(item);
        }
        result.add("output", output); result.addProperty("status", "completed"); result.add("usage", normalizedUsage(source.getAsJsonObject("usage"))); return result;
    }

    private static JsonObject anthropicResponseToResponses(JsonObject source) {
        JsonObject result = responsesBase(source); JsonArray output = new JsonArray(); JsonObject message = new JsonObject();
        message.addProperty("id", string(source, "id", "msg_" + UUID.randomUUID())); message.addProperty("type", "message"); message.addProperty("role", "assistant"); message.addProperty("status", "completed");
        JsonArray content = new JsonArray();
        if (source.has("content")) for (JsonElement element : source.getAsJsonArray("content")) {
            JsonObject block = element.getAsJsonObject();
            if ("text".equals(string(block, "type"))) { JsonObject text = new JsonObject(); text.addProperty("type", "output_text"); text.addProperty("text", string(block, "text", "")); text.add("annotations", new JsonArray()); content.add(text); }
            else if ("tool_use".equals(string(block, "type"))) { JsonObject call = new JsonObject(); call.addProperty("type", "function_call"); call.addProperty("id", string(block, "id", "")); call.addProperty("call_id", string(block, "id", "")); call.addProperty("name", string(block, "name", "")); call.addProperty("arguments", block.has("input") ? block.get("input").toString() : "{}"); call.addProperty("status", "completed"); output.add(call); }
        }
        message.add("content", content); output.add(message); result.add("output", output); result.addProperty("status", "completed"); result.add("usage", normalizedAnthropicUsage(source)); return result;
    }

    private static JsonObject responsesResponseToChat(JsonObject source) { return chatBase(source, responsesText(source)); }
    private static JsonObject anthropicResponseToChat(JsonObject source) {
        StringBuilder text = new StringBuilder(); if (source.has("content")) for (JsonElement item : source.getAsJsonArray("content")) if (item.isJsonObject() && "text".equals(string(item.getAsJsonObject(), "type"))) text.append(string(item.getAsJsonObject(), "text", ""));
        return chatBase(source, text.toString());
    }
    private static JsonObject chatBase(JsonObject source, String text) {
        JsonObject result = new JsonObject(); result.addProperty("id", string(source, "id", "chatcmpl-" + UUID.randomUUID())); result.addProperty("object", "chat.completion");
        JsonArray choices = new JsonArray(); JsonObject choice = new JsonObject(); choice.addProperty("index", 0); choice.add("message", message("assistant", text)); choice.addProperty("finish_reason", "stop"); choices.add(choice); result.add("choices", choices);
        if (source.has("usage")) result.add("usage", normalizedUsage(source.getAsJsonObject("usage"))); return result;
    }

    private static JsonObject responsesBase(JsonObject source) { JsonObject result = new JsonObject(); result.addProperty("id", string(source, "id", "resp_" + UUID.randomUUID())); result.addProperty("object", "response"); return result; }
    private static JsonObject chatMessage(JsonObject source) { try { return source.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message"); } catch (Exception ignored) { return null; } }
    private static String chatText(JsonObject source) { JsonObject message = chatMessage(source); return message == null ? "" : contentText(message.get("content")); }
    private static String responsesText(JsonObject source) {
        StringBuilder text = new StringBuilder(); if (source.has("output")) for (JsonElement item : source.getAsJsonArray("output")) {
            if (!item.isJsonObject()) continue; JsonObject object = item.getAsJsonObject(); if (!object.has("content")) continue;
            for (JsonElement content : object.getAsJsonArray("content")) if (content.isJsonObject()) text.append(string(content.getAsJsonObject(), "text", ""));
        } return text.toString();
    }
    private static JsonObject normalizedUsage(JsonObject usage) {
        JsonObject value = new JsonObject(); if (usage == null) return value;
        long in = number(usage, "input_tokens", number(usage, "prompt_tokens", 0)); long out = number(usage, "output_tokens", number(usage, "completion_tokens", 0));
        value.addProperty("input_tokens", in); value.addProperty("output_tokens", out); value.addProperty("total_tokens", in + out); return value;
    }
    private static JsonObject normalizedAnthropicUsage(JsonObject source) { return normalizedUsage(source.has("usage") ? source.getAsJsonObject("usage") : null); }
    private static String responsesSse(JsonObject response) {
        String id = string(response, "id", "resp_" + UUID.randomUUID()); String text = responsesText(response);
        JsonObject created = new JsonObject(); created.addProperty("type", "response.created"); JsonObject shell = new JsonObject(); shell.addProperty("id", id); shell.addProperty("status", "in_progress"); shell.addProperty("object", "response"); created.add("response", shell);
        String itemId = "msg_" + UUID.randomUUID();
        JsonObject pendingItem = new JsonObject(); pendingItem.addProperty("id", itemId); pendingItem.addProperty("type", "message"); pendingItem.addProperty("role", "assistant"); pendingItem.addProperty("status", "in_progress"); pendingItem.add("content", new JsonArray());
        JsonObject itemAdded = new JsonObject(); itemAdded.addProperty("type", "response.output_item.added"); itemAdded.addProperty("output_index", 0); itemAdded.add("item", pendingItem);
        JsonObject pendingPart = new JsonObject(); pendingPart.addProperty("type", "output_text"); pendingPart.addProperty("text", ""); pendingPart.add("annotations", new JsonArray());
        JsonObject partAdded = new JsonObject(); partAdded.addProperty("type", "response.content_part.added"); partAdded.addProperty("item_id", itemId); partAdded.addProperty("output_index", 0); partAdded.addProperty("content_index", 0); partAdded.add("part", pendingPart);
        JsonObject item = new JsonObject(); item.addProperty("id", itemId); item.addProperty("type", "message"); item.addProperty("role", "assistant"); item.addProperty("status", "completed");
        JsonArray itemContent = new JsonArray(); JsonObject itemText = new JsonObject(); itemText.addProperty("type", "output_text"); itemText.addProperty("text", text); itemText.add("annotations", new JsonArray()); itemContent.add(itemText); item.add("content", itemContent);
        JsonObject delta = new JsonObject(); delta.addProperty("type", "response.output_text.delta"); delta.addProperty("item_id", itemId); delta.addProperty("output_index", 0); delta.addProperty("content_index", 0); delta.addProperty("delta", text);
        JsonObject textDone = new JsonObject(); textDone.addProperty("type", "response.output_text.done"); textDone.addProperty("item_id", itemId); textDone.addProperty("output_index", 0); textDone.addProperty("content_index", 0); textDone.addProperty("text", text);
        JsonObject partDone = new JsonObject(); partDone.addProperty("type", "response.content_part.done"); partDone.addProperty("item_id", itemId); partDone.addProperty("output_index", 0); partDone.addProperty("content_index", 0); partDone.add("part", itemText);
        JsonObject itemDone = new JsonObject(); itemDone.addProperty("type", "response.output_item.done"); itemDone.addProperty("output_index", 0); itemDone.add("item", item);
        JsonObject completed = new JsonObject(); completed.addProperty("type", "response.completed"); completed.add("response", response);
        return "event: response.created\ndata: " + created + "\n\n"
            + "event: response.output_item.added\ndata: " + itemAdded + "\n\n"
            + "event: response.content_part.added\ndata: " + partAdded + "\n\n"
            + "event: response.output_text.delta\ndata: " + delta + "\n\n"
            + "event: response.output_text.done\ndata: " + textDone + "\n\n"
            + "event: response.content_part.done\ndata: " + partDone + "\n\n"
            + "event: response.output_item.done\ndata: " + itemDone + "\n\n"
            + "event: response.completed\ndata: " + completed + "\n\n";
    }
    private static boolean catalogContains(CodexProviderProfile profile, String model) { if (model == null) return false; for (CodexProviderProfile.ModelMapping m : profile.modelCatalog) if (m != null && model.equals(m.model)) return true; return false; }
    private static String sessionId(JsonObject source) { if (source.has("metadata") && source.get("metadata").isJsonObject()) { JsonObject metadata = source.getAsJsonObject("metadata"); String value = string(metadata, "session_id"); if (value != null) return value; value = string(metadata, "user_id"); if (value != null) return value; } return null; }
    private static boolean knownCacheEndpoint(String base) { return base != null && (base.contains("api.openai.com") || base.contains("api.kimi.com/coding")); }
    private static void copy(JsonObject from, JsonObject to, String key) { if (from.has(key)) to.add(key, from.get(key)); }
    private static String string(JsonObject object, String key) { return object == null || !object.has(key) || object.get(key).isJsonNull() ? null : object.get(key).getAsString(); }
    private static String string(JsonObject object, String key, String fallback) { String value = string(object, key); return value == null ? fallback : value; }
    private static long number(JsonObject object, String key, long fallback) { try { return object.has(key) ? object.get(key).getAsLong() : fallback; } catch (Exception ignored) { return fallback; } }
}
