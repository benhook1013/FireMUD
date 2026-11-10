import { describe, expect, it, vi } from 'vitest';
import {
  AccountControlUiApiError,
  createAccountControlUiApi,
  maxControlUiSessionLifetimeMs,
  type AccountControlUiLoginRequest,
} from './accountControlUi';

const origin = 'https://gateway.example.test';
const accountId = '11111111-1111-4111-8111-111111111111';
const requestId = '22222222-2222-4222-8222-222222222222';
const callerContextId = '33333333-3333-4333-8333-333333333333';
const now = Date.parse('2026-11-07T12:00:00.000Z');
const authToken = 'header.payload.signature';

function successfulLogin(expiresAt = new Date(now + 60_000).toISOString()) {
  return new Response(
    JSON.stringify({
      status: 'SUCCESS',
      data: { accountId, authToken, expiresAt },
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } }
  );
}

const loginRequest: AccountControlUiLoginRequest = {
  requestId,
  callerContextId,
  email: 'owner@example.com',
  secret: 'primary-secret',
};

const invalidResponses: Array<[string, () => unknown]> = [
  [
    'extra response field',
    () => ({
      status: 'SUCCESS',
      data: {
        accountId,
        authToken,
        expiresAt: new Date(now + 60_000).toISOString(),
      },
      debug: 'unexpected',
    }),
  ],
  [
    'wrong status',
    () => ({
      status: 'FAILED',
      data: {
        accountId,
        authToken,
        expiresAt: new Date(now + 60_000).toISOString(),
      },
    }),
  ],
  [
    'non-JWT bearer',
    () => ({
      status: 'SUCCESS',
      data: {
        accountId,
        authToken: 'not-a-jwt',
        expiresAt: new Date(now + 60_000).toISOString(),
      },
    }),
  ],
  [
    'expired session',
    () => ({
      status: 'SUCCESS',
      data: {
        accountId,
        authToken,
        expiresAt: new Date(now - 1).toISOString(),
      },
    }),
  ],
  [
    'unbounded session lifetime',
    () => ({
      status: 'SUCCESS',
      data: {
        accountId,
        authToken,
        expiresAt: new Date(
          now + maxControlUiSessionLifetimeMs + 1
        ).toISOString(),
      },
    }),
  ],
];

describe('account control-ui login transport', () => {
  it('posts one stable request to the same-origin HTTPS Account route without cookies', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(successfulLogin());
    const api = createAccountControlUiApi(origin, fetchImpl, () => now);

    await expect(api.login(loginRequest)).resolves.toEqual({
      accountId,
      authToken,
      expiresAt: new Date(now + 60_000).toISOString(),
    });

    expect(fetchImpl).toHaveBeenCalledOnce();
    const [input, init] = fetchImpl.mock.calls[0];
    expect(input).toEqual(new URL('/api/account/auth/login', origin));
    expect(init).toMatchObject({
      method: 'POST',
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      mode: 'same-origin',
    });
    expect(JSON.parse(String(init?.body))).toEqual(loginRequest);
    expect(init?.headers).toEqual({
      'Content-Type': 'application/json',
      Accept: 'application/json',
    });
  });

  it('does not retry a transport failure or expose secret or server text', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockRejectedValue(new Error('primary-secret leaked by transport'));
    const api = createAccountControlUiApi(origin, fetchImpl, () => now);

    let error: unknown;
    try {
      await api.login(loginRequest);
    } catch (caught) {
      error = caught;
    }
    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(error).toBeInstanceOf(AccountControlUiApiError);
    expect(error).toMatchObject({ status: 0 });
    expect((error as Error).message).not.toContain('primary-secret');
  });

  it('discards non-success response bodies and preserves only the status', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response('sensitive internal response', { status: 503 })
      );
    const api = createAccountControlUiApi(origin, fetchImpl, () => now);

    await expect(api.login(loginRequest)).rejects.toMatchObject({
      name: 'AccountControlUiApiError',
      status: 503,
      message: 'Account sign-in could not be completed.',
    });
    expect(fetchImpl).toHaveBeenCalledOnce();
  });

  it.each(invalidResponses)(
    'rejects %s response data',
    async (_name, makeBody) => {
      const fetchImpl = vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response(JSON.stringify(makeBody()), { status: 200 })
        );
      const api = createAccountControlUiApi(origin, fetchImpl, () => now);
      await expect(api.login(loginRequest)).rejects.toBeInstanceOf(
        AccountControlUiApiError
      );
    }
  );

  it('rejects insecure origins before dispatch', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    expect(() =>
      createAccountControlUiApi('http://gateway.example.test', fetchImpl)
    ).toThrow('same-origin HTTPS');
    const api = createAccountControlUiApi(
      () => 'http://gateway.example.test',
      fetchImpl
    );
    await expect(api.login(loginRequest)).rejects.toBeInstanceOf(Error);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('rejects oversized response bodies', async () => {
    const oversized = (await successfulLogin().text()) + ' '.repeat(40 * 1024);
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(oversized, { status: 200 }));
    const api = createAccountControlUiApi(origin, fetchImpl, () => now);

    await expect(api.login(loginRequest)).rejects.toBeInstanceOf(
      AccountControlUiApiError
    );
  });
});
