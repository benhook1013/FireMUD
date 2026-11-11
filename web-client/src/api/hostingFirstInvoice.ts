/** Account owns authorization, the exact charge tuple, and payment completion. */
export interface FirstInvoice {
  subscriptionId: string;
  invoiceId: string;
  paymentIntentId: string;
  paymentInstrumentId: string;
  /** Positive integer decimal string in Stripe minor units, never a JS number. */
  amount: string;
  currency: string;
  tupleDigest: string;
  expectedRowVersion: number;
  state: 'AWAITING_PAYMENT' | 'COMMITTED';
  expiresAt: string;
  confirmationRequestId: string | null;
  /** Server-verified original identity only, never restored consent or proof. */
  preRowOriginalRequestId: string | null;
  authorizationVersion: number;
}

export interface BillingReauthentication {
  requestId: string;
  confirmationRequestId: string;
  billingBinding: {
    originalRequestId: string;
    expectedRowVersion: number;
    expectedAuthorizationVersion: number;
    acceptedTupleDigest: string;
    invoiceExpiresAt: string;
    authorizationPurpose:
      'INITIAL_CONFIRMATION' | 'PRE_ROW_RENEWAL' | 'FOLLOWUP_RENEWAL';
  };
  email: string;
  secret: string;
}

export interface FirstInvoiceConsent {
  requestId: string;
  expectedRowVersion: number;
  reauthenticationEvidenceId: string;
  acceptedTupleDigest: string;
  acceptCharge: true;
}

export interface FirstInvoiceHandoff {
  status: string;
  clientSecret: string | null;
}

export interface FirstInvoiceRenewal extends FirstInvoiceConsent {
  authorizationRequestId: string;
  expectedAuthorizationVersion: number;
}

export interface FirstInvoiceClient {
  read(subscriptionId: string, signal?: AbortSignal): Promise<FirstInvoice>;
  reauthenticate(
    subscriptionId: string,
    request: BillingReauthentication
  ): Promise<{ evidenceId: string }>;
  confirm(
    subscriptionId: string,
    paymentIntentId: string,
    request: FirstInvoiceConsent
  ): Promise<FirstInvoiceHandoff>;
  renew(
    subscriptionId: string,
    paymentIntentId: string,
    request: FirstInvoiceRenewal
  ): Promise<FirstInvoiceHandoff>;
}

/**
 * Supply only the real control-ui session owner's request function. It must
 * attach its current memory-only bearer, reject expiry/revocation, and never
 * log or persist request/response bodies. A gameplay token is not authority.
 * This module neither creates a session nor accepts a pasted bearer token.
 */
export type ControlUiAuthenticatedRequest = (
  url: URL,
  init: RequestInit
) => Promise<Response>;

export class FirstInvoiceError extends Error {
  readonly status: number;

  constructor(status: number) {
    super('Account could not confirm the checkout result.');
    this.name = 'FirstInvoiceError';
    this.status = status;
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function requireUuid(value: string): void {
  if (!uuid.test(value)) throw new FirstInvoiceError(0);
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new FirstInvoiceError(0);
  }
  return value as Record<string, unknown>;
}

function invoice(value: unknown, subscriptionId: string): FirstInvoice {
  const data = record(value);
  if (
    data.subscriptionId !== subscriptionId ||
    typeof data.invoiceId !== 'string' ||
    !/^in_[A-Za-z0-9]+$/.test(data.invoiceId) ||
    typeof data.paymentIntentId !== 'string' ||
    !/^pi_[A-Za-z0-9]+$/.test(data.paymentIntentId) ||
    typeof data.paymentInstrumentId !== 'string' ||
    !uuid.test(data.paymentInstrumentId) ||
    typeof data.amount !== 'string' ||
    !/^[1-9][0-9]*$/.test(data.amount) ||
    typeof data.currency !== 'string' ||
    !/^[a-z]{3}$/.test(data.currency) ||
    typeof data.tupleDigest !== 'string' ||
    !/^sha256:[a-f0-9]{64}$/.test(data.tupleDigest) ||
    !Number.isSafeInteger(data.expectedRowVersion) ||
    (data.expectedRowVersion as number) < 0 ||
    !Number.isSafeInteger(data.authorizationVersion) ||
    (data.authorizationVersion as number) < 0 ||
    (data.state !== 'AWAITING_PAYMENT' && data.state !== 'COMMITTED') ||
    typeof data.expiresAt !== 'string' ||
    !Number.isFinite(Date.parse(data.expiresAt)) ||
    (data.confirmationRequestId !== null &&
      (typeof data.confirmationRequestId !== 'string' ||
        !uuid.test(data.confirmationRequestId))) ||
    (data.preRowOriginalRequestId !== null &&
      (typeof data.preRowOriginalRequestId !== 'string' ||
        !uuid.test(data.preRowOriginalRequestId) ||
        data.confirmationRequestId !== null ||
        data.authorizationVersion !== 0 ||
        data.state !== 'AWAITING_PAYMENT'))
  ) {
    throw new FirstInvoiceError(0);
  }
  // Copy only the public owner projection, never an unexpected secret field.
  return {
    subscriptionId,
    invoiceId: data.invoiceId,
    paymentIntentId: data.paymentIntentId,
    paymentInstrumentId: data.paymentInstrumentId,
    amount: data.amount,
    currency: data.currency,
    tupleDigest: data.tupleDigest,
    expectedRowVersion: data.expectedRowVersion as number,
    state: data.state,
    expiresAt: data.expiresAt,
    confirmationRequestId: data.confirmationRequestId as string | null,
    preRowOriginalRequestId: data.preRowOriginalRequestId as string | null,
    authorizationVersion: data.authorizationVersion as number,
  };
}

export function createFirstInvoiceClient(
  gatewayOrigin: string,
  authenticatedRequest: ControlUiAuthenticatedRequest
): FirstInvoiceClient {
  const origin = new URL(gatewayOrigin);
  if (
    origin.protocol !== 'https:' ||
    origin.username ||
    origin.password ||
    origin.pathname !== '/' ||
    origin.search ||
    origin.hash
  ) {
    throw new Error('An HTTPS Gateway origin is required.');
  }

  async function request(
    subscriptionId: string,
    suffix: string,
    body?: BillingReauthentication | FirstInvoiceConsent | FirstInvoiceRenewal,
    signal?: AbortSignal
  ): Promise<unknown> {
    requireUuid(subscriptionId);
    const url = new URL(
      `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice${suffix}`,
      origin
    );
    let response: Response;
    try {
      response = await authenticatedRequest(url, {
        method: body ? 'POST' : 'GET',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        cache: 'no-store',
        credentials: 'omit',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
        signal,
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
    } catch {
      throw new FirstInvoiceError(0);
    }
    if (!response.ok) {
      // Provider/server error bodies may contain sensitive data. Never surface them.
      throw new FirstInvoiceError(response.status);
    }
    try {
      return await response.json();
    } catch {
      throw new FirstInvoiceError(0);
    }
  }

  async function handoff(
    subscriptionId: string,
    paymentIntentId: string,
    suffix: string,
    body: FirstInvoiceConsent | FirstInvoiceRenewal
  ): Promise<FirstInvoiceHandoff> {
    const data = record(await request(subscriptionId, suffix, body));
    if (
      typeof data.status !== 'string' ||
      ![
        'requires_action',
        'processing',
        'succeeded',
        'requires_confirmation',
        'requires_payment_method',
        'AUTHORIZED_NOT_CONFIRMED',
      ].includes(data.status) ||
      (data.status === 'requires_action'
        ? typeof data.clientSecret !== 'string' ||
          !data.clientSecret.startsWith(`${paymentIntentId}_secret_`)
        : data.clientSecret !== null)
    )
      throw new FirstInvoiceError(0);
    return {
      status: data.status,
      clientSecret: data.clientSecret as string | null,
    };
  }

  return {
    read: async (subscriptionId, signal) =>
      invoice(
        await request(subscriptionId, '', undefined, signal),
        subscriptionId
      ),
    reauthenticate: async (subscriptionId, body) => {
      const data = record(
        await request(subscriptionId, '/reauthenticate', body)
      );
      if (typeof data.evidenceId !== 'string' || !uuid.test(data.evidenceId)) {
        throw new FirstInvoiceError(0);
      }
      return { evidenceId: data.evidenceId };
    },
    confirm: (subscriptionId, paymentIntentId, body) =>
      handoff(subscriptionId, paymentIntentId, '/confirm', body),
    renew: (subscriptionId, paymentIntentId, body) =>
      handoff(subscriptionId, paymentIntentId, '/renew', body),
  };
}

/** Comparison includes the displayed fields, not only the opaque owner digest. */
export function sameCharge(a: FirstInvoice, b: FirstInvoice): boolean {
  return (
    a.subscriptionId === b.subscriptionId &&
    a.invoiceId === b.invoiceId &&
    a.paymentIntentId === b.paymentIntentId &&
    a.paymentInstrumentId === b.paymentInstrumentId &&
    a.amount === b.amount &&
    a.currency === b.currency &&
    a.tupleDigest === b.tupleDigest &&
    a.expectedRowVersion === b.expectedRowVersion &&
    a.expiresAt === b.expiresAt
  );
}

/** Stripe charge units, including ISK/UGX's two-decimal compatibility rule. */
export function formatFirstInvoiceAmount(
  amount: string,
  currency: string
): string {
  const zeroDecimal = new Set([
    'bif',
    'clp',
    'djf',
    'gnf',
    'jpy',
    'kmf',
    'krw',
    'mga',
    'pyg',
    'rwf',
    'vnd',
    'vuv',
    'xaf',
    'xof',
    'xpf',
  ]);
  const threeDecimal = new Set(['bhd', 'jod', 'kwd', 'omr', 'tnd']);
  const digits = zeroDecimal.has(currency)
    ? 0
    : threeDecimal.has(currency)
      ? 3
      : 2;
  const padded = amount.padStart(digits + 1, '0');
  const quantity = digits
    ? `${padded.slice(0, -digits)}.${padded.slice(-digits)}`
    : padded;
  return `${currency.toUpperCase()} ${quantity}`;
}
