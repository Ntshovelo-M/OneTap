package com.habitu.onetaprealhelp;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

public class SosAccessibilityService extends AccessibilityService {

    public static final String ACTION_SOS_TRIGGERED = "com.habitu.onetaprealhelp.SOS_TRIGGERED";
    public static final String ACTION_SOS_RESET = "com.habitu.onetaprealhelp.SOS_RESET";

    private WindowManager windowManager;
    private View floatingButtonView;
    private View sosButtonCircle;
    private View ripple1, ripple2, ripple3, ripple4, ripple5;
    private SharedPreferences prefs;

    private final BroadcastReceiver sosReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_SOS_TRIGGERED.equals(action)) {
                startTriplePulse(android.R.color.holo_green_dark);
            } else if (ACTION_SOS_RESET.equals(action)) {
                startTriplePulse(R.color.ss_red);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action) || 
                       Intent.ACTION_SCREEN_ON.equals(action)) {
                updateFloatingButtonVisibility();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // User just unlocked the device, hide immediately
                removeFloatingButton();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SOS_TRIGGERED);
        filter.addAction(ACTION_SOS_RESET);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        
        ContextCompat.registerReceiver(this, sosReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        
        updateFloatingButtonVisibility();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No specific events to handle
    }

    @Override
    public void onInterrupt() {
        // Handle interruptions
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "REFRESH_VISIBILITY".equals(intent.getAction())) {
            updateFloatingButtonVisibility();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void updateFloatingButtonVisibility() {
        boolean isEnabled = prefs.getBoolean("lock_screen_sos", false) || prefs.getBoolean("floating_sos_enabled", false);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        
        // Use inKeyguardRestrictedInputMode as it's more accurate for "showing" the lock screen
        boolean isLocked = km != null && km.inKeyguardRestrictedInputMode();
        boolean isInteractive = pm != null && pm.isInteractive();

        // Only show if enabled AND device is on the lock screen AND screen is on
        if (isEnabled && isLocked && isInteractive) {
            if (floatingButtonView == null) {
                createFloatingButton();
            } else {
                // If already showing, reset state to red/normal
                startTriplePulse(R.color.ss_red);
            }
        } else {
            removeFloatingButton();
        }
    }

    private void createFloatingButton() {
        if (floatingButtonView != null) return;

        floatingButtonView = LayoutInflater.from(this).inflate(R.layout.sos_button_layout, null);
        
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        sosButtonCircle = floatingButtonView.findViewById(R.id.widget_sos_button);
        ripple1 = floatingButtonView.findViewById(R.id.ripple_1);
        ripple2 = floatingButtonView.findViewById(R.id.ripple_2);
        ripple3 = floatingButtonView.findViewById(R.id.ripple_3);
        ripple4 = floatingButtonView.findViewById(R.id.ripple_4);
        ripple5 = floatingButtonView.findViewById(R.id.ripple_5);

        startTriplePulse(R.color.ss_red);
        
        sosButtonCircle.setOnClickListener(v -> triggerSos()); // Standard click listener as fallback
        
        sosButtonCircle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isMoving = false;
            private long touchDownTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchDownTime = System.currentTimeMillis();
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true;
                            params.x = initialX + dx;
                            params.y = initialY + dy;
                            windowManager.updateViewLayout(floatingButtonView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long duration = System.currentTimeMillis() - touchDownTime;
                        if (!isMoving && duration < 500) {
                            vibrate(); // Instant haptic feedback
                            v.performClick(); // Triggers triggerSos() via onClickListener
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingButtonView, params);
    }

    private void removeFloatingButton() {
        if (floatingButtonView != null) {
            windowManager.removeView(floatingButtonView);
            floatingButtonView = null;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Volume button shortcuts removed as requested
        return super.onKeyEvent(event);
    }

    private void triggerSos() {
        CoverageZone.check(this, (inside, known, metersOutside) -> {
            if (inside || !known) {
                new Handler(Looper.getMainLooper()).post(this::triggerSosInsideZone);
            } else {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(SosAccessibilityService.this,
                                "You're outside the SafeStep coverage area - SOS can't be sent. Call 10111 or 112 if in danger.",
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void triggerSosInsideZone() {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(SosAccessibilityService.this, "Triggering SOS Alert...", Toast.LENGTH_SHORT).show());
        
        AlertManager alertManager = new AlertManager(this);
        
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
        int batteryPct = (int) ((level / (float) scale) * 100);

        alertManager.sendAlert(batteryPct, new AlertManager.AlertListener() {
            @Override
            public void onAlertSent(String alertKey, double lat, double lng) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(SosAccessibilityService.this, "SOS Alert Sent successfully!", Toast.LENGTH_LONG).show();
                    startTriplePulse(android.R.color.holo_green_dark);

                    // Broadcast to HomeActivity
                    Intent triggerIntent = new Intent(ACTION_SOS_TRIGGERED);
                    triggerIntent.setPackage(getPackageName());
                    sendBroadcast(triggerIntent);

                    // Reset back to red after 30 seconds
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startTriplePulse(R.color.ss_red);
                        // Broadcast reset to HomeActivity
                        Intent resetIntent = new Intent(ACTION_SOS_RESET);
                        resetIntent.setPackage(getPackageName());
                        sendBroadcast(resetIntent);
                    }, 30000);
                });
            }

            @Override
            public void onAlertError(String message) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(SosAccessibilityService.this, "Failed to send SOS: " + message, Toast.LENGTH_LONG).show();
                    startTriplePulse(R.color.ss_red);
                });
            }
        });
    }

    private void startTriplePulse(int colorResId) {
        if (sosButtonCircle == null || ripple1 == null || ripple2 == null || ripple3 == null || ripple4 == null || ripple5 == null) return;

        int color = ContextCompat.getColor(this, colorResId);
        
        sosButtonCircle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ripple1.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ripple2.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ripple3.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ripple4.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ripple5.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));

        animateRipple(ripple1, 0);
        animateRipple(ripple2, 500);
        animateRipple(ripple3, 1000);
        animateRipple(ripple4, 1500);
        animateRipple(ripple5, 2000);
    }

    private void animateRipple(View ripple, long delay) {
        ripple.clearAnimation();
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.pulse);
        anim.setStartOffset(delay);
        ripple.startAnimation(anim);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeFloatingButton();
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(100);
            }
        }
    }
}
