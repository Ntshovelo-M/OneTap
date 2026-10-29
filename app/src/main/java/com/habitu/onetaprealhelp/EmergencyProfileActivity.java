package com.habitu.onetaprealhelp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class EmergencyProfileActivity extends AppCompatActivity {

    private TextInputEditText etBloodType, etAllergies, etLevel, etContactName;
    private MaterialButton btnSave;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_profile);

        // Firebase
        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Toolbar back button
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Emergency Profile");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Views
        etBloodType   = findViewById(R.id.et_blood_type);
        etAllergies   = findViewById(R.id.et_allergies);
        etLevel       = findViewById(R.id.et_level);
        etContactName = findViewById(R.id.et_contact_name);
        btnSave       = findViewById(R.id.btn_save);

        // Load any previously saved data
        loadExistingProfile();

        // Save on button tap
        btnSave.setOnClickListener(v -> saveProfile());
    }

    private void loadExistingProfile() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid())
                .child("emergencyProfile")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) return;

                        String bloodType   = snapshot.child("bloodType").getValue(String.class);
                        String allergies   = snapshot.child("allergies").getValue(String.class);
                        String level       = snapshot.child("level").getValue(String.class);
                        String contactName = snapshot.child("contactName").getValue(String.class);

                        if (bloodType   != null) etBloodType.setText(bloodType);
                        if (allergies   != null) etAllergies.setText(allergies);
                        if (level       != null) etLevel.setText(level);
                        if (contactName != null) etContactName.setText(contactName);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void saveProfile() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String bloodType   = etBloodType.getText().toString().trim();
        String allergies   = etAllergies.getText().toString().trim();
        String level       = etLevel.getText().toString().trim();
        String contactName = etContactName.getText().toString().trim();

        if (TextUtils.isEmpty(bloodType) && TextUtils.isEmpty(allergies)
                && TextUtils.isEmpty(level) && TextUtils.isEmpty(contactName)) {
            Toast.makeText(this, "Please fill in at least one field", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> profileData = new HashMap<>();
        profileData.put("bloodType",   bloodType);
        profileData.put("allergies",   allergies);
        profileData.put("level",       level);
        profileData.put("contactName", contactName);

        btnSave.setEnabled(false);
        btnSave.setText("Saving...");

        mDatabase.child("users")
                .child(user.getUid())
                .child("emergencyProfile")
                .setValue(profileData)
                .addOnSuccessListener(unused -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("Save Emergency Profile");
                    Toast.makeText(this, "Emergency profile saved!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("Save Emergency Profile");
                    Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}
