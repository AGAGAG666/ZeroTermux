package com.termux.zerocore.codex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Provider record compatible with the Codex/OpenCode subset of CC Switch. */
public class CodexProviderProfile {
    public static final String AGENT_CODEX = "codex";
    public static final String AGENT_OPENCODE = "opencode";
    public static final String FORMAT_RESPONSES = "openai_responses";
    public static final String FORMAT_CHAT = "openai_chat";
    public static final String FORMAT_ANTHROPIC = "anthropic";

    public String id = UUID.randomUUID().toString();
    public String agent = AGENT_CODEX;
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";
    public String model = "";
    public String apiFormat = FORMAT_RESPONSES;
    public boolean fullUrl;

    public String anthropicAuthField = "ANTHROPIC_AUTH_TOKEN";
    public boolean impersonateClaudeCode;
    public int maxOutputTokens;

    public String promptCacheRouting = "auto";
    public boolean supportsThinking;
    public boolean supportsEffort;
    public String thinkingParam = "thinking";
    public String effortParam = "reasoning_effort";
    public String effortValueMode = "passthrough";

    public String customUserAgent = "";
    public String headersJson = "{}";
    public String bodyJson = "{}";
    public boolean endpointAutoSelect;
    public List<String> alternateEndpoints = new ArrayList<>();
    public List<ModelMapping> modelCatalog = new ArrayList<>();
    public boolean failoverEnabled;
    public int failoverPriority;

    // Kept only so old draft JSON can be migrated without failing deserialization.
    @Deprecated public String wireApi;
    @Deprecated public boolean official;

    public void normalize(String targetAgent) {
        agent = AGENT_OPENCODE.equals(targetAgent) ? AGENT_OPENCODE : AGENT_CODEX;
        if (id == null || id.trim().isEmpty()) id = UUID.randomUUID().toString();
        if (name == null) name = "";
        if (baseUrl == null) baseUrl = "";
        if (apiKey == null) apiKey = "";
        if (model == null) model = "";
        if (apiFormat == null || apiFormat.trim().isEmpty()) {
            apiFormat = "chat".equals(wireApi) ? FORMAT_CHAT
                : (AGENT_OPENCODE.equals(agent) ? FORMAT_CHAT : FORMAT_RESPONSES);
        }
        if (promptCacheRouting == null) promptCacheRouting = "auto";
        if (anthropicAuthField == null) anthropicAuthField = "ANTHROPIC_AUTH_TOKEN";
        if (thinkingParam == null) thinkingParam = "thinking";
        if (effortParam == null) effortParam = "reasoning_effort";
        if (effortValueMode == null) effortValueMode = "passthrough";
        if (customUserAgent == null) customUserAgent = "";
        if (headersJson == null) headersJson = "{}";
        if (bodyJson == null) bodyJson = "{}";
        if (alternateEndpoints == null) alternateEndpoints = new ArrayList<>();
        if (modelCatalog == null) modelCatalog = new ArrayList<>();
        official = false;
    }

    public String safeProviderId() {
        return "ccs_" + id.replace("-", "").substring(0, Math.min(16, id.replace("-", "").length()));
    }

    public static class ModelMapping {
        public String displayName = "";
        public String model = "";
        public long contextWindow = 128000;
        public Boolean supportsParallelToolCalls;
        public List<String> inputModalities;
        public String baseInstructions;

        public ModelMapping() {}

        public ModelMapping(String displayName, String model, long contextWindow) {
            this.displayName = displayName;
            this.model = model;
            this.contextWindow = contextWindow;
        }
    }
}
