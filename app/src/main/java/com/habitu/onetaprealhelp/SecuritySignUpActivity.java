package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class SecuritySignUpActivity extends AppCompatActivity {

    private TextInputLayout tilName, tilBadgeNumber, tilCellNumber, tilAssignedPost, tilPassword, tilConfirmPassword;
    private TextInputEditText etName, etBadgeNumber, etCellNumber, etAssignedPost, etPassword, etConfirmPassword;
    private ImageView ivProfilePicture;
    private Uri selectedImageUri = null;

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedImageUri = result.getData().getData();
                    ivProfilePicture.setImageURI(selectedImageUri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_sign_up);

        // Text fields
        tilName           = findViewById(R.id.tilName);
        tilBadgeNumber    = findViewById(R.id.tilBadgeNumber);
        tilCellNumber     = findViewById(R.id.tilCellNumber);
        tilAssignedPost   = findViewById(R.id.tilAssignedPost);
        tilPassword       = findViewById(R.id.tilPassword);
        tilConfirmPassword= findViewById(R.id.tilConfirmPassword);

        etName            = findViewById(R.id.etName);
        etBadgeNumber     = findViewById(R.id.etBadgeNumber);
        etCellNumber      = findViewById(R.id.etCellNumber);
        etAssignedPost    = findViewById(R.id.etAssignedPost);
        etPassword        = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);

        // Profile picture views
        ivProfilePicture  = findViewById(R.id.ivProfilePicture);
        MaterialButton btnChoosePhoto = findViewById(R.id.btnChoosePhoto);

        // Open gallery on photo button tap
        btnChoosePhoto.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            imagePickerLauncher.launch(intent);
        });

        // Back button
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Register button
        MaterialButton btnRegisterSecurity = findViewById(R.id.btnRegisterSecurity);
        btnRegisterSecurity.setOnClickListener(v -> {
            if (validateFields()) {
                if (selectedImageUri == null) {
                    Toast.makeText(this, "Please add a profile picture", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(SecuritySignUpActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }
        });
    }

    private boolean validateFields() {
        boolean isValid = true;

        tilName.setError(null);
        tilBadgeNumber.setError(null);
        tilCellNumber.setError(null);
        tilAssignedPost.setError(null);
        tilPassword.setError(null);
        tilConfirmPassword.setError(null);

        String name            = etName.getText().toString().trim();
        String badgeNumber     = etBadgeNumber.getText().toString().trim();
        String cellNumber      = etCellNumber.getText().toString().trim();
        String assignedPost    = etAssignedPost.getText().toString().trim();
        String password        = etPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        // Full Name
        if (TextUtils.isEmpty(name)) {
            tilName.setError("Full name is required");
            isValid = false;
        } else if (name.length() < 3) {
            tilName.setError("Name must be at least 3 characters");
            isValid = false;
        }

        // Badge Number
        if (TextUtils.isEmpty(badgeNumber)) {
            tilBadgeNumber.setError("Badge number is required");
            isValid = false;
        } else if (badgeNumber.length() < 5) {
            tilBadgeNumber.setError("Enter a valid badge number");
            isValid = false;
        }

        // Cell Number
        if (TextUtils.isEmpty(cellNumber)) {
            tilCellNumber.setError("Cell number is required");
            isValid = false;
        } else if (!cellNumber.matches("^(0[6-8][0-9]{8})$")) {
            tilCellNumber.setError("Enter a valid SA cell number e.g. 0821234567");
            isValid = false;
        }

        // Assigned Post
        if (TextUtils.isEmpty(assignedPost)) {
            tilAssignedPost.setError("Assigned post is required");
            isValid = false;
        }

        // Password
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("Password is required");
            isValid = false;
        } else if (password.length() < 8) {
            tilPassword.setError("Password must be at least 8 characters");
            isValid = false;
        } else if (!password.matches(".*[A-Z].*")) {
            tilPassword.setError("Password must contain at least one uppercase letter");
            isValid = false;
        } else if (!password.matches(".*[0-9].*")) {
            tilPassword.setError("Password must contain at least one number");
            isValid = false;
        }

        // Confirm Password
        if (TextUtils.isEmpty(confirmPassword)) {
            tilConfirmPassword.setError("Please confirm your password");
            isValid = false;
        } else if (!confirmPassword.equals(password)) {
            tilConfirmPassword.setError("Passwords do not match");
            isValid = false;
        }

        return isValid;
    }
}
