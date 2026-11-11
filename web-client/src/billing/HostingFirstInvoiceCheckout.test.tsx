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
import type { PaymentIntent } from '@stripe/stripe-js';
import HostingFirstInvoiceCheckout from './HostingFirstInvoiceCheckout';
import {
  FirstInvoiceError,
  type FirstInvoice,
  type FirstInvoiceClient,
} from '../api/hostingFirstInvoice';
import type { HostingStripe } from './hostingStripe';

const initial: FirstInvoice = {
  subscriptionId: '11111111-1111-4111-8111-111111111111',
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
  authorizationVersion: 0,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function setup(value: FirstInvoice = initial) {
  let current = value;
  const client = {
    read: vi
      .fn<FirstInvoiceClient['read']>()
      .mockImplementation(async () => ({ ...current })),
    reauthenticate: vi
      .fn<FirstInvoiceClient['reauthenticate']>()
      .mockResolvedValue({
        evidenceId: '33333333-3333-4333-8333-333333333333',
      }),
    renew: vi.fn<FirstInvoiceClient['renew']>().mockResolvedValue({
      status: 'requires_action',
      clientSecret: 'pi_existing_secret_ephemeral',
    }),
    confirm: vi.fn<FirstInvoiceClient['confirm']>().mockResolvedValue({
      status: 'requires_action',
      clientSecret: 'pi_existing_secret_ephemeral',
    }),
  };
  const stripe = {
    handleNextAction: vi
      .fn<HostingStripe['handleNextAction']>()
      .mockResolvedValue({
        paymentIntent: {
          id: 'pi_existing',
          status: 'succeeded',
        } as PaymentIntent,
      }),
  };
  const queries = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queries}>
      <HostingFirstInvoiceCheckout
        subscriptionId={initial.subscriptionId}
        client={client}
        stripe={stripe}
      />
    </QueryClientProvider>
  );
  return {
    client,
    stripe,
    setCurrent: (next: FirstInvoice) => {
      current = next;
    },
  };
}

async function consent() {
  await screen.findByText('in_existing');
  fireEvent.change(screen.getByLabelText('Account email'), {
    target: { value: 'owner@example.com' },
  });
  fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
    target: { value: 'primary-secret' },
  });
  fireEvent.click(screen.getByRole('checkbox'));
  return screen.getByRole('button', { name: 'Authorize first-invoice charge' });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('owner first-invoice checkout', () => {
  it('requires exact invoice/instrument/amount consent and primary reauth, locking duplicate clicks', async () => {
    const { client, stripe } = setup();
    await screen.findByText('in_existing');
    expect(
      screen.queryByRole('button', { name: 'Renew exact authorization' })
    ).toBeNull();
    expect(
      (
        screen.getByRole('button', {
          name: 'Authorize first-invoice charge',
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true);
    const button = await consent();
    const confirmation = deferred<{
      status: string;
      clientSecret: string | null;
    }>();
    client.confirm.mockReturnValue(confirmation.promise);
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(client.confirm).toHaveBeenCalledTimes(1));
    const reauth = client.reauthenticate.mock.calls[0][1];
    const request = client.confirm.mock.calls[0][2];
    expect(reauth).toMatchObject({
      email: 'owner@example.com',
      secret: 'primary-secret',
      confirmationRequestId: request.requestId,
      billingBinding: {
        originalRequestId: request.requestId,
        expectedRowVersion: initial.expectedRowVersion,
        expectedAuthorizationVersion: 0,
        acceptedTupleDigest: initial.tupleDigest,
        invoiceExpiresAt: initial.expiresAt,
        authorizationPurpose: 'INITIAL_CONFIRMATION',
      },
    });
    expect(reauth.requestId).not.toBe(request.requestId);
    expect(request).toMatchObject({
      expectedRowVersion: 3,
      acceptedTupleDigest: initial.tupleDigest,
      acceptCharge: true,
    });
    expect(
      (
        screen.getByLabelText(
          'Primary authentication secret'
        ) as HTMLInputElement
      ).value
    ).toBe('');
    await act(async () =>
      confirmation.resolve({
        status: 'requires_action',
        clientSecret: 'pi_existing_secret_ephemeral',
      })
    );
    await waitFor(() =>
      expect(stripe.handleNextAction).toHaveBeenCalledExactlyOnceWith({
        clientSecret: 'pi_existing_secret_ephemeral',
      })
    );
    expect(screen.queryByText(/Account confirmed and committed/)).toBeNull();
    expect(document.body.textContent).not.toContain(
      'pi_existing_secret_ephemeral'
    );
  });

  it.each(['processing', 'succeeded'])(
    'keeps provider %s pending until independent Account COMMITTED read',
    async (status) => {
      const { client, stripe, setCurrent } = setup();
      client.confirm.mockResolvedValue({ status, clientSecret: null });
      fireEvent.click(await consent());
      await waitFor(() => expect(client.confirm).toHaveBeenCalledOnce());
      await screen.findByText(
        'Waiting for Account to verify and commit payment completion.'
      );
      expect(stripe.handleNextAction).not.toHaveBeenCalled();
      expect(screen.queryByText(/Account confirmed and committed/)).toBeNull();
      setCurrent({ ...initial, state: 'COMMITTED', expectedRowVersion: 4 });
      fireEvent.click(
        screen.getByRole('button', { name: 'Check Account status' })
      );
      await screen.findByText(
        'Account confirmed and committed this first-invoice payment.'
      );
      expect(screen.queryByRole('checkbox')).toBeNull();
    }
  );

  it('refuses a stale tuple before primary authentication or provider dispatch', async () => {
    const { client, setCurrent } = setup();
    const button = await consent();
    setCurrent({ ...initial, amount: '1500', expectedRowVersion: 4 });
    fireEvent.click(button);
    await screen.findByText(
      'The invoice changed or expired. Review the current details again.'
    );
    expect(client.reauthenticate).not.toHaveBeenCalled();
    expect(client.confirm).not.toHaveBeenCalled();
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(
      false
    );
    expect(screen.getByText('USD 15.00 (1500 minor units)')).toBeTruthy();
  });

  it('retries an ambiguous confirmation with exactly the same IDs, tuple, and evidence', async () => {
    const { client } = setup();
    client.confirm
      .mockRejectedValueOnce(new Error('private provider response'))
      .mockResolvedValueOnce({ status: 'processing', clientSecret: null });
    fireEvent.click(await consent());
    await screen.findByText(
      'The result is unknown. Check Account status or retry this same confirmation. Do not start another payment.'
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Resume exact confirmation' })
    );
    await waitFor(() => expect(client.confirm).toHaveBeenCalledTimes(2));
    expect(client.confirm.mock.calls[1]).toEqual(client.confirm.mock.calls[0]);
    expect(client.reauthenticate).toHaveBeenCalledOnce();
    expect(document.body.textContent).not.toContain(
      'private provider response'
    );
  });

  it.each([409, 410])(
    'explicitly renews the same original operation after status %i and no confirmation row',
    async (status) => {
      const { client, stripe, setCurrent } = setup();
      client.confirm
        .mockRejectedValueOnce(new Error('private uncertain response'))
        .mockRejectedValueOnce(new FirstInvoiceError(status));
      fireEvent.click(await consent());
      await screen.findByText(
        'The result is unknown. Check Account status or retry this same confirmation. Do not start another payment.'
      );
      const original = client.confirm.mock.calls[0][2];
      fireEvent.click(
        screen.getByRole('button', { name: 'Resume exact confirmation' })
      );
      await screen.findByText(
        'Your earlier confirmation is unresolved. You can explicitly renew authorization for this same invoice and original payment operation.'
      );
      expect(client.confirm.mock.calls[1]).toEqual(
        client.confirm.mock.calls[0]
      );
      expect(screen.queryByRole('checkbox')).toBeNull();
      expect(
        screen.queryByLabelText('Primary authentication secret')
      ).toBeNull();
      expect(
        screen.getByRole('button', { name: 'Renew exact authorization' })
      ).toBeTruthy();

      for (let count = 0; count < 2; count += 1) {
        const reads = client.read.mock.calls.length;
        fireEvent.click(
          screen.getByRole('button', { name: 'Check Account status' })
        );
        await waitFor(() =>
          expect(client.read.mock.calls.length).toBeGreaterThan(reads)
        );
        await waitFor(() =>
          expect(
            (
              screen.getByRole('button', {
                name: 'Check Account status',
              }) as HTMLButtonElement
            ).disabled
          ).toBe(false)
        );
      }
      expect(client.confirm).toHaveBeenCalledTimes(2);
      expect(client.reauthenticate).toHaveBeenCalledOnce();
      expect(client.renew).not.toHaveBeenCalled();
      expect(stripe.handleNextAction).not.toHaveBeenCalled();
      expect(document.body.textContent).not.toContain(
        'private uncertain response'
      );

      // A different invoice tuple cannot borrow the original operation's authority.
      setCurrent({ ...initial, paymentIntentId: 'pi_changed' });
      fireEvent.click(
        screen.getByRole('button', { name: 'Check Account status' })
      );
      await screen.findByText('pi_changed');
      expect(
        screen.queryByRole('button', { name: 'Renew exact authorization' })
      ).toBeNull();
      setCurrent(initial);
      fireEvent.click(
        screen.getByRole('button', { name: 'Check Account status' })
      );
      const renewal = await screen.findByRole('button', {
        name: 'Renew exact authorization',
      });
      expect(client.reauthenticate).toHaveBeenCalledOnce();
      expect(client.renew).not.toHaveBeenCalled();
      client.reauthenticate.mockResolvedValueOnce({
        evidenceId: '55555555-5555-4555-8555-555555555555',
      });
      client.renew.mockResolvedValue({
        status: 'processing',
        clientSecret: null,
      });
      fireEvent.click(renewal);
      fireEvent.change(screen.getByLabelText('Account email'), {
        target: { value: 'owner@example.com' },
      });
      fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
        target: { value: 'fresh-secret' },
      });
      fireEvent.click(screen.getByRole('checkbox'));
      fireEvent.click(
        screen.getByRole('button', { name: 'Resume exact confirmation' })
      );
      await waitFor(() => expect(client.renew).toHaveBeenCalledOnce());
      expect(client.renew.mock.calls[0][2]).toMatchObject({
        requestId: original.requestId,
        acceptedTupleDigest: original.acceptedTupleDigest,
        expectedRowVersion: original.expectedRowVersion,
        expectedAuthorizationVersion: 0,
        reauthenticationEvidenceId: '55555555-5555-4555-8555-555555555555',
        acceptCharge: true,
      });
      const renewRequest = client.renew.mock.calls[0][2];
      const renewalProof = client.reauthenticate.mock.calls[1][1];
      expect(renewRequest.authorizationRequestId).not.toBe(original.requestId);
      expect(renewalProof.confirmationRequestId).toBe(
        renewRequest.authorizationRequestId
      );
      expect(renewalProof.requestId).not.toBe(
        client.reauthenticate.mock.calls[0][1].requestId
      );
      expect(renewalProof.billingBinding).toEqual({
        originalRequestId: original.requestId,
        expectedRowVersion: original.expectedRowVersion,
        expectedAuthorizationVersion: 0,
        acceptedTupleDigest: original.acceptedTupleDigest,
        invoiceExpiresAt: initial.expiresAt,
        authorizationPurpose: 'PRE_ROW_RENEWAL',
      });
      expect(client.confirm).toHaveBeenCalledTimes(2);
      expect(stripe.handleNextAction).not.toHaveBeenCalled();
    }
  );

  it('does not dispatch after local close interrupts primary reauthentication', async () => {
    const { client } = setup();
    const proof = deferred<{ evidenceId: string }>();
    client.reauthenticate.mockReturnValue(proof.promise);
    fireEvent.click(await consent());
    await waitFor(() => expect(client.reauthenticate).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole('button', { name: 'Close checkout' }));
    await act(async () =>
      proof.resolve({ evidenceId: '33333333-3333-4333-8333-333333333333' })
    );
    expect(client.confirm).not.toHaveBeenCalled();
    expect(screen.getByText(/Checkout closed/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Resume checkout' }));
    await screen.findByText('in_existing');
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(
      false
    );
  });

  it('close after dispatch does not imply cancellation, and resumes the same operation', async () => {
    const { client, stripe } = setup();
    const reply = deferred<{ status: string; clientSecret: string | null }>();
    client.confirm
      .mockReturnValueOnce(reply.promise)
      .mockResolvedValueOnce({ status: 'processing', clientSecret: null });
    fireEvent.click(await consent());
    await waitFor(() => expect(client.confirm).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole('button', { name: 'Close checkout' }));
    await act(async () =>
      reply.resolve({
        status: 'requires_action',
        clientSecret: 'pi_existing_secret_ephemeral',
      })
    );
    expect(stripe.handleNextAction).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Resume checkout' }));
    await screen.findByText('in_existing');
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(
      screen.getByRole('button', { name: 'Resume exact confirmation' })
    );
    await waitFor(() => expect(client.confirm).toHaveBeenCalledTimes(2));
    expect(client.confirm.mock.calls[1]).toEqual(client.confirm.mock.calls[0]);
  });

  it('does not convert Stripe dismissal/error into success or expose provider text', async () => {
    const { stripe } = setup();
    stripe.handleNextAction.mockResolvedValue({
      error: { type: 'api_error', message: 'private client secret' },
    });
    fireEvent.click(await consent());
    await screen.findByText(
      'Stripe authentication was not completed. Check Account status or resume the same confirmation.'
    );
    expect(screen.queryByText(/Account confirmed and committed/)).toBeNull();
    expect(document.body.textContent).not.toContain('private client secret');
  });

  it('keeps reload of a retained confirmation read-only without a fresh payment or proof', async () => {
    const retainedId = '44444444-4444-4444-8444-444444444444';
    const { client, stripe } = setup({
      ...initial,
      confirmationRequestId: retainedId,
    });
    await screen.findByText('in_existing');
    expect(
      screen.getByText(/Account retained an earlier confirmation/)
    ).toBeTruthy();
    expect(screen.queryByLabelText('Primary authentication secret')).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    fireEvent.click(
      screen.getByRole('button', { name: 'Check Account status' })
    );
    await waitFor(() =>
      expect(client.read.mock.calls.length).toBeGreaterThan(1)
    );
    expect(client.reauthenticate).not.toHaveBeenCalled();
    expect(client.confirm).not.toHaveBeenCalled();
    expect(stripe.handleNextAction).not.toHaveBeenCalled();
  });

  it('holds an authorized-but-unconfirmed result without repeating provider work', async () => {
    const { client, stripe } = setup();
    client.confirm.mockResolvedValue({
      status: 'AUTHORIZED_NOT_CONFIRMED',
      clientSecret: null,
    });
    fireEvent.click(await consent());
    await screen.findByText(
      /Account retained the authorization, but provider confirmation is unresolved/
    );
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'Resume exact confirmation' })
    ).toBeNull();
    expect(stripe.handleNextAction).not.toHaveBeenCalled();
    expect(client.confirm).toHaveBeenCalledOnce();
  });

  it('requires an explicit renewal click, fresh proof and consent while preserving the original payment identity', async () => {
    const original = '44444444-4444-4444-8444-444444444444';
    const { client, stripe } = setup({
      ...initial,
      confirmationRequestId: original,
      authorizationVersion: 2,
    });
    await screen.findByText('in_existing');
    expect(client.renew).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole('button', { name: 'Renew exact authorization' })
    );
    fireEvent.change(screen.getByLabelText('Account email'), {
      target: { value: 'owner@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Primary authentication secret'), {
      target: { value: 'fresh-secret' },
    });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(
      screen.getByRole('button', { name: 'Resume exact confirmation' })
    );
    await waitFor(() => expect(client.renew).toHaveBeenCalledOnce());
    const renewed = client.renew.mock.calls[0][2];
    expect(renewed).toMatchObject({
      requestId: original,
      expectedAuthorizationVersion: 2,
      acceptedTupleDigest: initial.tupleDigest,
      expectedRowVersion: initial.expectedRowVersion,
      acceptCharge: true,
    });
    expect(renewed.authorizationRequestId).not.toBe(original);
    expect(client.reauthenticate.mock.calls[0][1]).toMatchObject({
      confirmationRequestId: renewed.authorizationRequestId,
      billingBinding: {
        originalRequestId: original,
        expectedRowVersion: initial.expectedRowVersion,
        expectedAuthorizationVersion: 2,
        acceptedTupleDigest: initial.tupleDigest,
        invoiceExpiresAt: initial.expiresAt,
        authorizationPurpose: 'FOLLOWUP_RENEWAL',
      },
      secret: 'fresh-secret',
    });
    expect(client.confirm).not.toHaveBeenCalled();
    await waitFor(() => expect(stripe.handleNextAction).toHaveBeenCalledOnce());
  });

  it('does not offer authorization renewal after the original invoice expires', async () => {
    setup({
      ...initial,
      confirmationRequestId: '44444444-4444-4444-8444-444444444444',
      authorizationVersion: 1,
      expiresAt: '2000-01-01T00:00:00Z',
    });
    await screen.findByText('in_existing');
    expect(
      screen.queryByRole('button', { name: 'Renew exact authorization' })
    ).toBeNull();
  });

  it('keeps expired checkout read-only and owner-session refusals non-authorizing', async () => {
    const { client } = setup({ ...initial, expiresAt: '2000-01-01T00:00:00Z' });
    await screen.findByText(
      'This checkout has expired. Only Account status recovery is available.'
    );
    expect((screen.getByRole('checkbox') as HTMLInputElement).disabled).toBe(
      true
    );
    client.read.mockRejectedValue(new FirstInvoiceError(403));
    fireEvent.click(
      screen.getByRole('button', { name: 'Check Account status' })
    );
    await screen.findByText(
      'The owner invoice is unavailable. No payment completion is confirmed.'
    );
    expect(client.confirm).not.toHaveBeenCalled();
  });
});
