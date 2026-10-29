package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    Button btnSos;
    TextView tvSosHint, tvUsername;
    boolean alertFired = false;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private AlertManager alertManager;

    private final ActivityResultLauncher<String[]> locationPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), (Map<String, Boolean> result) -> {
                fireAlert();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSos    = findViewById(R.id.btn_sos);
        tvSosHint = findViewById(R.id.tv_sos_hint);
        tvUsername = findViewById(R.id.tv_username);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();
        alertManager = new AlertManager(this);
        fetchUsername();

        // SOS button tap — toggles between fired and not fired
        btnSos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!alertFired) {
                    checkLocationPermissionThenFire();
                } else {
                    confirmCancelAlert();
                }
            }
        });

        // Quick card taps
        MaterialCardView cardTimer  = findViewById(R.id.card_timer);
        MaterialCardView cardAlerts = findViewById(R.id.card_alerts);
        MaterialCardView cardDashboard = findViewById(R.id.card_dashboard);

        cardTimer.setOnClickListener(v ->
                startActivity(new Intent(this, TimerActivity.class)));

        cardAlerts.setOnClickListener(v ->
                startActivity(new Intent(this, AlertsActivity.class)));

        cardDashboard.setOnClickListener(v ->
                startActivity(new Intent(this, DashboardActivity.class)));

        // Bottom nav
        setupBottomNav();

        // Handle SOS from Widget
        if (getIntent().getBooleanExtra("trigger_sos", false)) {
            checkLocationPermissionThenFire();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("trigger_sos", false)) {
            checkLocationPermissionThenFire();
        }
    }

    private void fetchUsername() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && tvUsername != null) {
            mDatabase.child("users").child(user.getUid()).child("name")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            String name = snapshot.getValue(String.class);
                            if (name != null && !name.isEmpty()) {
                                tvUsername.setText(name);
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {}
                    });
        }
    }

    private void checkLocationPermissionThenFire() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.SEND_SMS);
        }

        if (missing.isEmpty()) {
            fireAlert();
        } else {
            locationPermLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void fireAlert() {
        alertFired = true;
        btnSos.setText("Sent");
        btnSos.setEnabled(true);
        btnSos.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
        tvSosHint.setText("Security notified · GPS shared · Tap to cancel");
        
        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
        btnSos.startAnimation(pulse);

        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
            int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
            int batteryPct = (int) ((level / (float) scale) * 100);

            alertManager.sendAlert(batteryPct, (alertKey, lat, lng) -> runOnUiThread(() -> {
                askUrgency(alertKey);
            }));
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            cancelAlert();
        }
    }

    private void askUrgency(String alertKey) {
        if (isFinishing()) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("How urgent is this?")
                .setMessage("Your alert has already been sent. Let security know the severity.")
                .setPositiveButton("🔴 HIGH", (dialog, which) -> alertManager.updateUrgency("high"))
                .setNeutralButton("🟠 MEDIUM", (dialog, which) -> alertManager.updateUrgency("medium"))
                .setNegativeButton("🔵 LOW", (dialog, which) -> alertManager.updateUrgency("low"))
                .setCancelable(true)
                .show();
    }

    private void confirmCancelAlert() {
        if (isFinishing()) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cancel Emergency Alert?")
                .setMessage("Did you send this alert by mistake? Cancelling will notify security that you are safe.")
                .setPositiveButton("Yes, Cancel Alert", (dialog, which) -> cancelAlert())
                .setNegativeButton("Keep Active", null)
                .setCancelable(true)
                .show();
    }

    private void cancelAlert() {
        alertFired = false;
        alertManager.cancelAlert();
        btnSos.clearAnimation();
        btnSos.setText("SOS");
        btnSos.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.ss_red));
        tvSosHint.setText("Notifies security & contacts instantly");
    }

    void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null) return;
        bottomNav.setSelectedItemId(R.id.nav_home);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_timer) {
                startActivity(new Intent(this, TimerActivity.class));
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
}