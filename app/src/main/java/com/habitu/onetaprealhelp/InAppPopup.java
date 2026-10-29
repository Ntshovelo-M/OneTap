package com.habitu.onetaprealhelp;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

/** One place to show a pop-up for any notification - in the app, or outside it. */
public final class InAppPopup {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Deque<String[]> queue = new ArrayDeque<>();
    private static WeakReference<Activity> current;
    private static boolean showing = false;

    private InAppPopup() {}

    public static boolean isAppInForeground() {
        Activity a = current != null ? current.get() : null;
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /** Safe to call from any thread, screen or service. */
    public static void show(String title, String message) {
        main.post(() -> {
            if (isAppInForeground()) {
                if (queue.size() >= 10) queue.poll();
                queue.add(new String[]{title, message});
                showNext();
            } else {
                Context c = OneTapApp.getContext();
                if (c != null) PopupActivity.notifyUser(c, title, message);   // app closed / in background
            }
        });
    }

    static void setCurrent(Activity a) {
        if (a instanceof WalkStopPromptActivity || a instanceof PopupActivity) return;
        current = new WeakReference<>(a);
        main.post(InAppPopup::showNext);
    }

    static void clearCurrent(Activity a) {
        if (current != null && current.get() == a) current = null;
    }

    private static void showNext() {
        if (showing || queue.isEmpty() || !isAppInForeground()) return;
        Activity a = current.get();
        String[] item = queue.poll();
        if (item == null) return;
        showing = true;
        try {
            new AlertDialog.Builder(a)
                    .setTitle(item[0])
                    .setMessage(item[1])
                    .setPositiveButton("OK", null)
                    .setOnDismissListener(d -> { showing = false; showNext(); })
                    .show();
        } catch (Exception e) {
            showing = false;
        }
    }
}
