package com.habitu.onetaprealhelp;

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Watches the {@code schoolAlerts} node in Firebase and shows an {@link AlertDialog}
 * pop-up whenever a student submits a new campus broadcast — exactly mirroring the
 * pop-up that students already see when security responds to an SOS alert.
 *
 * <p>Usage in any security Activity:
 * <pre>
 *   private CampusAlertNotifier campusAlertNotifier;
 *
 *   &#64;Override protected void onStart() {
 *       super.onStart();
 *       campusAlertNotifier = new CampusAlertNotifier(this);
 *       campusAlertNotifier.start();
 *   }
 *
 *   &#64;Override protected void onStop() {
 *       super.onStop();
 *       if (campusAlertNotifier != null) campusAlertNotifier.stop();
 *   }
 * </pre>
 */
public class CampusAlertNotifier {

    /** SharedPreferences key that stores the timestamp of the last alert we already notified for. */
    private static final String PREFS_NAME  = "campus_alert_notifier";
    private static final String KEY_LAST_TS = "last_seen_timestamp";

    private final Activity activity;
    private final DatabaseReference alertsRef;
    private ValueEventListener listener;

    public CampusAlertNotifier(Activity activity) {
        this.activity  = activity;
        this.alertsRef = FirebaseDatabase.getInstance().getReference("schoolAlerts");
    }

    /** Attach the Firebase listener. Call from {@code onStart()}. */
    public void start() {
        if (listener != null) return; // already attached

        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                long lastSeen = getLastSeenTimestamp();
                long latestTs = lastSeen;

                String latestMessage = null;
                long   latestMsgTs   = 0;

                // Walk every child to find the newest alert the officer hasn't seen yet.
                for (DataSnapshot child : snapshot.getChildren()) {
                    Long   ts  = child.child("timestamp").getValue(Long.class);
                    String msg = child.child("message").getValue(String.class);

                    if (ts == null || msg == null) continue;

                    if (ts > latestTs) latestTs = ts;         // track overall newest

                    if (ts > lastSeen && ts > latestMsgTs) {  // newer than last-seen
                        latestMsgTs   = ts;
                        latestMessage = msg;
                    }
                }

                if (latestMessage != null) {
                    final String  finalMsg = latestMessage;
                    final long    finalTs  = latestMsgTs;
                    final long    newLast  = latestTs;

                    // Save before showing so rapid re-triggers don't double-pop.
                    saveLastSeenTimestamp(newLast);

                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;

                        String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(new Date(finalTs));

                        new AlertDialog.Builder(activity)
                                .setTitle("📢 CAMPUS BROADCAST")
                                .setMessage(finalMsg + "\n\n— Submitted at " + timeStr)
                                .setPositiveButton("VIEW FEED", (d, w) -> {
                                    activity.startActivity(
                                            new android.content.Intent(activity, BroadcastFeedActivity.class));
                                })
                                .setNegativeButton("DISMISS", null)
                                .setCancelable(true)
                                .show();
                    });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Silently ignore — security officers can still manually open the feed.
            }
        };

        alertsRef.addValueEventListener(listener);
    }

    /** Detach the Firebase listener. Call from {@code onStop()}. */
    public void stop() {
        if (listener != null) {
            alertsRef.removeEventListener(listener);
            listener = null;
        }
    }

    // ── SharedPreferences helpers ──────────────────────────────────────────────

    private long getLastSeenTimestamp() {
        return activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                       .getLong(KEY_LAST_TS, System.currentTimeMillis());
    }

    private void saveLastSeenTimestamp(long ts) {
        activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_TS, ts)
                .apply();
    }
}
