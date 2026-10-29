package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileActivity extends AppCompatActivity {

    private ImageView          ivProfilePic;
    private TextView           tvProfileName, tvProfileRole;
    private LinearLayout       containerInfo, containerContacts;
    private TextView           tvLabelContacts;
    private View               cardContacts;

    private FirebaseAuth       mAuth;
    private DatabaseReference  mDatabase;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> mGetContent = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    ivProfilePic.setImageURI(uri);
                    uploadProfileImage(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        ivProfilePic      = findViewById(R.id.iv_profile_pic);
        tvProfileName     = findViewById(R.id.tv_profile_name);
        tvProfileRole     = findViewById(R.id.tv_profile_role);
        containerInfo     = findViewById(R.id.container_info);
        containerContacts = findViewById(R.id.container_contacts);
        tvLabelContacts   = findViewById(R.id.tv_label_contacts);
        cardContacts      = findViewById(R.id.card_contacts);

        ivProfilePic.setOnClickListener(v -> mGetContent.launch("image/*"));

        View btnEditInfo = findViewById(R.id.btn_edit_info);
        if (btnEditInfo != null) {
            btnEditInfo.setOnClickListener(v ->
                    startActivity(new Intent(this, EditInformationActivity.class)));
        }

        View btnChangePassword = findViewById(R.id.btn_change_password);
        if (btnChangePassword != null) {
            btnChangePassword.setOnClickListener(v ->
                    startActivity(new Intent(this, ChangePasswordActivity.class)));
        }

        Button btnLogout = findViewById(R.id.btn_logout);
        btnLogout.setOnClickListener(v -> {
            mAuth.signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        Button btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                startActivity(new Intent(this, SettingsActivity.class));
            });
        }

        // Dropdown Collapsible Listeners
        View headerAccountInfo = findViewById(R.id.header_account_info);
        View contentAccountInfo = findViewById(R.id.content_account_info);
        TextView tvArrowInfo = findViewById(R.id.tv_arrow_info);

        if (headerAccountInfo != null && contentAccountInfo != null) {
            headerAccountInfo.setOnClickListener(v -> {
                if (contentAccountInfo.getVisibility() == View.VISIBLE) {
                    contentAccountInfo.setVisibility(View.GONE);
                    if (tvArrowInfo != null) tvArrowInfo.setText("▶");
                } else {
                    contentAccountInfo.setVisibility(View.VISIBLE);
                    if (tvArrowInfo != null) tvArrowInfo.setText("▼");
                }
            });
        }

        View headerContacts = findViewById(R.id.header_contacts);
        TextView tvArrowContacts = findViewById(R.id.tv_arrow_contacts);

        if (headerContacts != null && cardContacts != null) {
            headerContacts.setOnClickListener(v -> {
                if (cardContacts.getVisibility() == View.VISIBLE) {
                    cardContacts.setVisibility(View.GONE);
                    if (tvArrowContacts != null) tvArrowContacts.setText("▶");
                } else {
                    cardContacts.setVisibility(View.VISIBLE);
                    if (tvArrowContacts != null) tvArrowContacts.setText("▼");
                }
            });
        }

        loadUserData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUserData();
    }

    private void uploadProfileImage(Uri uri) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Toast.makeText(this, "Uploading image...", Toast.LENGTH_SHORT).show();

        executorService.execute(() -> {
            String base64Img = encodeImageToBase64(uri);
            if (base64Img != null) {
                mDatabase.child("users").child(user.getUid()).child("profileImageBase64")
                        .setValue(base64Img)
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                new Handler(Looper.getMainLooper()).post(() ->
                                        Toast.makeText(ProfileActivity.this, "Profile image updated!", Toast.LENGTH_SHORT).show());
                            } else {
                                new Handler(Looper.getMainLooper()).post(() ->
                                        Toast.makeText(ProfileActivity.this, "Failed to upload image", Toast.LENGTH_SHORT).show());
                            }
                        });
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

    private void loadUserData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) return;

                        String name        = snapshot.child("name").getValue(String.class);
                        String email       = snapshot.child("email").getValue(String.class);
                        String role        = snapshot.child("role").getValue(String.class);
                        String imageBase64 = snapshot.child("profileImageBase64").getValue(String.class);

                        tvProfileName.setText(name);
                        tvProfileRole.setText(role);

                        if (imageBase64 != null) {
                            Bitmap bitmap = decodeBase64(imageBase64);
                            if (bitmap != null) ivProfilePic.setImageBitmap(bitmap);
                        }

                        containerInfo.removeAllViews();
                        addInfoItem("Full Name",      name);
                        addInfoItem("Email Address",  email);

                        if ("Student".equals(role)) {
                            addInfoItem("Student Number",
                                    snapshot.child("studentNumber").getValue(String.class));

                            String disabilities = snapshot.child("disabilities")
                                    .getValue(String.class);
                            if (disabilities != null && !disabilities.isEmpty()) {
                                addInfoItem("Disabilities", disabilities);
                            }

                            loadContacts(user.getUid());

                        } else {
                            addInfoItem("Staff ID",
                                    snapshot.child("staffId").getValue(String.class));
                            addInfoItem("Department",
                                    snapshot.child("shift").getValue(String.class));
                            tvLabelContacts.setVisibility(View.GONE);
                            cardContacts.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(ProfileActivity.this, "Error loading profile", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void loadContacts(String userId) {
        mDatabase.child("contacts").child(userId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        containerContacts.removeAllViews();
                        if (snapshot.exists()) {
                            for (DataSnapshot contactSnap : snapshot.getChildren()) {
                                String name  = contactSnap.child("name").getValue(String.class);
                                String phone = contactSnap.child("phone").getValue(String.class);
                                addContactItem(name, phone);
                            }
                        } else {
                            addContactItem("No contacts added", "Tap the + button on Home to add");
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void addInfoItem(String label, String value) {
        View view = LayoutInflater.from(this)
                .inflate(R.layout.item_profile_info, containerInfo, false);
        TextView tvLabel = view.findViewById(R.id.tv_label);
        TextView tvValue = view.findViewById(R.id.tv_value);
        tvLabel.setText(label);
        tvValue.setText(value != null ? value : "Not set");
        containerInfo.addView(view);
    }

    private void addContactItem(String name, String phone) {
        View view = LayoutInflater.from(this)
                .inflate(R.layout.item_profile_info, containerContacts, false);
        TextView tvLabel = view.findViewById(R.id.tv_label);
        TextView tvValue = view.findViewById(R.id.tv_value);
        tvLabel.setText(name);
        tvValue.setText(phone);
        containerContacts.addView(view);
    }

    private Bitmap decodeBase64(String base64Str) {
        try {
            byte[] decodedBytes = Base64.decode(base64Str, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}