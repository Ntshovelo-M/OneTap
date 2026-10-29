package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class SecurityDashboardActivity extends AppCompatActivity {

    private CampusAlertNotifier campusAlertNotifier;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_dashboard);

        webView = findViewById(R.id.webview_dashboard);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient());

        // Load your LIVE hosted dashboard
        webView.loadUrl("https://onetaprealhelp-ab232.web.app/security_dashboard.html");

        setupBottomNav();

        // Handle back navigation
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
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

    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.security_bottom_nav);
        if (bottomNav == null) return;

        bottomNav.setSelectedItemId(R.id.nav_web_dashboard);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_analytics) {
                startActivity(new Intent(this, AnalyticsActivity.class));
                finish();
            } else if (id == R.id.nav_alerts) {
                startActivity(new Intent(this, AlertsActivity.class));
                finish();
            } else if (id == R.id.nav_web_dashboard) {
                return true;
            }
            return true;
        });
    }
}
