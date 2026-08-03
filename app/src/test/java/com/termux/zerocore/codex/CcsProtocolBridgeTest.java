package com.termux.zerocore.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class CcsProtocolBridgeTest {
    @Test public void routesClientPathsToSeparateAgents() {
        assertEquals(CodexProviderProfile.AGENT_CODEX, CcsProxyEngine.agentForPath("/v1/responses"));
        assertEquals(CodexProviderProfile.AGENT_CODEX, CcsProxyEngine.agentForPath("/v1/responses/compact"));
        assertEquals(CodexProviderProfile.AGENT_OPENCODE, CcsProxyEngine.agentForPath("/v1/chat/completions"));
    }

    @Test public void responsesToChatDropsProviderEncryptedContent() {
        CodexProviderProfile provider = new CodexProviderProfile();
        provider.apiFormat = CodexProviderProfile.FORMAT_CHAT;
        JsonObject input = JsonParser.parseString("{\"model\":\"m\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\",\"encrypted_content\":\"secret\"}]}").getAsJsonObject();

        JsonObject result = CcsProtocolBridge.toUpstream(provider, CodexProviderProfile.AGENT_CODEX, input);

        assertEquals("hello", result.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
        assertFalse(result.toString().contains("encrypted_content"));
        assertFalse(result.get("stream").getAsBoolean());
    }

    @Test public void joinsVersionedEndpointsOnlyOnce() {
        assertEquals("https://api.example/v1/responses", CcsConnectionTester.join("https://api.example/v1", "/responses"));
        assertEquals("https://api.example/v1/messages", CcsConnectionTester.join("https://api.example/v1", "/v1/messages"));
    }

    @Test public void mergesChatSseWhenUpstreamStreamsDespiteNonStreamingRequest() {
        CodexProviderProfile provider = new CodexProviderProfile();
        provider.apiFormat = CodexProviderProfile.FORMAT_CHAT;
        String raw = "data: {\"id\":\"chat-1\",\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"
            + "data: {\"id\":\"chat-1\",\"choices\":[{\"delta\":{\"content\":\"world\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";

        JsonObject result = CcsProxyEngine.parseUpstream(provider, raw);

        assertEquals("hello world", result.getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString());
    }

    @Test public void mergesAnthropicSseWhenUpstreamStreamsDespiteNonStreamingRequest() {
        CodexProviderProfile provider = new CodexProviderProfile();
        provider.apiFormat = CodexProviderProfile.FORMAT_ANTHROPIC;
        String raw = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"model\":\"claude\"}}\n\n"
            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

        JsonObject result = CcsProxyEngine.parseUpstream(provider, raw);

        assertEquals("hello", result.getAsJsonArray("content").get(0).getAsJsonObject()
            .get("text").getAsString());
    }

    @Test public void emitsCompleteResponsesEventLifecycleForCodex() {
        JsonObject response = JsonParser.parseString("{\"id\":\"resp-1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}").getAsJsonObject();

        String stream = CcsProtocolBridge.syntheticStream(CodexProviderProfile.AGENT_CODEX, response);

        int itemAdded = stream.indexOf("event: response.output_item.added");
        int partAdded = stream.indexOf("event: response.content_part.added");
        int delta = stream.indexOf("event: response.output_text.delta");
        int partDone = stream.indexOf("event: response.content_part.done");
        int itemDone = stream.indexOf("event: response.output_item.done");
        int completed = stream.indexOf("event: response.completed");
        assertTrue(itemAdded > 0);
        assertTrue(itemAdded < partAdded && partAdded < delta && delta < partDone
            && partDone < itemDone && itemDone < completed);
        assertTrue(stream.contains("\"delta\":\"hello\""));
    }
}
