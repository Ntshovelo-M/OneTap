package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    private RecyclerView recyclerView;
    private EditText etMessage;
    private ChatAdapter adapter;
    private final List<ChatMessage> messages = new ArrayList<>();
    private DatabaseReference chatRef;
    private String currentUserId;
    private String currentUserName;
    private String myPhone;
    private String contactName;
    private String contactPhone;

    // Alert-chat mode: talking directly to security about a specific SOS, instead of a saved contact.
    private boolean isAlertMode = false;
    private String alertKey;

    private final ActivityResultLauncher<String> smsPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "SEND_SMS permission granted for contact chat");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        alertKey = getIntent().getStringExtra("alertKey");
        isAlertMode = alertKey != null && !alertKey.isEmpty();

        contactName = isAlertMode ? "Campus Security" : getIntent().getStringExtra("contactName");
        contactPhone = getIntent().getStringExtra("contactPhone");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(contactName);
            if (!isAlertMode && contactPhone != null && !contactPhone.isEmpty()) {
                getSupportActionBar().setSubtitle(contactPhone + " (SMS enabled)");
            }
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }
        currentUserId = user.getUid();

        recyclerView = findViewById(R.id.rv_messages);
        etMessage = findViewById(R.id.et_message);
        ImageButton btnSend = findViewById(R.id.btn_send);

        adapter = new ChatAdapter(messages, currentUserId);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        if (!isAlertMode && contactPhone != null && !contactPhone.isEmpty()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                smsPermLauncher.launch(Manifest.permission.SEND_SMS);
            }
        }

        if (isAlertMode) {
            // Talking to security about this specific SOS - no phone/contact setup needed.
            chatRef = FirebaseDatabase.getInstance().getReference()
                    .child("alerts").child(alertKey).child("chat");
            loadMyData(this::listenForMessages);
        } else {
            // 1. Load my data (name and phone)
            loadMyData(() -> {
                // 2. Setup the shared room and listen for messages
                setupChatRoom();
                listenForMessages();
            });
        }

        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void loadMyData(Runnable onComplete) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        FirebaseDatabase.getInstance().getReference().child("users").child(currentUserId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        currentUserName = snapshot.child("name").getValue(String.class);
                        myPhone = snapshot.child("cellNumber").getValue(String.class);
                        if (myPhone == null || myPhone.isEmpty()) {
                            if (user != null && user.getPhoneNumber() != null && !user.getPhoneNumber().isEmpty()) {
                                myPhone = user.getPhoneNumber();
                            } else {
                                myPhone = currentUserId;
                            }
                        }
                        if (currentUserName == null || currentUserName.isEmpty()) currentUserName = "Me";
                        onComplete.run();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        myPhone = currentUserId;
                        currentUserName = "Me";
                        onComplete.run();
                    }
                });
    }

    private void setupChatRoom() {
        String p1 = myPhone.replaceAll("[^0-9]", "");
        String p2 = (contactPhone != null) ? contactPhone.replaceAll("[^0-9]", "") : "";
        
        String[] phones = {p1, p2};
        Arrays.sort(phones);
        String roomId = "room_" + phones[0] + "_" + phones[1];

        chatRef = FirebaseDatabase.getInstance().getReference().child("chats").child(roomId).child("messages");
    }

    private void listenForMessages() {
        if (chatRef == null) return;
        chatRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                messages.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ChatMessage msg = child.getValue(ChatMessage.class);
                    if (msg != null) messages.add(msg);
                }
                adapter.notifyDataSetChanged();
                if (!messages.isEmpty()) {
                    recyclerView.scrollToPosition(messages.size() - 1);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ChatActivity.this, "Failed to load messages", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendMessage() {
        if (chatRef == null) {
            Toast.makeText(this, "Chat not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        ChatMessage message = new ChatMessage(currentUserId, currentUserName, text, System.currentTimeMillis());
        chatRef.push().setValue(message).addOnSuccessListener(unused -> {
            etMessage.setText("");
            if (!isAlertMode && contactPhone != null && !contactPhone.trim().isEmpty()) {
                sendSmsToContact(contactPhone, text);
            }
        }).addOnFailureListener(e -> Toast.makeText(this, "Failed to send", Toast.LENGTH_SHORT).show());
    }

    private void sendSmsToContact(String phone, String text) {
        String smsContent = "[" + currentUserName + " via SafeStep]: " + text;
        new AlertManager(this).sendDirectSMS(phone, smsContent);
    }
}
