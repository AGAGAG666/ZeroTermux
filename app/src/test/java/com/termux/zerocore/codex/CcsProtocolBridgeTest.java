package com.termux.zerocore.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
}
