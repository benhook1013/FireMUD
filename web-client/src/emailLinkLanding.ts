export type EmailLinkLandingRoute =
  | { kind: 'verify-email'; token: string | null }
  | { kind: 'reset-password'; token: string | null }
  | null;

export type EmailLinkCompletionFailureKind =
  'missing-token' | 'http' | 'network';

export class EmailLinkCompletionError extends Error {
  readonly kind: EmailLinkCompletionFailureKind;
  readonly status: number | null;

  constructor(
    kind: EmailLinkCompletionFailureKind,
    status: number | null,
    message = 'The request could not be completed.'
  ) {
    super(message);
    this.name = 'EmailLinkCompletionError';
    this.kind = kind;
    this.status = status;
  }
}

function tokenFromFragment(hash: string): string | null {
  const values = new URLSearchParams(
    hash.startsWith('#') ? hash.slice(1) : hash
  );
  const token = values.get('token');
  return token && token.trim().length > 0 ? token : null;
}

export function captureEmailLinkLanding(
  location: Location,
  history: History
): EmailLinkLandingRoute {
  const path = location.pathname.replace(/\/$/, '') || '/';
  if (path !== '/verify-email' && path !== '/reset-password') {
    return null;
  }

  const token = tokenFromFragment(location.hash);
  // The token is consumed from memory only. Replacing the address also keeps
  // it out of copied URLs and any subsequent same-page navigation metadata.
  history.replaceState(null, '', location.pathname);

  return path === '/verify-email'
    ? { kind: 'verify-email', token }
    : { kind: 'reset-password', token };
}

export async function completeEmailLink(
  route: Exclude<EmailLinkLandingRoute, null>,
  newPassword?: string,
  request: typeof fetch = fetch
): Promise<void> {
  if (!route.token) {
    throw new EmailLinkCompletionError(
      'missing-token',
      null,
      'The link is invalid.'
    );
  }
  const verification = route.kind === 'verify-email';
  try {
    const response = await request(
      verification
        ? '/api/account/auth/verify-email'
        : '/api/account/auth/complete-password-reset',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(
          verification
            ? { token: route.token }
            : { token: route.token, newPassword }
        ),
      }
    );
    if (!response.ok) {
      throw new EmailLinkCompletionError('http', response.status ?? null);
    }
  } catch (error) {
    if (error instanceof EmailLinkCompletionError) {
      throw error;
    }
    throw new EmailLinkCompletionError('network', null);
  }
}

export function emailLinkFailureMessage(
  routeKind: Exclude<EmailLinkLandingRoute, null>['kind'],
  failure: Pick<EmailLinkCompletionError, 'kind' | 'status'>
): string {
  if (failure.kind === 'missing-token') {
    return 'This link is invalid or has expired. Request a new email and try again.';
  }

  if (failure.kind === 'http' && failure.status === 400) {
    return 'We could not accept this request. Check the submitted details and request a new email if needed.';
  }

  if (
    failure.kind === 'network' ||
    (failure.kind === 'http' &&
      (failure.status === 429 || (failure.status ?? 0) >= 500))
  ) {
    return routeKind === 'reset-password'
      ? 'We could not confirm whether the password reset completed. Try signing in with your intended new password before requesting another reset email.'
      : 'We could not confirm whether email verification completed. Check your account after signing in before requesting another verification email.';
  }

  return 'We could not accept this request. Check the submitted details and try again.';
}

// Capture and scrub before React mounts or any route can issue a request.
export const emailLinkLanding =
  typeof window === 'undefined'
    ? null
    : captureEmailLinkLanding(window.location, window.history);
