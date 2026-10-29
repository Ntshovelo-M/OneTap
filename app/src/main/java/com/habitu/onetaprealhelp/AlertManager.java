package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.content.SharedPreferences;

public class AlertManager {

    private static final String TAG = "AlertManager";
    private static final String INFOBIP_API_KEY = BuildConfig.INFOBIP_API_KEY;
    private static final String INFOBIP_API_URL = "https://qdn1zr.api.infobip.com/sms/2/text/advanced";
    private final Context context;
    private final FusedLocationProviderClient fusedLocationClient;
    private final DatabaseReference mDatabase;
    private String currentAlertKey;
    private LocationCallback locationCallback;
    private Double lastTrackLat = null, lastTrackLng = null;
    // ---- one-alert-at-a-time guard ----
    private static volatile String activeAlertKey = null;   // alert currently open in this app
    private static volatile boolean sending = false;        // an alert is being created right now
    private static volatile boolean duress = false;         // student sent the silent distress signal
    /** Minimum gap between two alerts. Change this number to taste. */
    private static final long ALERT_COOLDOWN_MS = 60_000L;
    private static final String GUARD_PREFS = "alert_guard";
    private static final String KEY_LAST_SENT = "last_sent_at";

    /** Returns a message if a new alert must NOT be sent right now, or null if it's fine. */
    public static String sendBlockedReason(Context c) {
        if (sending || activeAlertKey != null) {
            return "You already have an active alert. Tap the green button if you want to cancel it.";
        }
        long last = c.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SENT, 0);
        long waitMs = ALERT_COOLDOWN_MS - (System.currentTimeMillis() - last);
        if (waitMs > 0) {
            return "Please wait " + (waitMs / 1000 + 1) + " seconds before sending another alert.";
        }
        return null;
    }

    public boolean isDuressActive() { return duress; }
    public void setDuress(boolean value) { duress = value; }

    /** Re-attach to an alert that is still open (e.g. after the app was closed and reopened). */
    public void resumeAlert(String key) {
        currentAlertKey = key;
        activeAlertKey = key;
        lastTrackLat = null; lastTrackLng = null;
        startHighAccuracyTracking();
    }

    /** Alert is over (resolved by security): stop tracking and unlock the SOS button, WITHOUT changing its status. */
    public void finishAlert() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        currentAlertKey = null;
        activeAlertKey = null;
        duress = false;
    }

    /**
     * Silent distress: to anyone watching the phone this looks like a cancel, but the alert stays open,
     * live tracking keeps running, and security is told the student is still in serious danger.
     */
    public void signalDuress() {
        if (currentAlertKey == null) return;
        duress = true;
        Map<String, Object> u = new HashMap<>();
        u.put("duress", true);
        u.put("duressAt", System.currentTimeMillis());
        u.put("urgency", "high");
        u.put("note", "SILENT DISTRESS: student pretended to cancel and is still in serious danger");
        mDatabase.child("alerts").child(currentAlertKey).updateChildren(u);
    }

    public interface AlertListener {
        void onAlertSent(String alertKey, double lat, double lng);
        default void onAlertError(String message) {}
    }

    public interface SmsCallback {
        void onResult(boolean success);
    }

    public AlertManager(Context context) {
        this.context = context;
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        this.mDatabase = FirebaseDatabase.getInstance(
                "https://onetaprealhelp-ab232-default-rtdb.firebaseio.com/").getReference();
    }

    public void sendAlert(int batteryPct, AlertListener listener) {
        String blocked = sendBlockedReason(context);
        if (blocked != null) {
            if (listener != null) listener.onAlertError(blocked);
            return;
        }
        sending = true;
        context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_SENT, System.currentTimeMillis()).apply();
        // safety net: never leave the app stuck in "sending" if something goes wrong
        new Handler(Looper.getMainLooper()).postDelayed(() -> sending = false, 30_000L);

        startHighAccuracyTracking();

        String userId = FirebaseAuth.getInstance().getUid();

        boolean hasLocationPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasLocationPermission) {
            proceedWithUserLookup(userId, "Location permission disabled", 0, 0, batteryPct, listener);
            return;
        }

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            double lat = location.getLatitude();
                            double lng = location.getLongitude();
                            String address = getStreetAddress(lat, lng);
                            proceedWithUserLookup(userId, address, lat, lng, batteryPct, listener);
                        } else {
                            fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                                if (lastLoc != null) {
                                    double lat = lastLoc.getLatitude();
                                    double lng = lastLoc.getLongitude();
                                    String address = getStreetAddress(lat, lng);
                                    proceedWithUserLookup(userId, address, lat, lng, batteryPct, listener);
                                } else {
                                    proceedWithUserLookup(userId, "Location unavailable", 0, 0, batteryPct, listener);
                                }
                            }).addOnFailureListener(e -> proceedWithUserLookup(userId, "Location unavailable", 0, 0, batteryPct, listener));
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "getCurrentLocation failed: " + e.getMessage());
                        fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                            if (lastLoc != null) {
                                double lat = lastLoc.getLatitude();
                                double lng = lastLoc.getLongitude();
                                String address = getStreetAddress(lat, lng);
                                proceedWithUserLookup(userId, address, lat, lng, batteryPct, listener);
                            } else {
                                proceedWithUserLookup(userId, "Location unavailable", 0, 0, batteryPct, listener);
                            }
                        }).addOnFailureListener(e2 -> proceedWithUserLookup(userId, "Location unavailable", 0, 0, batteryPct, listener));
                    });
        } catch (SecurityException se) {
            proceedWithUserLookup(userId, "Location unavailable", 0, 0, batteryPct, listener);
        }
    }

    private void proceedWithUserLookup(String userId, String address, double lat, double lng, int batteryPct, AlertListener listener) {
        if (userId == null) {
            _proceedWithAlert("anonymous", "Anonymous User", null, address, lat, lng, batteryPct, listener);
            return;
        }

        mDatabase.child("users").child(userId).get().addOnCompleteListener(task -> {
            String name = "User";
            String cell = "";
            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                String fetchedName = task.getResult().child("name").getValue(String.class);
                if (fetchedName != null && !fetchedName.trim().isEmpty()) {
                    name = fetchedName;
                }
                cell = task.getResult().child("cellNumber").getValue(String.class);
            }
            _proceedWithAlert(userId, name, cell, address, lat, lng, batteryPct, listener);
            fetchContactsAndSendSMS(userId, name, address, lat, lng, batteryPct);
        });
    }

    private String getStreetAddress(double lat, double lng) {
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                if (addr.getAddressLine(0) != null && !addr.getAddressLine(0).trim().isEmpty()) {
                    return addr.getAddressLine(0);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Geocoding error: " + e.getMessage());
        }
        return String.format(Locale.getDefault(), "Exact Coordinates: %.6f, %.6f", lat, lng);
    }

    private void _proceedWithAlert(String userId, String name, String cell, String address, double lat, double lng, int batteryPct, AlertListener listener) {
        // --- COVERAGE CHECK ---
        float distanceMeters = CoverageZone.metersOutside(lat, lng);

        if (distanceMeters > 0) { // Outside the 2km radius defined in CoverageZone
            sending = false;
            if (listener != null) {
                listener.onAlertError(CoverageZone.outsideMessage(distanceMeters));
            }
            return;
        }

        currentAlertKey = mDatabase.child("alerts").push().getKey();
        lastTrackLat = null; lastTrackLng = null;

        Map<String, Object> alertData = new HashMap<>();
        alertData.put("key", currentAlertKey);
        alertData.put("userId", userId);
        alertData.put("name", name);
        alertData.put("address", address);
        if (cell != null && !cell.isEmpty()) alertData.put("cellNumber", cell);
        alertData.put("lat", lat);
        alertData.put("lng", lng);
        alertData.put("battery", batteryPct);
        alertData.put("timestamp", System.currentTimeMillis());
        alertData.put("status", "active");
        alertData.put("urgency", "high"); // default: an SOS is assumed high until the student says otherwise

        if (currentAlertKey != null) {
            final String createdKey = currentAlertKey;
            mDatabase.child("alerts").child(createdKey).setValue(alertData)
                    .addOnSuccessListener(aVoid -> {
                        sending = false;
                        activeAlertKey = createdKey;
                        if (listener != null) {
                            listener.onAlertSent(createdKey, lat, lng);
                        }
                    })
                    .addOnFailureListener(e -> {
                        sending = false;
                        activeAlertKey = null;
                        currentAlertKey = null;
                        context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
                                .edit().putLong(KEY_LAST_SENT, 0).apply();   // failed, so don't make them wait
                        if (listener != null) listener.onAlertError("Couldn't send the alert. Check your connection and try again.");
                    });
        }
        }


    private void startHighAccuracyTracking() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    updateLiveLocation(location.getLatitude(), location.getLongitude());
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, context.getMainLooper());
        }
    }

    private void updateLiveLocation(double lat, double lng) {
        if (currentAlertKey != null) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("lat", lat);
            updates.put("lng", lng);
            updates.put("timestamp", System.currentTimeMillis());
            updates.put("locUpdatedAt", System.currentTimeMillis());
            mDatabase.child("alerts").child(currentAlertKey).updateChildren(updates);

            // Breadcrumb trail so security can see the direction the student is moving in.
            // Only add a point once they've moved ~5 m, to keep the trail small and clean.
            boolean moved = true;
            if (lastTrackLat != null && lastTrackLng != null) {
                float[] d = new float[1];
                Location.distanceBetween(lastTrackLat, lastTrackLng, lat, lng, d);
                moved = d[0] >= 5f;
            }
            if (moved) {
                lastTrackLat = lat; lastTrackLng = lng;
                Map<String, Object> point = new HashMap<>();
                point.put("lat", lat);
                point.put("lng", lng);
                point.put("t", System.currentTimeMillis());
                mDatabase.child("alerts").child(currentAlertKey).child("track").push().setValue(point);
            }
        }
        
        String userId = FirebaseAuth.getInstance().getUid();
        if (userId != null) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("lat", lat);
            loc.put("lng", lng);
            loc.put("timestamp", System.currentTimeMillis());
            mDatabase.child("locationUpdates").child(userId).setValue(loc);
        }
    }

    public void fetchContactsAndSendSMS(String userId, String userName, String address, double lat, double lng, int batteryPct) {
        if (userId == null || "anonymous".equals(userId)) return;

        mDatabase.child("contacts").child(userId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Log.w(TAG, "No trusted contacts configured for user: " + userId);
                    return;
                }

                String message = buildEmergencyMessage(userName, address, lat, lng, batteryPct);
                int attempted = 0;
                int sent = 0;

                for (DataSnapshot contactSnap : snapshot.getChildren()) {
                    String phone = firstNonEmpty(
                            contactSnap.child("phone").getValue(String.class),
                            contactSnap.child("cellNumber").getValue(String.class),
                            contactSnap.child("number").getValue(String.class),
                            contactSnap.child("cell").getValue(String.class));
                    String contactName = contactSnap.child("name").getValue(String.class);
                    if (phone == null || phone.trim().isEmpty()) continue;
                    attempted++;
                    Log.i(TAG, "Sending emergency SMS to " + (contactName != null ? contactName : "contact") + " (" + phone + ")");
                    if (sendDirectSMS(phone, message)) sent++;
                }

                final int sentCount = sent;
                final int attemptedCount = attempted;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (sentCount > 0) {
                        Toast.makeText(context.getApplicationContext(),
                                "SOS text sent to " + sentCount + (sentCount == 1 ? " contact" : " contacts"),
                                Toast.LENGTH_LONG).show();
                    } else if (attemptedCount > 0) {
                        Toast.makeText(context.getApplicationContext(),
                                "Could not send SMS to trusted contacts. Allow SMS permission and check the numbers.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Database error loading contacts for SMS: " + error.getMessage());
            }
        });
    }

    public static String buildEmergencyMessage(String userName, String address, double lat, double lng, int batteryPct) {
        StringBuilder sb = new StringBuilder();
        sb.append("EMERGENCY SOS: ").append(userName).append(" needs urgent help!\n");
        sb.append("Battery: ").append(batteryPct).append("%\n");
        if (address != null && !address.trim().isEmpty() && !address.startsWith("Coordinates: 0.0, 0.0") && !address.contains("Location unavailable")) {
            sb.append("Location: ").append(address).append("\n");
        }
        if (lat != 0 || lng != 0) {
            sb.append("Map: https://maps.google.com/?q=").append(lat).append(",").append(lng);
        } else {
            sb.append("GPS: Unavailable. Please call or locate them immediately!");
        }
        return sb.toString();
    }

    public boolean sendDirectSMS(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            return false;
        }

        String cleanPhone = normalizePhoneNumber(phoneNumber);
        if (cleanPhone.isEmpty()) {
            Log.w(TAG, "Could not normalize phone number: " + phoneNumber);
            return false;
        }

        boolean sentNative = false;

        // 1. Primary Dispatch: Send directly via Android SmsManager if SEND_SMS permission is granted
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                SmsManager smsManager = resolveSmsManager();
                if (smsManager != null) {
                    dispatchSms(smsManager, cleanPhone, message);
                    Log.i(TAG, "Native device SMS sent successfully to " + cleanPhone);
                    sentNative = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Native SmsManager dispatch failed: " + e.getMessage(), e);
            }
        }

        // 2. Secondary / Cloud Fallback: Attempt Infobip gateway in background
        new Thread(() -> {
            try {
                String authHeader = "App " + INFOBIP_API_KEY;

                java.net.URL url = new java.net.URL(INFOBIP_API_URL);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", authHeader);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                String jsonBody = "{" +
                        "\"messages\":[" +
                            "{" +
                                "\"destinations\":[{\"to\":\"" + cleanPhone + "\"}]," +
                                "\"from\":\"OneTapHelp\"," +
                                "\"text\":\"" + message.replace("\"", "\\\"").replace("\n", "\\n") + "\"" +
                            "}" +
                        "]" +
                    "}";

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    Log.i(TAG, "Infobip SMS successfully sent to " + cleanPhone);
                } else {
                    Log.e(TAG, "Infobip SMS response code: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Infobip SMS exception for " + cleanPhone + ": " + e.getMessage());
            }
        }).start();

        return sentNative;
    }

    private void dispatchSms(SmsManager smsManager, String phone, String message) {
        ArrayList<String> parts = smsManager.divideMessage(message);
        if (parts != null && parts.size() > 1) {
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null);
        } else {
            String body = (parts != null && !parts.isEmpty()) ? parts.get(0) : message;
            smsManager.sendTextMessage(phone, null, body, null, null);
        }
    }

    private SmsManager resolveSmsManager() {
        SmsManager manager = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager = context.getApplicationContext().getSystemService(SmsManager.class);
            }
        } catch (Exception ignored) {}

        if (manager == null) {
            try {
                manager = SmsManager.getDefault();
            } catch (Exception e) {
                Log.e(TAG, "SmsManager.getDefault() failed", e);
            }
        }

        try {
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        manager = manager.createForSubscriptionId(subId);
                    } else {
                        manager = SmsManager.getSmsManagerForSubscriptionId(subId);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not bind SMS to the default SIM: " + e.getMessage());
        }
        return manager;
    }

    /**
     * Converts local South African numbers (e.g. 0821234567) to E.164 (+27821234567)
     * so the carrier actually delivers the message.
     */
    public static String normalizePhoneNumber(String raw) {
        if (raw == null) return "";
        String digits = raw.trim().replaceAll("[\\s\\-().]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        String plusKept = digits.startsWith("+") ? "+" + digits.substring(1).replaceAll("[^0-9]", "") : digits.replaceAll("[^0-9]", "");
        if (plusKept.isEmpty() || plusKept.equals("+")) return "";

        if (plusKept.startsWith("+")) {
            return plusKept;
        }
        // South Africa local mobile: 06/07/08 + 8 digits
        if (plusKept.matches("0[6-8]\\d{8}")) {
            return "+27" + plusKept.substring(1);
        }
        if (plusKept.matches("27\\d{9}")) {
            return "+" + plusKept;
        }
        return plusKept;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    public void sendTestSMS(String phoneNumber, String contactName, String userName, SmsCallback callback) {
        String testMessage = "[ONETAP] Test Alert: " + (contactName != null ? contactName : "Contact") +
                ", you are listed as a trusted emergency contact for " + (userName != null ? userName : "User") +
                ". You will receive emergency text messages if they trigger an SOS.";
        boolean sent = sendDirectSMS(phoneNumber, testMessage);
        if (callback != null) {
            callback.onResult(sent);
        }
    }

    /**
     * Lets the student adjust how urgent the situation really is, after the SOS
     * has already gone out. Never blocks/delays the initial alert.
     */
    public void updateUrgency(String urgency) {
        if (currentAlertKey != null) {
            mDatabase.child("alerts").child(currentAlertKey).child("urgency").setValue(urgency);
        }
    }

    /** The student confirming (or denying) that security's "resolved" mark is accurate. */
    public void confirmResolution(String alertKey, boolean actuallyResolved) {
        if (alertKey == null) return;
        Map<String, Object> updates = new HashMap<>();
        updates.put("studentConfirmedResolved", actuallyResolved);
        if (!actuallyResolved) {
            // Student says it's NOT resolved -> reopen it so security sees it as active again
            updates.put("status", "active");
        }
        mDatabase.child("alerts").child(alertKey).updateChildren(updates);
    }

    public void cancelAlert() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        if (currentAlertKey != null) {
            mDatabase.child("alerts").child(currentAlertKey).child("status").setValue("cancelled");
            currentAlertKey = null;
        }
        activeAlertKey = null;
        duress = false;
    }
    }
