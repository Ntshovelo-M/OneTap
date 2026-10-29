package com.habitu.onetaprealhelp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;

public class ForgotPasswordActivity extends AppCompatActivity {

    private TextInputEditText etEmail;
    private Button btnReset;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        mAuth = FirebaseAuth.getInstance();

        etEmail = findViewById(R.id.et_forgot_email);
        btnReset = findViewById(R.id.btn_reset_password);

        btnReset.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();

            if (TextUtils.isEmpty(email)) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show();
                return;
            }

            sendResetEmail(email);
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void sendResetEmail(String email) {
        btnReset.setEnabled(false);
        btnReset.setText(R.string.sending_reset);

        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    btnReset.setEnabled(true);
                    btnReset.setText(R.string.send_reset_link);
                    if (task.isSuccessful()) {
                        Toast.makeText(ForgotPasswordActivity.this,
                                R.string.reset_link_sent, Toast.LENGTH_LONG).show();
                        finish();
                    } else {
                        Toast.makeText(ForgotPasswordActivity.this,
                                "Error: " + (task.getException() != null ? task.getException().getMessage() : "Unknown error"),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}
