// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from '../App';
import {
  AccountControlUiApiError,
  type AccountControlUiApi,
} from '../api/accountControlUi';
import type { HostingStripe } from '../billing/hostingStripe';
import {
  createControlUiSessionStore,
  type ControlUiSessionStore,
} from './controlUiSession';

const origin = 'https://gateway.example.test';
const subscriptionId = '11111111-1111-4111-8111-111111111111';
const accountId = '22222222-2222-4222-8222-222222222222';
const checkoutPath = `/account/subscriptions/${subscriptionId}/checkout`;
const stores = new Set<ControlUiSessionStore>();

const firstInvoice = {
  subscriptionId,
  invoiceId: 'in_existing',
  paymentIntentId: 'pi_existing',
  paymentInstrumentId: '33333333-3333-4333-8333-333333333333',
  amount: '1299',
  currency: 'usd',
  tupleDigest: `sha256:${'a'.repeat(64)}`,
  expectedRowVersion: 3,
  state: 'AWAITING_PAYMENT',
  expiresAt: '2099-01-01T00:00:00Z',
  confirmationRequestId: null,
  preRowOriginalRequestId: null,
  authorizationVersion: 0,
};

function makeIds() {
  const ids = [
    '44444444-4444-4444-8444-444444444444',
    '55555555-5555-4555-8555-555555555555',
    '66666666-6666-4666-8666-666666666666',
    '77777777-7777-4777-8777-777777777777',
    '88888888-8888-4888-8888-888888888888',
  ];
  let index = 0;
  return () => ids[index++] ?? '99999999-9999-4999-8999-999999999999';
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function setupApp(
  options: {
    key?: string;
    invoiceStatus?: number;
    loadStripeClient?: (publishableKey: string) => Promise<HostingStripe>;
  } = {}
) {
  const queries = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const clearQueryCache = vi.fn(() => queries.clear());
  const fetchImpl = vi
    .fn<typeof fetch>()
    .mockImplementation(async (input, init) => {
      const url = input instanceof URL ? input : new URL(String(input));
      if (
        url.pathname ===
          `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice` &&
        (init?.method ?? 'GET') === 'GET'
      ) {
        if (options.invoiceStatus && options.invoiceStatus !== 200) {
          return new Response('private owner response', {
            status: options.invoiceStatus,
          });
        }
        return new Response(JSON.stringify(firstInvoice), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('private owner response', { status: 404 });
    });
  const sessionStore = createControlUiSessionStore({
    origin: () => origin,
    fetchImpl,
    idFactory: makeIds(),
    clearQueryCache,
  });
  stores.add(sessionStore);
  const api: AccountControlUiApi = {
    login: vi.fn().mockResolvedValue({
      accountId,
      authToken: 'header.payload.signature',
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    }),
  };
  const stripe: HostingStripe = {
    handleNextAction: vi.fn().mockResolvedValue({}),
  };
  const loadStripeClient =
    options.loadStripeClient ?? vi.fn().mockResolvedValue(stripe);
  window.history.replaceState({}, '', checkoutPath);
  const view = render(
    <QueryClientProvider client={queries}>
      <App
        accountCheckoutRouteProps={{
          sessionStore,
          api,
          stripePublishableKey: options.key ?? 'pk_test_firemud',
          loadStripeClient,
        }}
      />
    </QueryClientProvider>
  );
  return {
    ...view,
    api,
    queries,
    clearQueryCache,
    fetchImpl,
    sessionStore,
    loadStripeClient,
  };
}

async function signIn() {
  fireEvent.change(screen.getByLabelText('Account email'), {
    target: { value: 'owner@example.com' },
  });
  fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
    target: { value: 'primary-secret' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByText('in_existing');
}

afterEach(() => {
  cleanup();
  for (const store of stores) store.signOut();
  stores.clear();
  window.history.replaceState({}, '', '/');
  vi.restoreAllMocks();
});

describe('App-owned first-invoice route', () => {
  it('renders the login form, loads only the existing owner invoice, and signs out locally', async () => {
    const { api, fetchImpl, clearQueryCache } = setupApp();
    expect(
      screen.getByRole('heading', { name: 'Sign in to Account' })
    ).toBeTruthy();
    await signIn();

    expect(api.login).toHaveBeenCalledOnce();
    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(fetchImpl.mock.calls[0][0]).toEqual(
      new URL(
        `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice`,
        origin
      )
    );
    expect(
      new Headers(fetchImpl.mock.calls[0][1]?.headers).get('authorization')
    ).toBe('Bearer header.payload.signature');
    expect(
      fetchImpl.mock.calls.some(([input]) =>
        String(input).includes('/first-invoice/confirm')
      )
    ).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await screen.findByRole('heading', { name: 'Sign in to Account' });
    expect(
      screen.getByText(/Account token revocation is not available here/)
    ).toBeTruthy();
    expect(clearQueryCache).toHaveBeenCalled();
    expect(
      fetchImpl.mock.calls.some(([input]) =>
        String(input).includes('/auth/logout')
      )
    ).toBe(false);
  });

  it('keeps Account status recovery available when the Vite key is missing', async () => {
    const loadStripeClient = vi.fn();
    const { fetchImpl } = setupApp({ key: '', loadStripeClient });
    await signIn();
    expect(screen.getByText(/publishable key is not configured/)).toBeTruthy();
    expect(loadStripeClient).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole('button', { name: 'Check Account status' })
    );
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    expect(
      fetchImpl.mock.calls.every(
        ([, init]) => (init?.method ?? 'GET') === 'GET'
      )
    ).toBe(true);
  });

  it('reads and refreshes Account status while Stripe loading fails', async () => {
    const loadStripeClient = vi
      .fn()
      .mockRejectedValue(new Error('script blocked'));
    const { fetchImpl } = setupApp({ loadStripeClient });
    await signIn();
    await screen.findByText(/Stripe authentication is unavailable/);
    fireEvent.click(
      screen.getByRole('button', { name: 'Check Account status' })
    );
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    expect(
      fetchImpl.mock.calls.every(
        ([, init]) => (init?.method ?? 'GET') === 'GET'
      )
    ).toBe(true);
  });

  it('reads and refreshes Account status while Stripe loading is pending', async () => {
    const loading = deferred<HostingStripe>();
    const loadStripeClient = vi.fn().mockReturnValue(loading.promise);
    const { fetchImpl } = setupApp({ loadStripeClient });
    await signIn();
    expect(
      screen.getByText('Loading secure payment authentication.')
    ).toBeTruthy();
    fireEvent.click(
      screen.getByRole('button', { name: 'Check Account status' })
    );
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    expect(
      fetchImpl.mock.calls.every(
        ([, init]) => (init?.method ?? 'GET') === 'GET'
      )
    ).toBe(true);
  });

  it.each(['unchanged', 'replaced key', 'replaced loader'])(
    'binds an in-flight confirmation to its %s Stripe loading context',
    async (contextChange) => {
      const loading = deferred<HostingStripe>();
      const replacementLoading = deferred<HostingStripe>();
      const confirmation = deferred<Response>();
      const stripe: HostingStripe = {
        handleNextAction: vi.fn().mockResolvedValue({}),
      };
      const replacement: HostingStripe = {
        handleNextAction: vi.fn().mockResolvedValue({}),
      };
      const loader = vi
        .fn()
        .mockReturnValueOnce(loading.promise)
        .mockReturnValue(replacementLoading.promise);
      const replacementLoader = vi
        .fn()
        .mockReturnValue(replacementLoading.promise);
      const { api, fetchImpl, queries, rerender, sessionStore } = setupApp({
        loadStripeClient: loader,
      });
      const read = fetchImpl.getMockImplementation()!;
      fetchImpl.mockImplementation(async (input, init) => {
        if (String(input).endsWith('/reauthenticate')) {
          return new Response(
            JSON.stringify({
              evidenceId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            }),
            { status: 200 }
          );
        }
        if (String(input).endsWith('/confirm')) return confirmation.promise;
        return read(input, init);
      });
      await signIn();
      fireEvent.change(screen.getByLabelText('Account email'), {
        target: { value: 'owner@example.com' },
      });
      fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
        target: { value: 'primary-secret' },
      });
      fireEvent.click(screen.getByRole('checkbox'));
      fireEvent.click(
        screen.getByRole('button', { name: 'Authorize first-invoice charge' })
      );
      await waitFor(() =>
        expect(
          fetchImpl.mock.calls.filter(([input]) =>
            String(input).endsWith('/confirm')
          )
        ).toHaveLength(1)
      );

      if (contextChange !== 'unchanged') {
        rerender(
          <QueryClientProvider client={queries}>
            <App
              accountCheckoutRouteProps={{
                sessionStore,
                api,
                stripePublishableKey:
                  contextChange === 'replaced key'
                    ? 'pk_test_replaced'
                    : 'pk_test_firemud',
                loadStripeClient:
                  contextChange === 'replaced loader'
                    ? replacementLoader
                    : loader,
              }}
            />
          </QueryClientProvider>
        );
      }
      await act(async () => {
        loading.resolve(stripe);
        await loading.promise;
      });
      if (contextChange !== 'unchanged') {
        expect(
          screen.getByText('Loading secure payment authentication.')
        ).toBeTruthy();
        await act(async () => {
          replacementLoading.resolve(replacement);
          await replacementLoading.promise;
        });
      }
      await waitFor(() =>
        expect(
          screen.queryByText('Loading secure payment authentication.')
        ).toBeNull()
      );
      await act(async () => {
        confirmation.resolve(
          new Response(
            JSON.stringify({
              status: 'requires_action',
              clientSecret: 'pi_existing_secret_ephemeral',
            }),
            { status: 200 }
          )
        );
        await confirmation.promise;
      });
      if (contextChange === 'unchanged') {
        await waitFor(() =>
          expect(stripe.handleNextAction).toHaveBeenCalledExactlyOnceWith({
            clientSecret: 'pi_existing_secret_ephemeral',
          })
        );
      } else {
        await screen.findByText(
          /Stripe authentication is unavailable. Check Account status/
        );
        expect(stripe.handleNextAction).not.toHaveBeenCalled();
      }
      expect(replacement.handleNextAction).not.toHaveBeenCalled();
      expect(
        fetchImpl.mock.calls.filter(([input]) =>
          String(input).endsWith('/confirm')
        )
      ).toHaveLength(1);
      expect(
        fetchImpl.mock.calls.some(([input]) => String(input).endsWith('/renew'))
      ).toBe(false);
      expect(screen.queryByText(/Account confirmed and committed/)).toBeNull();
      expect(document.body.textContent).not.toContain(
        'pi_existing_secret_ephemeral'
      );
    }
  );

  it.each([0, 429, 502, 503])(
    'shows safe temporary feedback for sign-in status %i without retrying',
    async (status) => {
      const { api } = setupApp();
      vi.mocked(api.login).mockRejectedValue(
        new AccountControlUiApiError(status)
      );
      fireEvent.change(screen.getByLabelText('Account email'), {
        target: { value: 'owner@example.com' },
      });
      fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
        target: { value: 'primary-secret' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

      await screen.findByText(
        status === 429
          ? 'Account sign-in is temporarily unavailable. Try again later.'
          : /Account sign-in could not be confirmed/
      );
      expect(api.login).toHaveBeenCalledOnce();
      expect(vi.mocked(api.login).mock.calls[0][0].secret).toBe('');
      expect(
        (
          screen.getByLabelText(
            'Primary authentication secret'
          ) as HTMLInputElement
        ).value
      ).toBe('');
    }
  );

  it('re-enters a consumed one-time code and explicitly recovers the original sign-in', async () => {
    const { api } = setupApp();
    const login = vi.mocked(api.login);
    let original: { requestId: string; callerContextId: string } | undefined;
    login
      .mockImplementationOnce(
        async ({ requestId, callerContextId, secret }) => {
          expect(secret).toBe('012345');
          original = { requestId, callerContextId };
          throw new AccountControlUiApiError(0);
        }
      )
      .mockImplementationOnce(
        async ({ requestId, callerContextId, secret }) => {
          if (
            !original ||
            requestId !== original.requestId ||
            callerContextId !== original.callerContextId ||
            secret !== '012345'
          ) {
            throw new AccountControlUiApiError(401);
          }
          return {
            accountId,
            authToken: 'header.payload.signature',
            expiresAt: new Date(Date.now() + 60_000).toISOString(),
          };
        }
      );
    fireEvent.change(screen.getByLabelText('Account email'), {
      target: { value: 'owner@example.com' },
    });
    const secret = screen.getByLabelText(
      'Primary authentication secret'
    ) as HTMLInputElement;
    fireEvent.change(secret, { target: { value: '012345' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByText(/re-enter the same secret and select Sign in/);
    expect(secret.value).toBe('');
    expect(login).toHaveBeenCalledOnce();
    expect(login.mock.calls[0][0].secret).toBe('');
    expect(
      (screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement)
        .disabled
    ).toBe(true);

    fireEvent.change(secret, { target: { value: '012345' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByText('in_existing');
    expect(login).toHaveBeenCalledTimes(2);
    expect(login.mock.calls[1][0].secret).toBe('');
  });

  it.each(['navigation', 'replacement login'])(
    'restores only the server original operation after %s before a confirmation row exists',
    async (interruption) => {
      const { fetchImpl } = setupApp();
      let original: string | null = null;
      const openingEvidence = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
      const freshEvidence = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
      const proofs: Array<Record<string, unknown>> = [];
      const renewals: Array<Record<string, unknown>> = [];
      let confirmations = 0;
      fetchImpl.mockImplementation(async (input, init) => {
        const path = new URL(String(input)).pathname;
        if ((init?.method ?? 'GET') === 'GET') {
          return Response.json({
            ...firstInvoice,
            preRowOriginalRequestId: original,
          });
        }
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        if (path.endsWith('/reauthenticate')) {
          proofs.push(body);
          const binding = body.billingBinding as Record<string, unknown>;
          if (original === null) original = binding.originalRequestId as string;
          return Response.json({
            evidenceId: proofs.length === 1 ? openingEvidence : freshEvidence,
          });
        }
        if (path.endsWith('/confirm')) {
          confirmations += 1;
          return new Response(null, { status: 409 });
        }
        if (path.endsWith('/renew')) {
          renewals.push(body);
          return Response.json({ status: 'processing', clientSecret: null });
        }
        return new Response(null, { status: 404 });
      });
      await signIn();
      async function authorize() {
        fireEvent.change(screen.getByLabelText('Account email'), {
          target: { value: 'owner@example.com' },
        });
        fireEvent.change(
          screen.getByLabelText('Primary authentication secret'),
          { target: { value: 'fresh-primary-secret' } }
        );
        fireEvent.click(screen.getByRole('checkbox'));
        fireEvent.click(
          screen.getByRole('button', {
            name: /Authorize first-invoice charge|Resume exact confirmation/,
          })
        );
      }
      await authorize();
      await screen.findByRole('button', { name: 'Renew exact authorization' });
      expect(proofs).toHaveLength(1);
      expect(confirmations).toBe(1);
      if (interruption === 'navigation') {
        act(() => {
          window.history.pushState({}, '', '/');
          window.dispatchEvent(new PopStateEvent('popstate'));
        });
        await screen.findByRole('heading', { name: 'Vite + React' });
        act(() => {
          window.history.pushState({}, '', checkoutPath);
          window.dispatchEvent(new PopStateEvent('popstate'));
        });
      } else {
        fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
        await screen.findByRole('heading', { name: 'Sign in to Account' });
        await signIn();
      }
      const renew = await screen.findByRole('button', {
        name: 'Renew exact authorization',
      });
      expect(screen.queryByRole('checkbox')).toBeNull();
      expect(
        screen.queryByLabelText('Primary authentication secret')
      ).toBeNull();
      expect(proofs).toHaveLength(1);
      expect(renewals).toHaveLength(0);
      fireEvent.click(renew);
      expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(
        false
      );
      expect(
        (
          screen.getByLabelText(
            'Primary authentication secret'
          ) as HTMLInputElement
        ).value
      ).toBe('');
      await authorize();
      await waitFor(() => expect(renewals).toHaveLength(1));
      expect(confirmations).toBe(1);
      expect(proofs).toHaveLength(2);
      expect(renewals[0]).toMatchObject({
        requestId: original,
        expectedAuthorizationVersion: 0,
        reauthenticationEvidenceId: freshEvidence,
        acceptCharge: true,
      });
      expect(renewals[0].authorizationRequestId).not.toBe(original);
      expect(proofs[1].requestId).not.toBe(proofs[0].requestId);
      expect(proofs[1].billingBinding).toMatchObject({
        originalRequestId: original,
        authorizationPurpose: 'PRE_ROW_RENEWAL',
        expectedAuthorizationVersion: 0,
      });
    }
  );

  it('handles browser Back and Forward by remounting read-only checkout without confirming', async () => {
    const { fetchImpl } = setupApp();
    await signIn();
    expect(fetchImpl).toHaveBeenCalledOnce();

    window.history.pushState({}, '', '/');
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(
      await screen.findByRole('heading', { name: 'Vite + React' })
    ).toBeTruthy();

    window.history.pushState({}, '', checkoutPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
    await screen.findByText('in_existing');
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    expect(
      fetchImpl.mock.calls.some(([input]) =>
        String(input).includes('/first-invoice/confirm')
      )
    ).toBe(false);
  });

  it('preserves Account recovery and ignores replaced Stripe context without confirming', async () => {
    const stripeA: HostingStripe = { handleNextAction: vi.fn() };
    const stripeB: HostingStripe = { handleNextAction: vi.fn() };
    const stripeC: HostingStripe = { handleNextAction: vi.fn() };
    const loadA = vi.fn().mockResolvedValue(stripeA);
    const { api, fetchImpl, queries, rerender, sessionStore } = setupApp({
      loadStripeClient: loadA,
    });
    await signIn();
    expect(screen.getByText('Hosting first invoice')).toBeTruthy();

    const loadBDeferred = deferred<HostingStripe>();
    const loadB = vi.fn().mockReturnValue(loadBDeferred.promise);
    const renderWith = (
      key: string,
      loadStripeClient: (publishableKey: string) => Promise<HostingStripe>
    ) =>
      rerender(
        <QueryClientProvider client={queries}>
          <App
            accountCheckoutRouteProps={{
              sessionStore,
              api,
              stripePublishableKey: key,
              loadStripeClient,
            }}
          />
        </QueryClientProvider>
      );

    renderWith('pk_test_replaced', loadB);
    expect(screen.getByText('Hosting first invoice')).toBeTruthy();
    expect(
      screen.getByText('Loading secure payment authentication.')
    ).toBeTruthy();
    await waitFor(() => expect(loadB).toHaveBeenCalledWith('pk_test_replaced'));

    const loadCDeferred = deferred<HostingStripe>();
    const loadC = vi.fn().mockReturnValue(loadCDeferred.promise);
    renderWith('pk_test_replacedagain', loadC);
    await waitFor(() =>
      expect(loadC).toHaveBeenCalledWith('pk_test_replacedagain')
    );

    await act(async () => {
      loadBDeferred.resolve(stripeB);
      await loadBDeferred.promise;
    });
    expect(screen.getByText('Hosting first invoice')).toBeTruthy();
    expect(
      screen.getByText('Loading secure payment authentication.')
    ).toBeTruthy();

    await act(async () => {
      loadCDeferred.resolve(stripeC);
      await loadCDeferred.promise;
    });
    await screen.findByText('in_existing');
    expect(
      fetchImpl.mock.calls.some(([input]) => String(input).includes('/confirm'))
    ).toBe(false);
    expect(stripeA.handleNextAction).not.toHaveBeenCalled();
    expect(stripeB.handleNextAction).not.toHaveBeenCalled();
    expect(stripeC.handleNextAction).not.toHaveBeenCalled();
  });

  it('unmounts the checkout and returns to login when Account rejects the current bearer', async () => {
    const { sessionStore, fetchImpl } = setupApp({ invoiceStatus: 403 });
    fireEvent.change(screen.getByLabelText('Account email'), {
      target: { value: 'owner@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
      target: { value: 'primary-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledOnce());
    await waitFor(() => expect(sessionStore.getSession()).toBeNull());
    await screen.findByRole('heading', { name: 'Sign in to Account' });
    expect(screen.queryByText('Hosting first invoice')).toBeNull();
  });
});
