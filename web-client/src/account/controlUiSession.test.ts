// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  AccountControlUiApi,
  AccountControlUiLoginRequest,
} from '../api/accountControlUi';
import {
  ControlUiSessionError,
  createControlUiSessionStore,
} from './controlUiSession';

const origin = 'https://gateway.example.test';
const accountId = '11111111-1111-4111-8111-111111111111';
const subscriptionId = '22222222-2222-4222-8222-222222222222';
const authToken = 'header.payload.signature';
const stores = new Set<ReturnType<typeof createControlUiSessionStore>>();

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function ids() {
  const values = [
    '33333333-3333-4333-8333-333333333333',
    '44444444-4444-4444-8444-444444444444',
    '55555555-5555-4555-8555-555555555555',
    '66666666-6666-4666-8666-666666666666',
    '77777777-7777-4777-8777-777777777777',
    '88888888-8888-4888-8888-888888888888',
    '99999999-9999-4999-8999-999999999999',
  ];
  let index = 0;
  return () => values[index++] ?? 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
}

function successfulLogin(
  expiresAt = new Date(Date.now() + 60_000).toISOString()
) {
  return {
    accountId,
    authToken,
    expiresAt,
  };
}

function makeStore(
  options: {
    fetchImpl?: typeof fetch;
    now?: () => number;
    clearQueryCache?: () => void;
  } = {}
) {
  const store = createControlUiSessionStore({
    origin: () => origin,
    fetchImpl: options.fetchImpl ?? vi.fn<typeof fetch>(),
    now: options.now,
    idFactory: ids(),
    clearQueryCache: options.clearQueryCache,
  });
  stores.add(store);
  return store;
}

function loginApi(result = successfulLogin()): AccountControlUiApi {
  return {
    login: vi.fn<AccountControlUiApi['login']>().mockResolvedValue(result),
  };
}

function getRequest(
  url = new URL(
    `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice`,
    origin
  )
) {
  return {
    url,
    init: { method: 'GET', headers: { Accept: 'application/json' } },
  };
}

afterEach(() => {
  for (const store of stores) store.signOut();
  stores.clear();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('memory-only control-ui session owner', () => {
  it('uses one login attempt with stable IDs and attaches the current bearer only to the exact owner read', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ state: 'AWAITING_PAYMENT' }), {
        status: 200,
      })
    );
    const store = makeStore({ fetchImpl });
    const sentLoginRequests: AccountControlUiLoginRequest[] = [];
    const login = vi
      .fn<AccountControlUiApi['login']>()
      .mockImplementation(async (request) => {
        sentLoginRequests.push({ ...request });
        return successfulLogin();
      });
    const api: AccountControlUiApi = { login };

    const session = await store.signIn(api, {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    const [loginRequest] = sentLoginRequests;
    expect(login).toHaveBeenCalledOnce();
    expect(loginRequest).toMatchObject({
      requestId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
      callerContextId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    expect(login.mock.calls[0][0].secret).toBe('');
    expect(session).not.toHaveProperty('authToken');

    const { url, init } = getRequest();
    const response = await store.authenticatedRequest(url, init);
    expect(await response.json()).toEqual({ state: 'AWAITING_PAYMENT' });
    expect(fetchImpl).toHaveBeenCalledOnce();
    const [sentUrl, sentInit] = fetchImpl.mock.calls[0];
    expect(sentUrl).toEqual(url);
    expect(new Headers(sentInit?.headers).get('authorization')).toBe(
      `Bearer ${authToken}`
    );
    expect(sentInit).toMatchObject({
      method: 'GET',
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      mode: 'same-origin',
    });
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('rejects cross-origin, non-HTTPS, wrong-route, and caller-supplied bearer requests before dispatch', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const store = makeStore({ fetchImpl });
    await store.signIn(loginApi(), {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });

    const cases = [
      getRequest(
        new URL(
          `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice`,
          'https://attacker.example.test'
        )
      ),
      getRequest(
        new URL(
          `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice`,
          'http://gateway.example.test'
        )
      ),
      getRequest(new URL('/api/account/auth/logout', origin)),
      {
        ...getRequest(),
        init: {
          method: 'GET',
          headers: {
            Accept: 'application/json',
            Authorization: 'Bearer caller-supplied',
          },
        },
      },
    ];

    for (const { url, init } of cases) {
      await expect(
        store.authenticatedRequest(url, init)
      ).rejects.toBeInstanceOf(ControlUiSessionError);
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('clears local auth and query state on expiry and sign-out', async () => {
    vi.useFakeTimers();
    const clearQueryCache = vi.fn();
    const store = makeStore({ clearQueryCache });
    await store.signIn(loginApi(), {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    expect(store.getSession()).not.toBeNull();

    vi.advanceTimersByTime(60_001);
    expect(store.getSession()).toBeNull();
    expect(clearQueryCache).toHaveBeenCalled();

    const secondStore = makeStore({ clearQueryCache });
    await secondStore.signIn(loginApi(), {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    const countBeforeSignOut = clearQueryCache.mock.calls.length;
    secondStore.signOut();
    expect(secondStore.getSession()).toBeNull();
    expect(clearQueryCache.mock.calls.length).toBe(countBeforeSignOut + 1);
  });

  it('rearms expiry when the wall clock rolls back before the timer fires', async () => {
    const initialTime = 1_700_000_000_000;
    let now = initialTime;
    vi.useFakeTimers({ now: initialTime });
    const clearQueryCache = vi.fn();
    const store = makeStore({
      now: () => now,
      clearQueryCache,
    });
    await store.signIn(
      loginApi(successfulLogin(new Date(initialTime + 60_000).toISOString())),
      { email: 'owner@example.com', secret: 'primary-secret' }
    );
    const clearQueryCacheCalls = clearQueryCache.mock.calls.length;

    now = initialTime - 100_000;
    vi.advanceTimersByTime(60_000);

    expect(store.getSession()).not.toBeNull();
    expect(clearQueryCache.mock.calls.length).toBe(clearQueryCacheCalls);

    now = initialTime + 60_000;
    vi.advanceTimersByTime(160_000);

    expect(store.getSession()).toBeNull();
    expect(clearQueryCache.mock.calls.length).toBe(clearQueryCacheCalls + 1);
  });

  it('keeps an ordinary 503 session but clears it after an authentication rejection', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response('private error details', { status: 503 })
      );
    const store = makeStore({ fetchImpl });
    await store.signIn(loginApi(), {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    const { url, init } = getRequest();
    const unavailable = await store.authenticatedRequest(url, init);
    expect(unavailable.status).toBe(503);
    expect(store.getSession()).not.toBeNull();

    fetchImpl.mockResolvedValue(
      new Response('private error details', { status: 401 })
    );
    await expect(store.authenticatedRequest(url, init)).rejects.toMatchObject({
      code: 'AUTH_REJECTED',
    });
    expect(store.getSession()).toBeNull();
  });

  it('denies a stale owner response after sign-out even if fetch ignores abort', async () => {
    const pending = deferred<Response>();
    const fetchImpl = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    const store = makeStore({ fetchImpl });
    await store.signIn(loginApi(), {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    const { url, init } = getRequest();
    const request = store.authenticatedRequest(url, init);
    await Promise.resolve();
    expect(fetchImpl).toHaveBeenCalledOnce();

    store.signOut();
    pending.resolve(
      new Response(JSON.stringify({ state: 'COMMITTED' }), { status: 200 })
    );
    await expect(request).rejects.toMatchObject({ code: 'SESSION_REPLACED' });
    expect(store.getSession()).toBeNull();
  });

  it('aborts and denies old owner work when a signed-in identity is replaced', async () => {
    const pending = deferred<Response>();
    const fetchImpl = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    const clearQueryCache = vi.fn();
    const store = makeStore({ fetchImpl, clearQueryCache });
    await store.signIn(loginApi(), {
      email: 'first@example.com',
      secret: 'first-secret',
    });
    const oldSession = store.getSession();
    const { url, init } = getRequest();
    const request = store.authenticatedRequest(url, init);
    expect(fetchImpl).toHaveBeenCalledOnce();

    const nextSession = await store.signIn(
      loginApi({
        accountId,
        authToken: 'next.header.signature',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
      { email: 'second@example.com', secret: 'second-secret' }
    );
    pending.resolve(
      new Response(JSON.stringify({ state: 'COMMITTED' }), { status: 200 })
    );

    await expect(request).rejects.toMatchObject({ code: 'SESSION_REPLACED' });
    expect(oldSession?.identity).not.toBe(nextSession.identity);
    expect(store.getSession()?.identity).toBe(nextSession.identity);
    expect(clearQueryCache.mock.calls.length).toBeGreaterThanOrEqual(3);
  });

  it('denies a stale login response when a newer attempt replaces it', async () => {
    const earlier = deferred<ReturnType<typeof successfulLogin>>();
    const login = vi
      .fn<AccountControlUiApi['login']>()
      .mockReturnValueOnce(earlier.promise)
      .mockResolvedValueOnce(successfulLogin());
    const store = makeStore();
    const api: AccountControlUiApi = { login };

    const first = store
      .signIn(api, { email: 'first@example.com', secret: 'first-secret' })
      .catch((error: unknown) => error);
    const second = await store.signIn(api, {
      email: 'second@example.com',
      secret: 'second-secret',
    });
    earlier.resolve(successfulLogin());

    expect(await first).toMatchObject({ code: 'SESSION_REPLACED' });
    expect(store.getSession()?.identity).toBe(second.identity);
    expect(login).toHaveBeenCalledTimes(2);
    expect(login.mock.calls[0][0].requestId).not.toBe(
      login.mock.calls[1][0].requestId
    );
  });

  it('rejects a buffered success body if the session changes before JSON is consumed', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ state: 'AWAITING_PAYMENT' }), {
        status: 200,
      })
    );
    const store = makeStore({ fetchImpl });
    await store.signIn(loginApi(), {
      email: 'owner@example.com',
      secret: 'primary-secret',
    });
    const { url, init } = getRequest();
    const response = await store.authenticatedRequest(url, init);
    store.signOut();
    await expect(response.json()).rejects.toMatchObject({
      code: 'SESSION_REPLACED',
    });
  });
});
