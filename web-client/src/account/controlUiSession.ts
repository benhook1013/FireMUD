import {
  maxControlUiSessionLifetimeMs,
  type AccountControlUiApi,
  type AccountControlUiLoginRequest,
  type AccountControlUiLoginResult,
} from '../api/accountControlUi';

export interface ControlUiSessionView {
  readonly identity: string;
  readonly accountId: string;
  readonly expiresAt: string;
}

export interface ControlUiCredentials {
  email: string;
  secret: string;
}

export type ControlUiSessionErrorCode =
  | 'SESSION_REQUIRED'
  | 'SESSION_EXPIRED'
  | 'SESSION_REPLACED'
  | 'AUTH_REJECTED'
  | 'REQUEST_CANCELLED'
  | 'REQUEST_FAILED'
  | 'INVALID_RESPONSE';

export class ControlUiSessionError extends Error {
  readonly code: ControlUiSessionErrorCode;

  constructor(code: ControlUiSessionErrorCode) {
    super('The account session is unavailable.');
    this.name = 'ControlUiSessionError';
    this.code = code;
  }
}

export interface ControlUiSessionOptions {
  origin: () => string;
  fetchImpl?: typeof fetch;
  now?: () => number;
  idFactory?: () => string;
  clearQueryCache?: () => void;
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const jwt = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const ownerPath =
  /^\/api\/account\/billing\/hosting\/subscriptions\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/first-invoice(?:\/(reauthenticate|confirm|renew))?$/i;
const maxOwnerRequestBytes = 16 * 1024;
const maxOwnerResponseBytes = 64 * 1024;

function secureOrigin(value: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  if (
    parsed.protocol !== 'https:' ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== '/' ||
    parsed.search ||
    parsed.hash
  ) {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  return parsed;
}

function checkedLoginResult(
  result: AccountControlUiLoginResult,
  now: number
): number {
  if (
    typeof result.accountId !== 'string' ||
    !uuid.test(result.accountId) ||
    typeof result.authToken !== 'string' ||
    result.authToken.length > 16_384 ||
    !jwt.test(result.authToken) ||
    typeof result.expiresAt !== 'string'
  ) {
    throw new ControlUiSessionError('INVALID_RESPONSE');
  }
  const expiry = Date.parse(result.expiresAt);
  if (
    !Number.isFinite(expiry) ||
    expiry <= now ||
    expiry - now > maxControlUiSessionLifetimeMs
  ) {
    throw new ControlUiSessionError('INVALID_RESPONSE');
  }
  return expiry;
}

function makeUuid(): string {
  if (!globalThis.crypto?.randomUUID) {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  return globalThis.crypto.randomUUID();
}

async function readBoundedBytes(
  response: Response,
  signal: AbortSignal
): Promise<Uint8Array> {
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      if (signal.aborted) throw new ControlUiSessionError('REQUEST_CANCELLED');
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxOwnerResponseBytes) {
        await reader.cancel();
        throw new ControlUiSessionError('REQUEST_FAILED');
      }
      chunks.push(value);
    }
  } catch {
    try {
      await reader.cancel();
    } catch {
      // The response body is private Account data and is never surfaced here.
    }
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

function validateOwnerRequest(
  url: URL,
  init: RequestInit,
  origin: URL
): string {
  if (
    url.protocol !== 'https:' ||
    url.origin !== origin.origin ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  ) {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  const match = ownerPath.exec(url.pathname);
  if (!match) throw new ControlUiSessionError('REQUEST_FAILED');
  const expectedMethod = match[2] ? 'POST' : 'GET';
  const method = (init.method ?? 'GET').toUpperCase();
  if (method !== expectedMethod)
    throw new ControlUiSessionError('REQUEST_FAILED');
  if (method === 'GET' && init.body != null) {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  if (
    method === 'POST' &&
    (typeof init.body !== 'string' || init.body.length > maxOwnerRequestBytes)
  ) {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }

  let headers: Headers;
  try {
    headers = new Headers(init.headers);
  } catch {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  if (headers.has('authorization')) {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  for (const [name, value] of headers.entries()) {
    if (
      name.toLowerCase() !== 'accept' &&
      name.toLowerCase() !== 'content-type'
    ) {
      throw new ControlUiSessionError('REQUEST_FAILED');
    }
    if (
      name.toLowerCase() === 'content-type' &&
      value.toLowerCase() !== 'application/json'
    ) {
      throw new ControlUiSessionError('REQUEST_FAILED');
    }
  }
  if (method === 'POST' && headers.get('content-type') !== 'application/json') {
    throw new ControlUiSessionError('REQUEST_FAILED');
  }
  return method;
}

function guardedJsonResponse(
  status: number,
  bytes: Uint8Array,
  assertCurrent: () => void,
  contentType: string
): Response {
  const body = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(body).set(bytes);
  const response = new Response(body, {
    status,
    headers: {
      'Content-Type': contentType,
      'Cache-Control': 'no-store',
    },
  });
  const json = response.json.bind(response);
  Object.defineProperty(response, 'json', {
    configurable: false,
    value: async () => {
      assertCurrent();
      const value = await json();
      assertCurrent();
      return value;
    },
  });
  return response;
}

/**
 * Memory-only owner for the current Account control-ui token. It is intentionally
 * narrower than a general API client: only the exact first-invoice owner paths
 * can receive the bearer, and local sign-out makes no server revocation claim.
 */
export class ControlUiSessionStore {
  private readonly listeners = new Set<() => void>();
  private readonly requests = new Set<AbortController>();
  private readonly origin: () => string;
  private readonly fetchImpl: typeof fetch;
  private readonly now: () => number;
  private readonly idFactory: () => string;
  private readonly clearQueryCache?: () => void;
  private snapshot: ControlUiSessionView | null = null;
  private bearer: string | null = null;
  private revision = 0;
  private loginController: AbortController | null = null;
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(options: ControlUiSessionOptions) {
    this.origin = options.origin;
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.now = options.now ?? Date.now;
    this.idFactory = options.idFactory ?? makeUuid;
    this.clearQueryCache = options.clearQueryCache;
  }

  readonly getSession = (): ControlUiSessionView | null => {
    if (this.snapshot && this.now() >= Date.parse(this.snapshot.expiresAt)) {
      return null;
    }
    return this.snapshot;
  };

  readonly getGatewayOrigin = (): string => secureOrigin(this.origin()).origin;

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  readonly authenticatedRequest = async (
    input: URL,
    init: RequestInit
  ): Promise<Response> => {
    let target: URL;
    let origin: URL;
    let method: string;
    try {
      origin = secureOrigin(this.origin());
      target = new URL(input.href);
      method = validateOwnerRequest(target, init, origin);
    } catch (error) {
      if (error instanceof ControlUiSessionError) throw error;
      throw new ControlUiSessionError('REQUEST_FAILED');
    }

    const session = this.requireCurrentSession();
    const revision = this.revision;
    const bearer = this.bearer;
    if (!bearer) throw new ControlUiSessionError('SESSION_REQUIRED');

    const controller = new AbortController();
    const externalSignal = init.signal;
    const cancelFromCaller = () => controller.abort();
    if (externalSignal?.aborted) controller.abort();
    else
      externalSignal?.addEventListener('abort', cancelFromCaller, {
        once: true,
      });
    this.requests.add(controller);

    const isCurrent = () =>
      this.revision === revision &&
      this.snapshot?.identity === session.identity &&
      this.bearer === bearer &&
      this.now() < Date.parse(session.expiresAt);
    const assertCurrent = () => {
      if (
        this.revision === revision &&
        this.snapshot?.identity === session.identity &&
        this.bearer === bearer &&
        this.now() >= Date.parse(session.expiresAt)
      ) {
        this.clearIfCurrent(session.identity, revision);
        throw new ControlUiSessionError('SESSION_EXPIRED');
      }
      if (!isCurrent()) throw new ControlUiSessionError('SESSION_REPLACED');
    };

    try {
      assertCurrent();

      const headers = new Headers(init.headers);
      headers.set('Authorization', `Bearer ${bearer}`);
      let response: Response;
      try {
        response = await this.fetchImpl(target, {
          method,
          headers,
          ...(init.body !== undefined ? { body: init.body } : {}),
          cache: 'no-store',
          credentials: 'omit',
          redirect: 'error',
          referrerPolicy: 'no-referrer',
          mode: 'same-origin',
          signal: controller.signal,
        });
      } catch {
        assertCurrent();
        throw new ControlUiSessionError(
          controller.signal.aborted ? 'REQUEST_CANCELLED' : 'REQUEST_FAILED'
        );
      }

      assertCurrent();
      if (response.status === 401 || response.status === 403) {
        this.clearIfCurrent(session.identity, revision);
        throw new ControlUiSessionError('AUTH_REJECTED');
      }
      if (!response.ok) {
        try {
          await response.body?.cancel();
        } catch {
          // Error bodies may contain sensitive provider or Account details.
        }
        assertCurrent();
        return new Response(null, {
          status: response.status,
          headers: { 'Cache-Control': 'no-store' },
        });
      }
      if (response.status !== 200) {
        try {
          await response.body?.cancel();
        } catch {
          // A success response outside the owner contract is discarded.
        }
        assertCurrent();
        throw new ControlUiSessionError('REQUEST_FAILED');
      }

      const bytes = await readBoundedBytes(response, controller.signal);
      assertCurrent();
      return guardedJsonResponse(
        response.status,
        bytes,
        assertCurrent,
        response.headers.get('content-type') ?? 'application/json'
      );
    } finally {
      externalSignal?.removeEventListener('abort', cancelFromCaller);
      this.requests.delete(controller);
    }
  };

  async signIn(
    api: AccountControlUiApi,
    credentials: ControlUiCredentials
  ): Promise<ControlUiSessionView> {
    const hadSession = this.snapshot !== null || this.bearer !== null;
    const revision = this.beginReplacement();
    if (hadSession) this.emitSessionChange();
    const controller = new AbortController();
    this.loginController = controller;
    const request: AccountControlUiLoginRequest = {
      requestId: this.idFactory(),
      callerContextId: this.idFactory(),
      email: credentials.email,
      secret: credentials.secret,
    };

    try {
      const result = await api.login(request, controller.signal);
      if (
        controller.signal.aborted ||
        this.revision !== revision ||
        this.loginController !== controller
      ) {
        throw new ControlUiSessionError('SESSION_REPLACED');
      }

      const expiry = checkedLoginResult(result, this.now());
      const identity = this.idFactory();
      if (!uuid.test(identity))
        throw new ControlUiSessionError('INVALID_RESPONSE');
      const next: ControlUiSessionView = Object.freeze({
        identity,
        accountId: result.accountId,
        expiresAt: result.expiresAt,
      });
      this.snapshot = next;
      this.bearer = result.authToken;
      this.scheduleExpiry(next.identity, revision, expiry);
      this.loginController = null;
      this.emitSessionChange();
      return next;
    } catch (error) {
      if (
        controller.signal.aborted ||
        this.revision !== revision ||
        this.loginController !== controller
      ) {
        throw new ControlUiSessionError('SESSION_REPLACED');
      }
      this.loginController = null;
      throw error;
    } finally {
      request.secret = '';
    }
  }

  /** Clear only this browser's in-memory credentials; no remote logout is sent. */
  signOut(): void {
    const hadSession = this.snapshot !== null || this.bearer !== null;
    this.beginReplacement();
    if (hadSession) this.emitSessionChange();
  }

  private requireCurrentSession(): ControlUiSessionView {
    if (this.snapshot && this.now() >= Date.parse(this.snapshot.expiresAt)) {
      const { identity } = this.snapshot;
      this.clearIfCurrent(identity, this.revision);
      throw new ControlUiSessionError('SESSION_EXPIRED');
    }
    if (!this.snapshot || !this.bearer) {
      throw new ControlUiSessionError('SESSION_REQUIRED');
    }
    return this.snapshot;
  }

  private beginReplacement(): number {
    this.revision += 1;
    this.loginController?.abort();
    this.loginController = null;
    this.abortRequests();
    this.clearExpiryTimer();
    this.snapshot = null;
    this.bearer = null;
    return this.revision;
  }

  private expireIfCurrent(identity: string, revision: number): void {
    if (this.revision !== revision || this.snapshot?.identity !== identity) {
      return;
    }
    const expiry = Date.parse(this.snapshot.expiresAt);
    if (this.now() >= expiry) this.clearIfCurrent(identity, revision);
    else this.scheduleExpiry(identity, revision, expiry);
  }

  private clearIfCurrent(identity: string, revision: number): void {
    if (this.revision !== revision || this.snapshot?.identity !== identity) {
      return;
    }
    this.beginReplacement();
    this.emitSessionChange();
  }

  private scheduleExpiry(
    identity: string,
    revision: number,
    expiry: number
  ): void {
    this.clearExpiryTimer();
    this.expiryTimer = setTimeout(
      () => {
        this.expiryTimer = null;
        this.expireIfCurrent(identity, revision);
      },
      Math.max(0, expiry - this.now())
    );
  }

  private clearExpiryTimer(): void {
    if (this.expiryTimer !== null) clearTimeout(this.expiryTimer);
    this.expiryTimer = null;
  }

  private abortRequests(): void {
    for (const controller of this.requests) controller.abort();
    this.requests.clear();
  }

  private emitSessionChange(): void {
    try {
      this.clearQueryCache?.();
    } catch {
      // Cache clearing must not restore or retain the invalidated token.
    }
    for (const listener of this.listeners) listener();
  }
}

export function createControlUiSessionStore(
  options: ControlUiSessionOptions
): ControlUiSessionStore {
  return new ControlUiSessionStore(options);
}
