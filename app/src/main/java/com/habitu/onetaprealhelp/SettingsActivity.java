package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS = "SafetyPhrasePrefs";
    public static final String KEY_PHRASE = "safetyPhrase";
    public static final String KEY_VOICE_ENABLED = "voiceRecognitionEnabled";

    private SharedPreferences prefs;
    private SharedPreferences appPrefs;
    private DatabaseReference mDatabase;
    private FirebaseUser currentUser;

    private SwitchCompat swMic, swLocation, swNotifications, swVoice, swWalkLocation, swWalkLock;
    private TextView tvMicStatus, tvLocationStatus, tvNotifStatus, tvVoiceStatus;
    private EditText etSafetyPhrase;

    private ActivityResultLauncher<String> micPermissionLauncher;
    private ActivityResultLauncher<String> locationPermissionLauncher;
    private ActivityResultLauncher<String> notifPermissionLauncher;

    private boolean updatingVoiceSwitch;
    private boolean pendingEnableVoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mDatabase = FirebaseDatabase.getInstance().getReference();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        appPrefs = getSharedPreferences("settings", MODE_PRIVATE);

        ImageView btnBack = findViewById(R.id.btn_back);
        SwitchCompat switchDarkMode = findViewById(R.id.switch_dark_mode);
        SwitchCompat switchLockSos = findViewById(R.id.switch_lockscreen_sos);

        btnBack.setOnClickListener(v -> finish());

        // Dark Mode
        boolean isDarkMode = appPrefs.getBoolean("dark_mode", true);
        switchDarkMode.setChecked(isDarkMode);
        switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appPrefs.edit().putBoolean("dark_mode", isChecked).apply();
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
            recreate();
        });

        // Persistent Lock Screen SOS Notification
        boolean isLockSosActive = appPrefs.getBoolean("lock_screen_sos", false);
        switchLockSos.setChecked(isLockSosActive);
        switchLockSos.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appPrefs.edit().putBoolean("lock_screen_sos", isChecked).apply();
            getSharedPreferences("OneTapPrefs", MODE_PRIVATE).edit().putBoolean("lock_screen_sos", isChecked).apply();
            if (isChecked) {
                startSosService();
            } else {
                stopSosService();
            }
        });

        // Control Centre / Permissions & Safety Phrase setup
        swMic            = findViewById(R.id.sw_mic);
        swLocation       = findViewById(R.id.sw_location);
        swNotifications  = findViewById(R.id.sw_notifications);
        swVoice          = findViewById(R.id.sw_voice_recognition);
        swWalkLocation   = findViewById(R.id.sw_walk_one_time_location);
        swWalkLock       = findViewById(R.id.sw_walk_lock_screen);
        tvMicStatus      = findViewById(R.id.tv_mic_status);
        tvLocationStatus = findViewById(R.id.tv_location_status);
        tvNotifStatus    = findViewById(R.id.tv_notif_status);
        tvVoiceStatus    = findViewById(R.id.tv_voice_status);
        etSafetyPhrase   = findViewById(R.id.et_safety_phrase);

        registerPermissionLaunchers();
        setupSwitches();

        etSafetyPhrase.setText(prefs.getString(KEY_PHRASE, ""));
        findViewById(R.id.btn_save_phrase).setOnClickListener(v -> saveSafetyPhrase());

        swVoice.setOnCheckedChangeListener((btn, checked) -> onVoiceToggle(checked));
        setVoiceSwitch(prefs.getBoolean(KEY_VOICE_ENABLED, false));
        updateVoiceStatus();

        // Walk With Me settings
        swWalkLocation.setChecked(appPrefs.getBoolean("walk_one_time_location", false));
        swWalkLocation.setOnCheckedChangeListener((btn, checked) -> appPrefs.edit().putBoolean("walk_one_time_location", checked).apply());

        swWalkLock.setChecked(appPrefs.getBoolean("walk_lock_screen", false));
        swWalkLock.setOnCheckedChangeListener((btn, checked) -> appPrefs.edit().putBoolean("walk_lock_screen", checked).apply());

        findViewById(R.id.btn_about_link).setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://safestep.habitu.co.za"));
            startActivity(browserIntent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStates();
    }

    private void registerPermissionLaunchers() {
        micPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            refreshPermissionStates();
            if (pendingEnableVoice) {
                pendingEnableVoice = false;
                if (granted) tryEnableVoice();
                else Toast.makeText(this, "Microphone permission is needed for voice recognition", Toast.LENGTH_SHORT).show();
            } else if (!granted) {
                Toast.makeText(this, "Microphone permission is needed for the safety phrase", Toast.LENGTH_SHORT).show();
            }
        });
        locationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> refreshPermissionStates());
        notifPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            refreshPermissionStates();
            if (pendingEnableVoice) {
                pendingEnableVoice = false;
                if (granted) tryEnableVoice();
                else Toast.makeText(this, "Notifications are required so listening can stay active", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupSwitches() {
        swMic.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            } else if (!checked && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                promptOpenSettingsToRevoke("Microphone");
                refreshPermissionStates();
            }
        });
        swLocation.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            } else if (!checked && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                promptOpenSettingsToRevoke("Location");
                refreshPermissionStates();
            }
        });
        swNotifications.setOnCheckedChangeListener((btn, checked) -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
            if (checked && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else if (!checked && hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                promptOpenSettingsToRevoke("Notifications");
                refreshPermissionStates();
            }
        });
        refreshPermissionStates();
    }

    private void promptOpenSettingsToRevoke(String label) {
        new AlertDialog.Builder(this)
                .setTitle("Turn off " + label + "?")
                .setMessage("Android requires you to do this from system Settings.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshPermissionStates() {
        boolean mic = hasPermission(Manifest.permission.RECORD_AUDIO);
        boolean loc = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS);

        swMic.setChecked(mic);
        swLocation.setChecked(loc);
        swNotifications.setChecked(notif);

        tvMicStatus.setText(mic ? "Granted" : "Needed for the safety phrase feature");
        tvLocationStatus.setText(loc ? "Granted" : "Needed so security can find you during an SOS");
        tvNotifStatus.setText(notif ? "Granted" : "Needed for security responses and alerts");

        boolean voiceOn = VoiceRecognitionHelper.isEnabled(this);
        setVoiceSwitch(voiceOn && mic);
        if (!mic) {
            VoiceRecognitionHelper.stop(this);
        } else if (voiceOn) {
            VoiceRecognitionHelper.startIfEnabled(this);
        }
        updateVoiceStatus();
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void saveSafetyPhrase() {
        String phrase = etSafetyPhrase.getText().toString().trim();
        if (phrase.isEmpty()) {
            Toast.makeText(this, "Enter a word or phrase first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phrase.length() < 3) {
            Toast.makeText(this, "Use at least 3 characters so it isn't triggered by accident", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(KEY_PHRASE, phrase).apply();
        Toast.makeText(this, "Safety phrase saved", Toast.LENGTH_SHORT).show();
        updateVoiceStatus();
        if (swVoice.isChecked()) {
            VoiceRecognitionHelper.restart(this);
        }
    }

    private void onVoiceToggle(boolean checked) {
        if (updatingVoiceSwitch) return;
        if (!checked) {
            prefs.edit().putBoolean(KEY_VOICE_ENABLED, false).apply();
            VoiceRecognitionHelper.stop(this);
            updateVoiceStatus();
            return;
        }
        tryEnableVoice();
    }

    private void tryEnableVoice() {
        String phrase = prefs.getString(KEY_PHRASE, "").trim();
        if (phrase.isEmpty()) {
            phrase = etSafetyPhrase.getText().toString().trim();
            if (phrase.length() >= 3) {
                prefs.edit().putString(KEY_PHRASE, phrase).apply();
            }
        }
        if (phrase.length() < 3) {
            Toast.makeText(this, "Set and save a safety phrase first", Toast.LENGTH_SHORT).show();
            setVoiceSwitch(false);
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingEnableVoice = true;
            setVoiceSwitch(false);
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            pendingEnableVoice = true;
            setVoiceSwitch(false);
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "This device has no speech recognition. Install or enable Google speech services.", Toast.LENGTH_LONG).show();
            setVoiceSwitch(false);
            return;
        }

        prefs.edit().putBoolean(KEY_VOICE_ENABLED, true).putString(KEY_PHRASE, phrase).apply();
        setVoiceSwitch(true);
        updateVoiceStatus();
        VoiceRecognitionHelper.start(this);
        Toast.makeText(this, "Listening for \"" + phrase + "\"", Toast.LENGTH_SHORT).show();
    }

    private void setVoiceSwitch(boolean checked) {
        updatingVoiceSwitch = true;
        swVoice.setChecked(checked);
        updatingVoiceSwitch = false;
    }

    private void updateVoiceStatus() {
        if (tvVoiceStatus == null) return;
        String phrase = prefs.getString(KEY_PHRASE, "").trim();
        boolean enabled = prefs.getBoolean(KEY_VOICE_ENABLED, false);
        boolean mic = hasPermission(Manifest.permission.RECORD_AUDIO);
        if (enabled && !mic) {
            tvVoiceStatus.setText("Microphone permission needed to listen");
        } else if (enabled && !phrase.isEmpty()) {
            tvVoiceStatus.setText("Listening for: \"" + phrase + "\"");
        } else if (!phrase.isEmpty()) {
            tvVoiceStatus.setText("Phrase saved. Turn on to start listening.");
        } else {
            tvVoiceStatus.setText("Listens in the background and sends SOS when it hears your phrase");
        }
    }

    private void startSosService() {
        Intent serviceIntent = new Intent(this, SosNotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopSosService() {
        Intent serviceIntent = new Intent(this, SosNotificationService.class);
        stopService(serviceIntent);
    }
}
