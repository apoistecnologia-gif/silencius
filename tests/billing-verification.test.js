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
