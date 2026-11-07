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

  constructor(status: number) {
    super('Account sign-in could not be completed.');
    this.name = 'AccountControlUiApiError';
    this.status = status;
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const jwt = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const timestamp =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const maxLoginResponseBytes = 32 * 1024;
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
  signal?: AbortSignal
): Promise<unknown> {
  if (!response.body) throw new AccountControlUiApiError(0);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      if (signal?.aborted) throw new AccountControlUiApiError(0);
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxLoginResponseBytes) {
        await reader.cancel();
        throw new AccountControlUiApiError(0);
      }
      chunks.push(value);
    }
  } catch {
    try {
      await reader.cancel();
    } catch {
      // Do not expose a stream or transport error that could contain response data.
    }
    throw new AccountControlUiApiError(0);
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
      const url = new URL('/api/account/auth/login', currentOrigin());
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
        throw new AccountControlUiApiError(0);
      }

      let response: Response;
      try {
        response = await fetchImpl(url, {
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
          signal,
        });
      } catch {
        throw new AccountControlUiApiError(0);
      }

      if (response.status !== 200) {
        try {
          await response.body?.cancel();
        } catch {
          // The body is deliberately discarded; server text is never surfaced.
        }
        throw new AccountControlUiApiError(response.status);
      }

      return parseLoginResult(await readBoundedJson(response, signal), now());
    },
  };
}
