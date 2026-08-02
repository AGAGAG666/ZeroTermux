package com.termux.zerocore.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class CodexHistoryMigratorTest {
    @Test public void migratesOnlySessionMetadataProviderBucket() {
        String source = "{\"type\":\"session_meta\",\"payload\":{\"id\":\"thread\",\"model_provider\":\"openai\"}}";
        JsonObject migrated = JsonParser.parseString(CodexHistoryMigrator.migrateLine(source)).getAsJsonObject();
        assertEquals("custom", migrated.getAsJsonObject("payload").get("model_provider").getAsString());

        String message = "{\"type\":\"response_item\",\"payload\":{\"model_provider\":\"openai\"}}";
        assertEquals(message, CodexHistoryMigrator.migrateLine(message));
    }

    @Test public void normalizesProvidersWithoutOfficialMode() {
        CodexProviderProfile profile = new CodexProviderProfile();
        profile.official = true; profile.wireApi = "chat"; profile.apiFormat = null;
        profile.normalize(CodexProviderProfile.AGENT_OPENCODE);
        assertEquals(CodexProviderProfile.AGENT_OPENCODE, profile.agent);
        assertEquals(CodexProviderProfile.FORMAT_CHAT, profile.apiFormat);
        assertTrue(!profile.official);
    }
}
