package com.termux.zerocore.codex;

import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class CodexProcessDetector {
    private CodexProcessDetector() {}

    public static boolean isCodexRunning(TerminalSession session) {
        return session != null && containsCodexProcess(session.getPid(), new HashSet<>(), 0);
    }

    private static boolean containsCodexProcess(int pid, Set<Integer> visited, int depth) {
        if (pid <= 0 || depth > 8 || !visited.add(pid)) return false;
        String command = read(new File("/proc/" + pid + "/cmdline")).replace('\0', ' ').toLowerCase();
        if (command.contains("/codex ") || command.endsWith("/codex") || command.startsWith("codex ") || command.equals("codex") || command.contains("codex-cli-termux")) return true;
        String children = read(new File("/proc/" + pid + "/task/" + pid + "/children")).trim();
        if (children.isEmpty()) return false;
        for (String child : children.split("\\s+")) {
            try {
                if (containsCodexProcess(Integer.parseInt(child), visited, depth + 1)) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    private static String read(File file) {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
}
