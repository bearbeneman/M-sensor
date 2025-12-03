const admin = require('firebase-admin');

const COOLDOWN_MS = parseInt(process.env.ALERT_COOLDOWN_MS || '20000', 10);
const ALERT_TOPIC = process.env.FCM_TOPIC || 'soil-alerts';
const VALID_STATES = new Set(['low', 'high']);
const lastStateTimestamps = { low: 0, high: 0 };

let firebaseInitialized = false;

function getPrivateKey() {
  const base64 = process.env.FIREBASE_PRIVATE_KEY_BASE64;
  if (base64) {
    try {
      return Buffer.from(base64, 'base64').toString('utf8');
    } catch (err) {
      console.error('Failed to decode base64 private key', err);
      throw new Error('Invalid base64 private key');
    }
  }
  const inline = process.env.FIREBASE_PRIVATE_KEY;
  if (inline) {
    return inline.replace(/\\n/g, '\n');
  }
  throw new Error('Firebase private key not configured');
}

function initFirebase() {
  if (firebaseInitialized) return;
  const projectId = process.env.FIREBASE_PROJECT_ID;
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL;
  const privateKeyRaw = getPrivateKey();

  if (!projectId || !clientEmail) {
    throw new Error('Firebase credentials are missing');
  }

  admin.initializeApp({
    credential: admin.credential.cert({
      projectId,
      clientEmail,
      privateKey: privateKeyRaw
    })
  });

  firebaseInitialized = true;
}

function shouldThrottle(state) {
  const previous = lastStateTimestamps[state] || 0;
  const elapsed = Date.now() - previous;
  if (Number.isNaN(elapsed) || elapsed >= COOLDOWN_MS) {
    return { throttled: false };
  }
  return { throttled: true, remaining: COOLDOWN_MS - elapsed };
}

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method Not Allowed' };
  }

  let payload = {};
  try {
    payload = JSON.parse(event.body || '{}');
  } catch (err) {
    return { statusCode: 400, body: 'Invalid JSON' };
  }

  const { secret, state, moisture } = payload;
  if (!secret || secret !== process.env.ALERT_SHARED_SECRET) {
    return { statusCode: 401, body: 'Unauthorized' };
  }

  if (!state || !VALID_STATES.has(state)) {
    return { statusCode: 400, body: 'Invalid state' };
  }

  const numericMoisture = Number(moisture);
  if (Number.isNaN(numericMoisture)) {
    return { statusCode: 400, body: 'Invalid moisture' };
  }

  const throttleInfo = shouldThrottle(state);

  if (throttleInfo.throttled) {
    return {
      statusCode: 200,
      body: JSON.stringify({
        ok: false,
        reason: 'cooldown',
        remainingMs: throttleInfo.remaining
      })
    };
  }

  try {
    initFirebase();
  } catch (err) {
    console.error('Firebase init failed', err);
    return {
      statusCode: 500,
      body: JSON.stringify({
        ok: false,
        error: 'firebase_init_failed',
        message: err.message || 'Firebase configuration error'
      })
    };
  }

  const title = state === 'low' ? 'Soil getting dry' : 'Soil saturated';
  const body = `Moisture is at ${numericMoisture}%`;

  try {
    await admin.messaging().send({
      topic: ALERT_TOPIC,
      notification: { title, body },
      data: {
        state,
        moisture: String(numericMoisture)
      }
    });
    lastStateTimestamps[state] = Date.now();
    return { statusCode: 200, body: JSON.stringify({ ok: true }) };
  } catch (err) {
    console.error('FCM send failed', err);
    return {
      statusCode: 500,
      body: JSON.stringify({
        ok: false,
        error: 'fcm_send_failed',
        message: err.message || 'FCM send failed'
      })
    };
  }
};

