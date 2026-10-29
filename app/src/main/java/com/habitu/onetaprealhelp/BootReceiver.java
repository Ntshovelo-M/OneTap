package com.habitu.onetaprealhelp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences appPrefs = context.getSharedPreferences("OneTapPrefs", Context.MODE_PRIVATE);
            boolean isLockSosActive = appPrefs.getBoolean("lock_screen_sos", false);
            if (isLockSosActive) {
                Intent serviceIntent = new Intent(context, SosNotificationService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }

            // Start Safety Phrase listener if enabled
            if (VoiceRecognitionHelper.isEnabled(context) && VoiceRecognitionHelper.hasMicPermission(context)) {
                VoiceRecognitionHelper.start(context);
            }

            if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                Intent l = new Intent(context, CampusListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(l);
                else context.startService(l);
            }
        }
    }
}
