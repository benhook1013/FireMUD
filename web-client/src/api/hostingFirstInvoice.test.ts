import { describe, expect, it, vi } from 'vitest';
import {
  createFirstInvoiceClient,
  FirstInvoiceError,
  formatFirstInvoiceAmount,
  sameCharge,
  type FirstInvoice,
} from './hostingFirstInvoice';

const subscriptionId = '11111111-1111-4111-8111-111111111111';
const invoice: FirstInvoice = {
  subscriptionId,
  invoiceId: 'in_existing',
  paymentIntentId: 'pi_existing',
  paymentInstrumentId: '22222222-2222-4222-8222-222222222222',
  amount: '1299',
  currency: 'usd',
  tupleDigest: `sha256:${'a'.repeat(64)}`,
  expectedRowVersion: 3,
  state: 'AWAITING_PAYMENT',
  expiresAt: '2099-01-01T00:00:00Z',
  confirmationRequestId: null,
  openingRequestId: null,
  authorizationVersion: 0,
};

describe('first-invoice owner API', () => {
  it('renews only the original operation with a separately fenced authorization body', async () => {
    const authenticated = vi.fn().mockResolvedValue(
      Response.json({
        status: 'AUTHORIZED_NOT_CONFIRMED',
        clientSecret: null,
      })
    );
    const client = createFirstInvoiceClient(
      'https://game.example',
      authenticated
    );
    const renewal = {
      requestId: '44444444-4444-4444-8444-444444444444',
      authorizationRequestId: '55555555-5555-4555-8555-555555555555',
      expectedAuthorizationVersion: 1,
      expectedRowVersion: 3,
      reauthenticationEvidenceId: '33333333-3333-4333-8333-333333333333',
      acceptedTupleDigest: invoice.tupleDigest,
      acceptCharge: true as const,
    };
    expect(
      await client.renew(subscriptionId, invoice.paymentIntentId, renewal)
    ).toEqual({ status: 'AUTHORIZED_NOT_CONFIRMED', clientSecret: null });
    expect(authenticated.mock.calls[0][0].pathname).toBe(
      `/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice/renew`
    );
    expect(JSON.parse(authenticated.mock.calls[0][1].body)).toEqual(renewal);
  });
  it('uses the authenticated injection, exact Gateway route, and no-store HTTPS without URL credentials', async () => {
    const authenticated = vi.fn().mockResolvedValue(Response.json(invoice));
    const client = createFirstInvoiceClient(
      'https://game.example',
      authenticated
    );
    expect(await client.read(subscriptionId)).toEqual(invoice);
    const [url, init] = authenticated.mock.calls[0];
    expect(url.href).toBe(
      `https://game.example/api/account/billing/hosting/subscriptions/${subscriptionId}/first-invoice`
    );
    expect(url.search).toBe('');
    expect(init).toMatchObject({
      method: 'GET',
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
    });
    expect(init.headers).not.toHaveProperty('Authorization');
  });

  it.each([
    'http://game.example',
    'https://token@game.example',
    'https://game.example/?token=x',
    'https://game.example/#x',
    'https://game.example/path',
  ])('refuses an unsafe Gateway origin %s', (origin) => {
    expect(() => createFirstInvoiceClient(origin, vi.fn())).toThrow(
      'HTTPS Gateway origin'
    );
  });

  it('sends billing reauth and consent in bodies only, preserving exact request identity', async () => {
    const evidenceId = '33333333-3333-4333-8333-333333333333';
    const authenticated = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ evidenceId }))
      .mockResolvedValueOnce(
        Response.json({
          status: 'requires_action',
          clientSecret: 'pi_existing_secret_ephemeral',
        })
      );
    const client = createFirstInvoiceClient(
      'https://game.example',
      authenticated
    );
    const reauth = {
      requestId: '44444444-4444-4444-8444-444444444444',
      confirmationRequestId: '55555555-5555-4555-8555-555555555555',
      billingBinding: {
        originalRequestId: '55555555-5555-4555-8555-555555555555',
        expectedRowVersion: invoice.expectedRowVersion,
        expectedAuthorizationVersion: 0,
        acceptedTupleDigest: invoice.tupleDigest,
        invoiceExpiresAt: invoice.expiresAt,
        authorizationPurpose: 'INITIAL_CONFIRMATION' as const,
      },
      email: 'owner@example.com',
      secret: 'primary-secret',
    };
    expect(await client.reauthenticate(subscriptionId, reauth)).toEqual({
      evidenceId,
    });
    const consent = {
      requestId: reauth.confirmationRequestId,
      expectedRowVersion: 3,
      reauthenticationEvidenceId: evidenceId,
      acceptedTupleDigest: invoice.tupleDigest,
      acceptCharge: true as const,
    };
    await expect(
      client.confirm(subscriptionId, 'pi_existing', consent)
    ).resolves.toEqual({
      status: 'requires_action',
      clientSecret: 'pi_existing_secret_ephemeral',
    });
    expect(JSON.parse(authenticated.mock.calls[0][1].body)).toEqual(reauth);
    expect(JSON.parse(authenticated.mock.calls[1][1].body)).toEqual(consent);
    expect(authenticated.mock.calls.map(([url]) => url.search)).toEqual([
      '',
      '',
    ]);
  });

  it('does not expose provider error bodies or raw transport errors', async () => {
    const response = new Response('pi_existing_secret_private', {
      status: 500,
    });
    const authenticated = vi
      .fn()
      .mockResolvedValueOnce(response)
      .mockRejectedValueOnce(new Error('primary-secret'));
    const client = createFirstInvoiceClient(
      'https://game.example',
      authenticated
    );
    await expect(client.read(subscriptionId)).rejects.toEqual(
      new FirstInvoiceError(500)
    );
    expect(response.bodyUsed).toBe(false);
    await expect(client.read(subscriptionId)).rejects.toEqual(
      new FirstInvoiceError(0)
    );
  });

  it('refuses another intent secret, secret on non-action status, and unknown status', async () => {
    const authenticated = vi.fn();
    const client = createFirstInvoiceClient(
      'https://game.example',
      authenticated
    );
    for (const handoff of [
      { status: 'requires_action', clientSecret: 'pi_other_secret_private' },
      { status: 'succeeded', clientSecret: 'pi_existing_secret_private' },
      { status: 'canceled', clientSecret: null },
    ]) {
      authenticated.mockResolvedValueOnce(Response.json(handoff));
      await expect(
        client.confirm(subscriptionId, 'pi_existing', {
          requestId: subscriptionId,
          expectedRowVersion: 3,
          reauthenticationEvidenceId: subscriptionId,
          acceptedTupleDigest: invoice.tupleDigest,
          acceptCharge: true,
        })
      ).rejects.toBeInstanceOf(FirstInvoiceError);
    }
  });

  it('refuses malformed owner tuples and strips unexpected secret fields', async () => {
    const authenticated = vi.fn();
    const client = createFirstInvoiceClient(
      'https://game.example',
      authenticated
    );
    for (const changes of [
      { subscriptionId: 'another' },
      { amount: 1299 },
      { amount: '12.99' },
      { expectedRowVersion: Number.MAX_SAFE_INTEGER + 1 },
      { state: 'PAID' },
      { currency: 'USD' },
      { confirmationRequestId: 'untrusted' },
    ]) {
      authenticated.mockResolvedValueOnce(
        Response.json({ ...invoice, ...changes })
      );
      await expect(client.read(subscriptionId)).rejects.toBeInstanceOf(
        FirstInvoiceError
      );
    }
    authenticated.mockResolvedValueOnce(
      Response.json({ ...invoice, clientSecret: 'private' })
    );
    expect(await client.read(subscriptionId)).not.toHaveProperty(
      'clientSecret'
    );
  });

  it('compares all consent-bearing display fields and row version', () => {
    expect(sameCharge(invoice, { ...invoice })).toBe(true);
    for (const changes of [
      { amount: '1300' },
      { invoiceId: 'in_other' },
      { paymentIntentId: 'pi_other' },
      { paymentInstrumentId: subscriptionId },
      { currency: 'nzd' },
      { expectedRowVersion: 4 },
      { expiresAt: '2098-01-01T00:00:00Z' },
      { tupleDigest: `sha256:${'b'.repeat(64)}` },
    ]) {
      expect(sameCharge(invoice, { ...invoice, ...changes })).toBe(false);
    }
  });

  it.each([
    ['1299', 'usd', 'USD 12.99'],
    ['1', 'nzd', 'NZD 0.01'],
    ['1299', 'jpy', 'JPY 1299'],
    ['1000', 'kwd', 'KWD 1.000'],
    ['100', 'isk', 'ISK 1.00'],
    ['100', 'ugx', 'UGX 1.00'],
    ['9223372036854775807', 'usd', 'USD 92233720368547758.07'],
  ])(
    'preserves exact minor-unit amount %s %s',
    (amount, currency, expected) => {
      expect(formatFirstInvoiceAmount(amount, currency)).toBe(expected);
    }
  );
});
