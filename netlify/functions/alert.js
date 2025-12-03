const admin = require('firebase-admin');
const { getStore } = require('@netlify/blobs');

const COOLDOWN_MS = parseInt(process.env.ALERT_COOLDOWN_MS || '20000', 10);
const ALERT_TOPIC = process.env.FCM_TOPIC || 'soil-alerts';
const VALID_STATES = new Set(['low', 'high']);
const store = getStore({ name: 'alert-state' });

let firebaseInitialized = false;

function initFirebase() {
  if (firebaseInitialized) return;
  const projectId = process.env.FIREBASE_PROJECT_ID;
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL;
  const privateKeyRaw = process.env.FIREBASE_PRIVATE_KEY;

  if (!projectId || !clientEmail || !privateKeyRaw) {
    throw new Error('Firebase credentials are missing');
  }

  admin.initializeApp({
    credential: admin.credential.cert({
      projectId,
      clientEmail,
      privateKey: privateKeyRaw.replace(/\\n/g, '\n')
    })
  });

  firebaseInitialized = true;
}

async function shouldThrottle(state) {
  const key = `last:${state}`;
  const previous = await store.get(key, { type: 'text' });
  if (!previous) return { throttled: false, key };
  const elapsed = Date.now() - Number(previous);
  if (Number.isNaN(elapsed) || elapsed >= COOLDOWN_MS) {
    return { throttled: false, key };
  }
  return { throttled: true, key, remaining: COOLDOWN_MS - elapsed };
}

async function setLastTimestamp(key) {
  await store.set(key, String(Date.now()));
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

  let throttleInfo;
  try {
    throttleInfo = await shouldThrottle(state);
  } catch (err) {
    console.error('Throttle check failed', err);
    return { statusCode: 500, body: 'Throttle check failed' };
  }

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
    return { statusCode: 500, body: 'Firebase configuration error' };
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
    await setLastTimestamp(throttleInfo.key);
    return { statusCode: 200, body: JSON.stringify({ ok: true }) };
  } catch (err) {
    console.error('FCM send failed', err);
    return { statusCode: 500, body: 'FCM send failed' };
  }
};

