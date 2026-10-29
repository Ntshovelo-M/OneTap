package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class SecurityAccessActivity extends AppCompatActivity {

    private TextInputEditText etAccessCode;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_access);

        mDatabase = FirebaseDatabase.getInstance("https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/")
                .getReference("officers");

        etAccessCode = findViewById(R.id.et_access_code);
        Button btnVerify = findViewById(R.id.btn_verify);

        btnVerify.setOnClickListener(v -> {
            String enteredCodeStr = etAccessCode.getText().toString().trim();

            if (enteredCodeStr.isEmpty()) {
                etAccessCode.setError("Access Code Required");
                return;
            }

            try {
                int code = Integer.parseInt(enteredCodeStr);
                verifyOfficer(code);
            } catch (NumberFormatException e) {
                etAccessCode.setError("Invalid Code Format");
            }
        });
    }

    private void verifyOfficer(int code) {
        // Step 1: Sign in anonymously to satisfy Firebase Database rules
        FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                // Step 2: Query for the officer code
                mDatabase.orderByChild("accessCode").equalTo(code).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            for (DataSnapshot officerSnap : snapshot.getChildren()) {
                                Boolean active = officerSnap.child("active").getValue(Boolean.class);
                                String name = officerSnap.child("name").getValue(String.class);

                                if (Boolean.TRUE.equals(active)) {
                                    Toast.makeText(SecurityAccessActivity.this, "Welcome " + name, Toast.LENGTH_SHORT).show();

                                    // Pass officer name to dashboard
                                    Intent intent = new Intent(SecurityAccessActivity.this, SecurityDashboardActivity.class);
                                    intent.putExtra("officerName", name);
                                    startActivity(intent);
                                    finish();
                                    return;
                                } else {
                                    Toast.makeText(SecurityAccessActivity.this, "Account Deactivated by Admin", Toast.LENGTH_LONG).show();
                                    return;
                                }
                            }
                        }
                        etAccessCode.setError("Invalid Access Code");
                        Toast.makeText(SecurityAccessActivity.this, "Access Denied", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(SecurityAccessActivity.this, "Database Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Unknown error";
                Toast.makeText(SecurityAccessActivity.this, "Authentication failed: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }
}
