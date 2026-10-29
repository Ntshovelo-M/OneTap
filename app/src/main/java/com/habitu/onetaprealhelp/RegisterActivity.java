package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity {

    // Common fields
    private TextInputEditText etName, etEmail, etCellNumber, etPassword, etConfirmPassword;
    private TextInputLayout tilEmail, tilCell, tilPassword;
    private Button btnRegister;
    private ImageView ivProfileImage, ivProfilePlaceholder;
    private Uri imageUri;

    // Student fields
    private LinearLayout studentFields;
    private TextInputEditText etStudentNumber, etDisabilities;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> mGetContent = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    imageUri = uri;
                    ivProfileImage.setImageURI(uri);
                    ivProfileImage.setVisibility(View.VISIBLE);
                    ivProfilePlaceholder.setVisibility(View.GONE);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance("https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/")
                .getReference();

        // Initialize UI
        initViews();

        // Default Student setup (Security registration moved to Web Management Portal)
        studentFields.setVisibility(View.VISIBLE);
        tilCell.setVisibility(View.VISIBLE);
        tilEmail.setHint("UMP Email (e.g. 240769147@ump.ac.za)");
        tilEmail.setHelperText("Must be your 9-digit UMP student email");

        // Profile image selection
        findViewById(R.id.card_profile_image).setOnClickListener(v -> mGetContent.launch("image/*"));

        // Register button logic
        btnRegister.setOnClickListener(v -> registerUser());

        // Login link
        findViewById(R.id.tv_login_link).setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private void initViews() {
        // Common
        etName = findViewById(R.id.et_name);
        etEmail = findViewById(R.id.et_email);
        etCellNumber = findViewById(R.id.et_cell_number);
        etPassword = findViewById(R.id.et_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        
        tilEmail = findViewById(R.id.til_email);
        tilCell = findViewById(R.id.til_cell);
        tilPassword = findViewById(R.id.til_password);
        
        btnRegister = findViewById(R.id.btn_register);
        ivProfileImage = findViewById(R.id.iv_profile_image);
        ivProfilePlaceholder = findViewById(R.id.iv_profile_placeholder);

        // Student
        studentFields = findViewById(R.id.student_fields);
        etStudentNumber = findViewById(R.id.et_student_number);
        etDisabilities = findViewById(R.id.et_disabilities);
    }

    private void registerUser() {
        String name = Objects.requireNonNull(etName.getText()).toString().trim();
        String email = Objects.requireNonNull(etEmail.getText()).toString().trim();
        String cell = Objects.requireNonNull(etCellNumber.getText()).toString().trim();
        String password = Objects.requireNonNull(etPassword.getText()).toString().trim();
        String confirmPassword = Objects.requireNonNull(etConfirmPassword.getText()).toString().trim();

        // Reset errors
        tilEmail.setError(null);
        tilCell.setError(null);
        tilPassword.setError(null);

        // Common Validation
        if (name.isEmpty() || email.isEmpty() || cell.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Email Validation (UMP only)
        if (!email.matches("^[0-9]{9}@ump\\.ac\\.za$")) {
            tilEmail.setError("Invalid format. Use [9-digit student number]@ump.ac.za");
            return;
        }

        // 1b. Student number must be 9 digits and must match the UMP e-mail
        String studentNo = Objects.requireNonNull(etStudentNumber.getText()).toString().trim();
        if (!studentNo.matches("^[0-9]{9}$")) {
            Toast.makeText(this, "Student number must be exactly 9 digits", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!email.startsWith(studentNo + "@")) {
            tilEmail.setError("Your UMP e-mail must be " + studentNo + "@ump.ac.za");
            return;
        }

        // 2. Cell Phone Validation
        if (!cell.matches("^[0-9]{10}$")) {
            tilCell.setError("Phone number must be exactly 10 digits");
            return;
        }

        // 3. Password Strength Validation (Min 8 chars, Upper, Lower, Number, Special)
        String passwordPattern = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";
        if (!password.matches(passwordPattern)) {
            tilPassword.setError("Password too weak. Must include uppercase, lowercase, number, and special character.");
            return;
        }

        if (!Objects.equals(password, confirmPassword)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        if (studentNo.isEmpty()) {
            Toast.makeText(this, "Please fill in your Student Number", Toast.LENGTH_SHORT).show();
            return;
        }

        btnRegister.setEnabled(false);
        btnRegister.setText(R.string.registering);

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            if (imageUri != null) {
                                // Background processing to avoid ANR
                                executorService.execute(() -> {
                                    String base64Img = encodeImageToBase64(imageUri);
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        saveUserToDatabase(user.getUid(), name, email, cell, base64Img);
                                    });
                                });
                            } else {
                                saveUserToDatabase(user.getUid(), name, email, cell, null);
                            }
                        }
                    } else {
                        btnRegister.setEnabled(true);
                        btnRegister.setText(R.string.register);
                        Toast.makeText(RegisterActivity.this,
                                "Authentication failed: " + (task.getException() != null ? task.getException().getMessage() : "Unknown Error"),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private String encodeImageToBase64(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream);
            byte[] byteArray = outputStream.toByteArray();
            return Base64.encodeToString(byteArray, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveUserToDatabase(String userId, String name, String email, String cell, String base64Image) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("name", name);
        userData.put("email", email);
        if (cell != null && !cell.isEmpty()) userData.put("cellNumber", cell);
        if (base64Image != null) userData.put("profileImageBase64", base64Image);

        userData.put("role", "Student");
        userData.put("emailVerified", false);
        userData.put("studentNumber", Objects.requireNonNull(etStudentNumber.getText()).toString().trim());
        userData.put("disabilities", Objects.requireNonNull(etDisabilities.getText()).toString().trim());

        mDatabase.child("users").child(userId).setValue(userData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(RegisterActivity.this, "Registration Successful!", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(RegisterActivity.this, VerifyEmailActivity.class));
                        finish();
                    } else {
                        btnRegister.setEnabled(true);
                        btnRegister.setText(R.string.register);
                        String errorMsg = (task.getException() != null) ? task.getException().getMessage() : "Unknown Database Error";
                        Toast.makeText(RegisterActivity.this, "Database error: " + errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
