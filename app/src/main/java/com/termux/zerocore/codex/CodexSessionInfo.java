package com.termux.zerocore.codex;

import java.io.File;

public class CodexSessionInfo {
    private final String id;
    private final String title;
    private final String cwd;
    private final long updatedAt;
    private final File sourceFile;
    private final boolean running;
    private final String terminalHandle;

    public CodexSessionInfo(String id, String title, String cwd, long updatedAt, File sourceFile,
                            boolean running, String terminalHandle) {
        this.id = id; this.title = title; this.cwd = cwd; this.updatedAt = updatedAt;
        this.sourceFile = sourceFile; this.running = running; this.terminalHandle = terminalHandle;
    }

    public CodexSessionInfo withRuntime(boolean isRunning, String handle) {
        return new CodexSessionInfo(id, title, cwd, updatedAt, sourceFile, isRunning, handle);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getCwd() { return cwd; }
    public long getUpdatedAt() { return updatedAt; }
    public File getSourceFile() { return sourceFile; }
    public boolean isRunning() { return running; }
    public String getTerminalHandle() { return terminalHandle; }
}
