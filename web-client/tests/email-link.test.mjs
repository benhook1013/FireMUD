import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  captureEmailLinkLanding,
  completeEmailLink,
  emailLinkFailureMessage,
  EmailLinkCompletionError,
} from '../src/emailLinkLanding.ts';

test('format check and fix cover the same source and test globs', async () => {
  const { scripts } = JSON.parse(
    await readFile(new URL('../package.json', import.meta.url), 'utf8')
  );
  const getGlobs = (command) =>
    Array.from(command.matchAll(/"([^"]+)"/g), (match) => match[1]);
  const expectedGlobs = ['src/**/*.{ts,tsx}', 'tests/**/*.mjs'];

  assert.deepEqual(getGlobs(scripts.format), expectedGlobs);
  assert.deepEqual(getGlobs(scripts['format:fix']), expectedGlobs);
});

test('reset form and API schema retain the Account password bounds', async () => {
  const form = await readFile(
    new URL('../src/EmailLinkLanding.tsx', import.meta.url),
    'utf8'
  );
  const dto = await readFile(
    new URL(
      '../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/dto/CompletePasswordResetRequest.java',
      import.meta.url
    ),
    'utf8'
  );
  const schema = await readFile(
    new URL(
      '../../services/account-service/src/main/resources/openapi.yaml',
      import.meta.url
    ),
    'utf8'
  );
  assert.match(dto, /@Size\(min = 6, max = 100\) String newPassword/);
  assert.match(
    form,
    /<input\b[^>]*maxLength=\{100\}[^>]*minLength=\{6\}[^>]*name="newPassword"/
  );
  const resetSchema = schema
    .split('    CompletePasswordResetRequest:')[1]
    ?.split('    UsernameRecoveryRequest:')[0];
  assert.ok(resetSchema, 'CompletePasswordResetRequest must exist');
  assert.match(
    resetSchema,
    /newPassword:\s+type: string\s+minLength: 6\s+maxLength: 100/
  );
});

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

test('completion preserves HTTP failure categories without exposing the token', async () => {
  for (const status of [400, 429, 503]) {
    await assert.rejects(
      completeEmailLink(
        { kind: 'verify-email', token: 'secret-token' },
        undefined,
        async () => ({ ok: false, status })
      ),
      (error) => {
        assert.ok(error instanceof EmailLinkCompletionError);
        assert.equal(error.kind, 'http');
        assert.equal(error.status, status);
        assert.doesNotMatch(error.message, /secret-token/);
        return true;
      }
    );
  }
});

test('completion preserves network failures without retrying the POST', async () => {
  let calls = 0;
  await assert.rejects(
    completeEmailLink(
      { kind: 'verify-email', token: 'secret-token' },
      undefined,
      async () => {
        calls += 1;
        throw new Error('offline');
      }
    ),
    (error) => {
      assert.ok(error instanceof EmailLinkCompletionError);
      assert.equal(error.kind, 'network');
      assert.equal(error.status, null);
      assert.doesNotMatch(error.message, /secret-token|offline/);
      return true;
    }
  );
  assert.equal(calls, 1);
});

test('failure messages distinguish rejected input from uncertain completion', () => {
  const rejected = emailLinkFailureMessage(
    'verify-email',
    new EmailLinkCompletionError('http', 400)
  );
  assert.match(rejected, /could not accept this request/i);
  assert.doesNotMatch(rejected, /invalid|expired/i);

  for (const failure of [
    new EmailLinkCompletionError('http', 429),
    new EmailLinkCompletionError('http', 503),
    new EmailLinkCompletionError('network', null),
  ]) {
    const verification = emailLinkFailureMessage('verify-email', failure);
    assert.match(verification, /could not confirm/i);
    assert.match(verification, /check your account after signing in/i);

    const reset = emailLinkFailureMessage('reset-password', failure);
    assert.match(reset, /could not confirm/i);
    assert.match(reset, /try signing in with your intended new password/i);
    assert.doesNotMatch(reset, /invalid|expired/i);
  }

  assert.match(
    emailLinkFailureMessage(
      'verify-email',
      new EmailLinkCompletionError('missing-token', null)
    ),
    /invalid or has expired/i
  );
});
