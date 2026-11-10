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

  it('fails closed before network or Stripe loading when the Vite key is missing', () => {
    const queries = new QueryClient();
    const fetchImpl = vi.fn<typeof fetch>();
    const api: AccountControlUiApi = { login: vi.fn() };
    const loadStripeClient = vi.fn();
    const sessionStore = createControlUiSessionStore({
      origin: () => origin,
      fetchImpl,
      idFactory: makeIds(),
      clearQueryCache: () => queries.clear(),
    });
    stores.add(sessionStore);
    window.history.replaceState({}, '', checkoutPath);
    render(
      <QueryClientProvider client={queries}>
        <App
          accountCheckoutRouteProps={{
            sessionStore,
            api,
            stripePublishableKey: '',
            loadStripeClient,
          }}
        />
      </QueryClientProvider>
    );
    expect(screen.getByText(/No checkout request was sent/)).toBeTruthy();
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(api.login).not.toHaveBeenCalled();
    expect(loadStripeClient).not.toHaveBeenCalled();
  });

  it.each([429, 0])(
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
        'Account sign-in is temporarily unavailable. Try again later.'
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

  it('hides replaced Stripe context and ignores a stale loader result without confirming', async () => {
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
    expect(screen.queryByText('Hosting first invoice')).toBeNull();
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
    expect(screen.queryByText('Hosting first invoice')).toBeNull();
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
