package com.termux.zerocore.ccs;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 拉起 cc-switch 原生 sidecar 并托管其生命周期。
 *
 * <p>移植结构：cc-switch 桌面版是 Tauri 应用（Rust 核心 + React 前端）。本移植把
 * Rust 核心交叉编译成 Android aarch64 可执行文件，前端产物原样复用，二者通过
 * sidecar 自己开的回环 HTTP 面通信：
 *
 * <ul>
 *   <li>{@code invoke(cmd, args)} → {@code POST /rpc/{cmd}}</li>
 *   <li>{@code listen(event, cb)} → {@code GET /events}（SSE）</li>
 *   <li>前端静态资源 → sidecar 用 {@code --webroot} 同源托管</li>
 * </ul>
 *
 * <p>同源托管是刻意选择：若让 WebView 从 {@code file://} 加载页面，再去 fetch
 * {@code http://127.0.0.1}，属跨源请求，必须打开 WebView 的
 * {@code allowUniversalAccessFromFileURLs}（等于给本地页面万能跨源权限），
 * 且 {@code EventSource} 在 file 源下同样受限。让 sidecar 自己发页面后，
 * 页面与 {@code /rpc} 同源，两个问题一起消失。
 *
 * <p>可执行文件通过 {@code jniLibs/<abi>/libccsidecar.so} 交付：Android 会把
 * jniLibs 解包到 {@code nativeLibraryDir} 并带执行位（本 App 已有 libadb.so 先例），
 * 这是在 targetSdk 29+ 的 W^X 限制下唯一稳妥的可执行文件投递方式——写进
 * {@code filesDir} 再 chmod +x 在新版本 Android 上会被 execve 拒绝。
 */
public final class CcsSidecar {
    private static final String TAG = "CcsSidecar";
    /** jniLibs 里的可执行文件名。必须是 lib*.so 才会被解包并带执行位。 */
    private static final String BINARY = "libccsidecar.so";
    /** assets 里前端产物的压缩包名。 */
    private static final String WEB_ASSET_ZIP = "ccs-web.zip";
    /** 解包后的 webroot 目录名（在 filesDir 下）。 */
    private static final String WEB_DIR = "ccs-web";
    /** sidecar 交叉编译所用的最低 API。低于此版本 execve 会因缺符号失败。 */
    private static final int MIN_SDK_FOR_SIDECAR = 24;
    /** 握手行等待上限。首启要跑数据库迁移（v1→v16，含插入 188 条模型定价）。 */
    private static final long HANDSHAKE_TIMEOUT_MS = 60_000L;

    private static final Object LOCK = new Object();
    @Nullable private static CcsSidecar instance;

    /** sidecar 握手结果。 */
    public static final class Handshake {
        public final int port;
        public final String token;
        public final String url;

        Handshake(int port, String token) {
            this.port = port;
            this.token = token;
            this.url = "http://127.0.0.1:" + port + "/";
        }
    }

    private final Context appContext;
    @Nullable private Process process;
    @Nullable private Handshake handshake;

    private CcsSidecar(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static CcsSidecar get(Context context) {
        synchronized (LOCK) {
            if (instance == null) instance = new CcsSidecar(context);
            return instance;
        }
    }

    /** 当前握手信息；未启动时为 null。 */
    @Nullable public Handshake handshake() {
        synchronized (LOCK) { return handshake; }
    }

    public boolean isRunning() {
        synchronized (LOCK) {
            return process != null && process.isAlive();
        }
    }

    /**
     * 确保 sidecar 已就绪，返回握手信息。
     *
     * <p>幂等：已在跑且 /health 可达时直接复用。阻塞调用，必须在工作线程执行。
     */
    public Handshake ensureStarted() throws IOException {
        synchronized (LOCK) {
            if (process != null && process.isAlive() && handshake != null) {
                if (healthy(handshake)) return handshake;
                // 进程活着但 HTTP 面不可达（极少数：端口被回收/线程死锁），重来。
                Log.w(TAG, "sidecar 进程存活但 /health 不可达，重启");
                shutdownLocked();
            }
            return startLocked();
        }
    }

    /** 当前设备是否可能跑得起 sidecar（仅版本面判断，不代表 ABI 匹配）。 */
    public static boolean isSupportedPlatform() {
        return android.os.Build.VERSION.SDK_INT >= MIN_SDK_FOR_SIDECAR;
    }

    private Handshake startLocked() throws IOException {
        // sidecar 以 API 24 交叉编译（aws-lc / getentropy 等约束），API 23 上会
        // 因动态链接符号缺失直接失败。提前给明确错误，而不是让 execve 抛怪异异常。
        if (!isSupportedPlatform()) {
            throw new IOException("CC Switch 需要 Android 7.0(API 24) 及以上，当前系统为 API "
                + android.os.Build.VERSION.SDK_INT);
        }

        File binary = new File(appContext.getApplicationInfo().nativeLibraryDir, BINARY);
        if (!binary.isFile()) {
            throw new IOException("找不到 cc-switch sidecar：" + binary.getAbsolutePath()
                + "（当前 APK 可能未包含本机 ABI 的原生库）");
        }
        if (!binary.canExecute()) {
            // nativeLibraryDir 下的文件本应自带执行位；这里只做兜底提示。
            throw new IOException("cc-switch sidecar 不可执行：" + binary.getAbsolutePath());
        }

        File webroot = ensureWebroot();

        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        // 端口交内核分配，避免与用户已有服务/上一次残留端口冲突。
        cmd.add("--port");
        cmd.add("0");
        if (webroot != null) {
            cmd.add("--webroot");
            cmd.add(webroot.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        // HOME 决定 cc-switch 的配置根（~/.cc-switch）与它管理的 ~/.codex、~/.claude。
        // 必须对齐 Termux 家目录，否则 GUI 改的供应商和终端里 codex 读的配置是两套。
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
        // 纯 Rust 进程，不依赖 Termux 的 LD_LIBRARY_PATH；显式清掉以防继承到脏值。
        env.remove("LD_LIBRARY_PATH");
        env.remove("LD_PRELOAD");
        pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));

        Log.i(TAG, "启动 sidecar: " + cmd);
        Process proc = pb.start();
        process = proc;

        // stderr 全量转 logcat：Rust 侧的 android_log 走 stderr。
        pumpToLog(proc.getErrorStream(), "ccs-stderr");

        Handshake result = readHandshake(proc);
        handshake = result;
        Log.i(TAG, "sidecar 就绪 port=" + result.port);
        return result;
    }

    /**
     * 读 stdout 首行 JSON 握手。
     *
     * <p>协议：sidecar 只在 stdout 打这一行结构化数据，格式
     * {@code {"ready":true,"port":N,"token":"..","url":"..","webroot":bool}}。
     * 读到之后剩余 stdout 继续转 logcat，防止管道写满把 sidecar 卡死。
     */
    private Handshake readHandshake(Process proc) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;

        String line;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                destroyQuietly(proc);
                throw new IOException("等待 cc-switch sidecar 握手超时");
            }
            line = reader.readLine();
            if (line == null) {
                int code = -1;
                try { code = proc.exitValue(); } catch (IllegalThreadStateException ignored) { }
                destroyQuietly(proc);
                throw new IOException("cc-switch sidecar 未输出握手就退出（exit=" + code + "）");
            }
            line = line.trim();
            if (line.startsWith("{")) break;
            // 非 JSON 行（理论上不该有）当日志。
            Log.i(TAG, "ccs-stdout: " + line);
        }

        int port;
        String token;
        try {
            JSONObject obj = new JSONObject(line);
            if (!obj.optBoolean("ready", false)) {
                throw new IOException("握手行 ready != true: " + line);
            }
            port = obj.getInt("port");
            token = obj.getString("token");
            if (!obj.optBoolean("webroot", false)) {
                // 前端没被托管，页面会白屏。明确报错比让用户看空白页好。
                Log.w(TAG, "sidecar 未启用静态站点，前端资源可能未随包解包");
            }
        } catch (IOException e) {
            destroyQuietly(proc);
            throw e;
        } catch (Exception e) {
            destroyQuietly(proc);
            throw new IOException("解析 sidecar 握手行失败: " + line, e);
        }
        if (port <= 0 || token.isEmpty()) {
            destroyQuietly(proc);
            throw new IOException("sidecar 握手信息不完整: " + line);
        }

        // 握手之后 stdout 不再承载协议，转日志即可。
        pumpReaderToLog(reader, "ccs-stdout");
        return new Handshake(port, token);
    }

    /** 停止 sidecar。 */
    public void shutdown() {
        synchronized (LOCK) { shutdownLocked(); }
    }

    private void shutdownLocked() {
        Process proc = process;
        process = null;
        handshake = null;
        if (proc == null) return;
        destroyQuietly(proc);
    }

    private static void destroyQuietly(Process proc) {
        proc.destroy();
        try {
            // 给 axum 的 graceful 退出留一点时间，超时再强杀。
            if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    // ── /health 探活 ────────────────────────────────────────────

    private static boolean healthy(Handshake h) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + h.port + "/health").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── 前端产物解包 ────────────────────────────────────────────

    /**
     * 把 assets 里的前端产物解包到 {@code filesDir/ccs-web/<versionCode>}。
     *
     * <p>为什么必须解包：sidecar 是独立原生进程，读不到 APK 里的 assets（那是
     * AssetManager 的虚拟路径），只能给它一个真实文件系统目录。
     *
     * <p>产物以单个 zip 交付而非 assets 目录树：一次流式解压比对 27 个文件逐个
     * {@code AssetManager.list()} + open 快得多，且构建侧只需校验一个 SHA-256。
     *
     * <p>按 versionCode 分目录 + 完成标记，保证覆盖安装后必定用新产物，
     * 且不会每次启动都重复解包 5MB。
     */
    @Nullable private File ensureWebroot() {
        String version = String.valueOf(versionCode());
        File root = new File(appContext.getFilesDir(), WEB_DIR);
        File target = new File(root, version);
        File stamp = new File(target, ".unpacked");

        if (stamp.isFile() && new File(target, "index.html").isFile()) {
            return target;
        }

        try {
            deleteRecursively(target);
            if (!target.mkdirs() && !target.isDirectory()) {
                Log.e(TAG, "无法创建 webroot 目录: " + target);
                return null;
            }
            int count = unpackWebZip(target);
            if (count == 0) {
                Log.e(TAG, "assets/" + WEB_ASSET_ZIP + " 为空，前端产物未随包");
                return null;
            }
            if (!new File(target, "index.html").isFile()) {
                Log.e(TAG, "webroot 缺少 index.html");
                return null;
            }
            try (OutputStream os = new FileOutputStream(stamp)) {
                os.write(version.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "解包前端产物 " + count + " 个文件到 " + target);
            // 清掉旧版本目录，避免覆盖安装后残留占空间。
            pruneOldVersions(root, version);
            return target;
        } catch (Exception e) {
            Log.e(TAG, "解包前端产物失败", e);
            return null;
        }
    }

    /**
     * 流式解压 {@code assets/ccs-web.zip} 到 {@code outDir}，返回写出的文件数。
     *
     * <p>逐条目做前缀校验（zip slip 防护）：条目名经规范化后必须仍位于 outDir 之内。
     * 产物是自己 CI 产的，但解压路径拼接一旦被污染就是任意写，这个校验不能省。
     */
    private int unpackWebZip(File outDir) throws IOException {
        String base = outDir.getCanonicalPath() + File.separator;
        int count = 0;
        try (InputStream raw = appContext.getAssets().open(WEB_ASSET_ZIP);
             ZipInputStream zis = new ZipInputStream(raw)) {
            ZipEntry entry;
            byte[] buf = new byte[32 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.contains("..")) {
                    throw new IOException("zip 条目名非法: " + name);
                }
                File out = new File(outDir, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(base)) {
                    throw new IOException("zip 条目逃逸目标目录: " + name);
                }
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) {
                        throw new IOException("无法创建目录: " + out);
                    }
                    zis.closeEntry();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
                zis.closeEntry();
                count++;
            }
        }
        return count;
    }

    private void pruneOldVersions(File root, String keep) {
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            if (dir.isDirectory() && !dir.getName().equals(keep)) {
                deleteRecursively(dir);
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        if (!file.delete()) Log.w(TAG, "删除失败: " + file);
    }

    private long versionCode() {
        try {
            PackageInfo info = appContext.getPackageManager()
                .getPackageInfo(appContext.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }

    // ── 日志泵 ──────────────────────────────────────────────────

    private static void pumpToLog(InputStream stream, String tag) {
        pumpReaderToLog(new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)), tag);
    }

    private static void pumpReaderToLog(BufferedReader reader, String tag) {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, tag + ": " + line);
                }
            } catch (IOException ignored) {
                // 进程退出时管道关闭，属正常路径。
            }
        }, "ccs-" + tag);
        t.setDaemon(true);
        t.start();
    }
}
