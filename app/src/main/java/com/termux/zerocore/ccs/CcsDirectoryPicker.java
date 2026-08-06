package com.termux.zerocore.ccs;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 目录选择器。
 *
 * <p>为什么不用 SAF（{@code ACTION_OPEN_DOCUMENT_TREE}）：SAF 返回的是
 * {@code content://} 树 Uri，而 cc-switch 要的是能写进 {@code config.toml} /
 * {@code settings.json} 并被 Termux 里的 CLI 直接使用的**真实文件系统路径**，
 * 两者不可互转。而且 cc-switch 关心的目录（{@code ~/.codex}、{@code ~/.claude}、
 * {@code ~/.cc-switch}）都在 Termux 私有目录内，外部文件选择器根本看不见。
 *
 * <p>因此这里自己实现一个基于 {@link File} 的浏览器：从 Termux 家目录起步，
 * 可上下导航、可直接确认当前目录、也可手输路径。语义与桌面版的原生目录选择器
 * 一致——选中返回绝对路径，取消返回 {@code null}。
 */
final class CcsDirectoryPicker {

    interface Callback {
        /** @param path 绝对路径；用户取消时为 {@code null}。 */
        void onPicked(@Nullable String path);
    }

    private CcsDirectoryPicker() {}

    /**
     * 弹出选择器。必须在主线程调用。
     *
     * @param start 起始目录；为空或不可用时退回 Termux 家目录。
     */
    static void show(@NonNull Activity activity, @Nullable String start, @NonNull Callback cb) {
        File dir = resolveStart(start);
        browse(activity, dir, cb);
    }

    private static File resolveStart(@Nullable String start) {
        if (start != null && !start.trim().isEmpty()) {
            File f = new File(start.trim());
            // 传进来的可能是文件路径（比如 config.toml），取其父目录。
            if (f.isDirectory() && f.canRead()) return f;
            File parent = f.getParentFile();
            if (parent != null && parent.isDirectory() && parent.canRead()) return parent;
        }
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (home.isDirectory() && home.canRead()) return home;
        return new File("/");
    }

    private static void browse(@NonNull Activity activity, @NonNull File dir,
                              @NonNull Callback cb) {
        List<File> children = listDirs(dir);
        File parent = dir.getParentFile();
        boolean hasParent = parent != null && parent.canRead();

        List<String> labels = new ArrayList<>();
        List<File> targets = new ArrayList<>();
        if (hasParent) {
            labels.add("../  （上一级）");
            targets.add(parent);
        }
        for (File c : children) {
            labels.add(c.getName() + "/");
            targets.add(c);
        }
        if (children.isEmpty() && !hasParent) {
            labels.add("（此目录下没有可读的子目录）");
            targets.add(null);
        }

        // 取消（返回 null）必须覆盖所有退出路径：点按钮、点外部、按返回键。
        final boolean[] settled = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(dir.getAbsolutePath())
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                File target = targets.get(which);
                settled[0] = true;
                if (target == null) {
                    browse(activity, dir, cb);
                } else {
                    browse(activity, target, cb);
                }
            })
            .setPositiveButton("选择此目录", (d, which) -> {
                settled[0] = true;
                cb.onPicked(dir.getAbsolutePath());
            })
            .setNeutralButton("手动输入", (d, which) -> {
                settled[0] = true;
                manualInput(activity, dir.getAbsolutePath(), cb);
            })
            .setNegativeButton("取消", (d, which) -> {
                settled[0] = true;
                cb.onPicked(null);
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!settled[0]) cb.onPicked(null);
        });
        dialog.show();
    }

    /**
     * 只列可读子目录；隐藏目录保留（{@code ~/.codex} 这类正是目标）。
     *
     * <p>刻意不用 {@code List.removeIf} / {@code List.sort} /
     * {@code Comparator.comparing}：那三个都是 API 24 起才有的默认/静态方法，
     * 而本模块的 minSdk 是 23，且工程未开 core library desugaring。
     */
    private static List<File> listDirs(@NonNull File dir) {
        List<File> out = new ArrayList<>();
        File[] raw = dir.listFiles();
        if (raw == null) return out;
        for (File f : raw) {
            if (f.isDirectory() && f.canRead()) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    private static void manualInput(@NonNull Activity activity, @NonNull String preset,
                                   @NonNull Callback cb) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(preset);
        input.setSelection(preset.length());

        final boolean[] settled = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("输入目录路径")
            .setView(input)
            .setPositiveButton("确定", (d, which) -> {
                settled[0] = true;
                String path = input.getText().toString().trim();
                if (path.isEmpty()) {
                    cb.onPicked(null);
                    return;
                }
                File f = new File(path);
                if (!f.isDirectory()) {
                    Toast.makeText(activity, "目录不存在: " + path, Toast.LENGTH_LONG).show();
                    cb.onPicked(null);
                    return;
                }
                cb.onPicked(f.getAbsolutePath());
            })
            .setNegativeButton("取消", (d, which) -> {
                settled[0] = true;
                cb.onPicked(null);
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!settled[0]) cb.onPicked(null);
        });
        dialog.show();
    }
}
