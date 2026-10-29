package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class AnalyticsActivity extends AppCompatActivity {

    private CampusAlertNotifier campusAlertNotifier;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analytics);

        setupBottomNav();
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

    void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null) return;
        
        // Use security menu
        bottomNav.getMenu().clear();
        bottomNav.inflateMenu(R.menu.security_nav_menu);
        bottomNav.setSelectedItemId(R.id.nav_analytics);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_alerts) {
                startActivity(new Intent(this, AlertsActivity.class));
                finish();
            } else if (id == R.id.nav_web_dashboard) {
                startActivity(new Intent(this, SecurityDashboardActivity.class));
                finish();
            }
            return true;
        });
    }
}
