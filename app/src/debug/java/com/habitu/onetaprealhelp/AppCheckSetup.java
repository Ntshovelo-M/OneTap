package com.habitu.onetaprealhelp;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

final class AppCheckSetup {
    private AppCheckSetup() {}

    // Debug builds: logs a debug token to Logcat ("DebugAppCheckProvider") that must be
    // registered in Firebase Console > App Check > Apps > Manage debug tokens.
    static void install(Context context) {
        FirebaseApp.initializeApp(context);
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance());
    }
}
