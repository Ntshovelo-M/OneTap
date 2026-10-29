package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.android.gms.maps.model.LatLngBounds;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

public class HomeActivity extends AppCompatActivity implements OnMapReadyCallback {

    /** Starts the background listener and asks (once) for the permissions that make pop-ups work when the app is closed. */
    private void setUpBackgroundPopups() {
        ContextCompat.startForegroundService(this, new Intent(this, CampusListenerService.class));

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 501);
        }

        SharedPreferences p = getSharedPreferences("OneTapPrefs", MODE_PRIVATE);
        if (!p.getBoolean("popup_perm_asked", false) && !Settings.canDrawOverlays(this)) {
            p.edit().putBoolean("popup_perm_asked", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Allow pop-ups over other apps")
                    .setMessage("So SafeStep can show you security responses and campus alerts as a pop-up even when you're in another app or your phone is locked, please allow \"Display over other apps\" on the next screen.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Not now", null)
                    .show();
        }
    }


            /** Students must have verified their UMP e-mail before they can use the app. */
            private void enforceEmailVerified() {
                String uid = mAuth.getUid();
                if (uid == null) return;
                mDatabase.child("users").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        String role = s.child("role").getValue(String.class);
                        boolean verified = Boolean.TRUE.equals(s.child("emailVerified").getValue(Boolean.class));
                        if ("Student".equals(role) && !verified) {
                            startActivity(new Intent(HomeActivity.this, VerifyEmailActivity.class));
                            finish();
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            }


    public static final String ACTION_SOS_TRIGGERED = "com.habitu.onetaprealhelp.SOS_TRIGGERED";
    public static final String ACTION_SOS_RESET = "com.habitu.onetaprealhelp.SOS_RESET";

    private DrawerLayout drawerLayout;
    private Button btnSos;
    private View ripple1, ripple2, ripple3, ripple4, ripple5;
    private TextView tvSosHint, tvWelcome, tvLocation, tvLocationStatus, tvActiveSecurity, tvCoordinates;
    private boolean alertFired = false;
    private AlertManager alertManager;
    private GoogleMap mMap;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    
    private static final double CAMPUS_SECURITY_LAT = -25.4365196;
    private static final double CAMPUS_SECURITY_LNG = 30.9818024;
    private static final double LUHAMBO_SECURITY_LAT = -25.447930;
    private static final double LUHAMBO_SECURITY_LNG = 30.994180;
    private static final float SECURITY_RADIUS_METERS = 1500f;

    private String closestSecurityProvider;
    
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastImageBase64 = "";
    private ValueEventListener responseListener;
    private DatabaseReference userRef;
    private ValueEventListener userListener;
    private String currentActiveAlertKey = null;
    private TextView tvCancelAlert;

    private final BroadcastReceiver sosReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SOS_TRIGGERED.equals(intent.getAction())) {
                if (!alertFired) {
                    alertFired = true;
                    updateUiToActiveState();
                }
            } else if (ACTION_SOS_RESET.equals(intent.getAction())) {
                if (alertFired) {
                    alertFired = false;
                    updateUiToIdleState();
                }
            }
        }
    };

    private FusedLocationProviderClient fusedLocationClient;

    private final ActivityResultLauncher<String> smsPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "SMS permission is needed so trusted contacts get an emergency text", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), (Map<String, Boolean> result) -> {
                Boolean fine   = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                Boolean coarse = result.get(Manifest.permission.ACCESS_COARSE_LOCATION);

                if ((fine != null && fine) || (coarse != null && coarse)) {
                    if (mMap != null) enableMyLocation();
                    startLocationUpdates();
                }
                gateThenFire(this::fireAlert);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        alertManager          = new AlertManager(this);
        fusedLocationClient   = LocationServices.getFusedLocationProviderClient(this);
        drawerLayout          = findViewById(R.id.drawer_layout);
        btnSos                = findViewById(R.id.btn_sos);
        ripple1               = findViewById(R.id.ripple_1);
        ripple2               = findViewById(R.id.ripple_2);
        ripple3               = findViewById(R.id.ripple_3);
        ripple4               = findViewById(R.id.ripple_4);
        ripple5               = findViewById(R.id.ripple_5);
        tvSosHint             = findViewById(R.id.tv_sos_hint);
        tvWelcome             = findViewById(R.id.tv_welcome);
        tvLocation            = findViewById(R.id.tv_location);
        tvLocationStatus      = findViewById(R.id.tv_location_status);
        tvActiveSecurity      = findViewById(R.id.tv_active_security);
        tvCoordinates         = findViewById(R.id.tv_coordinates);

        updateClosestSecurity(UMP_MBOMBELA_CAMPUS.latitude, UMP_MBOMBELA_CAMPUS.longitude);
        updateUiToIdleState();

        View cardHomeProfile = findViewById(R.id.card_home_profile);
        if (cardHomeProfile != null) {
            cardHomeProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        btnSos.setOnClickListener(v -> {
            if (alertFired) { confirmCancelAlert(); return; }
            if (alertManager.isDuressActive()) {
                // silent-distress mode: behave like a normal "sent" alert, nothing suspicious on screen
                alertFired = true;
                updateUiToActiveState();
                Toast.makeText(this, "Alert sent", Toast.LENGTH_SHORT).show();
                alertManager.signalDuress();
                return;
            }
            gateThenFire(this::checkPermissionsThenFire);
        });

        tvCancelAlert = findViewById(R.id.tv_cancel_alert);
        if (tvCancelAlert != null) tvCancelAlert.setOnClickListener(v -> confirmCancelAlert());

        setupDrawer();
        setupBottomNav();
        fetchUserData();
        VoiceRecognitionHelper.startIfEnabled(this);
        requestSmsPermissionIfNeeded();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        if (getIntent() != null && getIntent().getBooleanExtra("trigger_sos", false)) {
            gateThenFire(this::checkPermissionsThenFire);
        }
        
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        setUpBackgroundPopups();
        enforceEmailVerified();
        restoreActiveAlert();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("trigger_sos", false)) {
            if (!alertFired) gateThenFire(this::checkPermissionsThenFire);
        }
    }
    /** If the student still has an open alert (app was closed/reopened), show it as active again. */
    private void restoreActiveAlert() {
        String uid = mAuth.getUid();
        if (uid == null || alertFired || currentActiveAlertKey != null) return;
        mDatabase.child("alerts").orderByChild("userId").equalTo(uid).limitToLast(5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot a : snap.getChildren()) {
                            String st = a.child("status").getValue(String.class);
                            Long ts = a.child("timestamp").getValue(Long.class);
                            boolean open = "active".equals(st) || "acknowledged".equals(st);
                            boolean recent = ts != null && System.currentTimeMillis() - ts < 12L * 60 * 60 * 1000;
                            if (!open || !recent) continue;

                            String key = a.getKey();
                            currentActiveAlertKey = key;
                            alertManager.resumeAlert(key);
                            if (Boolean.TRUE.equals(a.child("duress").getValue(Boolean.class))) {
                                alertManager.setDuress(true);        // keep looking cancelled
                                alertFired = false;
                                updateUiToIdleState();
                            } else {
                                alertFired = true;
                                updateUiToActiveState();
                            }
                            listenForSecurityResponse(key);
                            return;
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void fetchUserData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            if (userRef != null && userListener != null) userRef.removeEventListener(userListener);
            userRef = mDatabase.child("users").child(user.getUid());
            userListener = userRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        String name = snapshot.child("name").getValue(String.class);
                        String imageBase64 = snapshot.child("profileImageBase64").getValue(String.class);
                        if (name != null) tvWelcome.setText(getString(R.string.welcome_placeholder, name));
                        if (imageBase64 != null && !imageBase64.equals(lastImageBase64)) {
                            lastImageBase64 = imageBase64;
                            executorService.execute(() -> {
                                Bitmap bitmap = decodeBase64(imageBase64);
                                if (bitmap != null) mainHandler.post(() -> {
                                    ImageView ivHomeProfile = findViewById(R.id.iv_home_profile);
                                    if (ivHomeProfile != null) { ivHomeProfile.setImageBitmap(bitmap); ivHomeProfile.setImageTintList(null); }
                                });
                            });
                        }
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {}
            });
        }
    }

    private Bitmap decodeBase64(String base64Str) {
        try { byte[] decodedBytes = Base64.decode(base64Str, Base64.DEFAULT); return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length); }
        catch (Exception e) { return null; }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setZoomGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        mMap.getUiSettings().setAllGesturesEnabled(true);

        enableMyLocation();
        startLocationUpdates();
        addUnsafeZones();
        addCoverageZone();

        showFullCoverageZone();
    }

    // ── COVERAGE RADIUS (testing phase) ──
    // Confirmed pin: University of Mpumalanga, Mbombela campus.
    private static final LatLng UMP_MBOMBELA_CAMPUS = new LatLng(-25.4365196, 30.9818024);
    // Confirmed pin: Luhambo Students.
    private static final LatLng LUHAMBO_AREA = new LatLng(-25.447930, 30.994180);

    /** Frames the whole blue coverage circle inside the map card so it is always fully visible. */
    private void showFullCoverageZone() {
        if (mMap == null) return;
        double lat = CoverageZone.CENTER_LAT, lng = CoverageZone.CENTER_LNG;
        double r = CoverageZone.radiusMeters();
        double dLat = Math.toDegrees(r / 6371000.0);
        double dLng = Math.toDegrees(r / (6371000.0 * Math.cos(Math.toRadians(lat))));
        LatLngBounds bounds = new LatLngBounds(
                new LatLng(lat - dLat, lng - dLng),
                new LatLng(lat + dLat, lng + dLng));
        float d = getResources().getDisplayMetrics().density;
        int w = getResources().getDisplayMetrics().widthPixels - (int) (40 * d); // card has 20dp margins
        int h = (int) (320 * d);                                                   // card height in the layout
        mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, w, h, (int) (16 * d)));
    }
    /**
     * Shows students, for this testing phase, roughly which area the app/security response
     * currently covers - anchored between Luhambo and the UMP Mbombela campus, not a wide
     * generic mile radius.
     */
    private void addCoverageZone() {
        if (mMap == null) return;

        LatLng center = new LatLng(CoverageZone.CENTER_LAT, CoverageZone.CENTER_LNG);
        double radiusMeters = CoverageZone.radiusMeters();

        mMap.addCircle(new CircleOptions()
                .center(center)
                .radius(radiusMeters)
                .strokeWidth(3f)
                .strokeColor(0xFF1E88E5)
                .fillColor(0x301E88E5));

        mMap.addMarker(new MarkerOptions()
                .position(center)
                .title("Coverage Zone (Testing Phase)")
                .snippet("Luhambo ↔ UMP Mbombela campus")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .alpha(0f)); // invisible pin, just so the title/snippet is tappable at the circle's center
    }

    private Marker studentMarker;

    private void updatePositionOnMapAndUi(double lat, double lng) {
        LatLng current = new LatLng(lat, lng);
        if (mMap != null) {
            if (studentMarker != null) {
                studentMarker.setPosition(current);
            } else {
                studentMarker = mMap.addMarker(new MarkerOptions()
                        .position(current)
                        .title("Student Exact Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            }
        }
        executorService.execute(() -> updateLocationText(lat, lng));
        updateClosestSecurity(lat, lng);
    }

    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener(location -> {
            if (location != null) {
                updatePositionOnMapAndUi(location.getLatitude(), location.getLongitude());
            } else {
                fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                    if (lastLoc != null) {
                        updatePositionOnMapAndUi(lastLoc.getLatitude(), lastLoc.getLongitude());
                    } else {

                        mainHandler.post(() -> {
                            if (tvLocation != null) tvLocation.setText("Location unavailable");
                            if (tvLocationStatus != null) tvLocationStatus.setText(" Turn on GPS and allow location so we can show where you are");
                        });
                    }
                });
            }
        });

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).setMinUpdateIntervalMillis(2000).build();
        fusedLocationClient.requestLocationUpdates(req, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location last = result.getLastLocation();
                if (last != null) {
                    updatePositionOnMapAndUi(last.getLatitude(), last.getLongitude());
                }
            }
        }, Looper.getMainLooper());
    }

    private void updateClosestSecurity(double userLat, double userLng) {
        float[] resultsCampus = new float[1];
        Location.distanceBetween(userLat, userLng, CAMPUS_SECURITY_LAT, CAMPUS_SECURITY_LNG, resultsCampus);
        float distanceCampus = resultsCampus[0];

        float[] resultsLuhambo = new float[1];
        Location.distanceBetween(userLat, userLng, LUHAMBO_SECURITY_LAT, LUHAMBO_SECURITY_LNG, resultsLuhambo);
        float distanceLuhambo = resultsLuhambo[0];

        String provider;
        if (distanceCampus <= distanceLuhambo) {
            provider = "Campus Security";
        } else {
            provider = "Luhambo Security";
        }

        closestSecurityProvider = provider;

        final boolean inside = CoverageZone.metersOutside(userLat, userLng) <= 0;
        final String statusText = inside ? "Secured by " + provider : "Outside coverage area";

        mainHandler.post(() -> {
            if (tvActiveSecurity != null) {
                tvActiveSecurity.setText(statusText);
            }
        });
    }

    public String getClosestSecurityProvider() {
        return closestSecurityProvider;
    }

    // Places students know by name. Checked first, smallest area first.
    private static class Place {
        final String name; final double lat, lng, radius;
        Place(String name, double lat, double lng, double radius) {
            this.name = name; this.lat = lat; this.lng = lng; this.radius = radius;
        }
    }
    private static final Place[] KNOWN_PLACES = {
            new Place("Building 7", -25.434729, 30.980458, 50),
            new Place("Building 6", -25.435388, 30.978055, 50),
            new Place("The Great Hall", -25.432133, 30.976081, 80),
            new Place("ICT LABS", -25.435582, 30.982368, 50),
            new Place("Main Gate (Security Monitored)", -25.437142, 30.981776, 150),
            new Place("N4 Bridge (High Risk)", -25.4385, 30.9780, 250),
            new Place("University of Mpumalanga (UMP) – Mbombela Campus", -25.4365196, 30.9818024, 600),
            new Place("Luhambo", -25.447930, 30.994180, 700),
            new Place("Kamagugu Shopping Centre", -25.451081, 30.996278, 300),
    };

    private long lastGeocodeAt = 0;
    private double lastGeocodeLat = 0, lastGeocodeLng = 0;
    private boolean mainGateAlertShown = false;

    private void updateLocationText(double lat, double lng) {
        long now = System.currentTimeMillis();
        float[] moved = new float[1];
        Location.distanceBetween(lastGeocodeLat, lastGeocodeLng, lat, lng, moved);

        // Geofencing Check: Main Gate
        float[] distToGate = new float[1];
        Location.distanceBetween(lat, lng, -25.437142, 30.981776, distToGate);
        if (distToGate[0] <= 150) {
            if (!mainGateAlertShown) {
                mainGateAlertShown = true;
                mainHandler.post(() -> InAppPopup.show("📍 Safe Zone Entered", 
                    "You have entered the Main Gate area. This zone is under 24/7 high-priority security surveillance."));
            }
        } else {
            mainGateAlertShown = false;
        }

        // Don't hammer the geocoder: only refresh after moving 25 m or after a minute.
        if (lastGeocodeAt != 0 && moved[0] < 25f && now - lastGeocodeAt < 60_000L) return;
        lastGeocodeAt = now; lastGeocodeLat = lat; lastGeocodeLng = lng;

        String place = null, street = null;

        for (Place p : KNOWN_PLACES) {
            float[] d = new float[1];
            Location.distanceBetween(lat, lng, p.lat, p.lng, d);
            if (d[0] <= p.radius) { place = p.name; break; }
        }

        try {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            List<Address> list = geocoder.getFromLocation(lat, lng, 1);
            if (list != null && !list.isEmpty()) {
                Address a = list.get(0);
                street = a.getAddressLine(0);
                if (place == null) {
                    if (a.getSubLocality() != null) place = a.getSubLocality();          // e.g. "West Acres"
                    else if (a.getThoroughfare() != null)
                        place = a.getThoroughfare() + (a.getLocality() != null ? ", " + a.getLocality() : "");
                    else if (a.getLocality() != null) place = a.getLocality();
                }
            }
        } catch (IOException ignored) {}

        if (place == null) place = String.format(Locale.US, "%.5f, %.5f", lat, lng);

        final String finalPlace = place;
        final String finalStreet = street;
        final boolean inside = CoverageZone.metersOutside(lat, lng) <= 0;
        final String time = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());

        mainHandler.post(() -> {
            if (tvLocation != null) tvLocation.setText(finalPlace);
            if (tvCoordinates != null) {
                tvCoordinates.setVisibility(View.VISIBLE);
                tvCoordinates.setText(String.format(Locale.US, "Lat: %.6f, Lon: %.6f", lat, lng));
            }
            if (tvLocationStatus != null) {
                tvLocationStatus.setText(" " + (finalStreet != null ? finalStreet : "Exact position detected")
                        + "\n" + (inside ? "Inside the SafeStep coverage area"
                        : "Outside the SafeStep coverage area – SOS can't be used here")
                        + "\nUpdated " + time);
            }
        });
    }

    private void enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (mMap != null) { mMap.setMyLocationEnabled(true); mMap.getUiSettings().setMyLocationButtonEnabled(true); }
        }
    }

    private void addUnsafeZones() {
        if (mMap == null) return;
        LatLng n4Bridge = new LatLng(-25.4385, 30.9780);
        mMap.addMarker(new MarkerOptions().position(n4Bridge).title("UNSAFE ZONE").snippet("N4 Bridge - High Risk").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        mMap.addMarker(new MarkerOptions().position(new LatLng(-25.4380, 30.9825)).title("UNSAFE ZONE").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
    }

    private void requestSmsPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
        }
    }

    /**
     * SOS is only allowed inside the coverage circle. If we can't tell where the person is
     * (no GPS / permission not granted yet) we let it through - a GPS glitch must never block
     * a real emergency.
     */
    private void gateThenFire(Runnable fire) {
        String blocked = AlertManager.sendBlockedReason(this);
        if (blocked != null) {
            Toast.makeText(this, blocked, Toast.LENGTH_LONG).show();
            return;
        }
        CoverageZone.check(this, (inside, known, metersOutside) -> runOnUiThread(() -> {
            if (inside || !known) fire.run();
            else showOutOfCoverageDialog(metersOutside);
        }));
    }

    private void showOutOfCoverageDialog(float metersOutside) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Outside coverage area")
                .setMessage(CoverageZone.outsideMessage(metersOutside))
                .setPositiveButton("OK", null)
                .setNegativeButton("View coverage map", (d, w) -> {
                    showFullCoverageZone();
                })
                .setCancelable(true)
                .show();
    }

    private void checkPermissionsThenFire() {
        List<String> missingPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.SEND_SMS);
        }

        if (missingPermissions.isEmpty()) {
            fireAlert();
        } else {
            permissionLauncher.launch(missingPermissions.toArray(new String[0]));
        }
    }

    private void fireAlert() {
        vibrate();
        Intent intent = new Intent(ACTION_SOS_TRIGGERED); intent.setPackage(getPackageName()); sendBroadcast(intent);

        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
            int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
            int batteryPct = (int) ((level / (float) scale) * 100);

            alertManager.sendAlert(batteryPct, new AlertManager.AlertListener() {
                @Override
                public void onAlertSent(String alertKey, double lat, double lng) {
                    currentActiveAlertKey = alertKey;
                    runOnUiThread(() -> {
                        Toast.makeText(HomeActivity.this,
                                getString(R.string.alert_sent_battery, batteryPct),
                                Toast.LENGTH_LONG).show();

                        // Stay green + "SENT" until security resolves it (or the student cancels)
                        alertFired = true;
                        updateUiToActiveState();
                        btnSos.setEnabled(true);

                        askUrgency(alertKey);
                    });
                    listenForSecurityResponse(alertKey);
                }

                @Override
                public void onAlertError(String message) {
                    runOnUiThread(() -> {
                        alertFired = false;
                        updateUiToIdleState();
                        btnSos.setEnabled(true);
                        Toast.makeText(HomeActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Exception e) {
            alertFired = false;
            updateUiToIdleState();
            btnSos.setEnabled(true);
        }
    }


    /** Non-blocking: the SOS is already sent, this just refines how urgent it really is. */
    private void askUrgency(String alertKey) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("How urgent is this?")
                .setMessage("Your alert has already been sent. Let security know the severity.")
                .setPositiveButton("🔴 HIGH", (dialog, which) -> alertManager.updateUrgency("high"))
                .setNeutralButton("🟠 MEDIUM", (dialog, which) -> alertManager.updateUrgency("medium"))
                .setNegativeButton("🔵 LOW", (dialog, which) -> alertManager.updateUrgency("low"))
                .setCancelable(true)
                .show();
    }

    private String lastResponseMessageSeen = null;
    private boolean resolutionPromptShown = false;

    private void stopResponseListener(String alertKey) {
        if (alertKey != null && responseListener != null) {
            mDatabase.child("alerts").child(alertKey).removeEventListener(responseListener);
        }
        responseListener = null;
    }

    private void listenForSecurityResponse(String alertKey) {
        lastResponseMessageSeen = null;
        resolutionPromptShown = false;
        if (responseListener != null) mDatabase.child("alerts").child(alertKey).removeEventListener(responseListener);
        responseListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (isFinishing()) return;

                String status = snapshot.child("status").getValue(String.class);
                boolean open = "active".equals(status) || "acknowledged".equals(status);

                // Alert was re-opened ("still need help") or restored: go green again
                if (open && !alertFired && !alertManager.isDuressActive()) {
                    alertFired = true;
                    alertManager.resumeAlert(alertKey);
                    updateUiToActiveState();
                }

                // Security response pop-up - NOT shown during a silent distress, so an attacker can't see it
                String msg = snapshot.child("responseMessage").getValue(String.class);
                String unit = snapshot.child("respondingUnit").getValue(String.class);
                String officer = snapshot.child("respondingOfficer").getValue(String.class);
                String currentResponseKey = (msg != null ? msg : "") + "|" + (unit != null ? unit : "") + "|" + (officer != null ? officer : "");

                if (!alertManager.isDuressActive() && (msg != null || unit != null) && !currentResponseKey.equals(lastResponseMessageSeen)) {
                    lastResponseMessageSeen = currentResponseKey;

                    String displayMsg = (msg != null && !msg.isEmpty()) ? msg : "Help is on the way!! 🛡️ stay where you are.";
                    if (unit != null || officer != null) {
                        displayMsg += "\n\nSecured by: " + (unit != null ? unit : "") + (officer != null ? " (" + officer + ")" : "");
                    }
                    if (unit != null && tvSosHint != null && alertFired) {
                        tvSosHint.setText("Secured by " + unit + (officer != null ? " (" + officer + ")" : ""));
                    }

                    new AlertDialog.Builder(HomeActivity.this)
                            .setTitle("🛡️ SECURITY RESPONSE")
                            .setMessage(displayMsg)
                            .setPositiveButton("REPLY", (d, w) -> {
                                Intent chatIntent = new Intent(HomeActivity.this, ChatActivity.class);
                                chatIntent.putExtra("alertKey", alertKey);
                                startActivity(chatIntent);
                            })
                            .setNegativeButton("UNDERSTOOD", null)
                            .setCancelable(true)
                            .show();
                }

                // Security marked it resolved: button goes back to red straight away
                if ("resolved".equals(status)) {
                    if (alertFired) {
                        alertFired = false;
                        updateUiToIdleState();
                    }
                    alertManager.finishAlert();     // stop tracking + unlock the SOS button (status stays "resolved")

                    if (!resolutionPromptShown) {
                        resolutionPromptShown = true;
                        new AlertDialog.Builder(HomeActivity.this)
                                .setTitle("Security marked this resolved")
                                .setMessage("Can you confirm you're safe now?")
                                .setPositiveButton("Yes, I'm safe", (d, w) -> {
                                    alertManager.confirmResolution(alertKey, true);
                                    stopResponseListener(alertKey);
                                    currentActiveAlertKey = null;
                                    Toast.makeText(HomeActivity.this, "Glad you're safe 💚", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("No, still need help", (d, w) -> {
                                    resolutionPromptShown = false;
                                    alertManager.confirmResolution(alertKey, false);   // re-opens it; button turns green again
                                })
                                .setCancelable(false)
                                .show();
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        mDatabase.child("alerts").child(alertKey).addValueEventListener(responseListener);
    }

    private void updateUiToActiveState() {
        btnSos.setText(R.string.sent);
        btnSos.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
        tvSosHint.setText("Alert sent. Tap the green button to cancel.");
        if (tvCancelAlert != null) tvCancelAlert.setVisibility(View.VISIBLE);
        startTriplePulse(android.R.color.holo_green_dark);
    }

    private void updateUiToIdleState() {
        btnSos.setText(R.string.sos);
        btnSos.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.ss_red));
        tvSosHint.setText(R.string.tap_for_emergency);
        if (tvCancelAlert != null) tvCancelAlert.setVisibility(View.GONE);
        startTriplePulse(R.color.ss_red);
    }

    /** Step 1 of 2: ask if they want to cancel. */
    private void confirmCancelAlert() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Cancel Emergency Alert?")
                .setMessage("Did you send this alert by mistake? Cancelling will notify security that you are safe.")
                .setPositiveButton("Yes, Cancel Alert", (dialog, which) -> confirmCancelAlertSecondStep())
                .setNegativeButton("Keep Active", null)
                .setCancelable(true)
                .show();
    }

    /** Step 2 of 2: "are you sure?" - stops an accidental tap from cancelling real help. */
    /** Step 2 of 2: "are you sure?". The small 👍 is the secret distress signal. */
    private void confirmCancelAlertSecondStep() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Are you sure you want to cancel?")
                .setMessage("Security will stop responding to your alert. Only continue if you are definitely not in danger.")
                .setPositiveButton("Yes, I'm sure", (dialog, which) -> cancelAlert())
                .setNeutralButton("👍", (dialog, which) -> silentDistressCancel())
                .setNegativeButton("No, keep alert active", null)
                .setCancelable(false)
                .show();
    }

    /** Looks like a normal cancel on screen, but the alert stays open and security is warned. */
    private void silentDistressCancel() {
        alertManager.signalDuress();        // flags the alert, keeps live tracking running
        alertFired = false;
        updateUiToIdleState();
        Toast.makeText(this, "Alert cancelled", Toast.LENGTH_SHORT).show();
        // Deliberately NOT stopping tracking, removing the listener or clearing currentActiveAlertKey.
    }

    private void cancelAlert() {
        alertFired = false;
        alertManager.cancelAlert();
        if (currentActiveAlertKey != null && responseListener != null) {
            mDatabase.child("alerts").child(currentActiveAlertKey).removeEventListener(responseListener);
        }
        btnSos.setEnabled(true);
        if (tvCancelAlert != null) tvCancelAlert.setVisibility(View.GONE);
        currentActiveAlertKey = null;
        updateUiToIdleState();
        Intent intent = new Intent(ACTION_SOS_RESET); intent.setPackage(getPackageName()); sendBroadcast(intent);
    }

    private void startTriplePulse(int colorResId) {
        if (ripple1 == null) return;
        int color = ContextCompat.getColor(this, colorResId);
        ColorStateList tint = ColorStateList.valueOf(color);
        ripple1.setBackgroundTintList(tint); ripple2.setBackgroundTintList(tint); ripple3.setBackgroundTintList(tint); ripple4.setBackgroundTintList(tint); ripple5.setBackgroundTintList(tint);
        animateRipple(ripple1, 0); animateRipple(ripple2, 500); animateRipple(ripple3, 1000); animateRipple(ripple4, 1500); animateRipple(ripple5, 2000);
    }

    private void animateRipple(View ripple, long delay) {
        ripple.clearAnimation(); Animation anim = AnimationUtils.loadAnimation(this, R.anim.pulse);
        anim.setStartOffset(delay); ripple.startAnimation(anim);
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1));
            else v.vibrate(new long[]{0, 200, 100, 200}, -1);
        }
    }

    private void setupDrawer() {
        ImageView btnMenu = findViewById(R.id.btn_menu);
        NavigationView navigationView = findViewById(R.id.nav_view);
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dash) startActivity(new Intent(this, DashboardActivity.class));
            else if (id == R.id.nav_settings) startActivity(new Intent(this, SettingsActivity.class));
            else if (id == R.id.nav_feedback) startActivity(new Intent(this, FeedbackActivity.class));
            else if (id == R.id.nav_broadcasts) startActivity(new Intent(this, BroadcastFeedActivity.class));
            else if (id == R.id.nav_about) startActivity(new Intent(this, AboutActivity.class));
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });
    }

    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null) return;
        bottomNav.setSelectedItemId(R.id.nav_home);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_timer) {
                startActivity(new Intent(this, TimerActivity.class));
            } else if (id == R.id.nav_report_concern) {
                startActivity(new Intent(this, SubmitTicketActivity.class));
            } else if (id == R.id.nav_chat) {
                startActivity(new Intent(this, ChatContactsActivity.class));
            } else if (id == R.id.nav_contact) {
                startActivity(new Intent(this, AddContactActivity.class));
            }
            return true;
        });
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SOS_TRIGGERED);
        filter.addAction(ACTION_SOS_RESET);
        ContextCompat.registerReceiver(this, sosReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        VoiceRecognitionHelper.startIfEnabled(this);
    }
    @Override protected void onStop() { super.onStop(); try { unregisterReceiver(sosReceiver); } catch (Exception e) {} }
    @Override protected void onDestroy() {
        super.onDestroy();
        // Detach before sign-out revokes access, otherwise the listener fails with "Permission denied".
        if (userRef != null && userListener != null) userRef.removeEventListener(userListener);
        executorService.shutdown();
    }
}
