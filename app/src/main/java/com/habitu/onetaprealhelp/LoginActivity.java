package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etEmail, etPassword;
    private Button btnLogin;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance("https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/").getReference();

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        TextView tvSignupLink = findViewById(R.id.tv_signup_link);
        TextView tvForgotPassword = findViewById(R.id.tv_forgot_password);

        btnLogin.setOnClickListener(v -> {
            String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
            String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all details", Toast.LENGTH_SHORT).show();
            } else {
                performLogin(email, password);
            }
        });

        tvSignupLink.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
        });

        tvForgotPassword.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class));
        });

        // Already signed in from a previous session — check user role and redirect.
        if (mAuth.getCurrentUser() != null) {
            checkUserRole();
        }
    }

    private void performLogin(String email, String password) {
        if (btnLogin != null) {
            btnLogin.setEnabled(false);
            btnLogin.setText(R.string.logging_in);
        }

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        checkUserRole();
                    } else {
                        if (btnLogin != null) {
                            btnLogin.setEnabled(true);
                            btnLogin.setText(R.string.login);
                        }
                        Toast.makeText(LoginActivity.this, "Login Failed: " +
                                        (task.getException() != null ? task.getException().getMessage() : "Unknown error"),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void checkUserRole() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            // Force a token refresh first: a saved session for a deleted/disabled account
            // otherwise reaches the database unauthenticated and gets "Permission denied".
            user.getIdToken(true).addOnCompleteListener(this, tokenTask -> {
                if (tokenTask.isSuccessful()) {
                    readUserRole(user.getUid());
                } else {
                    resetSession("Your session has expired. Please log in again.");
                }
            });
        }
    }

    private void resetSession(String message) {
        mAuth.signOut();
        if (btnLogin != null) {
            btnLogin.setEnabled(true);
            btnLogin.setText(R.string.login);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void readUserRole(String userId) {
        mDatabase.child("users").child(userId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String role = snapshot.child("role").getValue(String.class);
                        boolean verified = Boolean.TRUE.equals(snapshot.child("emailVerified").getValue(Boolean.class));
                        if (role != null) {
                            redirectBasedOnRole(role, verified);
                        } else {
                            mAuth.signOut();
                            if (btnLogin != null) {
                                btnLogin.setEnabled(true);
                                btnLogin.setText(R.string.login);
                            }
                            Toast.makeText(LoginActivity.this, "User role not found", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        if (error.getCode() == DatabaseError.PERMISSION_DENIED) {
                            resetSession("Your session has expired. Please log in again.");
                            return;
                        }
                        if (btnLogin != null) {
                            btnLogin.setEnabled(true);
                            btnLogin.setText(R.string.login);
                        }
                        Toast.makeText(LoginActivity.this, "Database error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void redirectBasedOnRole(String role, boolean emailVerified) {
        Intent intent;
        if ("Security".equals(role)) {
            intent = new Intent(LoginActivity.this, SecurityAccessActivity.class);
        } else if (!emailVerified) {
            intent = new Intent(LoginActivity.this, VerifyEmailActivity.class);   // must verify first
        } else {
            intent = new Intent(LoginActivity.this, HomeActivity.class);
        }
        startActivity(intent);
        finish();
    }
}