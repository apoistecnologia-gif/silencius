'use strict';

/**
 * Verificação do Silêncius Premium pelo Google Play Developer API.
 * Projetado para uma função HTTP serverless em Node 20+ (ex.: Vercel).
 * NÃO armazene as credenciais Google no repositório ou no aplicativo.
 *
 * Variáveis privadas:
 *   GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = JSON da conta de serviço com permissão Play Console
 *   GOOGLE_PLAY_PACKAGE_NAME         = br.com.silencius.app
 */
const { createSign } = require('node:crypto');

const PRODUCT_ID = 'silencius_premium';
const GOOGLE_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';

function evaluateSubscription(info, now = Date.now()) {
  const lines = Array.isArray(info?.lineItems)
    ? info.lineItems.filter(item => item.productId === PRODUCT_ID)
    : [];
  const expiry = Math.max(0, ...lines.map(item => Date.parse(item.expiryTime || '') || 0));
  const validState = [
    'SUBSCRIPTION_STATE_ACTIVE',
    'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
    'SUBSCRIPTION_STATE_CANCELED' // cancelada mas ainda dentro do período já adquirido
  ].includes(info?.subscriptionState);
  return {
    active: validState && expiry > now,
    expiryTime: expiry ? new Date(expiry).toISOString() : null,
    subscriptionState: info?.subscriptionState || 'SUBSCRIPTION_STATE_UNSPECIFIED',
    productId: PRODUCT_ID
  };
}

async function getAccessToken(credentials) {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
  const claims = Buffer.from(JSON.stringify({
    iss: credentials.client_email,
    scope: GOOGLE_SCOPE,
    aud: GOOGLE_TOKEN_URL,
    iat: now,
    exp: now + 3000
  })).toString('base64url');
  const signingInput = header + '.' + claims;
  const signer = createSign('RSA-SHA256');
  signer.update(signingInput);
  signer.end();
  const jwt = signingInput + '.' + signer.sign(credentials.private_key).toString('base64url');
  const response = await fetch(GOOGLE_TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt
    }),
    signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) throw new Error('Falha de autenticação Google');
  const result = await response.json();
  if (!result.access_token) throw new Error('Google não retornou token de acesso');
  return result.access_token;
}

async function playRequest(url, accessToken, method = 'GET') {
  const response = await fetch(url, {
    method,
    headers: {
      authorization: 'Bearer ' + accessToken,
      accept: 'application/json',
      ...(method === 'POST' ? { 'content-type': 'application/json' } : {})
    },
    ...(method === 'POST' ? { body: '{}' } : {}),
    signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) {
    const err = new Error('Google Play Developer API retornou ' + response.status);
    err.status = response.status;
    throw err;
  }
  return response.status === 204 ? null : response.text().then(text => text ? JSON.parse(text) : null);
}

async function verifyPurchase(purchaseToken) {
  const packageName = process.env.GOOGLE_PLAY_PACKAGE_NAME || 'br.com.silencius.app';
  const secret = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!secret) throw new Error('Credenciais da Google Play não configuradas');
  const credentials = JSON.parse(secret);
  if (!credentials.client_email || !credentials.private_key) {
    throw new Error('Conta de serviço inválida');
  }
  const token = await getAccessToken(credentials);
  const base = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' +
    encodeURIComponent(packageName) + '/purchases';
  const sub = await playRequest(
    base + '/subscriptionsv2/tokens/' + encodeURIComponent(purchaseToken), token
  );
  if (sub.packageName && sub.packageName !== packageName) {
    throw new Error('Compra associada a outro aplicativo');
  }
  const state = evaluateSubscription(sub);
  if (state.active && sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING') {
    // Reconhecer somente compras confirmadas e elegíveis, nunca pendentes ou recusadas.
    await playRequest(
      base + '/subscriptions/' + encodeURIComponent(PRODUCT_ID) +
        '/tokens/' + encodeURIComponent(purchaseToken) + ':acknowledge',
      token,
      'POST'
    );
  }
  return state;
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ error: 'Use POST' });
  }
  const purchaseToken = req.body?.purchaseToken;
  if (typeof purchaseToken !== 'string' || purchaseToken.length < 8 ||
      purchaseToken.length > 4096 || /[\u0000-\u001f]/.test(purchaseToken)) {
    return res.status(400).json({ error: 'Token de compra inválido' });
  }
  try {
    const state = await verifyPurchase(purchaseToken);
    return res.status(200).json(state);
  } catch (error) {
    // Não registrar compra, token nem credenciais em logs.
    const code = error.status === 404 || error.status === 400 ? 400 : 503;
    return res.status(code).json({
      active: false,
      error: code === 400 ? 'Compra não encontrada na Google Play'
        : 'Validação temporariamente indisponível'
    });
  }
};

module.exports.evaluateSubscription = evaluateSubscription;
