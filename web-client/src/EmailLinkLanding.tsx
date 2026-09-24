import { useEffect, useState, type FormEvent } from 'react';
import type { EmailLinkLandingRoute } from './emailLinkLanding';

interface EmailLinkLandingProps {
  route: Exclude<EmailLinkLandingRoute, null>;
}

function installNoReferrerPolicy(): void {
  let policy = document.head.querySelector<HTMLMetaElement>(
    'meta[name="referrer"]'
  );
  if (!policy) {
    policy = document.createElement('meta');
    policy.name = 'referrer';
    document.head.append(policy);
  }
  policy.content = 'no-referrer';
}

async function postToken(path: string, token: string, newPassword?: string) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(
      newPassword === undefined ? { token } : { token, newPassword }
    ),
  });
  if (!response.ok) {
    throw new Error('The request could not be completed.');
  }
}

export default function EmailLinkLanding({ route }: EmailLinkLandingProps) {
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [complete, setComplete] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    installNoReferrerPolicy();
  }, []);

  const token = route.token;
  const isVerification = route.kind === 'verify-email';
  const title = isVerification ? 'Verify your email' : 'Choose a new password';

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || submitting || complete) return;

    setSubmitting(true);
    setFailed(false);
    try {
      await postToken(
        isVerification
          ? '/api/account/auth/verify-email'
          : '/api/account/auth/complete-password-reset',
        token,
        isVerification ? undefined : password
      );
      setComplete(true);
      setPassword('');
    } catch {
      setFailed(true);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="email-link-page">
      <section className="email-link-panel" aria-labelledby="email-link-title">
        <h1 id="email-link-title">{title}</h1>
        {complete ? (
          <p role="status">
            {isVerification
              ? 'Your email address is verified.'
              : 'Your password has been updated.'}
          </p>
        ) : !token ? (
          <p role="status">
            This link is invalid or has expired. Request a new email and try
            again.
          </p>
        ) : (
          <>
            <p>
              {isVerification
                ? 'Select the button below to verify this email address.'
                : 'Enter a new password, then select the button to finish resetting it.'}
            </p>
            <form onSubmit={submit}>
              {!isVerification && (
                <label className="email-link-field">
                  New password
                  <input
                    autoComplete="new-password"
                    name="newPassword"
                    onChange={(event) => setPassword(event.target.value)}
                    required
                    type="password"
                    value={password}
                  />
                </label>
              )}
              <button disabled={submitting} type="submit">
                {submitting
                  ? 'Submitting…'
                  : isVerification
                    ? 'Verify email'
                    : 'Update password'}
              </button>
            </form>
            {failed && (
              <p role="alert">
                We could not complete this request. The link may be invalid or
                expired. Request a new email and try again.
              </p>
            )}
          </>
        )}
      </section>
    </main>
  );
}
