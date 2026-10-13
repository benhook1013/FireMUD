package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Minimum genuine backend creator issuance/recovery, deliberately unregistered and unmounted.
 * Primary credentials, existing owner sources and actual Coordination are all required. An
 * independently protected exact peer binding supplies transport identity, never creator authority.
 */
public final class AccountControlUiIssuanceService {
  private final AccountServiceImpl primaryAuthentication;
  private final AccountControlUiIssuanceRepository operations;
  private final AccountControlUiAuthority authority;
  private final DraftAuthorizationFenceRepository fences;
  private final AccountControlUiSignerOwner signers;
  private final AccountControlUiResponseCryptography cryptography;
  private final AccountControlUiCoordination registry;
  private final AccountControlUiActorService actors;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String protectedCallerUri;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected primary authentication and Draft fence services remain internal Account owner collaborators.")
  public AccountControlUiIssuanceService(
      AccountServiceImpl primaryAuthentication,
      AccountControlUiIssuanceRepository operations,
      AccountControlUiAuthority authority,
      DraftAuthorizationFenceRepository fences,
      AccountControlUiSignerOwner signers,
      AccountControlUiResponseCryptography cryptography,
      AccountControlUiCoordination registry,
      AccountControlUiActorService actors,
      PlatformTransactionManager transactions,
      Clock clock,
      String independentlyProtectedCallerUri) {
    this.primaryAuthentication = Objects.requireNonNull(primaryAuthentication);
    this.operations = Objects.requireNonNull(operations);
    this.authority = Objects.requireNonNull(authority);
    this.fences = Objects.requireNonNull(fences);
    this.signers = Objects.requireNonNull(signers);
    this.cryptography = Objects.requireNonNull(cryptography);
    this.registry = Objects.requireNonNull(registry);
    this.actors = Objects.requireNonNull(actors);
    this.clock = Objects.requireNonNull(clock);
    protectedCallerUri =
        GrpcPeerIdentity.parseUri(independentlyProtectedCallerUri)
            .orElseThrow(
                () -> new IllegalArgumentException("One protected exact issuer peer URI required"))
            .uri();
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public IssuedCredential issue(Request request, CapturedEnvironmentBoundary environment) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Control-ui workflow cannot inherit owner SQL locks");
    }
    Objects.requireNonNull(request);
    Objects.requireNonNull(environment);
    var peer = GrpcPeerIdentity.current();
    if (peer == null || !protectedCallerUri.equals(peer.uri())) {
      throw denied();
    }
    String email = EmailCanonicalization.normalize(request.email);
    byte[] exactRequest =
        AccountControlUiAuthority.canonical(
            Map.of(
                "schema",
                "account-control-ui-request/v1",
                "requestId",
                request.requestId.toString(),
                "email",
                email,
                "secret",
                request.secret,
                "canonicalTenantId",
                request.tenantId.toString(),
                "callerWorkload",
                peer.uri(),
                "callerContextId",
                request.callerContextId.toString()));
    try {
      InitialPreparation initial =
          transaction.execute(
              ignored -> {
                var existing = operations.findRequest(request.requestId);
                if (existing != null) {
                  requireRequest(existing, request, peer.uri(), exactRequest);
                  return new InitialPreparation(current(existing, environment), null);
                }
                // Preserve canonical tenant -> Account owner order before OTP/advisory
                // authentication.
                var source = authority.captureInitial(request.tenantId, environment);
                fences.lockProducerSourcesNowait(source.sources());
                AccountServiceImpl.ControlUiPrimaryIdentity authenticated;
                try {
                  authenticated =
                      primaryAuthentication.authenticateControlUiPrimaryIdentity(
                          email, request.secret);
                } catch (AuthenticationException denial) {
                  // Only primary denial commits attempt accounting. No signer/issuance work
                  // follows.
                  return new InitialPreparation(
                      null, new PrimaryDenial(denial.getCode(), denial.getMessage()));
                }
                if (!source.actor().equals(authenticated.accountId())) {
                  throw denied();
                }
                var signer = signers.captureCurrent();
                String macKey = cryptography.currentKeyId();
                String requestDigest = cryptography.requestDigest(macKey, exactRequest);
                var stored =
                    operations.prepare(
                        request.requestId,
                        peer.uri(),
                        request.callerContextId,
                        macKey,
                        requestDigest,
                        authenticated,
                        source,
                        signer,
                        Instant.ofEpochSecond(clock.instant().getEpochSecond()));
                return new InitialPreparation(new Prepared(stored, source, signer), null);
              });
      if (initial == null) {
        throw denied();
      }
      if (initial.denial != null) {
        throw new AuthenticationException(initial.denial.code, initial.denial.message);
      }
      Prepared preparation = Objects.requireNonNull(initial.prepared);
      var original = preparation.stored;
      if ("PREPARED".equals(original.status)) {
        var spec =
            new AccountControlUiSigningSpec(
                original.operationId,
                original.requestId,
                original.jti,
                preparation.source,
                original.issuedAt,
                original.expiresAt);
        if (!Arrays.equals(original.claims, AccountControlUiAuthority.canonical(spec.claims()))) {
          throw denied();
        }
        signers.sign(
            preparation.signer,
            spec,
            (tokenHash, credential) -> {
              String compact = new String(credential, StandardCharsets.US_ASCII);
              var signed = actors.verifySigned(compact);
              actors.requireSignerIdentity(original, signed);
              if (!Arrays.equals(
                  original.claims, AccountControlUiAuthority.canonical(signed.claims.claims()))) {
                throw denied();
              }
              byte[] binding = envelopeBinding(original, tokenHash);
              byte[] encrypted = cryptography.seal(credential, binding, original.recoveryExpiry);
              transaction.execute(
                  ignored -> {
                    var stillCurrent = current(original, environment);
                    operations.bindCandidate(
                        stillCurrent.stored, tokenHash, encrypted, binding, stillCurrent.signer);
                    return null;
                  });
            });
      }
      var candidate = transaction.execute(ignored -> current(original, environment).stored);
      if (candidate == null) {
        throw denied();
      }
      AccountControlUiIssuanceRepository.Committed committed;
      if ("CANDIDATE".equals(candidate.status)) {
        var pending =
            registry.registerPending(
                candidate.tokenHash, candidate.pendingRegistry, candidate.expiryMillis());
        committed =
            transaction.execute(
                ignored -> {
                  var observed = current(candidate, environment);
                  return operations.commit(observed.stored, pending);
                });
      } else if ("COMMITTED".equals(candidate.status)) {
        committed =
            transaction.execute(
                ignored -> {
                  current(candidate, environment);
                  return operations.requireCommitted(candidate);
                });
      } else {
        throw denied();
      }
      if (committed == null) {
        throw denied();
      }
      registry.activate(committed);
      var envelope =
          transaction.execute(
              ignored -> {
                current(committed.stored, environment);
                return operations.envelope(committed.stored);
              });
      if (envelope == null
          || !Arrays.equals(
              envelope.binding(), envelopeBinding(committed.stored, committed.tokenHash()))) {
        throw new RecoveryLost(committed.stored);
      }
      byte[] credential;
      try {
        credential =
            cryptography.open(
                envelope.encrypted(), envelope.binding(), committed.stored.recoveryExpiry);
      } catch (AccountResponseEnvelopeCryptography.IntegrityException
          | AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException permanent) {
        throw new RecoveryLost(committed.stored);
      }
      try {
        if (!committed.tokenHash().equals(AccountControlUiIssuanceRepository.hash(credential))) {
          throw new RecoveryLost(committed.stored);
        }
        var actor =
            actors.authenticate(
                new String(credential, StandardCharsets.US_ASCII), request.tenantId, environment);
        if (!actor.accountId().equals(committed.stored.accountId)
            || !actor.issuanceOperationId().equals(committed.stored.operationId)) {
          throw denied();
        }
        return new IssuedCredential(
            credential, committed.stored.expiresAt, committed.stored.recoveryExpiry);
      } finally {
        Arrays.fill(credential, (byte) 0);
      }
    } catch (RecoveryLost permanent) {
      // Caller conflicts and unavailable SQL/custody/Coordination never enter this path.
      // Persist denial first; ambiguous/missing Redis evidence leaves REVOKING, never success.
      var failed =
          transaction.execute(ignored -> operations.beginRecoveryFailure(permanent.original));
      if (failed != null && "REVOKING".equals(failed.status)) {
        var receipt = registry.revoke(failed);
        transaction.execute(
            ignored -> {
              operations.finishRecoveryFailure(failed, receipt);
              return null;
            });
      }
      throw denied();
    } finally {
      Arrays.fill(exactRequest, (byte) 0);
    }
  }

  private void requireRequest(
      AccountControlUiIssuanceRepository.Stored stored,
      Request request,
      String caller,
      byte[] exactRequest) {
    if (!stored.requestId.equals(request.requestId)
        || !stored.tenantId.equals(request.tenantId)
        || !stored.caller.equals(caller)
        || !stored.callerContextId.equals(request.callerContextId)
        || !stored.requestDigest.equals(
            cryptography.requestDigest(stored.requestMacKeyId, exactRequest))) {
      throw new IllegalArgumentException("Control-ui issuance idempotency conflict");
    }
  }

  private Prepared current(
      AccountControlUiIssuanceRepository.Stored original, CapturedEnvironmentBoundary environment) {
    if (!clock.instant().isBefore(original.recoveryExpiry)
        || java.util.List.of("FAILED", "REVOKING", "REVOKED").contains(original.status)) {
      throw new RecoveryLost(original);
    }
    var source = authority.capture(original.accountId, original.tenantId, environment);
    fences.lockProducerSourcesNowait(source.sources());
    if (!Arrays.equals(source.evidence(), original.sources)) {
      throw denied();
    }
    var signer = signers.requireOriginal(original.signerReceipt);
    var stored = operations.findRequest(original.requestId);
    if (stored == null
        || !stored.operationId.equals(original.operationId)
        || !Arrays.equals(stored.sources, original.sources)
        || !Arrays.equals(stored.claims, original.claims)
        || !Arrays.equals(stored.signerReceipt, original.signerReceipt)) {
      throw denied();
    }
    return new Prepared(stored, source, signer);
  }

  private static byte[] envelopeBinding(
      AccountControlUiIssuanceRepository.Stored original, String tokenHash) {
    return AccountControlUiAuthority.canonical(
        Map.ofEntries(
            Map.entry("purpose", "account-control-ui-original-credential/v1"),
            Map.entry("operationId", original.operationId.toString()),
            Map.entry("requestId", original.requestId.toString()),
            Map.entry("accountId", original.accountId.toString()),
            Map.entry("tenantId", original.tenantId.toString()),
            Map.entry("callerWorkload", original.caller),
            Map.entry("callerContextId", original.callerContextId.toString()),
            Map.entry("requestMacKeyId", original.requestMacKeyId),
            Map.entry("requestDigest", original.requestDigest),
            Map.entry("tokenHash", tokenHash),
            Map.entry("claimsDigest", AccountControlUiIssuanceRepository.hash(original.claims)),
            Map.entry("bundleDigest", AccountControlUiIssuanceRepository.hash(original.bundle)),
            Map.entry(
                "signerReceiptDigest",
                AccountControlUiIssuanceRepository.hash(original.signerReceipt)),
            Map.entry("recoveryExpiresAt", original.recoveryExpiry.toString())));
  }

  private record PrimaryDenial(String code, String message) {
    private PrimaryDenial {
      Objects.requireNonNull(code);
      Objects.requireNonNull(message);
    }
  }

  private record InitialPreparation(Prepared prepared, PrimaryDenial denial) {
    private InitialPreparation {
      if ((prepared == null) == (denial == null)) {
        throw new IllegalArgumentException(
            "Exactly one initial creator preparation outcome required");
      }
    }
  }

  private record Prepared(
      AccountControlUiIssuanceRepository.Stored stored,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiSignerOwner.Capture signer) {}

  /**
   * Only permanent loss established against the original owner record, never dependency failure.
   */
  private static final class RecoveryLost extends RuntimeException {
    private final AccountControlUiIssuanceRepository.Stored original;

    private RecoveryLost(AccountControlUiIssuanceRepository.Stored original) {
      super("Original protected control-ui recovery permanently unavailable");
      this.original = original;
    }
  }

  public record Request(
      UUID requestId, String email, String secret, UUID tenantId, UUID callerContextId) {
    public Request {
      if (requestId == null
          || email == null
          || email.isBlank()
          || email.length() > 254
          || secret == null
          || secret.isEmpty()
          || secret.length() > 1024
          || tenantId == null
          || callerContextId == null) {
        throw new IllegalArgumentException("Exact bounded issuer request required");
      }
    }

    @Override
    public String toString() {
      return "AccountControlUiIssuanceService.Request[redacted]";
    }
  }

  public static final class IssuedCredential implements AutoCloseable {
    private final byte[] bytes;
    private final Instant expiresAt, recoveryExpiresAt;

    private IssuedCredential(byte[] bytes, Instant expiresAt, Instant recoveryExpiresAt) {
      this.bytes = bytes.clone();
      this.expiresAt = expiresAt;
      this.recoveryExpiresAt = recoveryExpiresAt;
    }

    public byte[] compactBytes() {
      return bytes.clone();
    }

    public Instant expiresAt() {
      return expiresAt;
    }

    public Instant recoveryExpiresAt() {
      return recoveryExpiresAt;
    }

    @Override
    public void close() {
      Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public String toString() {
      return "AccountControlUiIssuanceService.IssuedCredential[redacted]";
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Control-ui original issuance/current recovery unavailable");
  }
}
