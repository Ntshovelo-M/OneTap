package com.habitu.onetaprealhelp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class SosNotificationService extends Service {
    public static final String ACTION_TRIGGER_SOS = "com.habitu.onetaprealhelp.ACTION_TRIGGER_SOS";
    public static final String ACTION_CANCEL_SOS = "com.habitu.onetaprealhelp.ACTION_CANCEL_SOS";
    private static final String CHANNEL_ID = "sos_channel";
    private static final int NOTIFICATION_ID = 101;

    private AlertManager alertManager;
    private boolean isAlertActive = false;

    private final BroadcastReceiver sosStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (HomeActivity.ACTION_SOS_TRIGGERED.equals(intent.getAction())) {
                isAlertActive = true;
                updateNotification(true);
            } else if (HomeActivity.ACTION_SOS_RESET.equals(intent.getAction())) {
                isAlertActive = false;
                updateNotification(false);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        alertManager = new AlertManager(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(HomeActivity.ACTION_SOS_TRIGGERED);
        filter.addAction(HomeActivity.ACTION_SOS_RESET);
        ContextCompat.registerReceiver(this, sosStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_TRIGGER_SOS.equals(action)) {
                triggerSos();
                return START_STICKY;
            } else if (ACTION_CANCEL_SOS.equals(action)) {
                cancelSos();
                return START_STICKY;
            }
        }

        updateNotification(isAlertActive);
        return START_STICKY;
    }

    private void triggerSos() {
        CoverageZone.check(this, (inside, known, metersOutside) -> {
            if (inside || !known) {
                new Handler(Looper.getMainLooper()).post(this::triggerSosInsideZone);
            } else {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(SosNotificationService.this,
                                CoverageZone.outsideMessage(metersOutside),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void triggerSosInsideZone() {
        vibrate();
        isAlertActive = true;

        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
        int batteryPct = (int) ((level / (float) scale) * 100);

        if (alertManager == null) {
            alertManager = new AlertManager(this);
        }

        alertManager.sendAlert(batteryPct, new AlertManager.AlertListener() {
            @Override
            public void onAlertSent(String alertKey, double lat, double lng) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(SosNotificationService.this, "🚨 SOS Emergency Alert Sent!", Toast.LENGTH_LONG).show()
                );
            }
        });

        // Broadcast alert state to HomeActivity
        Intent broadcastIntent = new Intent(HomeActivity.ACTION_SOS_TRIGGERED);
        sendBroadcast(broadcastIntent);

        updateNotification(true);
    }

    private void cancelSos() {
        vibrate();
        isAlertActive = false;

        if (alertManager != null) {
            alertManager.cancelAlert();
        }

        // Broadcast reset state to HomeActivity
        Intent broadcastIntent = new Intent(HomeActivity.ACTION_SOS_RESET);
        sendBroadcast(broadcastIntent);

        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(SosNotificationService.this, "SOS Emergency Alert Cancelled", Toast.LENGTH_SHORT).show()
        );

        updateNotification(false);
    }

    private void updateNotification(boolean active) {
        Intent homeIntent = new Intent(this, HomeActivity.class);
        if (active) {
            homeIntent.putExtra("trigger_sos", true);
        }
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent openAppPendingIntent = PendingIntent.getActivity(
                this, 1, homeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        RemoteViews customView = new RemoteViews(getPackageName(), R.layout.notification_sos_button);

        if (active) {
            customView.setTextViewText(R.id.tv_sos_button_text, "CANCEL");
            customView.setTextViewText(R.id.tv_sos_button_subtext, "TAP");
            customView.setTextViewText(R.id.tv_notification_title, "🚨 SOS ALERT ACTIVE!");
            customView.setTextViewText(R.id.tv_notification_subtext, "Security notified · GPS shared · Tap button to cancel");

            Intent cancelIntent = new Intent(this, SosNotificationService.class);
            cancelIntent.setAction(ACTION_CANCEL_SOS);
            PendingIntent cancelPendingIntent = PendingIntent.getService(
                    this, 102, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            customView.setOnClickPendingIntent(R.id.notification_sos_button, cancelPendingIntent);
        } else {
            customView.setTextViewText(R.id.tv_sos_button_text, "SOS");
            customView.setTextViewText(R.id.tv_sos_button_subtext, "PRESS");
            customView.setTextViewText(R.id.tv_notification_title, "Lock Screen SOS Button");
            customView.setTextViewText(R.id.tv_notification_subtext, "Tap the red SOS button to trigger immediate emergency alert");

            Intent triggerIntent = new Intent(this, SosNotificationService.class);
            triggerIntent.setAction(ACTION_TRIGGER_SOS);
            PendingIntent triggerPendingIntent = PendingIntent.getService(
                    this, 101, triggerIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            customView.setOnClickPendingIntent(R.id.notification_sos_button, triggerPendingIntent);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_exclamation)
                .setCustomContentView(customView)
                .setCustomBigContentView(customView)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(openAppPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Lock Screen SOS Button Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(300);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(sosStateReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
