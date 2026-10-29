package com.habitu.onetaprealhelp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/** Pop-up shown when WalkTrackingService notices the student has stopped moving. */
public class WalkStopPromptActivity extends AppCompatActivity {

    private TextView tvCountdown;
    private Button btnDanger, btnOther, btnSend;
    private EditText etOther;
    private View otherBox;
    private CountDownTimer timer;

    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_walk_stop_prompt);
        setFinishOnTouchOutside(false);

        // Show over the lock screen so the student sees it even with the phone locked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        tvCountdown = findViewById(R.id.tv_prompt_countdown);
        btnDanger   = findViewById(R.id.btn_prompt_danger);
        btnOther    = findViewById(R.id.btn_prompt_other);
        btnSend     = findViewById(R.id.btn_prompt_send);
        etOther     = findViewById(R.id.et_prompt_other);
        otherBox    = findViewById(R.id.box_prompt_other);

        btnDanger.setOnClickListener(v -> {
            startService(new Intent(this, WalkTrackingService.class).setAction(WalkTrackingService.ACTION_ANSWER_DANGER));
            tvCountdown.setText("Alerting campus security now\u2026");
            disableAll();
            finishSoon();
        });

        btnOther.setOnClickListener(v -> {
            otherBox.setVisibility(View.VISIBLE);
            btnOther.setVisibility(View.GONE);
            etOther.requestFocus();
        });

        btnSend.setEnabled(false);
        etOther.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { btnSend.setEnabled(s.toString().trim().length() > 0); }
        });
        btnSend.setOnClickListener(v -> {
            String reason = etOther.getText().toString().trim();
            if (reason.isEmpty()) return;
            // Answering stops the walk timer, so no automatic alert will be sent.
            startService(new Intent(this, WalkTrackingService.class)
                    .setAction(WalkTrackingService.ACTION_ANSWER_OTHER)
                    .putExtra(WalkTrackingService.EXTRA_REASON, reason));
            finish();
        });

        // They must answer - back button is ignored.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { /* ignore */ }
        });

        ContextCompat.registerReceiver(this, closeReceiver,
                new IntentFilter(WalkTrackingService.BROADCAST_PROMPT_CLOSE), ContextCompat.RECEIVER_NOT_EXPORTED);

        startCountdown(getIntent().getLongExtra(WalkTrackingService.EXTRA_DEADLINE, System.currentTimeMillis() + 60_000L));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startCountdown(intent.getLongExtra(WalkTrackingService.EXTRA_DEADLINE, System.currentTimeMillis() + 60_000L));
    }

    private void startCountdown(long deadline) {
        if (timer != null) timer.cancel();
        long left = Math.max(1000, deadline - System.currentTimeMillis());
        timer = new CountDownTimer(left, 1000) {
            @Override public void onTick(long ms) {
                tvCountdown.setText("Alerting campus security in " + (ms / 1000) + " s");
            }
            @Override public void onFinish() {
                tvCountdown.setText("No answer \u2014 campus security has been alerted");
                disableAll();
                finishSoon();
            }
        }.start();
    }

    private void disableAll() {
        btnDanger.setEnabled(false); btnOther.setEnabled(false);
        btnSend.setEnabled(false); etOther.setEnabled(false);
    }

    private void finishSoon() {
        tvCountdown.postDelayed(this::finish, 2500);
    }

    @Override
    protected void onDestroy() {
        if (timer != null) timer.cancel();
        try { unregisterReceiver(closeReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
