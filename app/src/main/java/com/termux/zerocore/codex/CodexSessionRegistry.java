package com.termux.zerocore.codex;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Persistent terminal-handle to Codex-conversation binding. */
public final class CodexSessionRegistry {
    public static final String DEDICATED_PREFIX = "Codex|";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Binding>>() {}.getType();
    private static List<Binding> bindings;
    private static long lastReconcile;

    private CodexSessionRegistry() {}

    public static final class Binding {
        public String terminalHandle;
        public String conversationId;
        public String title;
        public String cwd;
        public String originalName;
        public String previousTerminalHandle;
        public String providerId;
        public boolean dedicated;
        public long detectedAt;
    }

    public static synchronized Binding forConversation(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (Binding binding : load()) if (id.equals(binding.conversationId)) return binding;
        return null;
    }

    public static synchronized boolean isCodexTerminal(TerminalSession session) {
        if (session == null) return false;
        if (!TextUtils.isEmpty(session.mSessionName) && session.mSessionName.startsWith(DEDICATED_PREFIX)) return true;
        return forHandle(session.mHandle) != null;
    }

    public static synchronized TerminalSession findRunning(TermuxService service, String conversationId) {
        Binding binding = forConversation(conversationId);
        return binding == null || service == null ? null : service.getTerminalSessionForHandle(binding.terminalHandle);
    }

    public static synchronized void bindDedicated(TerminalSession session, String id, String title, String cwd,
                                                   String previousHandle, String providerId) {
        removeHandle(session.mHandle);
        Binding binding = new Binding(); binding.terminalHandle = session.mHandle; binding.conversationId = id;
        binding.title = title; binding.cwd = cwd; binding.previousTerminalHandle = previousHandle;
        binding.providerId = providerId; binding.dedicated = true; binding.detectedAt = System.currentTimeMillis();
        load().add(binding); save();
    }

    public static synchronized String onFinished(TerminalSession session) {
        Binding binding = forHandle(session == null ? null : session.mHandle);
        if (binding == null) return null;
        if (!binding.dedicated) session.mSessionName = binding.originalName;
        String previous = binding.previousTerminalHandle;
        removeHandle(binding.terminalHandle); save();
        return binding.dedicated ? previous : null;
    }

    public static synchronized boolean reconcile(TermuxService service) {
        if (service == null || System.currentTimeMillis() - lastReconcile < 400) return false;
        lastReconcile = System.currentTimeMillis();
        boolean changed = false;
        Set<String> handles = new HashSet<>();
        for (TermuxSession wrapped : service.getTermuxSessions()) {
            TerminalSession session = wrapped.getTerminalSession();
            if (session == null) continue;
            handles.add(session.mHandle);
            Binding binding = forHandle(session.mHandle);
            boolean codexRunning = session.isRunning() && CodexProcessDetector.isCodexRunning(session);
            boolean dedicated = !TextUtils.isEmpty(session.mSessionName) && session.mSessionName.startsWith(DEDICATED_PREFIX);
            if (!codexRunning && binding != null && !binding.dedicated) {
                session.mSessionName = binding.originalName; removeHandle(session.mHandle); changed = true; continue;
            }
            if (!codexRunning) continue;
            if (dedicated) {
                if (binding == null) {
                    String[] parts = session.mSessionName.split("\\|", 3);
                    binding = new Binding(); binding.terminalHandle = session.mHandle;
                    binding.conversationId = parts.length > 1 ? parts[1] : null;
                    binding.title = parts.length > 2 ? parts[2] : "Codex";
                    binding.cwd = session.getCwd(); binding.detectedAt = System.currentTimeMillis(); binding.dedicated = true;
                    load().add(binding); changed = true;
                }
                if (TextUtils.isEmpty(binding.conversationId) || binding.conversationId.startsWith("new-")) {
                    CodexSessionInfo match = newestUnboundSession(binding.cwd, binding.detectedAt);
                    if (match != null) {
                        binding.conversationId = match.getId(); binding.title = match.getTitle();
                        session.mSessionName = DEDICATED_PREFIX + match.getId() + "|" + match.getTitle().replace('|', ' ');
                        changed = true;
                    }
                }
                continue;
            }
            if (binding == null) {
                binding = new Binding(); binding.terminalHandle = session.mHandle; binding.originalName = session.mSessionName;
                binding.cwd = session.getCwd(); binding.detectedAt = System.currentTimeMillis(); binding.dedicated = false;
                load().add(binding); changed = true;
            }
            CodexSessionInfo match = newestUnboundSession(binding.cwd, binding.detectedAt);
            if (match != null && !match.getId().equals(binding.conversationId)) {
                binding.conversationId = match.getId(); binding.title = match.getTitle(); changed = true;
            }
            String title = TextUtils.isEmpty(binding.title) ? "Codex" : binding.title;
            String display = "Termux-codex · " + title;
            if (!display.equals(session.mSessionName)) { session.mSessionName = display; changed = true; }
        }
        Iterator<Binding> iterator = load().iterator();
        while (iterator.hasNext()) {
            Binding binding = iterator.next();
            if (!handles.contains(binding.terminalHandle)) { iterator.remove(); changed = true; }
        }
        if (changed) save();
        return changed;
    }

    private static CodexSessionInfo newestUnboundSession(String cwd, long since) {
        CodexSessionInfo best = null;
        for (CodexSessionInfo item : CodexSessionRepository.loadSessions()) {
            Binding existing = forConversation(item.getId());
            if (existing != null) continue;
            if (!TextUtils.isEmpty(cwd) && !cwd.equals(item.getCwd())) continue;
            if (item.getUpdatedAt() + 15000 < since) continue;
            if (best == null || item.getUpdatedAt() > best.getUpdatedAt()) best = item;
        }
        return best;
    }

    private static Binding forHandle(String handle) {
        if (handle == null) return null;
        for (Binding binding : load()) if (handle.equals(binding.terminalHandle)) return binding;
        return null;
    }
    private static void removeHandle(String handle) { load().removeIf(binding -> handle != null && handle.equals(binding.terminalHandle)); }
    private static List<Binding> load() {
        if (bindings != null) return bindings;
        try { bindings = GSON.fromJson(CodexProviderStore.read(file()), LIST_TYPE); } catch (Exception ignored) {}
        if (bindings == null) bindings = new ArrayList<>();
        return bindings;
    }
    private static void save() {
        try { CodexProviderStore.writeAtomic(file(), GSON.toJson(load()) + "\n"); } catch (Exception ignored) {}
    }
    private static File file() { return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cc-switch/running_sessions.json"); }
}
