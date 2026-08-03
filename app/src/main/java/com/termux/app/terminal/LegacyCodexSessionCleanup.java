package com.termux.app.terminal;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Removes session-name state written by the removed Codex session integration. */
public final class LegacyCodexSessionCleanup {
    private static final Gson GSON = new Gson();
    private static final String PREFIX = "Termux-codex · ";
    private static final String DEDICATED_PREFIX = "Codex|";

    private LegacyCodexSessionCleanup() {}

    public static void run(TermuxService service) {
        if (service == null) return;
        File state = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cc-switch/running_sessions.json");
        Map<String, String> originalNames = new HashMap<>();
        if (state.isFile()) {
            try {
                List<Binding> bindings = GSON.fromJson(
                    read(state),
                    new TypeToken<List<Binding>>() {}.getType());
                if (bindings != null) for (Binding binding : bindings) {
                    if (binding != null && binding.terminalHandle != null)
                        originalNames.put(binding.terminalHandle, binding.originalName);
                }
            } catch (Exception ignored) {}
        }
        for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession wrapped : service.getTermuxSessions()) {
            TerminalSession session = wrapped.getTerminalSession();
            if (session == null || !isLegacyName(session.mSessionName)) continue;
            String originalName = originalNames.get(session.mHandle);
            session.mSessionName = originalName;
            wrapped.getExecutionCommand().shellName = originalName;
        }
        if (state.exists()) state.delete();
    }

    private static boolean isLegacyName(String name) {
        return name != null && (name.startsWith(PREFIX) || name.startsWith(DEDICATED_PREFIX));
    }

    private static String read(File file) throws Exception {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }

    private static final class Binding {
        String terminalHandle;
        String originalName;
    }
}
