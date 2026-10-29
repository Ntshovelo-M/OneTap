package com.habitu.onetaprealhelp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Pop-up that appears even when SafeStep is closed or the phone is locked. */
public class PopupActivity extends AppCompatActivity {

    static final String EXTRA_TITLE = "title";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_NOTIF_ID = "notif_id";
    private static final String CHANNEL = "safestep_popups";

    /** Heads-up banner always; plus the full pop-up straight away if "Display over other apps" is allowed. */
    public static void notifyUser(Context c, String title, String message) {
        int id = (int) (System.currentTimeMillis() & 0x7fffffff);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "SafeStep pop-up alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Intent i = new Intent(c, PopupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_NOTIF_ID, id);
        PendingIntent pi = PendingIntent.getActivity(c, id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build();
        try { NotificationManagerCompat.from(c).notify(id, n); } catch (SecurityException ignored) {}

        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)) {
            try { c.startActivity(i); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setContentView(R.layout.activity_popup);
        setFinishOnTouchOutside(false);
        fill(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        fill(intent);
    }

    private void fill(Intent intent) {
        ((TextView) findViewById(R.id.tv_popup_title)).setText(intent.getStringExtra(EXTRA_TITLE));
        ((TextView) findViewById(R.id.tv_popup_message)).setText(intent.getStringExtra(EXTRA_MESSAGE));
        final int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1);

        findViewById(R.id.btn_popup_ok).setOnClickListener(v -> {
            NotificationManagerCompat.from(this).cancel(notifId);
            finish();
        });
        findViewById(R.id.btn_popup_open).setOnClickListener(v -> {
            NotificationManagerCompat.from(this).cancel(notifId);
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
    }
}