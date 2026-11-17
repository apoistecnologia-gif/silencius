'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluateSubscription } = require('../api/verify-subscription');

const now = Date.parse('2026-11-17T12:00:00Z');
const future = new Date(now + 86_400_000).toISOString();
const past = new Date(now - 86_400_000).toISOString();
function info(subscriptionState, expiryTime, productId = 'silencius_premium') {
  return { subscriptionState, lineItems: [{ productId, expiryTime }] };
}

test('permite assinatura ativa e teste gratuito confirmado', () => {
  assert.equal(evaluateSubscription(info('SUBSCRIPTION_STATE_ACTIVE', future), now).active, true);
});
test('mantém acesso de cancelamento até o fim do período contratado', () => {
  assert.equal(evaluateSubscription(info('SUBSCRIPTION_STATE_CANCELED', future), now).active, true);
  assert.equal(evaluateSubscription(info('SUBSCRIPTION_STATE_CANCELED', past), now).active, false);
});
test('respeita período de carência', () => {
  assert.equal(evaluateSubscription(info('SUBSCRIPTION_STATE_IN_GRACE_PERIOD', future), now).active, true);
});
test('nunca libera compra pendente, expirada, suspensa ou vencida', () => {
  for (const state of ['SUBSCRIPTION_STATE_PENDING', 'SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_ON_HOLD']) {
    assert.equal(evaluateSubscription(info(state, future), now).active, false);
  }
  assert.equal(evaluateSubscription(info('SUBSCRIPTION_STATE_ACTIVE', past), now).active, false);
});
test('recusa outro produto, lineItems vazios e respostas incompletas', () => {
  assert.equal(evaluateSubscription(info('SUBSCRIPTION_STATE_ACTIVE', future, 'outro_produto'), now).active, false);
  assert.equal(evaluateSubscription({}, now).active, false);
  assert.equal(evaluateSubscription({ lineItems: null }, now).active, false);
});

function mockResponse() {
  return {
    statusCode: 200,
    headers: {},
    setHeader(name, value) { this.headers[name.toLowerCase()] = value; return this; },
    status(code) { this.statusCode = code; return this; },
    json(body) { this.body = body; return this; }
  };
}
async function expectRequestRejected(req, statusCode) {
  const res = mockResponse();
  await require('../api/verify-subscription')(req, res);
  assert.equal(res.statusCode, statusCode);
  assert.equal(res.headers['cache-control'], 'no-store');
}
test('nega GET e corpo malformado sem consultar o Google', async () => {
  await expectRequestRejected({ method: 'GET', headers: {} }, 405);
  await expectRequestRejected({
    method: 'POST', headers: { 'content-type': 'text/plain' },
    body: { purchaseToken: 'abcdefgh' }
  }, 415);
  await expectRequestRejected({
    method: 'POST', headers: { 'content-type': 'application/json', 'content-length': '9000' },
    body: { purchaseToken: 'abcdefgh' }
  }, 413);
  await expectRequestRejected({
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: { purchaseToken: 'curto' }
  }, 400);
});

test('integra SubscriptionPurchaseV2 sem packageName e reconhece compra ativa', async () => {
  // A resposta real de subscriptionsv2.get NÃO contém packageName.
  // Simula OAuth/Google Play sem qualquer compra, credencial ou rede reais.
  const fs = require('node:fs');
  const vm = require('node:vm');
  const { generateKeyPairSync } = require('node:crypto');
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const verificationSource = fs.readFileSync(
    require.resolve('../api/verify-subscription'), 'utf8'
  );
  const calls = [];
  const serviceAccount = {
    type: 'service_account',
    client_email: 'test@example.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' })
  };
  const fakeFetch = async (url, options) => {
    const address = String(url);
    if (address === 'https://oauth2.googleapis.com/token') {
      return { ok: true, status: 200, json: async () => ({ access_token: 'mock-google-token' }) };
    }
    if (address.includes('/subscriptionsv2/tokens/')) {
      calls.push('subscriptionsv2.get');
      const data = {
        subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
        acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
        lineItems: [{
          productId: 'silencius_premium',
          expiryTime: new Date(Date.now() + 60_000).toISOString()
        }]
        // Sem campo packageName: corresponde à especificação oficial do Google.
      };
      return { ok: true, status: 200, text: async () => JSON.stringify(data) };
    }
    if (address.endsWith(':acknowledge') && options?.method === 'POST') {
      calls.push('subscriptions.acknowledge');
      return { ok: true, status: 204 };
    }
    throw new Error('Endpoint inesperado no teste');
  };
  const harness = {
    module: { exports: {} },
    require,
    process: { env: {
      GOOGLE_PLAY_PACKAGE_NAME: 'br.com.silencius.app',
      GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify(serviceAccount)
    } },
    fetch: fakeFetch,
    Buffer,
    Date,
    URLSearchParams,
    AbortSignal,
    console: { info() {}, warn() {} }
  };
  vm.runInNewContext(verificationSource, harness, { filename: 'verify-subscription.js' });
  const response = mockResponse();
  await harness.module.exports({
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: { purchaseToken: 'fake-purchase-token-for-unit-test' }
  }, response);
  assert.equal(response.statusCode, 200);
  assert.equal(response.body.active, true);
  assert.equal(response.body.acknowledged, true);
  assert.deepEqual(calls, ['subscriptionsv2.get', 'subscriptions.acknowledge']);
});

test('limita tentativas excessivas com mesmo token e limpa janela no tempo', () => {
  const { isRateLimited } = require('../api/verify-subscription');
  const req = { headers: { 'x-forwarded-for': '192.0.2.41' } };
  const token = 'sample-token-rate-limit-20261117';
  for (let i = 0; i < 20; i++) {
    assert.equal(isRateLimited(req, token, now), false);
  }
  assert.equal(isRateLimited(req, token, now), true);
  assert.equal(isRateLimited(req, token, now + 61_000), false);
});

test('limite de token é independente de cabeçalho de IP alterado', () => {
  const { isRateLimited } = require('../api/verify-subscription');
  const token = 'sample-token-second-defense-20261117';
  for (let i = 0; i < 20; i++) {
    assert.equal(isRateLimited({
      headers: { 'x-forwarded-for': '192.0.2.' + (i + 1) }
    }, token, now), false);
  }
  assert.equal(isRateLimited({ headers: { 'x-forwarded-for': '198.51.100.99' } }, token, now), true);
});
