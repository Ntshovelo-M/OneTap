package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
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
import java.util.List;

public class ChatContactsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private final List<String[]> contacts = new ArrayList<>(); // [name, phone]
    private ContactAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_contacts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Chat with Contact");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.rv_contacts);
        tvEmpty = findViewById(R.id.tv_empty);

        adapter = new ContactAdapter(contacts, contact -> {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("contactName", contact[0]);
            intent.putExtra("contactPhone", contact[1]);
            startActivity(intent);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        loadContacts();
    }

    private void loadContacts() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        DatabaseReference ref = FirebaseDatabase.getInstance().getReference()
                .child("contacts").child(user.getUid());

        ref.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                contacts.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String name = child.child("name").getValue(String.class);
                    String phone = child.child("phone").getValue(String.class);
                    if (name != null && phone != null) {
                        contacts.add(new String[]{name, phone});
                    }
                }
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(contacts.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ChatContactsActivity.this, "Failed to load contacts", Toast.LENGTH_SHORT).show();
            }
        });
    }

    interface OnContactClickListener {
        void onClick(String[] contact);
    }

    static class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ContactViewHolder> {
        private final List<String[]> contacts;
        private final OnContactClickListener listener;

        ContactAdapter(List<String[]> contacts, OnContactClickListener listener) {
            this.contacts = contacts;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_contact_chat, parent, false);
            return new ContactViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
            String[] contact = contacts.get(position);
            holder.tvName.setText(contact[0]);
            holder.tvPhone.setText(contact[1]);
            holder.itemView.setOnClickListener(v -> listener.onClick(contact));
        }

        @Override
        public int getItemCount() { return contacts.size(); }

        static class ContactViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone;

            ContactViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_contact_name);
                tvPhone = itemView.findViewById(R.id.tv_contact_phone);
            }
        }
    }
}
