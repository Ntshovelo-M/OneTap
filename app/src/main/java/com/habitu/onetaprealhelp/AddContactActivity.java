package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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

public class AddContactActivity extends AppCompatActivity {

    private TextInputEditText etName, etPhone;
    private Button btnSave;
    private LinearLayout containerSavedContacts;
    private DatabaseReference mDatabase;
    private FirebaseAuth mAuth;
    private AlertManager alertManager;
    private String currentUserName = "User";

    private final ActivityResultLauncher<String> smsPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "SMS permission granted! Trusted contacts can receive emergency text alerts.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "SMS permission is required so trusted contacts can receive text alerts.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_contact);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();
        alertManager = new AlertManager(this);

        etName = findViewById(R.id.et_contact_name);
        etPhone = findViewById(R.id.et_contact_phone);
        btnSave = findViewById(R.id.btn_save_contact);
        containerSavedContacts = findViewById(R.id.container_saved_contacts);

        btnSave.setOnClickListener(v -> saveContact());

        checkSmsPermission();
        fetchCurrentUserName();
        loadSavedContacts();
    }

    private void checkSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
        }
    }

    private void fetchCurrentUserName() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            mDatabase.child("users").child(user.getUid()).child("name")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            String name = snapshot.getValue(String.class);
                            if (name != null && !name.trim().isEmpty()) {
                                currentUserName = name;
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {}
                    });
        }
    }

    private void loadSavedContacts() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("contacts").child(user.getUid())
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        containerSavedContacts.removeAllViews();
                        if (snapshot.exists()) {
                            for (DataSnapshot contactSnap : snapshot.getChildren()) {
                                String contactId = contactSnap.getKey();
                                String name = contactSnap.child("name").getValue(String.class);
                                String phone = contactSnap.child("phone").getValue(String.class);
                                addContactItemView(contactId, name, phone);
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(AddContactActivity.this, "Error loading contacts", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void addContactItemView(String contactId, String name, String phone) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_trusted_contact, containerSavedContacts, false);
        TextView tvName = view.findViewById(R.id.tv_contact_name);
        TextView tvPhone = view.findViewById(R.id.tv_contact_phone);
        ImageView btnEdit = view.findViewById(R.id.btn_edit_contact);
        ImageView btnTestSms = view.findViewById(R.id.btn_test_sms);
        ImageView btnDelete = view.findViewById(R.id.btn_delete_contact);

        tvName.setText(name);
        tvPhone.setText(phone);

        if (btnEdit != null) {
            btnEdit.setOnClickListener(v -> showEditContactDialog(contactId, name, phone));
        }

        if (btnTestSms != null) {
            btnTestSms.setOnClickListener(v -> confirmAndSendTestSms(name, phone));
        }

        btnDelete.setOnClickListener(v -> showDeleteConfirmation(contactId, name));

        containerSavedContacts.addView(view);
    }

    private void showEditContactDialog(String contactId, String currentName, String currentPhone) {
        if (isFinishing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Trusted Contact");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final TextInputEditText inputName = new TextInputEditText(this);
        inputName.setHint("Contact Name");
        inputName.setText(currentName);
        layout.addView(inputName);

        final TextInputEditText inputPhone = new TextInputEditText(this);
        inputPhone.setHint("Phone Number");
        inputPhone.setText(currentPhone);
        inputPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        layout.addView(inputPhone);

        builder.setView(layout);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String newName = inputName.getText() != null ? inputName.getText().toString().trim() : "";
            String newPhone = AlertManager.normalizePhoneNumber(
                    inputPhone.getText() != null ? inputPhone.getText().toString() : "");
            if (newName.isEmpty() || newPhone.isEmpty()) {
                Toast.makeText(this, "Enter a name and a valid phone number", Toast.LENGTH_SHORT).show();
                return;
            }
            updateContact(contactId, newName, newPhone);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void updateContact(String contactId, String name, String phone) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Map<String, String> contactData = new HashMap<>();
        contactData.put("name", name);
        contactData.put("phone", phone);

        mDatabase.child("contacts").child(user.getUid()).child(contactId).setValue(contactData)
                .addOnSuccessListener(unused -> Toast.makeText(this, "Contact updated successfully", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed to update contact: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void confirmAndSendTestSms(String name, String phone) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Test Text Message")
                .setMessage("Send a test emergency SMS to " + name + " (" + phone + ") to confirm they can receive text messages?")
                .setPositiveButton("Send Test SMS", (dialog, which) -> {
                    alertManager.sendTestSMS(phone, name, currentUserName, success -> {
                        if (success) {
                            Toast.makeText(this, "Test text message sent to " + name + "!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Unable to send SMS directly. Check permissions.", Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDeleteConfirmation(String contactId, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Contact")
                .setMessage("Remove " + name + " from trusted contacts?")
                .setPositiveButton("Delete", (dialog, which) -> deleteContact(contactId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteContact(String contactId) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("contacts").child(user.getUid()).child(contactId)
                .removeValue()
                .addOnSuccessListener(unused -> Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed to delete contact", Toast.LENGTH_SHORT).show());
    }

    private void saveContact() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String phone = AlertManager.normalizePhoneNumber(
                etPhone.getText() != null ? etPhone.getText().toString() : "");

        if (name.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Enter a name and a valid phone number", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);
        btnSave.setText("Saving...");

        // Check contact limit (Max 5)
        mDatabase.child("contacts").child(user.getUid()).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.getChildrenCount() >= 5) {
                    Toast.makeText(AddContactActivity.this, "Maximum of 5 trusted contacts reached.", Toast.LENGTH_LONG).show();
                    btnSave.setEnabled(true);
                    btnSave.setText("Save Contact");
                } else {
                    performSaveContact(user.getUid(), name, phone);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                btnSave.setEnabled(true);
                btnSave.setText("Save Contact");
                Toast.makeText(AddContactActivity.this, "Error checking contact limit", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performSaveContact(String userId, String name, String phone) {
        String contactId = mDatabase.child("contacts").child(userId).push().getKey();
        Map<String, String> contactData = new HashMap<>();
        contactId = (contactId != null) ? contactId : String.valueOf(System.currentTimeMillis());

        contactData.put("name", name);
        contactData.put("phone", phone);

        mDatabase.child("contacts").child(userId).child(contactId).setValue(contactData)
                .addOnCompleteListener(task -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("Save Contact");
                    if (task.isSuccessful()) {
                        Toast.makeText(this, "Contact saved! They can now receive emergency text messages.", Toast.LENGTH_LONG).show();
                        etName.setText("");
                        etPhone.setText("");
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
                        }
                    } else {
                        Toast.makeText(this, "Error saving contact", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
