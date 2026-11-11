import { useEffect, useId, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import {
  FirstInvoiceError,
  formatFirstInvoiceAmount,
  sameCharge,
  type FirstInvoice,
  type FirstInvoiceClient,
} from '../api/hostingFirstInvoice';
import type { HostingStripe } from './hostingStripe';

interface Props {
  subscriptionId: string;
  client: FirstInvoiceClient;
  stripe: HostingStripe;
}

interface Attempt {
  invoice: FirstInvoice;
  requestId: string;
  reauthenticationRequestId: string;
  evidenceId?: string;
  dispatched: boolean;
  authorizationRequestId?: string;
}

/**
 * Mount under QueryClientProvider only from the App-owned account checkout
 * route, after its memory-only control-ui session is active. The route keys
 * this component by session identity and subscription so either change unmounts
 * it; mounting, remounting, or browser navigation never authorizes a payment.
 */
export default function HostingFirstInvoiceCheckout({
  subscriptionId,
  client,
  stripe,
}: Props) {
  const queryId = useId();
  const [accepted, setAccepted] = useState<FirstInvoice | null>(null);
  const [email, setEmail] = useState('');
  const [secret, setSecret] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(false);
  const [evidenceReady, setEvidenceReady] = useState(false);
  const [held, setHeld] = useState(false);
  const [retainedOrigin, setRetainedOrigin] = useState<Pick<
    Attempt,
    'invoice' | 'requestId'
  > | null>(null);
  const [renewing, setRenewing] = useState(false);
  const [closed, setClosed] = useState(false);
  const [now, setNow] = useState(Date.now);
  const attempt = useRef<Attempt | null>(null);
  const locked = useRef(false);
  const operation = useRef(0);

  useEffect(() => {
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    return () => {
      window.clearInterval(tick);
      operation.current += 1;
    };
  }, []);

  const query = useQuery({
    // Per-mount cache scope prevents another session's owner projection reuse.
    queryKey: ['hosting-first-invoice', queryId, subscriptionId],
    queryFn: ({ signal }) => client.read(subscriptionId, signal),
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: true,
    enabled: !closed,
    refetchInterval: (current) =>
      !closed &&
      current.state.data?.state !== 'COMMITTED' &&
      (pending || current.state.data?.confirmationRequestId)
        ? 3000
        : false,
  });

  const invoice = query.data;
  const committed = invoice?.state === 'COMMITTED' && !query.isError;
  const expired = invoice ? now >= Date.parse(invoice.expiresAt) : true;
  const consented = Boolean(
    invoice && accepted && sameCharge(invoice, accepted) && !expired
  );
  const recovering = Boolean(pending || invoice?.confirmationRequestId);
  const preRowOriginal =
    invoice &&
    invoice.confirmationRequestId === null &&
    invoice.authorizationVersion === 0 &&
    retainedOrigin &&
    sameCharge(invoice, retainedOrigin.invoice)
      ? retainedOrigin
      : null;

  // Reload never authorizes provider work. Renewal requires a separate explicit owner action.
  const readOnlyRecovery =
    held ||
    Boolean(invoice?.confirmationRequestId && !evidenceReady && !renewing);

  function renewAuthorization() {
    attempt.current = preRowOriginal
      ? {
          invoice: preRowOriginal.invoice,
          requestId: preRowOriginal.requestId,
          authorizationRequestId: crypto.randomUUID(),
          reauthenticationRequestId: crypto.randomUUID(),
          dispatched: false,
        }
      : null;
    setAccepted(null);
    setEvidenceReady(false);
    setHeld(false);
    setRenewing(true);
    setEmail('');
    setSecret('');
    setMessage(
      'Review this same invoice and authorize it again with fresh primary authentication. A previously claimed payment command will not be resent.'
    );
  }

  function close() {
    operation.current += 1;
    setClosed(true);
    setSecret('');
    setEmail('');
    setAccepted(null);
    // Closing is local dismissal, never a provider cancel/refund operation.
  }

  async function submit() {
    // A ref, not only a disabled button, excludes same-turn duplicate clicks.
    if (
      locked.current ||
      !invoice ||
      !accepted ||
      !consented ||
      committed ||
      readOnlyRecovery
    )
      return;
    locked.current = true;
    setBusy(true);
    const currentOperation = ++operation.current;
    const active = () => operation.current === currentOperation;
    const credentials = { email, secret };
    setSecret('');
    setMessage('Checking the current Account invoice before confirmation.');

    try {
      const current = await query.refetch({ throwOnError: true });
      if (!active()) return;
      const latest = current.data;
      if (latest?.state === 'COMMITTED') return;
      if (
        !latest ||
        !sameCharge(latest, accepted) ||
        Date.now() >= Date.parse(latest.expiresAt)
      ) {
        setAccepted(null);
        setMessage(
          'The invoice changed or expired. Review the current details again.'
        );
        return;
      }
      let retained = attempt.current;
      if (retained && !sameCharge(retained.invoice, latest)) {
        // Never turn an uncertain old charge into a replacement confirmation.
        if (retained.dispatched) {
          setMessage(
            'The earlier confirmation needs Account reconciliation. No new charge was requested.'
          );
          return;
        }
        retained = null;
      }
      if (!retained) {
        if (latest.confirmationRequestId && !renewing) {
          setHeld(true);
          setAccepted(null);
          setMessage(
            'Account retained an existing confirmation. Review and explicitly renew authorization to resume this same invoice.'
          );
          return;
        }
        retained = {
          invoice: latest,
          requestId: latest.confirmationRequestId ?? crypto.randomUUID(),
          ...(latest.confirmationRequestId
            ? { authorizationRequestId: crypto.randomUUID() }
            : {}),
          reauthenticationRequestId: crypto.randomUUID(),
          dispatched: false,
        };
        attempt.current = retained;
      }
      if (
        retained.authorizationRequestId &&
        retained.invoice.authorizationVersion === 0 &&
        !retained.dispatched &&
        (latest.confirmationRequestId !== null ||
          latest.authorizationVersion !== 0)
      ) {
        attempt.current = null;
        setHeld(true);
        setRenewing(false);
        setAccepted(null);
        setMessage(
          'Account retained a confirmation while you were reviewing this invoice. Check its status and explicitly renew the current authorization.'
        );
        return;
      }
      if (!retained.evidenceId) {
        setMessage(
          'Verifying your primary authentication for this exact charge.'
        );
        const proof = await client.reauthenticate(subscriptionId, {
          requestId: retained.reauthenticationRequestId,
          confirmationRequestId:
            retained.authorizationRequestId ?? retained.requestId,
          billingBinding: {
            originalRequestId: retained.requestId,
            expectedRowVersion: retained.invoice.expectedRowVersion,
            expectedAuthorizationVersion: retained.invoice.authorizationVersion,
            acceptedTupleDigest: retained.invoice.tupleDigest,
            invoiceExpiresAt: retained.invoice.expiresAt,
            authorizationPurpose: !retained.authorizationRequestId
              ? 'INITIAL_CONFIRMATION'
              : retained.invoice.authorizationVersion === 0
                ? 'PRE_ROW_RENEWAL'
                : 'FOLLOWUP_RENEWAL',
          },
          ...credentials,
        });
        retained.evidenceId = proof.evidenceId;
        if (!active()) return;
        setEvidenceReady(true);
      }
      if (Date.now() >= Date.parse(latest.expiresAt)) {
        setAccepted(null);
        setMessage('The invoice expired. No new confirmation was sent.');
        return;
      }
      retained.dispatched = true;
      setPending(true);
      setMessage(
        'Confirmation requested. Account has not confirmed payment completion.'
      );
      const consent = {
        requestId: retained.requestId,
        expectedRowVersion: retained.invoice.expectedRowVersion,
        reauthenticationEvidenceId: retained.evidenceId,
        acceptedTupleDigest: retained.invoice.tupleDigest,
        acceptCharge: true as const,
      };
      const handoff = retained.authorizationRequestId
        ? await client.renew(subscriptionId, latest.paymentIntentId, {
            ...consent,
            authorizationRequestId: retained.authorizationRequestId,
            expectedAuthorizationVersion: retained.invoice.authorizationVersion,
          })
        : await client.confirm(subscriptionId, latest.paymentIntentId, consent);
      if (!active()) return;
      if (handoff.status === 'AUTHORIZED_NOT_CONFIRMED') {
        setHeld(true);
        setRenewing(false);
        setAccepted(null);
        setMessage(
          'Account retained the authorization, but provider confirmation is unresolved. Only status recovery is available. No replacement charge was requested.'
        );
      } else if (handoff.status === 'requires_action' && handoff.clientSecret) {
        setMessage(
          'Complete the Stripe authentication prompt for this invoice.'
        );
        // The server already confirmed this exact intent. Never confirm/create another.
        const result = await stripe.handleNextAction({
          clientSecret: handoff.clientSecret,
        });
        if (!active()) return;
        setMessage(
          result.error
            ? 'Stripe authentication was not completed. Check Account status or resume the same confirmation.'
            : 'Stripe authentication returned. Waiting for Account to verify and commit payment completion.'
        );
      } else {
        setMessage(
          'Waiting for Account to verify and commit payment completion.'
        );
      }
      await query.refetch();
    } catch (error) {
      if (!active()) return;
      if (
        error instanceof FirstInvoiceError &&
        [401, 403].includes(error.status)
      ) {
        setAccepted(null);
        if (attempt.current?.dispatched) setHeld(true);
        setMessage(
          'The current control-ui session or billing-owner authority was refused. Sign in again through the account flow.'
        );
      } else if (
        error instanceof FirstInvoiceError &&
        [409, 410].includes(error.status)
      ) {
        setAccepted(null);
        const dispatched = attempt.current;
        if (dispatched?.dispatched) {
          setHeld(true);
          // Opening proof is returned only after Account retains the exact operation.
          if (
            dispatched.evidenceId &&
            dispatched.invoice.authorizationVersion === 0
          ) {
            setRetainedOrigin({
              invoice: dispatched.invoice,
              requestId: dispatched.requestId,
            });
          }
        }
        setMessage(
          'Account refused stale or expired checkout evidence. Refresh and review the invoice. Any earlier confirmation remains unresolved.'
        );
        await query.refetch();
      } else {
        setMessage(
          'The result is unknown. Check Account status or retry this same confirmation. Do not start another payment.'
        );
      }
    } finally {
      credentials.email = '';
      credentials.secret = '';
      locked.current = false;
      setBusy(false);
    }
  }

  if (closed) {
    return (
      <Stack spacing={2}>
        <Typography role="status">
          Checkout closed. Closing does not cancel a confirmation already sent.
        </Typography>
        <Button disabled={busy} onClick={() => setClosed(false)}>
          Resume checkout
        </Button>
      </Stack>
    );
  }

  return (
    <Box component="section" aria-label="Hosting first invoice checkout">
      <Stack spacing={2}>
        <Typography variant="h5" component="h2">
          Hosting first invoice
        </Typography>
        {query.isPending && (
          <Typography role="status">Loading the owner invoice.</Typography>
        )}
        {query.isError && (
          <Alert severity="error">
            The owner invoice is unavailable. No payment completion is
            confirmed.
          </Alert>
        )}
        {invoice && (
          <>
            <Box component="dl" sx={{ m: 0, textAlign: 'left' }}>
              <dt>Subscription</dt>
              <dd>{invoice.subscriptionId}</dd>
              <dt>Invoice</dt>
              <dd>{invoice.invoiceId}</dd>
              <dt>Amount due now</dt>
              <dd>
                {formatFirstInvoiceAmount(invoice.amount, invoice.currency)} (
                {invoice.amount} minor units)
              </dd>
              <dt>Bound payment instrument</dt>
              <dd>{invoice.paymentInstrumentId}</dd>
              <dt>Existing payment intent</dt>
              <dd>{invoice.paymentIntentId}</dd>
              <dt>Checkout expires</dt>
              <dd>{invoice.expiresAt}</dd>
            </Box>
            {committed ? (
              <Alert severity="success">
                Account confirmed and committed this first-invoice payment.
              </Alert>
            ) : (
              <>
                <Alert severity="info">
                  {recovering
                    ? 'A confirmation may already be in progress. Resume only this same invoice and payment intent. '
                    : ''}
                  Stripe or browser success does not activate hosting. Account
                  must verify provider evidence and commit completion.
                </Alert>
                {expired && (
                  <Alert severity="warning">
                    This checkout has expired. Only Account status recovery is
                    available.
                  </Alert>
                )}
                {readOnlyRecovery ? (
                  <Alert severity="warning">
                    {invoice.confirmationRequestId ? (
                      <>
                        Account retained an earlier confirmation. Only status
                        recovery is automatic. Explicit fresh authorization can
                        resume this same invoice, but cannot resend an uncertain
                        payment command.
                      </>
                    ) : preRowOriginal ? (
                      <>
                        Your earlier confirmation is unresolved. You can
                        explicitly renew authorization for this same invoice and
                        original payment operation.
                      </>
                    ) : (
                      <>
                        Your earlier confirmation is unresolved. Check Account
                        status for this same payment. Do not start another
                        payment. Authorization cannot safely be renewed yet.
                      </>
                    )}
                  </Alert>
                ) : (
                  <>
                    <TextField
                      label="Account email"
                      type="email"
                      autoComplete="off"
                      value={email}
                      disabled={busy || expired || evidenceReady}
                      onChange={(event) => setEmail(event.target.value)}
                      slotProps={{ htmlInput: { maxLength: 254 } }}
                    />
                    <TextField
                      label="Primary authentication secret"
                      type="password"
                      autoComplete="off"
                      value={secret}
                      disabled={busy || expired || evidenceReady}
                      onChange={(event) => setSecret(event.target.value)}
                      helperText="Use the primary factor required by your account. It is sent only to Account over HTTPS."
                      slotProps={{ htmlInput: { maxLength: 1024 } }}
                    />
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={consented}
                          disabled={busy || expired || query.isError}
                          onChange={(_, checked) =>
                            setAccepted(checked ? invoice : null)
                          }
                        />
                      }
                      label={`I authorize the exact ${formatFirstInvoiceAmount(invoice.amount, invoice.currency)} charge (${invoice.amount} minor units) for invoice ${invoice.invoiceId} using instrument ${invoice.paymentInstrumentId}.`}
                    />
                    <Button
                      variant="contained"
                      disabled={
                        busy ||
                        !consented ||
                        query.isError ||
                        (!evidenceReady && (!email.trim() || !secret))
                      }
                      onClick={() => void submit()}
                    >
                      {busy
                        ? 'Checking payment'
                        : recovering
                          ? 'Resume exact confirmation'
                          : 'Authorize first-invoice charge'}
                    </Button>
                  </>
                )}
              </>
            )}
          </>
        )}
        {!committed && message && (
          <Typography role="status">{message}</Typography>
        )}
        <Stack direction="row" spacing={1}>
          {readOnlyRecovery &&
            !committed &&
            !expired &&
            (invoice?.confirmationRequestId || preRowOriginal) && (
              <Button
                disabled={busy || query.isError}
                onClick={renewAuthorization}
              >
                Renew exact authorization
              </Button>
            )}
          <Button
            disabled={busy || query.isFetching}
            onClick={() => void query.refetch()}
          >
            Check Account status
          </Button>
          <Button onClick={close}>Close checkout</Button>
        </Stack>
      </Stack>
    </Box>
  );
}
