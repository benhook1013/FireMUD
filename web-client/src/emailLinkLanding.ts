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
  history.replaceState(null, '', `${location.pathname}${location.search}`);

  return path === '/verify-email'
    ? { kind: 'verify-email', token }
    : { kind: 'reset-password', token };
}

// Capture and scrub before React mounts or any route can issue a request.
export const emailLinkLanding = captureEmailLinkLanding(
  window.location,
  window.history
);
