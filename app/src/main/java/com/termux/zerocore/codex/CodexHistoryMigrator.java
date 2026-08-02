package com.termux.zerocore.codex;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One-time, backed-up migration of every Codex history bucket to model_provider=custom. */
final class CodexHistoryMigrator {
    private static final String VERSION = "codex-history-custom-v1";
    private CodexHistoryMigrator() {}

    static synchronized void migrateToCustom() throws Exception {
        File codex = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".codex");
        File marker = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cc-switch/migrations/" + VERSION + ".json");
        if (marker.isFile()) return;
        String generation = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File backup = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cc-switch/backups/" + VERSION + "/" + generation);
        int files = migrateTree(codex, new File(codex, "sessions"), backup);
        files += migrateTree(codex, new File(codex, "archived_sessions"), backup);
        int rows = migrateDatabase(codex, new File(codex, "state_5.sqlite"), backup);

        JsonObject result = new JsonObject(); result.addProperty("version", VERSION);
        result.addProperty("completedAt", System.currentTimeMillis()); result.addProperty("jsonlFiles", files);
        result.addProperty("stateRows", rows); result.addProperty("backup", backup.getAbsolutePath());
        CodexProviderStore.writeAtomic(marker, result + "\n");
    }

    private static int migrateTree(File root, File file, File backup) throws Exception {
        if (!file.exists()) return 0;
        if (file.isDirectory()) {
            int count = 0; File[] children = file.listFiles();
            if (children != null) for (File child : children) count += migrateTree(root, child, backup);
            return count;
        }
        if (!file.getName().endsWith(".jsonl")) return 0;
        List<String> lines = new ArrayList<>(); boolean changed = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String migrated = migrateLine(line);
                lines.add(migrated); changed |= !line.equals(migrated);
            }
        }
        if (!changed) return 0;
        File destination = new File(backup, relative(root, file)); copy(file, destination);
        StringBuilder output = new StringBuilder(); for (String line : lines) output.append(line).append('\n');
        CodexProviderStore.writeAtomic(file, output.toString());
        return 1;
    }

    static String migrateLine(String line) {
        if (!line.contains("\"session_meta\"") || !line.contains("\"model_provider\"")) return line;
        try {
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            if (!"session_meta".equals(root.get("type").getAsString())) return line;
            JsonObject payload = root.getAsJsonObject("payload");
            if (payload == null || "custom".equals(payload.get("model_provider").getAsString())) return line;
            payload.addProperty("model_provider", "custom"); return root.toString();
        } catch (Exception ignored) { return line; }
    }

    private static int migrateDatabase(File codex, File dbFile, File backup) throws Exception {
        if (!dbFile.isFile()) return 0;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            if (!hasColumn(db, "threads", "model_provider")) return 0;
            int count;
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM threads WHERE model_provider IS NULL OR model_provider <> ?", new String[]{"custom"})) {
                count = cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
            if (count == 0) return 0;
            db.close();
            copy(dbFile, new File(backup, relative(codex, dbFile)));
            copyIfExists(new File(dbFile + "-wal"), new File(backup, relative(codex, new File(dbFile + "-wal"))));
            copyIfExists(new File(dbFile + "-shm"), new File(backup, relative(codex, new File(dbFile + "-shm"))));
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            db.execSQL("UPDATE threads SET model_provider = 'custom' WHERE model_provider IS NULL OR model_provider <> 'custom'");
            db.setTransactionSuccessful(); db.endTransaction();
            return count;
        } finally { if (db.isOpen()) db.close(); }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int name = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) if (column.equals(cursor.getString(name))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static String relative(File root, File file) {
        String base = root.getAbsolutePath(); String path = file.getAbsolutePath();
        return path.startsWith(base + File.separator) ? path.substring(base.length() + 1) : file.getName();
    }
    private static void copyIfExists(File source, File target) throws Exception { if (source.isFile()) copy(source, target); }
    private static void copy(File source, File target) throws Exception {
        File parent = target.getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建 " + parent);
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768]; int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }
}
