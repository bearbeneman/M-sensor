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
    const docSnap = await db.collection('devices').doc('default').get();
    if (!docSnap.exists) {
      return { statusCode: 404, body: 'No data' };
    }

    const data = docSnap.data() || {};

    // Shape the response to match LiveDataResponse expected by the Android app.
    const body = {
      name: data.name || null,
      raw: typeof data.raw === 'number' ? data.raw : 0,
      moisture: typeof data.moisture === 'number' ? data.moisture : 0,
      time: typeof data.time === 'string' ? data.time : new Date().toISOString(),
      ip: typeof data.ip === 'string' ? data.ip : '',
      wet: typeof data.wet === 'number' ? data.wet : 0,
      dry: typeof data.dry === 'number' ? data.dry : 0,
      interval: typeof data.interval === 'number' ? data.interval : 0,
      maxPoints: typeof data.maxPoints === 'number' ? data.maxPoints : 0,
      notifCooldown: typeof data.notifCooldown === 'number' ? data.notifCooldown : 0,
      alertLow: typeof data.alertLow === 'number' ? data.alertLow : 0,
      alertHigh: typeof data.alertHigh === 'number' ? data.alertHigh : 0,
      alertsEnabled:
        typeof data.alertsEnabled === 'boolean' ? data.alertsEnabled : true,
    };

    return {
      statusCode: 200,
      body: JSON.stringify(body),
    };
  } catch (err) {
    console.error('Failed to read latest data', err);
    return {
      statusCode: 500,
      body: JSON.stringify({
        ok: false,
        error: 'read_failed',
        message: err.message || 'Failed to read latest data',
      }),
    };
  }
};


