package com.example.ghostprotocol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Persistent background guard service.
 * Keeps the screen-off and unlock broadcast monitors active in the background,
 * and launches MainActivity over the lockscreen when ACTION_USER_PRESENT fires.
 */
public class LockService extends Service {

    private static final String TAG = "LockService";
    private static final String CHANNEL_ID = "ghost_lock_guard_channel";
    private static final int NOTIF_ID = 1001;

    private BroadcastReceiver screenStateReceiver;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, LockService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start LockService: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        registerScreenStateReceiver();
        Log.d(TAG, "LockService started and screen receiver registered");
    }

    private void startInForeground() {
        createNotificationChannel();

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Ghost Protocol Active")
                .setContentText("Lock screen security guard is active")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Security Guard Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps Ghost Protocol security active");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void registerScreenStateReceiver() {
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();

                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    Log.d(TAG, "Screen off detected - locking app state");
                    context.getSharedPreferences("ghost_prefs", MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_currently_locked", true)
                            .apply();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    Log.d(TAG, "User present (phone unlocked) detected - launching PIN screen");
                    launchLockScreen(context);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, filter);
    }

    private void launchLockScreen(Context context) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra(MainActivity.KEY_FROM_UNLOCK, true);
        context.startActivity(launch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception ignored) {}
        }
        Log.d(TAG, "LockService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
