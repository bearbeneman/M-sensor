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
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method Not Allowed' };
  }

  let payload = {};
  try {
    payload = JSON.parse(event.body || '{}');
  } catch (err) {
    return { statusCode: 400, body: 'Invalid JSON' };
  }

  const { secret, ...reading } = payload;
  if (!secret || secret !== process.env.ALERT_SHARED_SECRET) {
    return { statusCode: 401, body: 'Unauthorized' };
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
    // For now we assume a single device per backend/site and overwrite one document.
    const docRef = db.collection('devices').doc('default');
    await docRef.set(
      {
        ...reading,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    return {
      statusCode: 200,
      body: JSON.stringify({ ok: true }),
    };
  } catch (err) {
    console.error('Failed to persist reading', err);
    return {
      statusCode: 500,
      body: JSON.stringify({
        ok: false,
        error: 'persist_failed',
        message: err.message || 'Failed to persist reading',
      }),
    };
  }
};


