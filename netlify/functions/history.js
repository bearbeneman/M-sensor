const admin = require('firebase-admin');

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
      privateKey: privateKeyRaw,
    }),
  });

  firebaseInitialized = true;
}

const HISTORY_WINDOW_SECONDS = 10 * 24 * 60 * 60; // 10 days
const HISTORY_MAX_POINTS = 10 * 24 * 60; // 10 days * 24h * 60min

exports.handler = async (event) => {
  if (event.httpMethod !== 'GET') {
    return { statusCode: 405, body: 'Method Not Allowed' };
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
        message: err.message || 'Firebase configuration error',
      }),
    };
  }

  try {
    const db = admin.firestore();
    const deviceId = 'default'; // placeholder for future multi-device support

    const nowSec = Math.floor(Date.now() / 1000);
    const cutoff = nowSec - HISTORY_WINDOW_SECONDS;

    const snap = await db
      .collection('devices')
      .doc(deviceId)
      .collection('history')
      .where('t', '>=', cutoff)
      .orderBy('t', 'asc')
      .limit(HISTORY_MAX_POINTS)
      .get();

    const points = [];
    snap.forEach((doc) => {
      const d = doc.data() || {};
      if (typeof d.t === 'number' && typeof d.m === 'number') {
        points.push({ t: d.t, m: d.m });
      }
    });

    return {
      statusCode: 200,
      body: JSON.stringify({
        maxPoints: HISTORY_MAX_POINTS,
        points,
      }),
    };
  } catch (err) {
    console.error('Failed to read history', err);
    return {
      statusCode: 500,
      body: JSON.stringify({
        ok: false,
        error: 'history_read_failed',
        message: err.message || 'Failed to read history',
      }),
    };
  }
};


