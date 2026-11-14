import { loadStripe } from '@stripe/stripe-js/pure';
import type { Stripe } from '@stripe/stripe-js';

/** No intent creation/confirmation capability is exposed to checkout. */
export interface HostingStripe {
  handleNextAction(options: {
    clientSecret: string;
  }): ReturnType<Stripe['handleNextAction']>;
}

/** Call from the real control-ui composition with its public Stripe key. */
export async function loadHostingStripe(
  publishableKey: string
): Promise<HostingStripe> {
  if (!/^pk_(test|live)_[A-Za-z0-9]+$/.test(publishableKey)) {
    throw new Error('A Stripe publishable key is required.');
  }
  const stripe = await loadStripe(publishableKey);
  if (!stripe) throw new Error('Stripe authentication is unavailable.');
  return { handleNextAction: (options) => stripe.handleNextAction(options) };
}
