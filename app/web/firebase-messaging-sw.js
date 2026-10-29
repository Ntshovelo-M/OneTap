// firebase-messaging-sw.js
// Runs in the background so a new SOS alert can raise an OS-level notification even when
// the security dashboard tab/browser isn't open - the same idea as Gmail's push notifications.
// Must live at the SAME PATH the dashboard is served from (Firebase Hosting root), so its
// scope covers the whole site.

importScripts('https://www.gstatic.com/firebasejs/9.23.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/9.23.0/firebase-messaging-compat.js');

// TODO: keep this identical to the firebaseConfig in security_dashboard.html /
// index.html (same messagingSenderId/appId you filled in there).
firebase.initializeApp({
  apiKey: "AIzaSyDcVYYJ8rWhtF00HBJLwIRgNLNvVVA6X_A",
  authDomain: "onetaprealhelp-ab232.firebaseapp.com",
  databaseURL: "https://onetaprealhelp-ab232-default-rtdb.firebaseio.com",
  projectId: "onetaprealhelp-ab232",
  storageBucket: "onetaprealhelp-ab232.appspot.com",
  messagingSenderId: "531199721908",
  appId: "1:531199721908:web:ac3b00fb125bdea9a572e1",
});

const messaging = firebase.messaging();

// Fired when a push arrives while no dashboard tab has focus (including fully closed).
messaging.onBackgroundMessage((payload) => {
  const title = (payload.notification && payload.notification.title) || '🚨 New SOS Alert';
  const body = (payload.notification && payload.notification.body) || 'A student needs help - open the dashboard.';

  self.registration.showNotification(title, {
    body: body,
    icon: '/icon.png', // optional - safe to leave even if this file doesn't exist yet
    tag: 'onetap-sos', // collapses rapid alerts into one notification instead of stacking
    data: { url: '/index.html' }, // where a tap should land
  });
});

// Tapping the notification should take security straight to the login page.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const targetUrl = (event.notification.data && event.notification.data.url) || '/index.html';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if (client.url.includes('index.html') || client.url.includes('security_dashboard.html')) {
          return client.focus();
        }
      }
      return self.clients.openWindow(targetUrl);
    })
  );
});
