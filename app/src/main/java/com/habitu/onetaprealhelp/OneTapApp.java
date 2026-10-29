package com.habitu.onetaprealhelp;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.firebase.database.FirebaseDatabase;
import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;

public class OneTapApp extends Application {

    private static OneTapApp instance;
    public static android.content.Context getContext() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity a) { InAppPopup.setCurrent(a); }
            @Override public void onActivityPaused(@NonNull Activity a) { InAppPopup.clearCurrent(a); }
            @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
        });
        
        // Handle Theme
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean isDarkMode = prefs.getBoolean("dark_mode", true);
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }

        // App Check provider (debug provider in debug builds, Play Integrity in release)
        AppCheckSetup.install(this);

        // Initialize Firebase with exact URL
        try {
            FirebaseDatabase.getInstance("https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/");
        } catch (Exception e) {
            // Ignore if already initialized
        }

        // Persistent Lock Screen SOS Button Service
        SharedPreferences appPrefs = getSharedPreferences("OneTapPrefs", MODE_PRIVATE);
        if (appPrefs.getBoolean("lock_screen_sos", false)) {
            Intent serviceIntent = new Intent(this, SosNotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        // Background Voice Recognition Service
        if (VoiceRecognitionHelper.isEnabled(this)) {
            VoiceRecognitionHelper.startIfEnabled(this);
        }
    }
}
