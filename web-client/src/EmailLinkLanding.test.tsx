// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => {
  cleanup();
  document.head.querySelector('meta[name="referrer"]')?.remove();
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
});

async function openLanding(href: string) {
  vi.resetModules();
  const request = vi
    .fn<typeof fetch>()
    .mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', request);
  window.history.replaceState(null, '', href);
  const { default: App } = await import('./App');
  render(<App />);
  return request;
}

it('mounts the verification link without sending anything until the user submits', async () => {
  const request = await openLanding(
    '/verify-email?ignored=1#token=synthetic-verification'
  );
  screen.getByRole('heading', { name: 'Verify your email' });
  expect(window.location.search + window.location.hash).toBe('');
  expect(request).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: 'Verify email' }));
  expect((await screen.findByRole('status')).textContent).toContain(
    'Your email address is verified.'
  );
  expect(request).toHaveBeenCalledExactlyOnceWith(
    '/api/account/auth/verify-email',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: 'synthetic-verification' }),
    }
  );
});

it('mounts the reset form and sends the typed password only on explicit submission', async () => {
  const request = await openLanding('/reset-password#token=synthetic-reset');
  screen.getByRole('heading', { name: 'Choose a new password' });
  const input = screen.getByLabelText('New password');
  expect(window.location.hash).toBe('');
  expect(request).not.toHaveBeenCalled();
  fireEvent.change(input, { target: { value: 'test-password' } });
  expect(request).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: 'Update password' }));
  expect((await screen.findByRole('status')).textContent).toContain(
    'Your password has been updated.'
  );
  expect(request).toHaveBeenCalledExactlyOnceWith(
    '/api/account/auth/complete-password-reset',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        token: 'synthetic-reset',
        newPassword: 'test-password',
      }),
    }
  );
});
