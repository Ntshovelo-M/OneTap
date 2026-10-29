package com.habitu.onetaprealhelp;

import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Talks to the two Cloud Functions that send and check the e-mail OTP. */
public final class OtpApi {

    // Paste the two URLs printed by "firebase deploy --only functions"
    private static final String SEND_URL   = "https://us-central1-onetaprealhelp-ab232.cloudfunctions.net/sendEmailOtp";
    private static final String VERIFY_URL = "https://us-central1-onetaprealhelp-ab232.cloudfunctions.net/verifyEmailOtp";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback { void onResult(boolean ok, boolean already, String message); }

    private OtpApi() {}

    public static void requestCode(Callback cb) { call(SEND_URL, null, cb); }
    public static void verifyCode(String code, Callback cb) { call(VERIFY_URL, code, cb); }

    private static void call(String url, String code, Callback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { cb.onResult(false, false, "Please log in again."); return; }

        user.getIdToken(true).addOnSuccessListener(result -> EXEC.execute(() -> {
            boolean ok = false, already = false;
            String msg;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Authorization", "Bearer " + result.getToken());
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);

                JSONObject body = new JSONObject();
                if (code != null) body.put("code", code);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = c.getResponseCode();
                InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject j = new JSONObject(sb.toString());
                ok = j.optBoolean("ok");
                already = j.optBoolean("already");
                msg = j.optString("message", ok ? "Done" : "Something went wrong");
            } catch (Exception e) {
                msg = "Couldn't reach the server. Check your connection and try again.";
            }
            final boolean fOk = ok, fAlready = already;
            final String fMsg = msg;
            MAIN.post(() -> cb.onResult(fOk, fAlready, fMsg));
        })).addOnFailureListener(e -> cb.onResult(false, false, "Please log in again."));
    }
}
