package com.habitu.onetaprealhelp;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class EditInformationActivity extends AppCompatActivity {

    private TextInputEditText etName, etField1, etField2, etField3;
    private TextInputLayout tilField1, tilField2, tilField3;
    private MaterialButton btnSave;
    private DatabaseReference mDatabase;
    private String currentUserId;
    private String userRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_information);

        mDatabase = FirebaseDatabase.getInstance().getReference();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }
        currentUserId = user.getUid();

        initViews();
        loadCurrentData();

        btnSave.setOnClickListener(v -> saveChanges());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void initViews() {
        etName = findViewById(R.id.et_edit_name);
        etField1 = findViewById(R.id.et_field_1);
        etField2 = findViewById(R.id.et_field_2);
        etField3 = findViewById(R.id.et_field_3);

        tilField1 = findViewById(R.id.til_field_1);
        tilField2 = findViewById(R.id.til_field_2);
        tilField3 = findViewById(R.id.til_field_3);

        btnSave = findViewById(R.id.btn_save_changes);
    }

    private void loadCurrentData() {
        mDatabase.child("users").child(currentUserId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                userRole = snapshot.child("role").getValue(String.class);
                String name = snapshot.child("name").getValue(String.class);
                etName.setText(name);

                if ("Student".equals(userRole)) {
                    setupStudentFields(snapshot);
                } else {
                    setupSecurityFields(snapshot);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(EditInformationActivity.this, "Failed to load data", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupStudentFields(DataSnapshot snapshot) {
        tilField1.setHint("Student Number");
        tilField2.setVisibility(android.view.View.GONE);
        tilField3.setHint("Disabilities (Optional)");
        tilField3.setVisibility(android.view.View.VISIBLE);

        etField1.setText(snapshot.child("studentNumber").getValue(String.class));
        etField3.setText(snapshot.child("disabilities").getValue(String.class));
    }

    private void setupSecurityFields(DataSnapshot snapshot) {
        tilField1.setHint("Staff ID");
        tilField2.setHint("Department");
        tilField3.setVisibility(android.view.View.GONE);

        etField1.setText(snapshot.child("staffId").getValue(String.class));
        etField2.setText(snapshot.child("shift").getValue(String.class)); // shift is used for department/shift
    }

    private void saveChanges() {
        String name = Objects.requireNonNull(etName.getText()).toString().trim();
        String f1 = Objects.requireNonNull(etField1.getText()).toString().trim();
        String f2 = Objects.requireNonNull(etField2.getText()).toString().trim();
        String f3 = Objects.requireNonNull(etField3.getText()).toString().trim();

        if ("Student".equals(userRole)) {
            if (name.isEmpty() || f1.isEmpty()) {
                Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (name.isEmpty() || f1.isEmpty() || f2.isEmpty()) {
                Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        btnSave.setEnabled(false);
        btnSave.setText("Saving...");

        // Safety timeout handler in case network is offline/slow
        android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (!isFinishing() && !btnSave.isEnabled()) {
                btnSave.setEnabled(true);
                btnSave.setText("Save Changes");
                Toast.makeText(this, "Network timeout. Check your connection and try again.", Toast.LENGTH_LONG).show();
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, 7000);

        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);

        if ("Student".equals(userRole)) {
            updates.put("studentNumber", f1);
            updates.put("disabilities", f3);
        } else {
            updates.put("staffId", f1);
            updates.put("shift", f2);
        }

        mDatabase.child("users").child(currentUserId).updateChildren(updates)
                .addOnSuccessListener(unused -> {
                    timeoutHandler.removeCallbacks(timeoutRunnable);
                    Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    timeoutHandler.removeCallbacks(timeoutRunnable);
                    btnSave.setEnabled(true);
                    btnSave.setText("Save Changes");
                    Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}
