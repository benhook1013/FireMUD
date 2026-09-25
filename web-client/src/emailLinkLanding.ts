export type EmailLinkLandingRoute =
  | { kind: 'verify-email'; token: string | null }
  | { kind: 'reset-password'; token: string | null }
  | null;

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
    throw new Error('The link is invalid.');
  }
  const verification = route.kind === 'verify-email';
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
    throw new Error('The request could not be completed.');
  }
}

// Capture and scrub before React mounts or any route can issue a request.
export const emailLinkLanding =
  typeof window === 'undefined'
    ? null
    : captureEmailLinkLanding(window.location, window.history);
