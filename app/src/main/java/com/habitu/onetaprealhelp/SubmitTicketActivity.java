package com.habitu.onetaprealhelp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SubmitTicketActivity extends AppCompatActivity {

    private EditText etDescription;
    private View btnAttachPhoto, btnAttachVideo;
    private View previewContainer;
    private ImageView ivPreview;
    private VideoView vvPreview;
    private ImageButton btnRemoveAttachment;
    private Button btnSubmit;
    private ProgressBar progressBar;

    private Uri selectedMediaUri;
    private boolean isVideo = false;

    private final ActivityResultLauncher<Intent> mediaLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    selectedMediaUri = result.getData().getData();
                    showPreview();
                }
            });

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_submit_ticket);

        etDescription = findViewById(R.id.et_description);
        btnAttachPhoto = findViewById(R.id.btn_attach_photo);
        btnAttachVideo = findViewById(R.id.btn_attach_video);
        previewContainer = findViewById(R.id.preview_container);
        ivPreview = findViewById(R.id.iv_preview);
        vvPreview = findViewById(R.id.vv_preview);
        btnRemoveAttachment = findViewById(R.id.btn_remove_attachment);
        btnSubmit = findViewById(R.id.btn_submit);
        progressBar = findViewById(R.id.progress_bar);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().setTint(androidx.core.content.ContextCompat.getColor(this, android.R.color.black));
        }

        btnAttachPhoto.setOnClickListener(v -> {
            isVideo = false;
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            mediaLauncher.launch(intent);
        });

        btnAttachVideo.setOnClickListener(v -> {
            isVideo = true;
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            mediaLauncher.launch(intent);
        });

        btnRemoveAttachment.setOnClickListener(v -> {
            selectedMediaUri = null;
            previewContainer.setVisibility(View.GONE);
        });

        btnSubmit.setOnClickListener(v -> submitTicket());
    }

    private void showPreview() {
        if (selectedMediaUri == null) return;
        previewContainer.setVisibility(View.VISIBLE);
        if (isVideo) {
            ivPreview.setVisibility(View.GONE);
            vvPreview.setVisibility(View.VISIBLE);
            vvPreview.setVideoURI(selectedMediaUri);
            vvPreview.start();
        } else {
            vvPreview.setVisibility(View.GONE);
            ivPreview.setVisibility(View.VISIBLE);
            ivPreview.setImageURI(selectedMediaUri);
        }
    }

    private void submitTicket() {
        String description = etDescription.getText().toString().trim();

        if (description.length() < 11) {
            Toast.makeText(this, R.string.description_too_short, Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnSubmit.setEnabled(false);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            proceedWithSubmission(description);
                        } else {
                            handleError("Auth failed: " + (task.getException() != null ? task.getException().getMessage() : "Unknown"));
                        }
                    });
        } else {
            proceedWithSubmission(description);
        }
    }

    private void proceedWithSubmission(String description) {
        if (selectedMediaUri != null) {
            uploadMediaAndSubmit(description);
        } else {
            saveTicketToDatabase(description, null);
        }
    }

    private void uploadMediaAndSubmit(String description) {
        String fileName = UUID.randomUUID().toString();
        StorageReference storageRef = FirebaseStorage.getInstance().getReference()
                .child("tickets")
                .child(fileName);

        storageRef.putFile(selectedMediaUri)
                .addOnSuccessListener(taskSnapshot -> storageRef.getDownloadUrl()
                        .addOnSuccessListener(uri -> saveTicketToDatabase(description, uri.toString()))
                        .addOnFailureListener(e -> handleError(e.getMessage())))
                .addOnFailureListener(e -> handleError(e.getMessage()));
    }

    private void saveTicketToDatabase(String description, String mediaUrl) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) uid = "anonymous";

        DatabaseReference ticketsRef = FirebaseDatabase.getInstance().getReference().child("tickets").push();
        String ticketId = ticketsRef.getKey();
        if (ticketId == null) {
            ticketId = String.valueOf(System.currentTimeMillis());
        }

        Map<String, Object> ticketData = new HashMap<>();
        ticketData.put("ticketId", ticketId);
        ticketData.put("userId", uid);
        ticketData.put("description", description);
        ticketData.put("mediaUrl", mediaUrl != null ? mediaUrl : "");
        ticketData.put("timestamp", System.currentTimeMillis());
        ticketData.put("status", "open");
        ticketData.put("type", isVideo ? "video" : "photo");

        ticketsRef.setValue(ticketData)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(SubmitTicketActivity.this, R.string.ticket_submitted, Toast.LENGTH_LONG).show();
                    finish();
                })
                .addOnFailureListener(e -> handleError(e.getMessage()));
    }

    @SuppressLint("StringFormatInvalid")
    private void handleError(String message) {
        progressBar.setVisibility(View.GONE);
        btnSubmit.setEnabled(true);
        Toast.makeText(this, getString(R.string.ticket_error, message), Toast.LENGTH_LONG).show();
    }
}