package com.termux.zerocore.codex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

/** Owns the global CC Switch proxy lifecycle. The network engine is added behind this stable service boundary. */
public class CcsProxyService extends Service {
    public static final String ACTION_START = "com.termux.ccswitch.START";
    public static final String ACTION_STOP = "com.termux.ccswitch.STOP";
    private static final String CHANNEL = "ccswitch_proxy";
    private CcsProxyEngine engine;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        engine = new CcsProxyEngine(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (engine != null) engine.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(4107, notification());
        try {
            if (engine != null) engine.start();
        } catch (Exception error) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (engine != null) engine.stop();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("CC Switch")
            .setContentText("本地路由代理运行中")
            .setOngoing(true)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "CC Switch 代理", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
