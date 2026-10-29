package com.habitu.onetaprealhelp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Single source of truth for the SOS coverage area:
 * a 2km circle centered between Luhambo and the UMP Mbombela campus.
 * The SOS trigger is only allowed when the student is inside this circle.
 */
public final class CoverageZone {

    public static final double CAMPUS_LAT = -25.4365196;
    public static final double CAMPUS_LNG = 30.9818024;
    public static final double LUHAMBO_LAT = -25.447930;
    public static final double LUHAMBO_LNG = 30.994180;

    public static final double CENTER_LAT = (CAMPUS_LAT + LUHAMBO_LAT) / 2;
    public static final double CENTER_LNG = (CAMPUS_LNG + LUHAMBO_LNG) / 2;

    public static final float RADIUS_METERS = 2000f; // 2km strict zone

    private CoverageZone() {}

    public static float radiusMeters() {
        return RADIUS_METERS;
    }

    /** Metres beyond the edge of the zone (<= 0 means inside). */
    public static float metersOutside(double lat, double lng) {
        float[] r = new float[1];
        Location.distanceBetween(CENTER_LAT, CENTER_LNG, lat, lng, r);
        return r[0] - RADIUS_METERS;
    }

    public interface Callback {
        /**
         * @param inside          true if inside the zone
         * @param known           false if we could not work out where the person is
         *                        (no permission / no GPS) - callers should fail OPEN so a
         *                        GPS problem never blocks a real emergency.
         * @param metersOutside   distance beyond the zone edge (only meaningful if known && !inside)
         */
        void onResult(boolean inside, boolean known, float metersOutside);
    }

    @SuppressLint("MissingPermission")
    public static void check(Context context, Callback cb) {
        boolean hasPerm = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasPerm) {
            cb.onResult(true, false, 0);
            return;
        }
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            deliver(loc, cb);
                        } else {
                            client.getLastLocation()
                                    .addOnSuccessListener(last -> {
                                        if (last != null) deliver(last, cb);
                                        else cb.onResult(true, false, 0);
                                    })
                                    .addOnFailureListener(e -> cb.onResult(true, false, 0));
                        }
                    })
                    .addOnFailureListener(e -> client.getLastLocation()
                            .addOnSuccessListener(last -> {
                                if (last != null) deliver(last, cb);
                                else cb.onResult(true, false, 0);
                            })
                            .addOnFailureListener(e2 -> cb.onResult(true, false, 0)));
        } catch (SecurityException se) {
            cb.onResult(true, false, 0);
        }
    }

    private static void deliver(Location loc, Callback cb) {
        float out = metersOutside(loc.getLatitude(), loc.getLongitude());
        cb.onResult(out <= 0, true, out);
    }

    public static String outsideMessage(float metersOutside) {
        String dist = metersOutside >= 1000
                ? String.format(java.util.Locale.US, "%.1f km", metersOutside / 1000f)
                : Math.round(metersOutside) + " m";
        return "You are about " + dist + " outside the SafeStep coverage area (Luhambo .. UMP Mbombela campus).\n\n"
                + "The SOS button can only be used inside this area, because campus security cannot reach you from here.\n\n"
                + "If you are in immediate danger, please call the national emergency number 10111 (police) or 112 from your phone.";
    }
}
