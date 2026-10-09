package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Genuine creator actor/currentness and same-transaction original Draft ordering; unregistered. */
public final class AccountControlUiActorService {
  private static final AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy POLICY =
      new AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy(
          "account-internal-creator-draft",
          "control-ui",
          "control-ui",
          "firemud-account-service",
          "control-ui",
          ControlUiJwtProfileValidator.REQUIRED_CLAIMS,
          ControlUiJwtProfileValidator.OPTIONAL_CLAIMS,
          300,
          0,
          16384,
          claims -> ControlUiJwtProfileValidator.validateClaims(claims, 1));
  private final AccountControlUiIssuanceRepository operations;
  private final AccountControlUiAuthority authority;
  private final AccountControlUiSignerOwner signers;
  private final AccountControlUiCoordination registry;
  private final AccountJwtJwksTrustedSource publicSource;
  private final DraftAuthorizationFenceRepository fences;
  private final TransactionTemplate transaction;
  private final Clock clock;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected Draft fence repository is an internal Account owner transaction collaborator.")
  public AccountControlUiActorService(
      AccountControlUiIssuanceRepository operations,
      AccountControlUiAuthority authority,
      AccountControlUiSignerOwner signers,
      AccountControlUiCoordination registry,
      AccountJwtJwksTrustedSource publicSource,
      DraftAuthorizationFenceRepository fences,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.operations = Objects.requireNonNull(operations);
    this.authority = Objects.requireNonNull(authority);
    this.signers = Objects.requireNonNull(signers);
    this.registry = Objects.requireNonNull(registry);
    this.publicSource = Objects.requireNonNull(publicSource);
    this.fences = Objects.requireNonNull(fences);
    this.clock = Objects.requireNonNull(clock);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public AuthenticatedActor authenticate(
      String compactJwt, UUID selectedTenant, CapturedEnvironmentBoundary environment) {
    return withCurrent(
        compactJwt,
        selectedTenant,
        environment,
        current ->
            new AuthenticatedActor(
                current.stored.accountId,
                current.stored.tenantId,
                current.stored.operationId,
                current.stored.tokenHash));
  }

  /** Exact retained producer recovery; absence alone permits a fresh authenticated claim. */
  boolean readHeldOriginalDraft(DraftAuthorizationFenceBinding original) {
    outsideSql();
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              var retained = fences.readOriginalBinding(original.operationId());
              if (retained.isEmpty()) return false;
              if (!Arrays.equals(retained.get().canonicalBytes(), original.canonicalBytes()))
                throw io.grpc.Status.FAILED_PRECONDITION.asRuntimeException();
              var order = fences.read(original);
              if (order.ordering() != DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER
                  || order.reservedAt() == null
                  || order.orderedAt() == null
                  || !Arrays.equals(order.binding(), original.canonicalBytes())
                  || fences.readSettlement(original)
                      != DraftAuthorizationFenceRepository.Settlement.PENDING)
                throw io.grpc.Status.FAILED_PRECONDITION.asRuntimeException();
              return true;
            }));
  }

  /** No transport or World mutation: original Account reservation/order is the only effect. */
  public DraftAuthorizationFenceRepository.FenceSnapshot claimOriginalDraft(
      String compactJwt,
      DraftAuthorizationFenceBinding original,
      CapturedEnvironmentBoundary environment) {
    Objects.requireNonNull(original);
    return withCurrent(
        compactJwt,
        original.tenantId(),
        environment,
        current -> {
          if (!original.actorAccountId().equals(current.stored.accountId)
              || !exactSources(original.sources(), current.source.sources())) {
            throw denied();
          }
          // Never wait for a source-first writer while retaining captured owner rows.
          fences.lockProducerSourcesNowait(current.source.sources());
          fences.reserve(original);
          var order = fences.claimCommitOrder(original);
          if (fences.readSettlement(original)
              != DraftAuthorizationFenceRepository.Settlement.PENDING) throw denied();
          return order;
        });
  }

  <T> T withCurrent(
      String compactJwt,
      UUID selectedTenant,
      CapturedEnvironmentBoundary environment,
      Function<Current, T> action) {
    outsideSql();
    var signed = verifySigned(compactJwt);
    String tokenHash =
        AccountControlUiIssuanceRepository.hash(compactJwt.getBytes(StandardCharsets.US_ASCII));
    byte[] exactRegistry = registry.readActive(tokenHash);
    return transaction.execute(
        ignored -> {
          var stored = operations.findToken(tokenHash);
          if (stored == null
              || !selectedTenant.equals(stored.tenantId)
              || !"COMMITTED".equals(stored.status)
              || !Arrays.equals(stored.activeRegistry, exactRegistry)
              || !Arrays.equals(
                  stored.claims, AccountControlUiAuthority.canonical(signed.claims.claims()))) {
            throw denied();
          }
          requireSignerIdentity(stored, signed);
          signers.requireOriginal(stored.signerReceipt);
          var current = authority.capture(stored.accountId, selectedTenant, environment);
          if (!Arrays.equals(current.evidence(), stored.sources)) {
            throw denied();
          }
          var committed = operations.requireCommitted(stored);
          if (!committed.tokenHash().equals(tokenHash)) {
            throw denied();
          }
          return action.apply(new Current(stored, current));
        });
  }

  /** Production asymmetric/profile verification, also used before any candidate is persisted. */
  SignedObservation verifySigned(String compactJwt) {
    outsideSql();
    var pin = publicSource.sourceIdentity();
    var cache = new AccountPublicJwksCache(publicSource, pin, clock, Duration.ofSeconds(30));
    return new SignedObservation(
        new AccountAsymmetricJwtVerifier(cache, clock).verify(compactJwt, POLICY), pin);
  }

  void requireSignerIdentity(
      AccountControlUiIssuanceRepository.Stored stored, SignedObservation observation) {
    var signed = observation.claims;
    var receipt = AccountControlUiIssuanceRepository.object(stored.signerReceipt);
    var pin = observation.pin;
    if (!signed.keyId().equals(receipt.get("kid"))
        || !pin.environmentId().equals(receipt.get("environmentId"))
        || !pin.clusterId().equals(receipt.get("clusterId"))
        || !pin.clusterIncarnationUid().equals(receipt.get("clusterIncarnationUid"))
        || !pin.namespace().equals(receipt.get("namespace"))
        || !pin.namespaceUid().equals(receipt.get("namespaceUid"))
        || !pin.configMapUid().equals(receipt.get("publicConfigMapUid"))
        || !pin.bindingRevision().equals(receipt.get("apiConfigRevision"))
        || !stored.accountId.toString().equals(signed.claims().get("sub"))
        || !stored.accountId.toString().equals(signed.claims().get("accountId"))
        || !stored.jti.toString().equals(signed.claims().get("jti"))
        || !Map.of(stored.tenantId.toString(), List.of("tenantAdmin"))
            .equals(signed.claims().get("scopedRoles"))
        || signed.claims().containsKey("globalRoles")) {
      throw denied();
    }
  }

  private static boolean exactSources(
      List<DraftAuthorizationFenceBinding.SourceEvidence> first,
      List<DraftAuthorizationFenceBinding.SourceEvidence> second) {
    if (first.size() != second.size()) {
      return false;
    }
    for (int index = 0; index < first.size(); index++) {
      if (!Arrays.equals(first.get(index).canonicalBytes(), second.get(index).canonicalBytes())) {
        return false;
      }
    }
    return true;
  }

  record Current(
      AccountControlUiIssuanceRepository.Stored stored,
      AccountControlUiAuthority.Snapshot source) {}

  static final class SignedObservation {
    final AccountAsymmetricJwtVerifier.VerifiedClaims claims;
    final AccountPublicJwksCache.SourceIdentity pin;

    private SignedObservation(
        AccountAsymmetricJwtVerifier.VerifiedClaims claims,
        AccountPublicJwksCache.SourceIdentity pin) {
      this.claims = claims;
      this.pin = pin;
    }
  }

  /** No externally callable constructor; workload possession or a claimed UUID cannot create it. */
  public static final class AuthenticatedActor {
    private final UUID accountId, tenantId, issuanceOperationId;
    private final String tokenHash;

    private AuthenticatedActor(UUID accountId, UUID tenantId, UUID operationId, String tokenHash) {
      this.accountId = accountId;
      this.tenantId = tenantId;
      this.issuanceOperationId = operationId;
      this.tokenHash = tokenHash;
    }

    public UUID accountId() {
      return accountId;
    }

    public UUID tenantId() {
      return tenantId;
    }

    public UUID issuanceOperationId() {
      return issuanceOperationId;
    }

    public String tokenHash() {
      return tokenHash;
    }

    @Override
    public String toString() {
      return "AccountControlUiActorService.AuthenticatedActor[redacted]";
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Signature/registry reads must precede owner SQL locks");
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact current authenticated initial creator required");
  }
}
