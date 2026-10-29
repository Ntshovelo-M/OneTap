package com.habitu.onetaprealhelp;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BroadcastFeedActivity extends AppCompatActivity {

    private DatabaseReference mDatabase;
    private BroadcastAdapter adapter;
    private TextInputEditText etReportMessage;
    private Spinner spinnerCategory, spinnerArea;
    private TextView tvHighRiskZones;

    /** Report categories - index 0 is the "pick one" placeholder and is not accepted. */
    static final String[] CATEGORIES = {
            "Select a category...",
            "Assault / Violence",
            "Harassment / Intimidation",
            "Sexual harassment / Assault",
            "Theft / Robbery",
            "Stalking / Following",
            "Suspicious person / Activity",
            "Poor lighting / Unsafe infrastructure",
            "Drugs / Alcohol",
            "Other"
    };

    /** Where it happened - used to work out the high-risk zones. */
    static final String[] AREAS = {
            "Select the location...",
            "Res 3 walkway",
            "North gate",
            "Library park",
            "N4 Bridge",
            "Luhambo",
            "Campus main road",
            "Parking area",
            "Other area"
    };

    /** A zone becomes "high risk" once it has this many categorised reports in the window. */
    private static final int HIGH_RISK_MIN_REPORTS = 3;
    private static final long RISK_WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_ZONES_SHOWN = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast_feed);

        mDatabase = FirebaseDatabase.getInstance().getReference();

        View btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recycler_broadcast_feed);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BroadcastAdapter();
        recyclerView.setAdapter(adapter);

        spinnerCategory = findViewById(R.id.spinner_category);
        spinnerArea = findViewById(R.id.spinner_area);
        tvHighRiskZones = findViewById(R.id.tv_high_risk_zones);
        spinnerCategory.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CATEGORIES));
        spinnerArea.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, AREAS));

        etReportMessage = findViewById(R.id.et_report_message);
        View btnSendReport = findViewById(R.id.btn_send_report);
        btnSendReport.setOnClickListener(v -> submitAnonymousReport());

        loadBroadcastFeed();
    }

    private void loadBroadcastFeed() {
        mDatabase.child("schoolAlerts").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<BroadcastItem> items = new ArrayList<>();

                for (DataSnapshot child : snapshot.getChildren()) {
                    String message = child.child("message").getValue(String.class);
                    Long timestamp = child.child("timestamp").getValue(Long.class);

                    if (message != null && timestamp != null) {
                        items.add(new BroadcastItem(message, timestamp));
                    }
                }

                // Newest first
                Collections.sort(items, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));

                adapter.setItems(items);
                updateHighRiskZones(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(BroadcastFeedActivity.this,
                        "Failed to load alerts: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Builds the high-risk zone summary from categorised reports (area + category) in the last 30 days. */
    private void updateHighRiskZones(DataSnapshot snapshot) {
        long cutoff = System.currentTimeMillis() - RISK_WINDOW_MS;
        Map<String, Integer> perArea = new HashMap<>();
        Map<String, Map<String, Integer>> perAreaCategory = new HashMap<>();

        for (DataSnapshot child : snapshot.getChildren()) {
            String area = child.child("area").getValue(String.class);
            String category = child.child("category").getValue(String.class);
            Long ts = child.child("timestamp").getValue(Long.class);
            if (area == null || category == null || ts == null || ts < cutoff) continue; // older/uncategorised reports are ignored

            perArea.put(area, perArea.containsKey(area) ? perArea.get(area) + 1 : 1);
            Map<String, Integer> cats = perAreaCategory.get(area);
            if (cats == null) { cats = new HashMap<>(); perAreaCategory.put(area, cats); }
            cats.put(category, cats.containsKey(category) ? cats.get(category) + 1 : 1);
        }

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(perArea.entrySet());
        Collections.sort(ranked, (a, b) -> b.getValue() - a.getValue());

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : ranked) {
            if (e.getValue() < HIGH_RISK_MIN_REPORTS || shown >= MAX_ZONES_SHOWN) break;
            String topCategory = "";
            int topCount = 0;
            for (Map.Entry<String, Integer> c : perAreaCategory.get(e.getKey()).entrySet()) {
                if (c.getValue() > topCount) { topCount = c.getValue(); topCategory = c.getKey(); }
            }
            if (shown > 0) sb.append("\n");
            sb.append("\u26A0 ").append(e.getKey()).append(" \u2014 ").append(e.getValue())
              .append(" reports, mostly ").append(topCategory);
            shown++;
        }
        tvHighRiskZones.setText(shown == 0
                ? "No high-risk zones right now. A zone appears here once it has " + HIGH_RISK_MIN_REPORTS + "+ reports."
                : sb.toString());
    }

    private void submitAnonymousReport() {
        String message = etReportMessage.getText() != null
                ? etReportMessage.getText().toString().trim() : "";

        int catPos = spinnerCategory.getSelectedItemPosition();
        int areaPos = spinnerArea.getSelectedItemPosition();
        if (catPos == 0) {
            Toast.makeText(this, "Please choose a category", Toast.LENGTH_SHORT).show();
            return;
        }
        if (areaPos == 0) {
            Toast.makeText(this, "Please choose where it happened", Toast.LENGTH_SHORT).show();
            return;
        }
        if (message.isEmpty()) {
            Toast.makeText(this, "Please add a few details about what happened", Toast.LENGTH_SHORT).show();
            return;
        }
        String category = CATEGORIES[catPos];
        String area = AREAS[areaPos];

        String reportId = mDatabase.child("schoolAlerts").push().getKey();
        if (reportId == null) return;

        // Deliberately no sender ID stored anywhere, to keep this genuinely anonymous
        Map<String, Object> report = new HashMap<>();
        report.put("message", "[" + category + "] " + area + ": " + message);
        report.put("category", category);
        report.put("area", area);
        report.put("details", message);
        report.put("timestamp", System.currentTimeMillis());

        mDatabase.child("schoolAlerts").child(reportId).setValue(report);
        mDatabase.child("studentReports").child(reportId).setValue(report)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Report submitted & added to feed", Toast.LENGTH_SHORT).show();
                    etReportMessage.setText("");
                    spinnerCategory.setSelection(0);
                    spinnerArea.setSelection(0);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to submit: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}