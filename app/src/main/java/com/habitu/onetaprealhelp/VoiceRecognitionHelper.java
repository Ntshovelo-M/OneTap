package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.speech.SpeechRecognizer;

import androidx.core.content.ContextCompat;

/**
 * Starts or stops the safety-phrase listener from Settings, Home, and other screens.
 */
public final class VoiceRecognitionHelper {

    private VoiceRecognitionHelper() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(SettingsActivity.KEY_VOICE_ENABLED, false);
    }

    public static String phrase(Context context) {
        return prefs(context).getString(SettingsActivity.KEY_PHRASE, "").trim();
    }

    public static boolean hasMicPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canListen(Context context) {
        return isEnabled(context)
                && hasMicPermission(context)
                && phrase(context).length() >= 3
                && SpeechRecognizer.isRecognitionAvailable(context);
    }

    public static void startIfEnabled(Context context) {
        if (!canListen(context)) return;
        start(context);
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, SafetyPhraseListenerService.class);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            android.util.Log.e("VoiceRecognition", "Could not start listener: " + e.getMessage());
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SafetyPhraseListenerService.class));
    }

    public static void restart(Context context) {
        stop(context);
        if (canListen(context)) start(context);
    }
}
