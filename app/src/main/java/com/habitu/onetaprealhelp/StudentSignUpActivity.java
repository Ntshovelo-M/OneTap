package com.habitu.onetaprealhelp;

import android.app.ProgressDialog;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashMap;
import java.util.Map;

public class StudentSignUpActivity extends AppCompatActivity {

    private TextInputLayout tilName, tilStudentNumber, tilCellNumber,
            tilPassword, tilConfirmPassword;
    private TextInputEditText etName, etStudentNumber, etCellNumber,
            etPassword, etConfirmPassword;
    private ImageView ivProfilePicture;
    private Uri selectedImageUri = null;

    // Firebase
    private FirebaseAuth firebaseAuth;
    private DatabaseReference databaseRef;

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
        setContentView(R.layout.activity_student_sign_up);

        // Firebase instances
        firebaseAuth = FirebaseAuth.getInstance();
        databaseRef  = FirebaseDatabase.getInstance().getReference("students");

        // Text fields
        tilName            = findViewById(R.id.tilName);
        tilStudentNumber   = findViewById(R.id.tilStudentNumber);
        tilCellNumber      = findViewById(R.id.tilCellNumber);
        tilPassword        = findViewById(R.id.tilPassword);
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword);

        etName            = findViewById(R.id.etName);
        etStudentNumber   = findViewById(R.id.etStudentNumber);
        etCellNumber      = findViewById(R.id.etCellNumber);
        etPassword        = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);

        // Profile picture views
        ivProfilePicture = findViewById(R.id.ivProfilePicture);
        MaterialButton btnChoosePhoto = findViewById(R.id.btnChoosePhoto);

        btnChoosePhoto.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            imagePickerLauncher.launch(intent);
        });

        // Back button
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Register button
        MaterialButton btnRegisterStudent = findViewById(R.id.btnRegisterStudent);
        btnRegisterStudent.setOnClickListener(v -> {
            if (validateFields()) {
                registerStudent();
            }
        });
    }

    // ─────────────────────────────────────────────
    // Register with Firebase Auth + save to Realtime DB
    // ─────────────────────────────────────────────

    private void registerStudent() {
        String name           = etName.getText().toString().trim();
        String studentNumber  = etStudentNumber.getText().toString().trim();
        String cellNumber     = etCellNumber.getText().toString().trim();
        String password       = etPassword.getText().toString().trim();

        // Generate an email from the student number since Firebase Auth needs one
        String email = studentNumber + "@student.onetap.ac.za";

        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Creating account...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    String uid = authResult.getUser().getUid();

                    Map<String, Object> studentData = new HashMap<>();
                    studentData.put("uid",            uid);
                    studentData.put("name",           name);
                    studentData.put("studentNumber",  studentNumber);
                    studentData.put("cellNumber",     cellNumber);
                    studentData.put("role",           "student");
                    studentData.put("createdAt",      System.currentTimeMillis());

                    // Save under students/{uid}
                    databaseRef.child(uid).setValue(studentData)
                            .addOnSuccessListener(unused -> {
                                progressDialog.dismiss();
                                Toast.makeText(this,
                                        "Account created successfully!",
                                        Toast.LENGTH_SHORT).show();

                                Intent intent = new Intent(
                                        StudentSignUpActivity.this, MainActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                finish();
                            })
                            .addOnFailureListener(e -> {
                                progressDialog.dismiss();
                                Toast.makeText(this,
                                        "Failed to save details: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                })
                .addOnFailureListener(e -> {
                    progressDialog.dismiss();
                    Toast.makeText(this,
                            "Registration failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ─────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────

    private boolean validateFields() {
        boolean isValid = true;

        tilName.setError(null);
        tilStudentNumber.setError(null);
        tilCellNumber.setError(null);
        tilPassword.setError(null);
        tilConfirmPassword.setError(null);

        String name            = etName.getText().toString().trim();
        String studentNumber   = etStudentNumber.getText().toString().trim();
        String cellNumber      = etCellNumber.getText().toString().trim();
        String password        = etPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            tilName.setError("Full name is required");
            isValid = false;
        } else if (name.length() < 3) {
            tilName.setError("Name must be at least 3 characters");
            isValid = false;
        }

        if (TextUtils.isEmpty(studentNumber)) {
            tilStudentNumber.setError("Student number is required");
            isValid = false;
        } else if (studentNumber.length() < 9) {
            tilStudentNumber.setError("Enter a valid student number");
            isValid = false;
        }

        if (TextUtils.isEmpty(cellNumber)) {
            tilCellNumber.setError("Cell number is required");
            isValid = false;
        } else if (!cellNumber.matches("^(0[6-8][0-9]{8})$")) {
            tilCellNumber.setError("Enter a valid SA cell number e.g. 0821234567");
            isValid = false;
        }

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
