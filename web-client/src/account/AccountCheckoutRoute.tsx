import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
} from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useQueryClient } from '@tanstack/react-query';
import {
  AccountControlUiApiError,
  createAccountControlUiApi,
  type AccountControlUiApi,
} from '../api/accountControlUi';
import { createFirstInvoiceClient } from '../api/hostingFirstInvoice';
import HostingFirstInvoiceCheckout from '../billing/HostingFirstInvoiceCheckout';
import {
  loadHostingStripe,
  type HostingStripe,
} from '../billing/hostingStripe';
import { queryClient } from '../api/queryClient';
import {
  ControlUiSessionError,
  createControlUiSessionStore,
  type ControlUiCredentials,
  type ControlUiSessionStore,
} from './controlUiSession';

const subscriptionRoute =
  /^\/account\/subscriptions\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/checkout\/?$/i;
const publishableKeyPattern = /^pk_(test|live)_[A-Za-z0-9]+$/;

export interface AccountCheckoutRouteProps {
  pathname: string;
  sessionStore?: ControlUiSessionStore;
  api?: AccountControlUiApi;
  stripePublishableKey?: string;
  loadStripeClient?: (publishableKey: string) => Promise<HostingStripe>;
}

const defaultSessionStore = createControlUiSessionStore({
  origin: () => window.location.origin,
  fetchImpl: (input, init) => fetch(input, init),
  clearQueryCache: () => queryClient.clear(),
});
const defaultLoginApi = createAccountControlUiApi(
  () => window.location.origin,
  (input, init) => fetch(input, init)
);

function parseSubscriptionId(pathname: string): string | null {
  const match = subscriptionRoute.exec(pathname);
  return match?.[1].toLowerCase() ?? null;
}

export default function AccountCheckoutRoute({
  pathname,
  sessionStore = defaultSessionStore,
  api = defaultLoginApi,
  stripePublishableKey = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY,
  loadStripeClient = loadHostingStripe,
}: AccountCheckoutRouteProps) {
  const subscriptionId = parseSubscriptionId(pathname);
  const publishableKey = stripePublishableKey ?? '';

  if (!subscriptionId) {
    return (
      <Box component="main" sx={{ maxWidth: 680, mx: 'auto', p: 3 }}>
        <Alert severity="error">This account checkout route is invalid.</Alert>
      </Box>
    );
  }

  if (!publishableKeyPattern.test(publishableKey)) {
    return (
      <Box component="main" sx={{ maxWidth: 680, mx: 'auto', p: 3 }}>
        <Typography variant="h4" component="h1" gutterBottom>
          Hosting checkout
        </Typography>
        <Alert severity="error">
          Checkout is unavailable because the Stripe publishable key is not
          configured. No checkout request was sent.
        </Alert>
      </Box>
    );
  }

  return (
    <ActiveAccountCheckoutRoute
      subscriptionId={subscriptionId}
      sessionStore={sessionStore}
      api={api}
      stripePublishableKey={publishableKey}
      loadStripeClient={loadStripeClient}
    />
  );
}

interface ActiveRouteProps {
  subscriptionId: string;
  sessionStore: ControlUiSessionStore;
  api: AccountControlUiApi;
  stripePublishableKey: string;
  loadStripeClient: (publishableKey: string) => Promise<HostingStripe>;
}

function ActiveAccountCheckoutRoute({
  subscriptionId,
  sessionStore,
  api,
  stripePublishableKey,
  loadStripeClient,
}: ActiveRouteProps) {
  const queryClient = useQueryClient();
  const session = useSyncExternalStore(
    sessionStore.subscribe,
    sessionStore.getSession,
    () => null
  );
  const previousIdentity = useRef<string | null>(session?.identity ?? null);
  const [signoutNotice, setSignoutNotice] = useState('');

  useLayoutEffect(() => {
    const nextIdentity = session?.identity ?? null;
    if (previousIdentity.current !== nextIdentity) {
      // A cache belongs to one memory-only authority identity. Clear it before
      // the next route's passive query effects can run.
      queryClient.clear();
      previousIdentity.current = nextIdentity;
    }
  }, [queryClient, session?.identity]);

  if (!session) {
    return (
      <AccountLoginForm
        api={api}
        sessionStore={sessionStore}
        notice={signoutNotice}
      />
    );
  }

  return (
    <Box component="main" sx={{ maxWidth: 760, mx: 'auto', p: 3 }}>
      <Stack spacing={2}>
        <Stack
          direction="row"
          spacing={2}
          sx={{ alignItems: 'center', justifyContent: 'space-between' }}
        >
          <Box>
            <Typography variant="h4" component="h1">
              Hosting checkout
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Signed in to Account {session.accountId}
            </Typography>
          </Box>
          <Button
            onClick={() => {
              sessionStore.signOut();
              setSignoutNotice(
                'Signed out on this browser. Account token revocation is not available here.'
              );
            }}
          >
            Sign out
          </Button>
        </Stack>
        <Typography variant="caption" color="text.secondary">
          Sign out clears this browser&apos;s in-memory session. It does not
          revoke the Account token or cancel a confirmation already sent.
        </Typography>
        <CheckoutForSession
          key={`${session.identity}:${subscriptionId}`}
          subscriptionId={subscriptionId}
          sessionStore={sessionStore}
          sessionIdentity={session.identity}
          stripePublishableKey={stripePublishableKey}
          loadStripeClient={loadStripeClient}
        />
      </Stack>
    </Box>
  );
}

interface CheckoutProps {
  subscriptionId: string;
  sessionStore: ControlUiSessionStore;
  sessionIdentity: string;
  stripePublishableKey: string;
  loadStripeClient: (publishableKey: string) => Promise<HostingStripe>;
}

type StripeLoadResult =
  | {
      sessionIdentity: string;
      publishableKey: string;
      loader: CheckoutProps['loadStripeClient'];
      status: 'ready';
      stripe: HostingStripe;
    }
  | {
      sessionIdentity: string;
      publishableKey: string;
      loader: CheckoutProps['loadStripeClient'];
      status: 'unavailable';
    };

function CheckoutForSession({
  subscriptionId,
  sessionStore,
  sessionIdentity,
  stripePublishableKey,
  loadStripeClient,
}: CheckoutProps) {
  const [stripeLoadResult, setStripeLoadResult] =
    useState<StripeLoadResult | null>(null);
  const client = useMemo(
    () =>
      createFirstInvoiceClient(
        sessionStore.getGatewayOrigin(),
        sessionStore.authenticatedRequest
      ),
    [sessionStore]
  );

  useEffect(() => {
    let current = true;
    void Promise.resolve()
      .then(() => loadStripeClient(stripePublishableKey))
      .then(
        (loaded) => {
          if (current) {
            setStripeLoadResult({
              sessionIdentity,
              publishableKey: stripePublishableKey,
              loader: loadStripeClient,
              status: 'ready',
              stripe: loaded,
            });
          }
        },
        () => {
          if (current) {
            setStripeLoadResult({
              sessionIdentity,
              publishableKey: stripePublishableKey,
              loader: loadStripeClient,
              status: 'unavailable',
            });
          }
        }
      );
    return () => {
      current = false;
    };
  }, [loadStripeClient, sessionIdentity, stripePublishableKey]);

  const matchingResult =
    stripeLoadResult?.sessionIdentity === sessionIdentity &&
    stripeLoadResult.publishableKey === stripePublishableKey &&
    stripeLoadResult.loader === loadStripeClient
      ? stripeLoadResult
      : null;

  if (matchingResult?.status === 'unavailable') {
    return (
      <Alert severity="error">
        Stripe authentication is unavailable. Account payment status remains
        unchanged.
      </Alert>
    );
  }
  if (matchingResult?.status !== 'ready') {
    return (
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center' }}
        role="status"
      >
        <CircularProgress size={20} />
        <Typography>Loading secure payment authentication.</Typography>
      </Stack>
    );
  }
  return (
    <HostingFirstInvoiceCheckout
      subscriptionId={subscriptionId}
      client={client}
      stripe={matchingResult.stripe}
    />
  );
}

interface LoginFormProps {
  api: AccountControlUiApi;
  sessionStore: ControlUiSessionStore;
  notice: string;
}

function AccountLoginForm({ api, sessionStore, notice }: LoginFormProps) {
  const [email, setEmail] = useState('');
  const [secret, setSecret] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const locked = useRef(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked.current || busy) return;
    locked.current = true;
    setBusy(true);
    setMessage('');
    const credentials: ControlUiCredentials = {
      email: email.trim(),
      secret,
    };
    setSecret('');
    try {
      await sessionStore.signIn(api, credentials);
    } catch (error) {
      if (
        error instanceof AccountControlUiApiError &&
        [0, 429, 503].includes(error.status)
      ) {
        setMessage(
          'Account sign-in is temporarily unavailable. Try again later.'
        );
      } else if (
        error instanceof ControlUiSessionError &&
        error.code === 'SESSION_REPLACED'
      ) {
        setMessage('This sign-in attempt was replaced. Sign in again.');
      } else {
        setMessage(
          'Sign-in could not be completed. Check your email and primary authentication secret.'
        );
      }
    } finally {
      credentials.secret = '';
      locked.current = false;
      setBusy(false);
    }
  }

  return (
    <Box component="main" sx={{ maxWidth: 560, mx: 'auto', p: 3 }}>
      <Stack
        component="form"
        spacing={2}
        onSubmit={(event) => void submit(event)}
      >
        <Typography variant="h4" component="h1">
          Sign in to Account
        </Typography>
        <Typography>
          Sign in to review the existing hosting invoice. Sign-in does not
          authorize a payment.
        </Typography>
        {notice && <Alert severity="info">{notice}</Alert>}
        <TextField
          label="Account email"
          type="email"
          autoComplete="username"
          value={email}
          disabled={busy}
          onChange={(event) => setEmail(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 254 } }}
        />
        <TextField
          label="Primary authentication secret"
          type="password"
          autoComplete="current-password"
          value={secret}
          disabled={busy}
          onChange={(event) => setSecret(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 1024 } }}
        />
        <Button
          type="submit"
          variant="contained"
          disabled={busy || !email.trim() || !secret}
        >
          {busy ? 'Signing in' : 'Sign in'}
        </Button>
        {message && (
          <Alert severity="error" role="status">
            {message}
          </Alert>
        )}
        <Typography variant="caption" color="text.secondary">
          Sign out clears this browser&apos;s in-memory session. It does not
          confirm server-side token revocation.
        </Typography>
      </Stack>
    </Box>
  );
}
