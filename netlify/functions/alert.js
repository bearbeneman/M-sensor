const admin = require('firebase-admin');

let firebaseInitialized = false;

function initFirebase() {
  if (firebaseInitialized) return;
  const serviceAccount = {
    type: 'service_account',
    project_id: process.env.FIREBASE_PROJECT_ID || 'replace-me',
    private_key: (process.env.FIREBASE_PRIVATE_KEY || '').replace(/\\n/g, '\n'),
    client_email: process.env.FIREBASE_CLIENT_EMAIL || 'replace@example.com'
  };

  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
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

  const { secret, state, moisture, deviceToken } = payload;
  if (!secret || secret !== process.env.ALERT_SHARED_SECRET) {
    return { statusCode: 401, body: 'Unauthorized' };
  }

  if (!deviceToken) {
    return { statusCode: 400, body: 'Missing deviceToken' };
  }

  initFirebase();

  const title = state === 'low' ? 'Soil getting dry' : 'Soil saturated';
  const body = `Moisture at ${moisture || 'unknown'}%.`;

  try {
    await admin.messaging().send({
      token: deviceToken,
      notification: { title, body },
      data: {
        state: state || 'unknown',
        moisture: String(moisture || '')
      }
    });
    return { statusCode: 200, body: JSON.stringify({ ok: true }) };
  } catch (err) {
    console.error(err);
    return { statusCode: 500, body: 'FCM send failed' };
  }
};

