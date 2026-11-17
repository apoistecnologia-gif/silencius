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
const { createSign, createHash } = require('node:crypto');

const PRODUCT_ID = 'silencius_premium';
const GOOGLE_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';

function billingError(stage, status) {
  const error = new Error('Falha de verificação Google Play');
  error.stage = stage;
  if (Number.isInteger(status)) error.status = status;
  return error;
}
function diagnosticCode(error) {
  const stage = /^[a-z_]+$/.test(error?.stage || '') ? error.stage : 'unknown';
  const status = Number.isInteger(error?.status) ? String(error.status) : 'error';
  return stage + '_' + status;
}

// Defesa inicial, sem banco de dados nem dependência externa.
// Os limites são POR INSTÂNCIA da função serverless; não substituem firewall
// distribuído, detecção de abuso e vínculo de token a uma conta autenticada.
const RATE_WINDOW_MS = 60_000;
const RATE_TOKEN_LIMIT = 20;
const RATE_IP_LIMIT = 300;
const rateBuckets = new Map();

function withinRateLimit(key, max, now) {
  const existing = rateBuckets.get(key);
  const item = existing && now - existing.at < RATE_WINDOW_MS
    ? { at: existing.at, count: existing.count + 1 }
    : { at: now, count: 1 };
  rateBuckets.set(key, item);
  if (rateBuckets.size > 10_000) {
    for (const [id, value] of rateBuckets) {
      if (now - value.at >= RATE_WINDOW_MS) rateBuckets.delete(id);
    }
    // Limita a memória em cenários de ataques com tokens sempre diferentes.
    while (rateBuckets.size > 10_000) rateBuckets.delete(rateBuckets.keys().next().value);
  }
  return item.count <= max;
}

function isRateLimited(req, purchaseToken, now = Date.now()) {
  const tokenDigest = createHash('sha256').update(purchaseToken).digest('hex');
  const tokenAllowed = withinRateLimit('token:' + tokenDigest, RATE_TOKEN_LIMIT, now);
  // x-forwarded-for é fornecido pela infraestrutura de borda. Mesmo que
  // seja falseado, o limite separado por token continua sendo aplicado.
  const forwarded = req.headers?.['x-forwarded-for'];
  const rawIp = Array.isArray(forwarded) ? forwarded[0] : forwarded;
  const ip = String(rawIp || req.socket?.remoteAddress || '').split(',')[0].trim();
  const validIp = /^[0-9a-fA-F:.]{2,64}$/.test(ip) ? ip : '';
  const ipAllowed = validIp ? withinRateLimit('ip:' + validIp, RATE_IP_LIMIT, now) : true;
  return !tokenAllowed || !ipAllowed;
}

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
  if (!response.ok) throw billingError('google_oauth', response.status);
  const result = await response.json();
  if (!result.access_token) throw billingError('google_oauth', 502);
  return result.access_token;
}

async function playRequest(url, accessToken, method = 'GET') {
  const stage = method === 'POST' ? 'google_acknowledge' : 'google_purchase_lookup';
  let response;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      response = await fetch(url, {
        method,
        headers: {
          authorization: 'Bearer ' + accessToken,
          accept: 'application/json',
          ...(method === 'POST' ? { 'content-type': 'application/json' } : {})
        },
        ...(method === 'POST' ? { body: '{}' } : {}),
        signal: AbortSignal.timeout(10000)
      });
    } catch (_) {
      if (attempt === 0) continue;
      throw billingError(stage + '_network');
    }
    if (attempt === 0 && (response.status === 429 || response.status >= 500)) continue;
    break;
  }
  if (!response?.ok) throw billingError(stage, response?.status || 503);
  return response.status === 204 ? null : response.text().then(text => text ? JSON.parse(text) : null);
}

async function verifyPurchase(purchaseToken) {
  const packageName = process.env.GOOGLE_PLAY_PACKAGE_NAME || 'br.com.silencius.app';
  if (packageName !== 'br.com.silencius.app') throw billingError('invalid_package');
  const secret = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!secret) throw billingError('google_credentials_missing');
  const credentials = JSON.parse(secret);
  if (!credentials.client_email || !credentials.private_key) {
    throw billingError('google_credentials_invalid');
  }
  const token = await getAccessToken(credentials);
  const base = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' +
    encodeURIComponent(packageName) + '/purchases';
  const sub = await playRequest(
    base + '/subscriptionsv2/tokens/' + encodeURIComponent(purchaseToken), token
  );
  // purchases.subscriptionsv2.get NÃO retorna packageName no corpo.
  // A associação ao aplicativo é validada pelo próprio endpoint Google
  // /applications/{packageName}/purchases/subscriptionsv2/tokens/{token}.
  // Rejeitar packageName ausente aqui impediria toda compra legítima.
  if (!sub || typeof sub !== 'object' || !Array.isArray(sub.lineItems)) {
    throw billingError('google_purchase_invalid_response');
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
    state.acknowledged = true;
  } else {
    state.acknowledged = sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED';
  }
  return state;
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ error: 'Use POST' });
  }
  const size = Number(req.headers?.['content-length'] || 0);
  if (Number.isFinite(size) && size > 8192) {
    return res.status(413).json({ error: 'Corpo da requisição muito grande' });
  }
  const contentType = req.headers?.['content-type'] || '';
  if (!/^application\/json(?:\s*;|$)/i.test(contentType)) {
    return res.status(415).json({ error: 'Formato da requisição não aceito' });
  }
  const purchaseToken = req.body?.purchaseToken;
  if (typeof purchaseToken !== 'string' || purchaseToken.length < 8 ||
      purchaseToken.length > 4096 || /[\u0000-\u001f]/.test(purchaseToken)) {
    return res.status(400).json({ error: 'Token de compra inválido' });
  }
  if (isRateLimited(req, purchaseToken)) {
    res.setHeader('Retry-After', '60');
    return res.status(429).json({
      active: false,
      error: 'Muitas solicitações. Tente novamente em alguns instantes.',
      diagnosticCode: 'rate_limited'
    });
  }
  try {
    // Únicos metadados de diagnóstico: evento e estado. Nunca registrar purchaseToken,
    // credenciais Google ou dados do assinante.
    console.info('[SilenciusBilling] verification_received');
    const state = await verifyPurchase(purchaseToken);
    console.info('[SilenciusBilling] verification_result',
      state.active ? 'active' : 'inactive',
      state.acknowledged ? 'acknowledged' : 'not_acknowledged');
    return res.status(200).json(state);
  } catch (error) {
    // Diagnóstico restrito ao estágio e ao status HTTP. NÃO imprimir token, corpo, URL, credenciais ou dados do comprador.
    const diagnostic = diagnosticCode(error);
    console.warn('[SilenciusBilling] verification_failed', diagnostic);
    const code = error.status === 404 || error.status === 400 ? 400 : 503;
    return res.status(code).json({
      active: false,
      error: code === 400 ? 'Compra não encontrada na Google Play'
        : 'Validação temporariamente indisponível',
      diagnosticCode: diagnostic
    });
  }
};

module.exports.evaluateSubscription = evaluateSubscription;

module.exports.isRateLimited = isRateLimited;
