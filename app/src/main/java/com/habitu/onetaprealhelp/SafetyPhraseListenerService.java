package com.habitu.onetaprealhelp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Foreground microphone listener that watches for the student's saved safety phrase
 * and fires the same SOS path as the home-screen button.
 */
public class SafetyPhraseListenerService extends Service implements RecognitionListener {

    private static final String TAG = "SafetyPhrase";
    private static final String CHANNEL_ID = "safety_phrase_channel";
    private static final int NOTIF_ID = 4822;
    private static final long ALERT_COOLDOWN_MS = 60_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private PowerManager.WakeLock wakeLock;

    private String safetyPhrase = "";
    private Pattern phrasePattern;
    private boolean shouldKeepListening = false;
    private boolean isListening = false;
    private boolean alertInFlight = false;
    private long lastAlertAt = 0L;
    private int consecutiveClientErrors = 0;
    private boolean usingOnDevice = false;

    private final Runnable startListeningRunnable = this::startListeningNow;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startAsMicrophoneForeground();
        acquireWakeLock();
        loadPhrase();
        shouldKeepListening = true;
        mainHandler.post(this::prepareRecognizer);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsMicrophoneForeground();
        loadPhrase();
        shouldKeepListening = VoiceRecognitionHelper.canListen(this);
        if (!shouldKeepListening) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (speechRecognizer == null) {
            mainHandler.post(this::prepareRecognizer);
        } else {
            scheduleListen(250);
        }
        return START_STICKY;
    }

    private void loadPhrase() {
        safetyPhrase = VoiceRecognitionHelper.phrase(this).toLowerCase(Locale.getDefault()).trim();
        if (safetyPhrase.isEmpty()) {
            phrasePattern = null;
            return;
        }
        phrasePattern = Pattern.compile("\\b" + Pattern.quote(normalize(safetyPhrase)) + "\\b");
    }

    private void startAsMicrophoneForeground() {
        Notification notification = buildNotification("Listening for your safety phrase");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OneTap:SafetyPhrase");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(60 * 60 * 1000L);
    }

    private void prepareRecognizer() {
        destroyRecognizer();
        if (!shouldKeepListening) return;

        if (!VoiceRecognitionHelper.hasMicPermission(this)) {
            Log.w(TAG, "RECORD_AUDIO missing");
            stopSelf();
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition is not available on this device");
            stopSelf();
            return;
        }

        try {
            if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                usingOnDevice = false;
            }
            speechRecognizer.setRecognitionListener(this);
        } catch (Exception e) {
            Log.e(TAG, "createSpeechRecognizer failed", e);
            updateNotification("Could not start voice recognition");
            stopSelf();
            return;
        }

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L);

        scheduleListen(200);
    }

    private void scheduleListen(long delayMs) {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (!shouldKeepListening) return;
        mainHandler.postDelayed(startListeningRunnable, delayMs);
    }

    private void startListeningNow() {
        if (!shouldKeepListening || speechRecognizer == null) return;
        if (alertInFlight) {
            scheduleListen(1000);
            return;
        }
        if (isListening) return;
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L);
        }
        try {
            speechRecognizer.startListening(recognizerIntent);
            isListening = true;
        } catch (Exception e) {
            Log.w(TAG, "startListening failed: " + e.getMessage());
            isListening = false;
            recreateRecognizerSoon(600);
        }
    }

    private void recreateRecognizerSoon(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (!shouldKeepListening) return;
            prepareRecognizer();
        }, delayMs);
    }

    private void checkForPhrase(ArrayList<String> matches) {
        if (matches == null || phrasePattern == null || alertInFlight) return;
        if (System.currentTimeMillis() - lastAlertAt < ALERT_COOLDOWN_MS) return;

        for (String match : matches) {
            if (match == null) continue;
            String heard = normalize(match);
            if (heard.isEmpty()) continue;
            if (phrasePattern.matcher(heard).find() || heard.contains(normalize(safetyPhrase))) {
                triggerSafetyAlert(match);
                return;
            }
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.getDefault())
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void triggerSafetyAlert(String heard) {
        alertInFlight = true;
        lastAlertAt = System.currentTimeMillis();
        Log.i(TAG, "Safety phrase heard: " + heard);
        vibrate();
        updateNotification("Safety phrase heard — processing alert");

        // Delegate to SosNotificationService to ensure the alert is triggered consistently
        // with the lock-screen button and handles coverage zone checks/UI updates.
        Intent intent = new Intent(this, SosNotificationService.class);
        intent.setAction(SosNotificationService.ACTION_TRIGGER_SOS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delegate voice SOS to SosNotificationService", e);
        }

        mainHandler.postDelayed(() -> {
            alertInFlight = false;
            updateNotification("Listening for your safety phrase");
            scheduleListen(1000);
        }, 5000);
    }

    private int currentBatteryPct() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery != null ? battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
        int scale = battery != null ? battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
        if (scale <= 0) return 0;
        return (int) ((level / (float) scale) * 100);
    }

    private void vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(400);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onResults(Bundle results) {
        isListening = false;
        consecutiveClientErrors = 0;
        if (results != null) {
            checkForPhrase(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        }
        scheduleListen(350);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (partialResults != null) {
            checkForPhrase(partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        }
    }

    @Override
    public void onError(int error) {
        isListening = false;
        Log.d(TAG, "SpeechRecognizer onError: " + error);

        long delay = 400;
        switch (error) {
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                delay = 1200;
                try {
                    if (speechRecognizer != null) speechRecognizer.cancel();
                } catch (Exception ignored) {}
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                consecutiveClientErrors++;
                if (consecutiveClientErrors >= 2) {
                    consecutiveClientErrors = 0;
                    recreateRecognizerSoon(500);
                    return;
                }
                delay = 700;
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                updateNotification("Microphone permission is required");
                stopSelf();
                return;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                if (!usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    usingOnDevice = true;
                    recreateRecognizerSoon(300);
                    return;
                }
                delay = 1500;
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                delay = 250;
                break;
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_AUDIO:
                delay = 800;
                break;
            default:
                delay = 500;
                break;
        }
        scheduleListen(delay);
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onEndOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Safety Phrase Listening", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while OneTap is listening for your safety phrase");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, SettingsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("OneTap is listening")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void destroyRecognizer() {
        isListening = false;
        mainHandler.removeCallbacks(startListeningRunnable);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shouldKeepListening = false;
        mainHandler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        super.onDestroy();
    }
}
