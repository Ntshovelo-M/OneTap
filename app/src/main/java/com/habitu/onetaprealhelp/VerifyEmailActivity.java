package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class VerifyEmailActivity extends AppCompatActivity {

    private EditText etCode;
    private Button btnVerify, btnResend;
    private TextView tvInfo, tvStatus;
    private CountDownTimer resendTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verify_email);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { goToLogin(); return; }

        etCode = findViewById(R.id.et_otp_code);
        btnVerify = findViewById(R.id.btn_verify);
        btnResend = findViewById(R.id.btn_resend);
        tvInfo = findViewById(R.id.tv_verify_info);
        tvStatus = findViewById(R.id.tv_verify_status);

        tvInfo.setText("We'll send a 6-digit code to\n" + user.getEmail()
                + "\nOpen your UMP inbox (check spam too). The code expires in 10 minutes.");

        btnVerify.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            if (!code.matches("\\d{6}")) { etCode.setError("Enter the 6-digit code"); return; }
            setBusy(true);
            OtpApi.verifyCode(code, (ok, already, message) -> {
                setBusy(false);
                if (ok) {
                    Toast.makeText(this, "E-mail verified!", Toast.LENGTH_SHORT).show();
                    goToHome();
                } else {
                    tvStatus.setText(message);
                }
            });
        });

        btnResend.setOnClickListener(v -> sendCode());

        findViewById(R.id.tv_verify_logout).setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            goToLogin();
        });

        sendCode();   // send the first code as soon as the screen opens
    }

    private void sendCode() {
        setBusy(true);
        tvStatus.setText("");
        OtpApi.requestCode((ok, already, message) -> {
            setBusy(false);
            if (already) { goToHome(); return; }
            tvStatus.setTextColor(getColor(ok ? R.color.ss_green : R.color.ss_red));
            tvStatus.setText(message);
            startResendCooldown(60);
        });
    }

    private void startResendCooldown(int seconds) {
        if (resendTimer != null) resendTimer.cancel();
        btnResend.setEnabled(false);
        resendTimer = new CountDownTimer(seconds * 1000L, 1000) {
            @Override public void onTick(long ms) { btnResend.setText("Resend code in " + (ms / 1000) + " s"); }
            @Override public void onFinish() { btnResend.setEnabled(true); btnResend.setText("Resend code"); }
        }.start();
    }

    private void setBusy(boolean busy) {
        btnVerify.setEnabled(!busy);
        if (busy) btnResend.setEnabled(false);
    }

    private void goToHome() {
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (resendTimer != null) resendTimer.cancel();
        super.onDestroy();
    }
}
