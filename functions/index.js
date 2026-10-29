/**
 * Sends a push notification to every registered security device the moment a new SOS alert
 * is created - this is what actually makes the "even when the dashboard isn't open" push
 * notifications (see firebase-messaging-sw.js + security_dashboard.html) work. Without this
 * function deployed, tokens get stored but nothing ever triggers a push.
 *
 * Deploy with:
 *   cd functions && npm install
 *   firebase deploy --only functions
 *
 * Requires the project to be on the Firebase "Blaze" (pay-as-you-go) plan - Cloud Functions
 * don't run on the free Spark plan. The Realtime Database itself stays free either way.
 */

const { initializeApp } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");
const { onValueCreated } = require("firebase-functions/v2/database");
const { logger } = require("firebase-functions");
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { getAuth } = require("firebase-admin/auth");
const nodemailer = require("nodemailer");
const crypto = require("crypto");

initializeApp();

exports.notifySecurityOnNewAlert = onValueCreated(
  "/alerts/{alertKey}",
  async (event) => {
    const alert = event.data.val();
    if (!alert) return;

    const db = getDatabase();
    const tokensSnap = await db.ref("securityTokens").get();
    const tokensByUnit = tokensSnap.val() || {};

    // Flatten every unit's tokens into one list - every security officer should be notified,
    // not just the unit whose area it happened to fall in.
    const tokens = [];
    Object.values(tokensByUnit).forEach((unitTokens) => {
      Object.keys(unitTokens || {}).forEach((token) => tokens.push(token));
    });

    if (tokens.length === 0) {
      logger.info("New alert, but no registered security tokens to notify.");
      return;
    }

    const message = {
      notification: {
        title: "🚨 New SOS Alert",
        body: `${alert.name || "A student"} needs help — ${alert.address || "locating…"}`,
      },
      webpush: {
        fcmOptions: {
          link: "/index.html",
        },
      },
      tokens,
    };

    const response = await getMessaging().sendEachForMulticast(message);
    logger.info(`Alert push sent: ${response.successCount} succeeded, ${response.failureCount} failed.`);

    // Clean up dead tokens (uninstalled/unregistered) so the list doesn't grow forever.
    const staleRemovals = [];
    response.responses.forEach((res, i) => {
      if (!res.success && (res.error?.code === "messaging/registration-token-not-registered")) {
        const deadToken = tokens[i];
        Object.entries(tokensByUnit).forEach(([unit, unitTokens]) => {
          if (unitTokens && unitTokens[deadToken]) {
            staleRemovals.push(db.ref(`securityTokens/${unit}/${deadToken}`).remove());
          }
        });
      }
    });
    await Promise.all(staleRemovals);
  }
);

// ===================== School e-mail OTP verification =====================
const GMAIL_USER = defineSecret("GMAIL_USER");
const GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");

const OTP_TTL_MS = 10 * 60 * 1000;        // code valid for 10 minutes
const MAX_ATTEMPTS = 5;                   // wrong guesses allowed per code
const RESEND_COOLDOWN_MS = 60 * 1000;     // 1 minute between codes
const MAX_SENDS_PER_HOUR = 5;
const STUDENT_EMAIL_RE = /^(\d{9})@ump\.ac\.za$/i;

function hashCode(uid, code) {
  return crypto.createHash("sha256").update(uid + ":" + code).digest("hex");
}

async function authenticate(req, res) {
  if (req.method !== "POST") { res.status(405).json({ ok: false, message: "POST only" }); return null; }
  const h = req.headers.authorization || "";
  const token = h.startsWith("Bearer ") ? h.slice(7) : null;
  if (!token) { res.status(401).json({ ok: false, message: "Please log in again." }); return null; }
  try { return await getAuth().verifyIdToken(token); }
  catch (e) { res.status(401).json({ ok: false, message: "Please log in again." }); return null; }
}

exports.sendEmailOtp = onRequest(
  { region: "us-central1", secrets: [GMAIL_USER, GMAIL_APP_PASSWORD] },
  async (req, res) => {
    const decoded = await authenticate(req, res);
    if (!decoded) return;
    const uid = decoded.uid;
    const email = (decoded.email || "").toLowerCase();

    const m = STUDENT_EMAIL_RE.exec(email);
    if (!m) return res.status(400).json({ ok: false, message: "Only 9-digit @ump.ac.za student e-mails can be verified." });

    const db = getDatabase();
    const user = (await db.ref(`users/${uid}`).get()).val() || {};
    if (user.emailVerified === true) return res.json({ ok: true, already: true, message: "Already verified." });
    if (String(user.studentNumber || "").trim() !== m[1]) {
      return res.status(400).json({ ok: false, message: "Your student number doesn't match your UMP e-mail address." });
    }

    const ref = db.ref(`emailOtps/${uid}`);
    const rec = (await ref.get()).val() || {};
    const now = Date.now();

    if (rec.sentAt && now - rec.sentAt < RESEND_COOLDOWN_MS) {
      const wait = Math.ceil((RESEND_COOLDOWN_MS - (now - rec.sentAt)) / 1000);
      return res.status(429).json({ ok: false, message: `Please wait ${wait} seconds before asking for another code.` });
    }
    const sameWindow = rec.windowStart && now - rec.windowStart < 60 * 60 * 1000;
    const windowStart = sameWindow ? rec.windowStart : now;
    const sendCount = sameWindow ? (rec.sendCount || 0) : 0;
    if (sendCount >= MAX_SENDS_PER_HOUR) {
      return res.status(429).json({ ok: false, message: "Too many codes requested. Try again in an hour." });
    }

    const code = String(crypto.randomInt(0, 1000000)).padStart(6, "0");
    await ref.set({ hash: hashCode(uid, code), expiresAt: now + OTP_TTL_MS, attempts: 0, sentAt: now, windowStart, sendCount: sendCount + 1 });

    try {
      const transporter = nodemailer.createTransport({
        service: "gmail",
        auth: { user: GMAIL_USER.value(), pass: GMAIL_APP_PASSWORD.value() },
      });
      await transporter.sendMail({
        from: `"SafeStep" <${GMAIL_USER.value()}>`,
        to: email,
        subject: "Your SafeStep verification code",
        text: `Your SafeStep verification code is ${code}.\n\nIt expires in 10 minutes. If you didn't register for SafeStep, ignore this e-mail.`,
      });
    } catch (e) {
      logger.error("OTP e-mail failed", e);
      await ref.remove();
      return res.status(500).json({ ok: false, message: "We couldn't send the e-mail. Please try again." });
    }
    return res.json({ ok: true, message: `We sent a 6-digit code to ${email}` });
  }
);

exports.verifyEmailOtp = onRequest({ region: "us-central1" }, async (req, res) => {
  const decoded = await authenticate(req, res);
  if (!decoded) return;
  const uid = decoded.uid;
  const code = String((req.body || {}).code || "").trim();
  if (!/^\d{6}$/.test(code)) return res.status(400).json({ ok: false, message: "Enter the 6-digit code." });

  const db = getDatabase();
  const ref = db.ref(`emailOtps/${uid}`);
  const rec = (await ref.get()).val();
  if (!rec || !rec.hash) return res.status(400).json({ ok: false, message: "No code requested yet. Tap Resend." });

  if (Date.now() > rec.expiresAt) { await ref.remove(); return res.status(400).json({ ok: false, message: "That code expired. Tap Resend." }); }
  if ((rec.attempts || 0) >= MAX_ATTEMPTS) { await ref.remove(); return res.status(429).json({ ok: false, message: "Too many wrong tries. Tap Resend for a new code." }); }

  const good = crypto.timingSafeEqual(Buffer.from(hashCode(uid, code)), Buffer.from(rec.hash));
  if (!good) {
    const used = (rec.attempts || 0) + 1;
    await ref.child("attempts").set(used);
    return res.status(400).json({ ok: false, message: `Wrong code. ${MAX_ATTEMPTS - used} tries left.` });
  }

  await db.ref(`users/${uid}`).update({ emailVerified: true, verifiedAt: Date.now() });
  await ref.remove();
  return res.json({ ok: true, message: "E-mail verified!" });
});
