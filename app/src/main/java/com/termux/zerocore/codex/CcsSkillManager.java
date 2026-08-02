package com.termux.zerocore.codex;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class CcsSkillManager {
    private static final String KEY_LOCATION = "skill_location";
    private static final String KEY_METHOD = "skill_sync_method";
    private CcsSkillManager() {}

    static void show(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(CodexProviderStore.PREFS, Context.MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
        Spinner location = spinner(context, new String[]{"~/.cc-switch/skills", "~/.agents/skills"}, prefs.getInt(KEY_LOCATION, 0));
        Spinner method = spinner(context, new String[]{"自动（软连接失败时复制）", "软连接", "复制"}, prefs.getInt(KEY_METHOD, 0));
        ListView list = new ListView(context); list.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, skillNames(ssot(prefs))));
        panel.addView(location); panel.addView(method);
        panel.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 260)));
        new AlertDialog.Builder(context).setTitle("Skills 统一管理").setView(panel)
            .setNeutralButton("导入现有", (d, w) -> {
                prefs.edit().putInt(KEY_LOCATION, location.getSelectedItemPosition()).putInt(KEY_METHOD, method.getSelectedItemPosition()).commit();
                try { importExisting(prefs); syncAll(prefs); Toast.makeText(context, "Skills 已导入并同步", Toast.LENGTH_LONG).show(); }
                catch (Exception error) { Toast.makeText(context, error.getMessage(), Toast.LENGTH_LONG).show(); }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("保存并同步", (d, w) -> {
                prefs.edit().putInt(KEY_LOCATION, location.getSelectedItemPosition()).putInt(KEY_METHOD, method.getSelectedItemPosition()).commit();
                try { syncAll(prefs); Toast.makeText(context, "Skills 已同步", Toast.LENGTH_LONG).show(); }
                catch (Exception error) { Toast.makeText(context, error.getMessage(), Toast.LENGTH_LONG).show(); }
            }).show();
    }

    private static Spinner spinner(Context context, String[] values, int selected) {
        Spinner spinner = new Spinner(context); spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selected); return spinner;
    }
    private static File ssot(SharedPreferences prefs) {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, prefs.getInt(KEY_LOCATION, 0) == 1 ? ".agents/skills" : ".cc-switch/skills");
    }
    private static List<String> skillNames(File root) {
        File[] files = root.listFiles(File::isDirectory); List<String> names = new ArrayList<>();
        if (files != null) { Arrays.sort(files); for (File file : files) names.add(file.getName()); }
        if (names.isEmpty()) names.add("暂无 Skill"); return names;
    }
    private static void importExisting(SharedPreferences prefs) throws Exception {
        File root = ssot(prefs); if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建 " + root);
        for (File app : appDirs()) {
            File[] children = app.listFiles(File::isDirectory); if (children == null) continue;
            for (File child : children) {
                File target = new File(root, child.getName());
                if (!target.exists() && !java.nio.file.Files.isSymbolicLink(child.toPath())) copyDirectory(child, target);
            }
        }
    }
    private static void syncAll(SharedPreferences prefs) throws Exception {
        File root = ssot(prefs); if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建 " + root);
        File[] skills = root.listFiles(File::isDirectory); if (skills == null) return;
        for (File app : appDirs()) {
            if (!app.exists() && !app.mkdirs()) throw new IllegalStateException("无法创建 " + app);
            for (File skill : skills) sync(skill, new File(app, skill.getName()), prefs.getInt(KEY_METHOD, 0));
        }
    }
    private static void sync(File source, File target, int method) throws Exception {
        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) deleteManagedTarget(target);
        if (method != 2) {
            try { Os.symlink(source.getAbsolutePath(), target.getAbsolutePath()); return; }
            catch (Exception error) { if (method == 1) throw error; }
        }
        copyDirectory(source, target);
    }
    private static List<File> appDirs() {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        return Arrays.asList(new File(home, ".codex/skills"), new File(home, ".config/opencode/skills"));
    }
    private static void copyDirectory(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("无法创建 " + target);
            File[] children = source.listFiles(); if (children != null) for (File child : children) copyDirectory(child, new File(target, child.getName()));
        } else {
            try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            }
        }
    }
    private static void deleteManagedTarget(File path) throws Exception {
        if (java.nio.file.Files.isSymbolicLink(path.toPath())) { java.nio.file.Files.delete(path.toPath()); return; }
        File[] children = path.listFiles(); if (children != null) for (File child : children) deleteManagedTarget(child);
        if (!path.delete()) throw new IllegalStateException("无法替换 " + path);
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
