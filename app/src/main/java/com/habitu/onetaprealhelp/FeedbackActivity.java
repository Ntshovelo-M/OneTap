package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class FeedbackActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_rate_app).setOnClickListener(v -> launchInAppReview());
        findViewById(R.id.btn_recommend).setOnClickListener(v -> shareApp());

        EditText etSuggestion = findViewById(R.id.et_suggestion);
        findViewById(R.id.btn_submit_suggestion).setOnClickListener(v ->
                submitToFirebase("suggestions", etSuggestion.getText().toString().trim(), -1, etSuggestion));

        EditText etFeedback = findViewById(R.id.et_feedback);
        RatingBar ratingBar = findViewById(R.id.rating_feedback);
        findViewById(R.id.btn_submit_feedback).setOnClickListener(v ->
                submitToFirebase("feedback", etFeedback.getText().toString().trim(), (int) ratingBar.getRating(), etFeedback));
    }

    /** Google Play's official in-app review flow - shows Play's own rating dialog without leaving the app.
     *  Falls back to opening the Play Store listing if the flow can't be launched (e.g. debug builds,
     *  or the device doesn't support it). */
    private void launchInAppReview() {
        ReviewManager manager = ReviewManagerFactory.create(this);
        manager.requestReviewFlow().addOnCompleteListener(request -> {
            if (request.isSuccessful()) {
                ReviewInfo reviewInfo = request.getResult();
                manager.launchReviewFlow(this, reviewInfo);
                // Note: Play doesn't guarantee the dialog actually shows (quota-limited by Google),
                // and never tells the app whether the user rated - that's by design on Google's end.
            } else {
                openPlayStoreListing();
            }
        });
    }

    private void openPlayStoreListing() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=" + getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
        }
    }

    private void shareApp() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "OneTap Real Help - Campus Safety App");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                "I use OneTap Real Help to stay safe on campus - one tap sends an SOS with your live location to campus security. Get it here: https://play.google.com/store/apps/details?id=" + getPackageName());
        startActivity(Intent.createChooser(shareIntent, "Recommend OneTap"));
    }

    private void submitToFirebase(String node, String text, int rating, EditText fieldToClear) {
        if (text.isEmpty() && rating <= 0) {
            Toast.makeText(this, "Please write something first", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        Map<String, Object> entry = new HashMap<>();
        entry.put("userId", user != null ? user.getUid() : "anonymous");
        entry.put("text", text);
        entry.put("timestamp", System.currentTimeMillis());
        if (rating >= 0) entry.put("rating", rating);

        FirebaseDatabase.getInstance().getReference(node).push().setValue(entry)
                .addOnSuccessListener(v -> {
                    Toast.makeText(this, "Thank you - your feedback has been sent", Toast.LENGTH_SHORT).show();
                    fieldToClear.setText("");
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Couldn't send that - check your connection", Toast.LENGTH_SHORT).show());
    }
}
