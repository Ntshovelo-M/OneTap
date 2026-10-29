package com.habitu.onetaprealhelp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import android.util.Log;
import java.util.ArrayDeque;

/**
 * "Walk with Me" live tracking.
 *
 * While the walk timer runs this service:
 *  - streams the student's position to /walks/{uid} so security sees them move on the map,
 *  - detects when the student stops moving, and asks "why did you stop?",
 *  - if there is no answer within 1 minute (or the answer is "danger approaching") sends an SOS,
 *  - if the answer is "other" it logs the reason and stops the timer, so no automatic alert follows,
 *  - sends the SOS itself when the timer runs out.
 */
public class WalkTrackingService extends Service {

    public static final String ACTION_START         = "com.habitu.onetaprealhelp.WALK_START";
    public static final String ACTION_STOP          = "com.habitu.onetaprealhelp.WALK_STOP";
    public static final String ACTION_ANSWER_DANGER = "com.habitu.onetaprealhelp.WALK_ANSWER_DANGER";
    public static final String ACTION_ANSWER_OTHER  = "com.habitu.onetaprealhelp.WALK_ANSWER_OTHER";
    public static final String ACTION_CANCEL_SOS    = "com.habitu.onetaprealhelp.WALK_CANCEL_SOS";

    public static final String EXTRA_DURATION_SEC = "duration_sec";
    public static final String EXTRA_REASON       = "reason";
    public static final String EXTRA_DEADLINE     = "deadline";

    /** In-app broadcasts (package-restricted) so the open screens can update. */
    public static final String BROADCAST_PROMPT_CLOSE = "com.habitu.onetaprealhelp.WALK_PROMPT_CLOSE";
    public static final String BROADCAST_WALK_ENDED   = "com.habitu.onetaprealhelp.WALK_ENDED_BY_REASON";
    public static final String BROADCAST_WALK_ALERTED = "com.habitu.onetaprealhelp.WALK_ALERTED";

    // ---- tuning ----
    /** Standing (almost) still this long = "stopped". */
    static final long  STOP_DETECT_MS = 60_000L;
    /** Time the student gets to answer before security is alerted. */
    static final long  RESPONSE_MS    = 60_000L;
    /** Movement smaller than this (or the GPS accuracy, if worse) counts as standing still. */
    static final float STOP_RADIUS_M  = 20f;
    /** Only add a breadcrumb to the trail after moving this far. */
    static final float TRACK_MIN_MOVE_M = 5f;

    private static final String CH_TRACK  = "walk_tracking";
    private static final String CH_PROMPT = "walk_prompt";
    private static final int NOTIF_TRACK  = 4830;
    private static final int NOTIF_PROMPT = 4831;
    private static final String DB_URL = "https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/";

    public static volatile boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;
    private DatabaseReference db, walkRef;
    private AlertManager alertManager;

    private String uid;
    private String userName = "Student";
    private boolean walking = false, promptActive = false, alerted = false;
    private Location anchor, lastTrackLoc, lastKnown;
    private long anchorTime;

    private final Runnable expireRunnable = () -> {
        if (walking && !alerted) triggerAlert("Walk timer ran out and the student did not mark themselves safe");
    };
    private final Runnable noResponseRunnable = () -> {
        if (promptActive && !alerted) triggerAlert("Student stopped moving and did not answer within 1 minute");
    };

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) { stopSelf(); return START_NOT_STICKY; }
        switch (intent.getAction()) {
            case ACTION_START:         startWalk(intent.getIntExtra(EXTRA_DURATION_SEC, 15 * 60)); break;
            case ACTION_STOP:          if (!alerted) endWalkQuietly(); break;
            case ACTION_ANSWER_DANGER: if (promptActive && !alerted) triggerAlert("Student reported: danger approaching"); break;
            case ACTION_ANSWER_OTHER:  if (promptActive && !alerted) answerOther(intent.getStringExtra(EXTRA_REASON)); break;
            case ACTION_CANCEL_SOS:    cancelSos(); break;
        }
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- walk lifecycle

    private void startWalk(int durationSec) {
        // Android requires startForeground() right after startForegroundService(), so do it first.
        createChannels();
        startAsForeground(trackingNotification("Sharing your live location with campus security while your walk timer runs"));

        if (running) return;
        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { stopForeground(true); stopSelf(); return; }

        running = true; walking = true; alerted = false; promptActive = false;
        db = FirebaseDatabase.getInstance(DB_URL).getReference();
        walkRef = db.child("walks").child(uid);
        alertManager = new AlertManager(this);
        fused = LocationServices.getFusedLocationProviderClient(this);

        long now = System.currentTimeMillis();
        Map<String, Object> w = new HashMap<>();
        w.put("userId", uid);
        w.put("name", userName);
        w.put("status", "walking");
        w.put("startedAt", now);
        w.put("endAt", now + durationSec * 1000L);
        walkRef.removeValue();                 // fresh trail for this walk
        walkRef.updateChildren(w);

        db.child("users").child(uid).child("name").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String n = s.getValue(String.class);
                if (n != null && !n.isEmpty()) { userName = n; if (walkRef != null) walkRef.child("name").setValue(n); }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        handler.postDelayed(expireRunnable, durationSec * 1000L);
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        boolean ok = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!ok) return;
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(@NonNull LocationResult result) {
                for (Location l : result.getLocations()) onLocation(l);
            }
        };
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000).build();
        try {
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    private void stopLocationUpdates() {
        if (fused != null && locationCallback != null) fused.removeLocationUpdates(locationCallback);
        locationCallback = null;
    }

    private void onLocation(Location loc) {
        if (!walking || walkRef == null) return;
        long now = System.currentTimeMillis();
        lastKnown = loc;

        // live position
        Map<String, Object> u = new HashMap<>();
        u.put("lat", loc.getLatitude());
        u.put("lng", loc.getLongitude());
        u.put("t", now);
        walkRef.updateChildren(u);

        // breadcrumb trail
        if (lastTrackLoc == null || lastTrackLoc.distanceTo(loc) >= TRACK_MIN_MOVE_M) {
            lastTrackLoc = loc;
            Map<String, Object> p = new HashMap<>();
            p.put("lat", loc.getLatitude());
            p.put("lng", loc.getLongitude());
            p.put("t", now);
            walkRef.child("track").push().setValue(p);
        }

        // stop detection
        if (anchor == null) { anchor = loc; anchorTime = now; return; }
        float threshold = Math.max(STOP_RADIUS_M, loc.hasAccuracy() ? loc.getAccuracy() : 0f);
        if (anchor.distanceTo(loc) > threshold) {
            anchor = loc; anchorTime = now;              // they're moving again
        } else if (!promptActive && now - anchorTime >= STOP_DETECT_MS) {
            onStopDetected();
        }
    }

    // ---------------------------------------------------------------- "why did you stop?"

    private void onStopDetected() {
        promptActive = true;
        long deadline = System.currentTimeMillis() + RESPONSE_MS;

        Map<String, Object> u = new HashMap<>();
        u.put("status", "stopped");
        u.put("stoppedAt", System.currentTimeMillis());
        u.put("promptDeadline", deadline);
        walkRef.updateChildren(u);

        handler.postDelayed(noResponseRunnable, RESPONSE_MS);

        Intent prompt = new Intent(this, WalkStopPromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_DEADLINE, deadline);
        PendingIntent pi = PendingIntent.getActivity(this, 11, prompt,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CH_PROMPT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Why did you stop?")
                .setContentText("Answer within 1 minute or campus security will be alerted")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_PROMPT, n);
        } catch (SecurityException ignored) {}

        vibrate();
        try { startActivity(prompt); } catch (Exception ignored) { /* background launch blocked - notification handles it */ }
    }

    private void answerOther(String reason) {
        if (reason == null || reason.trim().isEmpty()) reason = "Other (no details given)";
        reason = reason.trim();
        long now = System.currentTimeMillis();

        walking = false; promptActive = false;
        handler.removeCallbacks(expireRunnable);
        handler.removeCallbacks(noResponseRunnable);
        stopLocationUpdates();

        // Security can see why the student stopped, and the walk is closed - so no automatic alert follows.
        Map<String, Object> u = new HashMap<>();
        u.put("status", "stopped_other");
        u.put("reason", reason);
        u.put("endedAt", now);
        walkRef.updateChildren(u);

        Map<String, Object> ev = new HashMap<>();
        ev.put("userId", uid);
        ev.put("name", userName);
        ev.put("type", "stopped_other");
        ev.put("reason", reason);
        ev.put("t", now);
        if (lastKnown != null) { ev.put("lat", lastKnown.getLatitude()); ev.put("lng", lastKnown.getLongitude()); }
        db.child("walkEvents").push().setValue(ev);

        cancelPromptNotification();
        sendInApp(BROADCAST_PROMPT_CLOSE);
        sendInApp(BROADCAST_WALK_ENDED);
        shutDown();
    }

    // ---------------------------------------------------------------- alert to security

    private void triggerAlert(String note) {
        if (alerted) return;
        alerted = true; walking = false; promptActive = false;
        handler.removeCallbacks(expireRunnable);
        handler.removeCallbacks(noResponseRunnable);
        stopLocationUpdates();                 // AlertManager takes over live tracking from here

        Map<String, Object> u = new HashMap<>();
        u.put("status", "alert");
        u.put("note", note);
        walkRef.updateChildren(u);

        cancelPromptNotification();
        sendInApp(BROADCAST_PROMPT_CLOSE);
        sendInApp(BROADCAST_WALK_ALERTED);
        vibrate();

        Intent bs = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = bs != null ? bs.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
        int scale = bs != null ? bs.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
        int battery = (int) ((level / (float) scale) * 100);

        alertManager.sendAlert(battery, (alertKey, lat, lng) -> {
            Map<String, Object> extra = new HashMap<>();
            extra.put("note", note);
            extra.put("source", "walk_with_me");
            db.child("alerts").child(alertKey).updateChildren(extra);
            updateTrackingNotificationForSos();
        });
    }

    private void updateTrackingNotificationForSos() {
        Intent cancel = new Intent(this, WalkTrackingService.class).setAction(ACTION_CANCEL_SOS);
        PendingIntent pi = PendingIntent.getService(this, 12, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CH_TRACK)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS sent to campus security")
                .setContentText("Your live location is being shared. Tap \"I'm safe\" if this was a mistake.")
                .setOngoing(true)
                .addAction(0, "I'm safe \u2014 cancel alert", pi)
                .build();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_TRACK, n);
        } catch (SecurityException ignored) {}
    }

    private void cancelSos() {
        if (alertManager != null) alertManager.cancelAlert();
        if (walkRef != null) walkRef.removeValue();
        shutDown();
    }

    // ---------------------------------------------------------------- shutdown helpers

    /** Student pressed "I'm home" - walk over, delete the live trail. */
    private void endWalkQuietly() {
        walking = false; promptActive = false;
        handler.removeCallbacks(expireRunnable);
        handler.removeCallbacks(noResponseRunnable);
        stopLocationUpdates();
        if (walkRef != null) walkRef.removeValue();
        cancelPromptNotification();
        sendInApp(BROADCAST_PROMPT_CLOSE);
        shutDown();
    }

    private void shutDown() {
        running = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notifications / misc

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_TRACK, "Walk with Me tracking", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel prompt = new NotificationChannel(CH_PROMPT, "Walk with Me - are you OK?", NotificationManager.IMPORTANCE_HIGH);
        prompt.enableVibration(true);
        nm.createNotificationChannel(prompt);
    }

    private Notification trackingNotification(String text) {
        return new NotificationCompat.Builder(this, CH_TRACK)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Walk with Me is active")
                .setContentText(text)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void startAsForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_TRACK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_TRACK, n);
        }
    }

    private void cancelPromptNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_PROMPT);
    }

    private void sendInApp(String action) {
        sendBroadcast(new Intent(action).setPackage(getPackageName()));
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1));
            else v.vibrate(new long[]{0, 300, 150, 300}, -1);
        }
    }
}
