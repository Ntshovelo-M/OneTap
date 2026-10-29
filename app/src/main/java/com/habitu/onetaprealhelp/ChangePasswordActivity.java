package com.habitu.onetaprealhelp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class ChangePasswordActivity extends AppCompatActivity {

    private TextInputEditText etCurrent, etNew, etConfirm;
    private Button btnUpdate;
    private FirebaseUser user;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }

        etCurrent = findViewById(R.id.et_current_password);
        etNew = findViewById(R.id.et_new_password);
        etConfirm = findViewById(R.id.et_confirm_new_password);
        btnUpdate = findViewById(R.id.btn_update_password);

        btnUpdate.setOnClickListener(v -> validateAndChange());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void validateAndChange() {
        String current = etCurrent.getText().toString().trim();
        String newPass = etNew.getText().toString().trim();
        String confirm = etConfirm.getText().toString().trim();

        if (TextUtils.isEmpty(current) || TextUtils.isEmpty(newPass) || TextUtils.isEmpty(confirm)) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPass.equals(confirm)) {
            Toast.makeText(this, "New passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPass.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        btnUpdate.setEnabled(false);
        btnUpdate.setText("Updating...");

        // Re-authenticate user
        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), current);
        user.reauthenticate(credential).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                user.updatePassword(newPass).addOnCompleteListener(updateTask -> {
                    btnUpdate.setEnabled(true);
                    btnUpdate.setText("Update Password");
                    if (updateTask.isSuccessful()) {
                        Toast.makeText(ChangePasswordActivity.this, "Password updated successfully!", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(ChangePasswordActivity.this, "Update failed: " + updateTask.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                btnUpdate.setEnabled(true);
                btnUpdate.setText("Update Password");
                Toast.makeText(ChangePasswordActivity.this, "Authentication failed. Check your current password.", Toast.LENGTH_LONG).show();
            }
        });
    }
}
