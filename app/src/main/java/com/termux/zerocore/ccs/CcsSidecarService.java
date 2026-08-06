package com.termux.zerocore.ccs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 持有 cc-switch 原生 sidecar 的前台服务。
 *
 * <p>为什么需要前台服务而不是让 {@link CcsSwitchActivity} 自己管进程：sidecar 不只
 * 是 GUI 的后端，它同时跑 cc-switch 的路由代理（把 Chat/Anthropic 上游转成 Codex
 * 需要的 Responses 格式）。终端里的 {@code codex} 把 baseURL 指向这个代理，
 * 所以 GUI 页面关掉之后代理必须继续活着；而 Android 会回收没有前台组件的进程组，
 * 只有前台服务能给它一个稳定的存活理由。
 */
public class CcsSidecarService extends Service implements CcsSidecar.Listener {
    private static final String TAG = "CcsSidecarService";
    public static final String ACTION_START = "com.termux.ccs.sidecar.START";
    public static final String ACTION_STOP = "com.termux.ccs.sidecar.STOP";
    private static final String CHANNEL = "ccs_sidecar";
    private static final int NOTIFICATION_ID = 4108;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** 请求启动服务（幂等）。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, CcsSidecarService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 请求停止服务并杀掉 sidecar。 */
    public static void stop(Context context) {
        context.startService(new Intent(context, CcsSidecarService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // sidecar 可能自行换端口重生（restart_app / 崩溃自愈），通知栏得跟着走，
        // 否则用户看到的端口是过期的。
        CcsSidecar.get(this).addListener(this);
    }

    @Override public void onSidecarReady(CcsSidecar.Handshake handshake) {
        updateNotification("本地服务运行中 · 端口 " + handshake.port);
    }

    @Override public void onSidecarLost(String message) {
        updateNotification(message);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            CcsSidecar.get(this).shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("本地服务运行中"));

        // ensureStarted 会解包前端产物并等握手（首启含数据库迁移），必须离开主线程。
        worker.execute(() -> {
            try {
                CcsSidecar.Handshake h = CcsSidecar.get(this).ensureStarted();
                Log.i(TAG, "sidecar 就绪 port=" + h.port);
                updateNotification("本地服务运行中 · 端口 " + h.port);
            } catch (Exception e) {
                Log.e(TAG, "sidecar 启动失败", e);
                updateNotification("启动失败：" + e.getMessage());
            }
        });
        // START_STICKY：被系统回收后重建服务并重新拉起 sidecar，
        // 保证终端里长跑的 codex 不会因为代理消失而在半路失联。
        return START_STICKY;
    }

    @Override public void onDestroy() {
        CcsSidecar.get(this).removeListener(this);
        // 服务销毁即代理不可用，进程留着只会变成孤儿。
        CcsSidecar.get(this).shutdown();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, CcsSwitchActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("CC Switch")
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "CC Switch 服务", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
