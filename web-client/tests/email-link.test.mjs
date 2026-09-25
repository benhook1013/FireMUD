import assert from 'node:assert/strict';
import test from 'node:test';
import {
  captureEmailLinkLanding,
  completeEmailLink,
} from '../src/emailLinkLanding.ts';

test('verification GET captures a fragment token without dispatching an API mutation', () => {
  const changes = [];
  const landing = captureEmailLinkLanding(
    {
      pathname: '/verify-email',
      search: '',
      hash: '#token=verification%2Dtoken',
    },
    { replaceState: (...args) => changes.push(args) }
  );

  assert.deepEqual(landing, {
    kind: 'verify-email',
    token: 'verification-token',
  });
  assert.deepEqual(changes, [[null, '', '/verify-email']]);
});

test('reset GET captures a fragment token and never sends it in the landing URL', () => {
  const changes = [];
  const landing = captureEmailLinkLanding(
    {
      pathname: '/reset-password',
      search: '',
      hash: '#token=reset-token',
    },
    { replaceState: (...args) => changes.push(args) }
  );

  assert.deepEqual(landing, { kind: 'reset-password', token: 'reset-token' });
  assert.deepEqual(changes, [[null, '', '/reset-password']]);
});

test('landing removes query parameters without accepting them as a token', () => {
  const changes = [];
  const landing = captureEmailLinkLanding(
    {
      pathname: '/verify-email',
      search: '?token=legacy-query-secret',
      hash: '',
    },
    { replaceState: (...args) => changes.push(args) }
  );

  assert.deepEqual(landing, { kind: 'verify-email', token: null });
  assert.deepEqual(changes, [[null, '', '/verify-email']]);
});

test('only explicit verification completion sends a public POST', async () => {
  const calls = [];
  await completeEmailLink(
    { kind: 'verify-email', token: 'verification-token' },
    undefined,
    async (...args) => {
      calls.push(args);
      return { ok: true };
    }
  );

  assert.deepEqual(calls, [
    [
      '/api/account/auth/verify-email',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: 'verification-token' }),
      },
    ],
  ]);
});

test('reset completion submits the new password only in the POST body', async () => {
  const calls = [];
  await completeEmailLink(
    { kind: 'reset-password', token: 'reset-token' },
    'new-secret',
    async (...args) => {
      calls.push(args);
      return { ok: true };
    }
  );

  assert.deepEqual(calls, [
    [
      '/api/account/auth/complete-password-reset',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          token: 'reset-token',
          newPassword: 'new-secret',
        }),
      },
    ],
  ]);
});

test('missing token and failed completion never report success', async () => {
  let calls = 0;
  const request = async () => {
    calls += 1;
    return { ok: false };
  };

  await assert.rejects(
    completeEmailLink({ kind: 'verify-email', token: null }, undefined, request)
  );
  assert.equal(calls, 0);
  await assert.rejects(
    completeEmailLink(
      { kind: 'verify-email', token: 'bad-token' },
      undefined,
      request
    )
  );
  assert.equal(calls, 1);
});
