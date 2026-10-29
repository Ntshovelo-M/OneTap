package com.habitu.onetaprealhelp;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class TimerActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "walk_with_me_channel";
    private static final int NOTIF_ID = 4821;
    private static final String PREFS = "WalkWithMePrefs";

    TextView tvCountdown, tvStatus, tvExpectedArrival, tvPlannedContactsCount, tvLocationSnapshot;
    LinearLayout rowExpectedArrival, rowLocationSnapshot;
    EditText etDestination;
    Button btnAction, btn15, btn30, btn60, btn90, btn120, btnCustom, btnChangeContacts, btnRefreshLocation;
    SwitchCompat swOneTimeLocation, swLockScreen;

    CountDownTimer countDownTimer;
    boolean timerRunning = false;
    int selectedSeconds = 15 * 60;

    FusedLocationProviderClient fusedLocationClient;

    // Live-walk events coming back from WalkTrackingService
    private final BroadcastReceiver walkReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (WalkTrackingService.BROADCAST_WALK_ENDED.equals(intent.getAction())) {
                resetUiAfterWalkEnded("Walk stopped \u2014 your reason was sent to security. No automatic alert will be sent.");
            } else if (WalkTrackingService.BROADCAST_WALK_ALERTED.equals(intent.getAction())) {
                showAlertedUi();
            }
        }
    };

    private final ActivityResultLauncher<String[]> walkPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), (Map<String, Boolean> r) -> startTimerNow());
    Double snapshotLat = null, snapshotLng = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initTimerScreen(savedInstanceState);
        } catch (Exception e) {
            // Temporary safety net: instead of silently crashing back to the launcher, show the
            // real error so we can see exactly what's wrong instead of guessing blind. Remove
            // this try/catch once the real cause is found and fixed.
            android.util.Log.e("TimerActivity", "Crash while opening Walk with Me", e);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Walk with Me couldn't open")
                    .setMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .setPositiveButton("Copy error & go back", (d, w) -> {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "error", android.util.Log.getStackTraceString(e)));
                        finish();
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void initTimerScreen(Bundle savedInstanceState) {
        setContentView(R.layout.activity_timer);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();

        tvCountdown            = findViewById(R.id.tv_countdown);
        tvStatus                = findViewById(R.id.tv_timer_status);
        tvExpectedArrival       = findViewById(R.id.tv_expected_arrival);
        tvPlannedContactsCount  = findViewById(R.id.tv_planned_contacts_count);
        tvLocationSnapshot      = findViewById(R.id.tv_location_snapshot);
        rowExpectedArrival      = findViewById(R.id.row_expected_arrival);
        rowLocationSnapshot     = findViewById(R.id.row_location_snapshot);
        etDestination           = findViewById(R.id.et_destination);
        btnAction               = findViewById(R.id.btn_timer_action);
        btn15                   = findViewById(R.id.btn_15min);
        btn30                   = findViewById(R.id.btn_30min);
        btn60                   = findViewById(R.id.btn_60min);
        btn90                   = findViewById(R.id.btn_90min);
        btn120                  = findViewById(R.id.btn_120min);
        btnCustom               = findViewById(R.id.btn_custom);
        btnChangeContacts       = findViewById(R.id.btn_change_contacts);
        btnRefreshLocation      = findViewById(R.id.btn_refresh_location);
        swOneTimeLocation       = findViewById(R.id.sw_one_time_location);
        swLockScreen            = findViewById(R.id.sw_lock_screen);

        btn15.setOnClickListener(v -> selectDuration(15, btn15));
        btn30.setOnClickListener(v -> selectDuration(30, btn30));
        btn60.setOnClickListener(v -> selectDuration(60, btn60));
        btn90.setOnClickListener(v -> selectDuration(90, btn90));
        btn120.setOnClickListener(v -> selectDuration(120, btn120));
        btnCustom.setOnClickListener(v -> showCustomDurationDialog());

        btnAction.setOnClickListener(v -> {
            if (!timerRunning) startTimer();
            else stopTimer(true);
        });

        btnChangeContacts.setOnClickListener(v -> startActivity(new Intent(this, AddContactActivity.class)));

        swOneTimeLocation.setOnCheckedChangeListener((btn, checked) -> {
            rowLocationSnapshot.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE);
            if (checked) captureLocationOnce();
        });
        btnRefreshLocation.setOnClickListener(v -> captureLocationOnce());

        IntentFilter walkFilter = new IntentFilter();
        walkFilter.addAction(WalkTrackingService.BROADCAST_WALK_ENDED);
        walkFilter.addAction(WalkTrackingService.BROADCAST_WALK_ALERTED);
        ContextCompat.registerReceiver(this, walkReceiver, walkFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        loadPlannedContactsCount();
        selectDuration(15, btn15); // sensible default, matches screenshot

        setupBottomNav();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPlannedContactsCount(); // in case they just changed contacts and came back
    }

    /** Trusted-contacts count from your existing /contacts/{uid} node - informational only. */
    private void loadPlannedContactsCount() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseDatabase.getInstance().getReference("contacts").child(user.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        long count = snapshot.getChildrenCount();
                        tvPlannedContactsCount.setText(count == 1 ? "1 planned contact" : count + " planned contacts");
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        tvPlannedContactsCount.setText("Unable to load contacts");
                    }
                });
    }

    void selectDuration(int minutes, Button chosen) {
        if (timerRunning) return;
        selectedSeconds = minutes * 60;

        Button[] all = { btn15, btn30, btn60, btn90, btn120, btnCustom };
        for (Button b : all) {
            b.setBackgroundTintList(getColorStateList(android.R.color.transparent));
            b.setTextColor(getColor(R.color.ss_text_secondary));
        }
        if (chosen != btnCustom) btnCustom.setText("Custom time\u2026");
        chosen.setBackgroundTintList(getColorStateList(R.color.ss_red));
        chosen.setTextColor(Color.WHITE);

        tvCountdown.setText(formatTime(selectedSeconds));
        tvCountdown.setTextColor(getColor(R.color.ss_text_primary));
        updateExpectedArrival(selectedSeconds);
    }

    /** Lets the student pick exactly how long their walk will take (hours + minutes). */
    private void showCustomDurationDialog() {
        if (timerRunning) return;

        int lastCustom = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("customMinutes", 45);
        int startMinutes = (selectedSeconds > 0 ? selectedSeconds / 60 : lastCustom);

        final android.widget.NumberPicker pickHours = new android.widget.NumberPicker(this);
        pickHours.setMinValue(0); pickHours.setMaxValue(12);
        pickHours.setValue(Math.min(12, startMinutes / 60));
        pickHours.setWrapSelectorWheel(false);

        final android.widget.NumberPicker pickMinutes = new android.widget.NumberPicker(this);
        pickMinutes.setMinValue(0); pickMinutes.setMaxValue(59);
        pickMinutes.setValue(startMinutes % 60);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, 0);

        TextView lblH = new TextView(this); lblH.setText(" h   ");
        TextView lblM = new TextView(this); lblM.setText(" min");
        row.addView(pickHours); row.addView(lblH);
        row.addView(pickMinutes); row.addView(lblM);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Set your walk time")
                .setMessage("If you haven't marked yourself safe when this runs out, an alert goes to your trusted contacts and security.")
                .setView(row)
                .setPositiveButton("Set timer", (d, w) -> {
                    int minutes = pickHours.getValue() * 60 + pickMinutes.getValue();
                    if (minutes < 1) {
                        android.widget.Toast.makeText(this, "Please choose at least 1 minute", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("customMinutes", minutes).apply();
                    selectDuration(minutes, btnCustom);
                    int h = minutes / 60, m = minutes % 60;
                    btnCustom.setText("Custom: " + (h > 0 ? h + " h " : "") + (m > 0 || h == 0 ? m + " min" : "").trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateExpectedArrival(int secondsFromNow) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, secondsFromNow);
        String time = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.getTime());
        tvExpectedArrival.setText("Expected arrival at " + time);
        rowExpectedArrival.setVisibility(android.view.View.VISIBLE);
    }

    /** Captures GPS once - never repeats automatically. Matches "local, simple, private". */
    private void captureLocationOnce() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            tvLocationSnapshot.setText("Location permission not granted - enable it in Control Centre");
            return;
        }
        tvLocationSnapshot.setText("Capturing…");
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location == null) {
                tvLocationSnapshot.setText("Couldn't get a location right now");
                return;
            }
            snapshotLat = location.getLatitude();
            snapshotLng = location.getLongitude();
            String time = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new java.util.Date());
            tvLocationSnapshot.setText("Captured at " + time + " · tap Refresh to update");
        });
    }

    void startTimer() {
        // Live tracking needs location (and notifications on Android 13+ for the "why did you stop?" pop-up).
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) startTimerNow();
        else walkPermissionLauncher.launch(missing.toArray(new String[0]));
    }

    private void startTimerNow() {
        if (timerRunning) return;
        timerRunning = true;
        long totalMillis = selectedSeconds * 1000L;

        // Destination note: local only, never sent to Firebase - matches the privacy note on screen.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("destination", etDestination.getText().toString().trim())
                .apply();

        btnAction.setText("I'm home — cancel");
        btnAction.setBackgroundTintList(getColorStateList(R.color.ss_card));
        btnAction.setTextColor(getColor(R.color.ss_text_secondary));
        tvStatus.setText("Walking now…");
        updateExpectedArrival(selectedSeconds);

        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        swOneTimeLocation.setChecked(sp.getBoolean("walk_one_time_location", false));
        swLockScreen.setChecked(sp.getBoolean("walk_lock_screen", false));

        if (swOneTimeLocation.isChecked() && snapshotLat == null) captureLocationOnce();

        startWalkTracking();

        countDownTimer = new CountDownTimer(totalMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                tvCountdown.setText(formatTime(secondsLeft));

                double percentLeft = (double) secondsLeft / selectedSeconds;
                if (percentLeft > 0.5) tvCountdown.setTextColor(getColor(R.color.ss_text_primary));
                else if (percentLeft > 0.2) tvCountdown.setTextColor(getColor(R.color.ss_amber));
                else tvCountdown.setTextColor(getColor(R.color.ss_red));

                if (swLockScreen.isChecked() && secondsLeft % 15 == 0) {
                    postLockScreenNotification(formatTime(secondsLeft));
                }
            }

            @Override
            public void onFinish() {
                timerRunning = false;
                tvCountdown.setText("Alert!");
                tvCountdown.setTextColor(getColor(R.color.ss_red));
                tvStatus.setText("Auto-alert sent to your trusted contacts");
                btnAction.setText("Reset timer");
                btnAction.setBackgroundTintList(getColorStateList(R.color.ss_red));
                btnAction.setTextColor(getColor(R.color.white));
                clearLockScreenNotification();
                clearDestinationNote();

                // The tracking service sends the SOS itself when the timer runs out.
                // Fallback only if tracking wasn't running (e.g. location permission was denied).
                if (!WalkTrackingService.running) {
                    new AlertManager(TimerActivity.this).sendAlert(100, (alertKey, lat, lng) -> {});
                }
            }
        }.start();
    }

    private void startWalkTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Location is off, so security can't follow your walk. The timer still works.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, WalkTrackingService.class)
                .setAction(WalkTrackingService.ACTION_START)
                .putExtra(WalkTrackingService.EXTRA_DURATION_SEC, selectedSeconds);
        ContextCompat.startForegroundService(this, i);
    }

    private void stopWalkTracking() {
        if (WalkTrackingService.running) {
            startService(new Intent(this, WalkTrackingService.class).setAction(WalkTrackingService.ACTION_STOP));
        }
    }

    /** Service closed the walk because the student answered "Other" - timer stops, no auto alert. */
    private void resetUiAfterWalkEnded(String message) {
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        clearLockScreenNotification();
        clearDestinationNote();
        tvCountdown.setText(formatTime(selectedSeconds));
        tvCountdown.setTextColor(getColor(R.color.ss_text_primary));
        tvStatus.setText(message);
        btnAction.setText("Start walking home");
        btnAction.setBackgroundTintList(getColorStateList(R.color.ss_red));
        btnAction.setTextColor(getColor(R.color.white));
    }

    /** Service sent the SOS (danger answer, no answer, or timer expired). */
    private void showAlertedUi() {
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        clearLockScreenNotification();
        clearDestinationNote();
        tvCountdown.setText("Alert!");
        tvCountdown.setTextColor(getColor(R.color.ss_red));
        tvStatus.setText("Alert sent to campus security and your trusted contacts");
        btnAction.setText("Reset timer");
        btnAction.setBackgroundTintList(getColorStateList(R.color.ss_red));
        btnAction.setTextColor(getColor(R.color.white));
    }

    void stopTimer(boolean safe) {
        stopWalkTracking();
        if (countDownTimer != null) countDownTimer.cancel();
        timerRunning = false;
        clearLockScreenNotification();
        clearDestinationNote();
        snapshotLat = null; snapshotLng = null;
        rowLocationSnapshot.setVisibility(android.view.View.GONE);
        swOneTimeLocation.setChecked(false);

        tvCountdown.setText(formatTime(selectedSeconds));
        tvCountdown.setTextColor(getColor(R.color.ss_text_primary));
        tvStatus.setText("Set your walk duration");
        btnAction.setText("Start walking home");
        btnAction.setBackgroundTintList(getColorStateList(R.color.ss_red));
        btnAction.setTextColor(getColor(R.color.white));

        if (safe) {
            btnAction.setText("Confirmed safe");
            btnAction.setBackgroundTintList(getColorStateList(R.color.ss_green));

            SharedPreferences statsPrefs = getSharedPreferences("StatsPrefs", MODE_PRIVATE);
            int checkIns = statsPrefs.getInt("checkIns", 0);
            statsPrefs.edit().putInt("checkIns", checkIns + 1).apply();

            btnAction.postDelayed(() -> {
                Intent intent = new Intent(TimerActivity.this, HomeActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            }, 2000);
        }
    }

    private void clearDestinationNote() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("destination").apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Walk with Me", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows walk countdown on the lock screen when enabled");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /** Public-visibility notification: title/time only, no destination or contact info - shows on lock screen. */
    private void postLockScreenNotification(String timeLeft) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return; // needs POST_NOTIFICATIONS on Android 13+; granted via Control Centre
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Walk with Me")
                .setContentText(timeLeft + " left")
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true);
        androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build());
    }

    private void clearLockScreenNotification() {
        androidx.core.app.NotificationManagerCompat.from(this).cancel(NOTIF_ID);
    }

    String formatTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null) return;
        bottomNav.setSelectedItemId(R.id.nav_timer);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                startActivity(new Intent(this, HomeActivity.class));
            } else if (id == R.id.nav_report_concern) {
                startActivity(new Intent(this, SubmitTicketActivity.class));
            } else if (id == R.id.nav_chat) {
                startActivity(new Intent(this, ChatContactsActivity.class));
            } else if (id == R.id.nav_contact) {
                startActivity(new Intent(this, AddContactActivity.class));
            }
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(walkReceiver); } catch (Exception ignored) {}
        if (countDownTimer != null) countDownTimer.cancel();
    }
}
