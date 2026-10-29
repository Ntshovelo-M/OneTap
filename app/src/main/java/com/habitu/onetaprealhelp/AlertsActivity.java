package com.habitu.onetaprealhelp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class AlertsActivity extends AppCompatActivity implements OnMapReadyCallback {

    private CampusAlertNotifier campusAlertNotifier;

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private String currentAlertId;
    private DatabaseReference alertLocationRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alerts);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        currentAlertId = getIntent().getStringExtra("alertId");
        if (currentAlertId != null) {
            alertLocationRef = FirebaseDatabase.getInstance()
                    .getReference("alerts")
                    .child(currentAlertId);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            startLocationUpdates();
        }

        setupBottomNav();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .build();

        // NOTE: this tracks the SECURITY OFFICER'S OWN device location (e.g. to show
        // "you are here" relative to unsafe zones). It must never be written into an
        // alert's lat/lng - that field belongs only to the student who triggered the SOS.
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    updateMapLocation(location.getLatitude(), location.getLongitude());
                }
            }
        };

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }

        // If we were opened for a specific alert, listen (read-only) for the STUDENT's
        // real-time location and plot it separately from the officer's own position.
        if (alertLocationRef != null) {
            alertLocationRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                    Double lat = snapshot.child("lat").getValue(Double.class);
                    Double lng = snapshot.child("lng").getValue(Double.class);
                    if (lat != null && lng != null) {
                        updateStudentMarker(lat, lng);
                    }
                }
                @Override
                public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {}
            });
        }
    }

    private com.google.android.gms.maps.model.Marker studentMarker;
    private com.google.android.gms.maps.model.Marker myMarker;

    private void updateStudentMarker(double lat, double lng) {
        if (mMap == null) return;
        LatLng studentLocation = new LatLng(lat, lng);
        if (studentMarker == null) {
            studentMarker = mMap.addMarker(new MarkerOptions().position(studentLocation)
                    .title("Student Location")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
                            com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(studentLocation, 13));
        } else {
            studentMarker.setPosition(studentLocation);
        }
    }

    private void updateMapLocation(double lat, double lng) {
        if (mMap == null) return;
        LatLng myLocation = new LatLng(lat, lng);
        if (myMarker == null) {
            myMarker = mMap.addMarker(new MarkerOptions().position(myLocation)
                    .title("Your Location")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
                            com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE)));
            if (studentMarker == null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 13));
            }
        } else {
            myMarker.setPosition(myLocation);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        campusAlertNotifier = new CampusAlertNotifier(this);
        campusAlertNotifier.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (campusAlertNotifier != null) campusAlertNotifier.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null) return;

        bottomNav.getMenu().clear();
        bottomNav.inflateMenu(R.menu.security_nav_menu);
        bottomNav.setSelectedItemId(R.id.nav_alerts);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_analytics) {
                startActivity(new Intent(this, AnalyticsActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_web_dashboard) {
                startActivity(new Intent(this, SecurityDashboardActivity.class));
                finish();
                return true;
            }
            return false;
        });
    }
}
