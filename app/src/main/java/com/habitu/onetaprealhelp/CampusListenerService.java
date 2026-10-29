package com.habitu.onetaprealhelp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps listening for campus broadcasts and security responses while SafeStep is closed,
 * and turns them into pop-ups (see InAppPopup / PopupActivity).
 */
public class CampusListenerService extends Service {

    private static final String DB_URL = "https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/";
    private static final String CHANNEL = "campus_listener";
    private static final int NOTIF_ID = 4840;
    private static final String PREFS = "campus_listener";
    private static final String KEY_LAST_BROADCAST = "last_broadcast_ts";

    /** What we last knew about each of the student's alerts, to spot what changed. */
    private static class Snap {
        String status, msg, unit, officer, chatText, chatSender, chatSenderId;
        long chatTs;
    }

    private DatabaseReference db;
    private String uid;
    private Query broadcastQuery, myAlertsQuery;
    private ChildEventListener broadcastListener, myAlertsListener;
    private FirebaseAuth.AuthStateListener authListener;
    private final Map<String, Snap> known = new HashMap<>();

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SafeStep is protecting you")
                .setContentText("Watching for campus alerts and security responses")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        if (broadcastListener == null) startListening();
        return START_STICKY;
    }

    private void startListening() {
        db = FirebaseDatabase.getInstance(DB_URL).getReference();
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.contains(KEY_LAST_BROADCAST)) {
            sp.edit().putLong(KEY_LAST_BROADCAST, System.currentTimeMillis()).apply(); // don't replay old ones
        }

        // 1) Campus broadcasts / feed uploads
        broadcastQuery = db.child("schoolAlerts").limitToLast(5);
        broadcastListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String prev) {
                Long ts = s.child("timestamp").getValue(Long.class);
                String msg = s.child("message").getValue(String.class);
                if (ts == null || msg == null) return;
                if (ts <= sp.getLong(KEY_LAST_BROADCAST, 0)) return;
                sp.edit().putLong(KEY_LAST_BROADCAST, ts).apply();
                InAppPopup.show("📢 Campus broadcast", msg);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        broadcastQuery.addChildEventListener(broadcastListener);

        // 2) Responses and messages on this student's own SOS alerts
        myAlertsQuery = db.child("alerts").orderByChild("userId").equalTo(uid).limitToLast(5);
        myAlertsQuery.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot c : snapshot.getChildren()) known.put(c.getKey(), parse(c));
                myAlertsListener = new ChildEventListener() {
                    @Override public void onChildAdded(@NonNull DataSnapshot s, String p) {
                        if (!known.containsKey(s.getKey())) known.put(s.getKey(), parse(s));
                    }
                    @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { handleAlertChange(s); }
                    @Override public void onChildRemoved(@NonNull DataSnapshot s) { known.remove(s.getKey()); }
                    @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                };
                myAlertsQuery.addChildEventListener(myAlertsListener);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        // Stop when the student logs out
        authListener = a -> { if (a.getCurrentUser() == null) { stopForeground(true); stopSelf(); } };
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
    }

    private Snap parse(DataSnapshot s) {
        Snap x = new Snap();
        x.status  = s.child("status").getValue(String.class);
        x.msg     = s.child("responseMessage").getValue(String.class);
        x.unit    = s.child("respondingUnit").getValue(String.class);
        x.officer = s.child("respondingOfficer").getValue(String.class);
        for (DataSnapshot m : s.child("chat").getChildren()) {
            Long t = m.child("timestamp").getValue(Long.class);
            if (t != null && t >= x.chatTs) {
                x.chatTs = t;
                x.chatText = m.child("text").getValue(String.class);
                x.chatSender = m.child("senderName").getValue(String.class);
                x.chatSenderId = m.child("senderId").getValue(String.class);
            }
        }
        return x;
    }

    private static boolean differs(String a, String b) { return a == null ? b != null : !a.equals(b); }

    private void handleAlertChange(DataSnapshot s) {
        Snap now = parse(s);
        Snap before = known.put(s.getKey(), now);
        if (before == null) return;
        if (Boolean.TRUE.equals(s.child("duress").getValue(Boolean.class))) return;   // silent distress: no pop-ups
        if (InAppPopup.isAppInForeground()) return;   // app is open: its own screens already handle this

        if ((now.msg != null || now.unit != null) && (differs(now.msg, before.msg) || differs(now.unit, before.unit))) {
            String text = (now.msg != null && !now.msg.isEmpty()) ? now.msg : "Help is on the way! 🛡️ Stay where you are.";
            if (now.unit != null || now.officer != null) {
                text += "\n\nSecured by: " + (now.unit != null ? now.unit : "")
                        + (now.officer != null ? " (" + now.officer + ")" : "");
            }
            InAppPopup.show("🛡️ Security response", text);
        }
        if ("resolved".equals(now.status) && !"resolved".equals(before.status)) {
            InAppPopup.show("Security marked this resolved", "Open SafeStep to confirm that you're safe.");
        }
        if (now.chatTs > before.chatTs && !uid.equals(now.chatSenderId) && now.chatText != null) {
            InAppPopup.show("💬 Message from " + (now.chatSender != null ? now.chatSender : "Security"), now.chatText);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "SafeStep protection", NotificationManager.IMPORTANCE_MIN));
    }

    @Override
    public void onDestroy() {
        if (broadcastQuery != null && broadcastListener != null) broadcastQuery.removeEventListener(broadcastListener);
        if (myAlertsQuery != null && myAlertsListener != null) myAlertsQuery.removeEventListener(myAlertsListener);
        if (authListener != null) FirebaseAuth.getInstance().removeAuthStateListener(authListener);
        super.onDestroy();
    }
}
