package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If a session already exists (from a previous install session), skip the welcome
        // screen entirely and let LoginActivity's own auto-redirect send them straight in.
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_welcome);

        Button btnHaveAccount = findViewById(R.id.btn_have_account);
        Button btnNewHere = findViewById(R.id.btn_new_here);

        btnHaveAccount.setOnClickListener(v ->
                startActivity(new Intent(WelcomeActivity.this, LoginActivity.class)));

        btnNewHere.setOnClickListener(v ->
                startActivity(new Intent(WelcomeActivity.this, RegisterActivity.class)));
    }
}