export interface AccountControlUiLoginRequest {
  requestId: string;
  callerContextId: string;
  email: string;
  secret: string;
}

export interface AccountControlUiLoginResult {
  accountId: string;
  authToken: string;
  expiresAt: string;
}

export interface AccountControlUiApi {
  login(
    request: AccountControlUiLoginRequest,
    signal?: AbortSignal
  ): Promise<AccountControlUiLoginResult>;
}

export class AccountControlUiApiError extends Error {
  readonly status: number;
  readonly uncertain: boolean;

  constructor(
    status: number,
    uncertain = status < 400 || status === 408 || status >= 500
  ) {
    super('Account sign-in could not be completed.');
    this.name = 'AccountControlUiApiError';
    this.status = status;
    this.uncertain = uncertain;
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const jwt = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const timestamp =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const maxLoginResponseBytes = 32 * 1024;
export const accountControlUiLoginTimeoutMs = 10_000;
export const maxControlUiSessionLifetimeMs = 24 * 60 * 60 * 1000;

function requireSecureOrigin(value: string): URL {
  let origin: URL;
  try {
    origin = new URL(value);
  } catch {
    throw new Error('A same-origin HTTPS Gateway URL is required.');
  }
  if (
    origin.protocol !== 'https:' ||
    origin.username ||
    origin.password ||
    origin.pathname !== '/' ||
    origin.search ||
    origin.hash
  ) {
    throw new Error('A same-origin HTTPS Gateway URL is required.');
  }
  return origin;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return (
    actual.length === expected.length &&
    actual.every((key, index) => key === expected[index])
  );
}

async function readBoundedJson(
  response: Response,
  signal: AbortSignal
): Promise<unknown> {
  if (!response.body) throw new AccountControlUiApiError(0);
  const reader = response.body.getReader();
  const discard = () => void reader.cancel().catch(() => {});
  signal.addEventListener('abort', discard, { once: true });
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      if (signal.aborted) throw new AccountControlUiApiError(0);
      const { done, value } = await reader.read();
      if (signal.aborted) throw new AccountControlUiApiError(0);
      if (done) break;
      size += value.byteLength;
      if (size > maxLoginResponseBytes) throw new AccountControlUiApiError(0);
      chunks.push(value);
    }
  } catch {
    discard();
    throw new AccountControlUiApiError(0);
  } finally {
    signal.removeEventListener('abort', discard);
    reader.releaseLock();
  }

  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return JSON.parse(text) as unknown;
  } catch {
    throw new AccountControlUiApiError(0);
  }
}

function parseLoginResult(
  value: unknown,
  now: number
): AccountControlUiLoginResult {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, ['status', 'data']) ||
    value.status !== 'SUCCESS' ||
    !isRecord(value.data) ||
    !hasExactKeys(value.data, ['accountId', 'authToken', 'expiresAt'])
  ) {
    throw new AccountControlUiApiError(0);
  }

  const { accountId, authToken, expiresAt } = value.data;
  if (
    typeof accountId !== 'string' ||
    !uuid.test(accountId) ||
    typeof authToken !== 'string' ||
    authToken.length > 16_384 ||
    !jwt.test(authToken) ||
    typeof expiresAt !== 'string' ||
    !timestamp.test(expiresAt)
  ) {
    throw new AccountControlUiApiError(0);
  }

  const expiry = Date.parse(expiresAt);
  if (
    !Number.isFinite(expiry) ||
    expiry <= now ||
    expiry - now > maxControlUiSessionLifetimeMs
  ) {
    throw new AccountControlUiApiError(0);
  }

  return { accountId, authToken, expiresAt };
}

/**
 * Builds the only login transport used by the account checkout route. The
 * caller supplies the browser's current origin, never an API URL from a form.
 */
export function createAccountControlUiApi(
  origin: string | (() => string),
  fetchImpl: typeof fetch = fetch,
  now: () => number = Date.now
): AccountControlUiApi {
  if (typeof origin === 'string') requireSecureOrigin(origin);

  function currentOrigin(): URL {
    return requireSecureOrigin(
      typeof origin === 'function' ? origin() : origin
    );
  }

  return {
    async login(request, signal) {
      let url: URL;
      try {
        url = new URL('/api/account/auth/login', currentOrigin());
      } catch {
        throw new AccountControlUiApiError(0, false);
      }
      if (
        !uuid.test(request.requestId) ||
        !uuid.test(request.callerContextId) ||
        typeof request.email !== 'string' ||
        request.email.length < 3 ||
        request.email.length > 254 ||
        request.email.trim() !== request.email ||
        typeof request.secret !== 'string' ||
        request.secret.length < 1 ||
        request.secret.length > 1024
      ) {
        throw new AccountControlUiApiError(0, false);
      }
      if (signal?.aborted) throw new AccountControlUiApiError(0, false);

      const controller = new AbortController();
      const abort = () => controller.abort();
      const deadline = performance.now() + accountControlUiLoginTimeoutMs;
      const timer = setTimeout(abort, accountControlUiLoginTimeoutMs);
      signal?.addEventListener('abort', abort, { once: true });
      const interrupted = new Promise<never>((_, reject) => {
        controller.signal.addEventListener(
          'abort',
          () => reject(new AccountControlUiApiError(0)),
          { once: true }
        );
      });
      const assertWithinDeadline = () => {
        if (performance.now() >= deadline) abort();
        if (controller.signal.aborted) throw new AccountControlUiApiError(0);
      };

      async function receive(): Promise<AccountControlUiLoginResult> {
        const response = await fetchImpl(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: JSON.stringify(request),
          cache: 'no-store',
          credentials: 'omit',
          redirect: 'error',
          referrerPolicy: 'no-referrer',
          mode: 'same-origin',
          signal: controller.signal,
        });
        try {
          assertWithinDeadline();
          if (response.status !== 200) {
            throw new AccountControlUiApiError(response.status);
          }
        } catch (error) {
          // Discard rejected and late responses without waiting for stream cancellation.
          void response.body?.cancel().catch(() => {});
          throw error;
        }
        const body = await readBoundedJson(response, controller.signal);
        assertWithinDeadline();
        return parseLoginResult(body, now());
      }

      try {
        return await Promise.race([interrupted, receive()]);
      } catch (error) {
        if (error instanceof AccountControlUiApiError) throw error;
        throw new AccountControlUiApiError(0);
      } finally {
        clearTimeout(timer);
        signal?.removeEventListener('abort', abort);
      }
    },
  };
}
