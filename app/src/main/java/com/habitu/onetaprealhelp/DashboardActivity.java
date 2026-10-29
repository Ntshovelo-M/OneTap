package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class DashboardActivity extends AppCompatActivity {

    // Header
    private TextView  tvDashboardName;
    private ImageView ivDashboardAvatar;

    // Profile summary
    private ImageView ivProfilePhoto;
    private TextView  tvProfileName, tvProfileStudentNumber, tvProfileCell;
    private TextView  tvEmergencyStatus, tvContactsStatus;

    // Stats
    private TextView tvStatSos, tvStatCheckins, tvStatContacts;

    // SOS history
    private TextView tvSosEmpty;

    // Emergency profile status
    private TextView tvEpBlood, tvEpAllergies, tvEpLevel, tvEpContact;

    // Buttons
    private MaterialButton btnEditEmergencyProfile;

    // Firebase
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        bindViews();
        fetchUserData();
        loadEmergencyProfileData();
        loadContactsData();
        setupButtons();
        VoiceRecognitionHelper.startIfEnabled(this);
    }

    // ─────────────────────────────────────────────
    // Bind views
    // ─────────────────────────────────────────────

    private void bindViews() {
        tvDashboardName        = findViewById(R.id.tv_dashboard_name);
        ivDashboardAvatar      = findViewById(R.id.iv_dashboard_avatar);

        ivProfilePhoto          = findViewById(R.id.iv_profile_photo);
        tvProfileName           = findViewById(R.id.tv_profile_name);
        tvProfileStudentNumber  = findViewById(R.id.tv_profile_student_number);
        tvProfileCell           = findViewById(R.id.tv_profile_cell);
        tvEmergencyStatus       = findViewById(R.id.tv_emergency_status);
        tvContactsStatus        = findViewById(R.id.tv_contacts_status);

        tvStatSos       = findViewById(R.id.tv_stat_sos);
        tvStatCheckins  = findViewById(R.id.tv_stat_checkins);
        tvStatContacts  = findViewById(R.id.tv_stat_contacts);

        tvSosEmpty = findViewById(R.id.tv_sos_empty);

        tvEpBlood     = findViewById(R.id.tv_ep_blood);
        tvEpAllergies = findViewById(R.id.tv_ep_allergies);
        tvEpLevel     = findViewById(R.id.tv_ep_level);
        tvEpContact   = findViewById(R.id.tv_ep_contact);

        btnEditEmergencyProfile = findViewById(R.id.btn_edit_emergency_profile);
    }

    // ─────────────────────────────────────────────
    // Load student profile from "users" node in Firebase
    // ─────────────────────────────────────────────

    private void fetchUserData() {
        FirebaseUser user = mAuth.getCurrentUser();

        if (user == null) {
            tvDashboardName.setText("Guest");
            tvProfileName.setText("Guest");
            return;
        }

        String userId = user.getUid();

        mDatabase.child("users").child(userId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) return;

                        String name          = snapshot.child("name").getValue(String.class);
                        String studentNumber = snapshot.child("studentNumber").getValue(String.class);
                        String cellNumber    = snapshot.child("cellNumber").getValue(String.class);

                        if (name != null && !name.isEmpty()) {
                            tvDashboardName.setText(name);
                            tvProfileName.setText(name);
                        }

                        tvProfileStudentNumber.setText(
                                "Student No: " + (studentNumber == null || studentNumber.isEmpty()
                                        ? "—" : studentNumber));

                        tvProfileCell.setText(
                                "Cell: " + (cellNumber == null || cellNumber.isEmpty()
                                        ? "—" : cellNumber));
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });

        // Fetch actual SOS alert count from Firebase
        mDatabase.child("alerts").orderByChild("userId").equalTo(userId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        long count = snapshot.getChildrenCount();
                        tvStatSos.setText(String.valueOf(count));
                        tvSosEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
                        
                        // Sync back to local prefs for consistency
                        getSharedPreferences("StatsPrefs", MODE_PRIVATE).edit().putInt("sosSent", (int)count).apply();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });

        // Stats still come from local SharedPreferences for now
        SharedPreferences statsPrefs = getSharedPreferences(
                "StatsPrefs", MODE_PRIVATE);
        int checkIns = statsPrefs.getInt("checkIns", 0);
        tvStatCheckins.setText(String.valueOf(checkIns));
    }

    // ─────────────────────────────────────────────
    // Load emergency profile data
    // ─────────────────────────────────────────────

    private void loadEmergencyProfileData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid())
                .child("emergencyProfile")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) {
                            tvEmergencyStatus.setText("Not set up");
                            tvEmergencyStatus.setTextColor(getColor(R.color.ss_red));
                            tvEpBlood.setText("Not set");
                            tvEpAllergies.setText("Not set");
                            tvEpLevel.setText("Not set");
                            tvEpContact.setText("Not set");
                            return;
                        }

                        String bloodType   = snapshot.child("bloodType").getValue(String.class);
                        String allergies   = snapshot.child("allergies").getValue(String.class);
                        String level       = snapshot.child("level").getValue(String.class);
                        String contactName = snapshot.child("contactName").getValue(String.class);

                        tvEpBlood.setText(isEmpty(bloodType)     ? "Not set" : bloodType);
                        tvEpAllergies.setText(isEmpty(allergies) ? "Not set" : allergies);
                        tvEpLevel.setText(isEmpty(level)         ? "Not set" : level);
                        tvEpContact.setText(isEmpty(contactName) ? "Not set" : contactName);

                        boolean isSetUp = !isEmpty(bloodType) || !isEmpty(allergies)
                                || !isEmpty(level) || !isEmpty(contactName);

                        if (isSetUp) {
                            tvEmergencyStatus.setText("Set up ✓");
                            tvEmergencyStatus.setTextColor(getColor(R.color.ss_green));
                        } else {
                            tvEmergencyStatus.setText("Not set up");
                            tvEmergencyStatus.setTextColor(getColor(R.color.ss_red));
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
    // ─────────────────────────────────────────────
    // Load trusted contacts count from Firebase
    // ─────────────────────────────────────────────

    private void loadContactsData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("contacts").child(user.getUid())
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        long count = snapshot.getChildrenCount();
                        tvStatContacts.setText(String.valueOf(count));
                        tvContactsStatus.setText(count + " saved");

                        tvContactsStatus.setTextColor(
                                count > 0 ? getColor(R.color.ss_green) : getColor(R.color.ss_red));
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }
    // ─────────────────────────────────────────────
    // Button actions
    // ─────────────────────────────────────────────

    private void setupButtons() {
        if (ivDashboardAvatar != null) {
            ivDashboardAvatar.setOnClickListener(v -> startActivity(new Intent(DashboardActivity.this, ProfileActivity.class)));
        }

        btnEditEmergencyProfile.setOnClickListener(v -> {
            Intent intent = new Intent(
                    DashboardActivity.this, EmergencyProfileActivity.class);
            startActivity(intent);
        });
    }
}
