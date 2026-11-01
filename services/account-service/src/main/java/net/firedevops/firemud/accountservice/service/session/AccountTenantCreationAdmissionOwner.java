package net.firedevops.firemud.accountservice.service.session;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AcceptedSignerKey;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRedisClient.QualifiedReceipt;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.PreparedAdmission;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account-owned initial tenant-creation admission orchestration. A returned value is
 * independently read-back data only; it is not permission to dispatch to Game Design.
 */
public final class AccountTenantCreationAdmissionOwner {
  private static final Pattern NAMESPACE =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private final String configuredNamespace;
  private final DSLContext dsl;
  private final AccountTenantCreationIntentRepository intents;
  private final AccountTenantCreationAdmissionRepository admissions;
  private final AccountControlUiIssuanceRepository issuances;
  private final AccountControlUiActorService actors;
  private final AccountRecentReauthenticationService reauthentication;
  private final AccountTenantCreationAdmissionRedisClient redis;
  private final AccountTenantCreationOwnerSourceCapture capture;
  private final AccountTenantCreationSourceOrderRepository orders;
  private final AccountTenantCreationAuthorizationPreparation preparation;
  private final TransactionTemplate readTransaction;

  AccountTenantCreationAdmissionOwner(
      String configuredNamespace,
      DSLContext dsl,
      AccountTenantCreationIntentRepository intents,
      AccountTenantCreationAdmissionRepository admissions,
      AccountControlUiIssuanceRepository issuances,
      AccountControlUiActorService actors,
      AccountRecentReauthenticationService reauthentication,
      AccountTenantCreationAdmissionRedisClient redis,
      AccountTenantCreationOwnerSourceCapture capture,
      AccountTenantCreationSourceOrderRepository orders,
      AccountTenantCreationAuthorizationPreparation preparation,
      PlatformTransactionManager transactionManager) {
    if (configuredNamespace == null || !NAMESPACE.matcher(configuredNamespace).matches()) {
      throw new IllegalArgumentException("One canonical Account tenant-creation namespace is required");
    }
    this.configuredNamespace = configuredNamespace;
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.admissions = Objects.requireNonNull(admissions);
    this.issuances = Objects.requireNonNull(issuances);
    this.actors = Objects.requireNonNull(actors);
    this.reauthentication = Objects.requireNonNull(reauthentication);
    this.redis = Objects.requireNonNull(redis);
    this.capture = Objects.requireNonNull(capture);
    this.orders = Objects.requireNonNull(orders);
    this.preparation = Objects.requireNonNull(preparation);
    readTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readTransaction.setReadOnly(false);
  }

  /**
   * Starts or exact-retries one authenticated Account-scoped operation. This method accepts the
   * original request and the genuine typed reauthentication evidence, but neither input grants
   * authority by itself. The namespace is fixed by this owner instance and is never selected by a
   * caller.
   */
  public PreparedResult start(
      String exactCurrentAccountJwt,
      AccountTenantCreationIntentRepository.Request originalRequest,
      AccountRecentReauthenticationEvidence recentReauthentication,
      CapturedEnvironmentBoundary capturedEnvironment) {
    outsideSql();
    Objects.requireNonNull(originalRequest, "Exact original tenant-creation request is required");
    Objects.requireNonNull(
        recentReauthentication, "Actual Account recent-reauthentication evidence is required");
    Objects.requireNonNull(capturedEnvironment, "Server-captured Account environment is required");
    if (exactCurrentAccountJwt == null
        || exactCurrentAccountJwt.isBlank()
        || !configuredNamespace.equals(originalRequest.targetNamespace())
        || !recentReauthentication
            .evidenceId()
            .equals(originalRequest.recentReauthenticationEvidenceId())) {
      throw denied();
    }

    // This actor entry point delegates to withCurrentAccountSources: it verifies the exact signed
    // Account-scoped JWT, issuance, active registry bytes, current Account sources, and producer
    // source locks before running the original-intent callback in that same owner transaction.
    Frozen initial =
        actors.withCurrentAccount(
            exactCurrentAccountJwt,
            actor -> prepareOriginal(actor, originalRequest, recentReauthentication));

    // This is an independent committed readback. Redis is not contacted until both immutable SQL
    // rows, the original issuance bytes, and their binding have survived the first transaction.
    Frozen frozen =
        Objects.requireNonNull(
            readTransaction.execute(
                ignored -> {
                  Persisted persisted = readPersisted(initial.binding, false);
                  requireSameFrozen(initial, persisted, true);
                  if (initial.admitted != null && persisted.admitted == null) throw denied();
                  if (persisted.admitted != null && initial.admitted != null) {
                    requireSameAdmitted(initial.admitted, persisted.admitted);
                  }
                  return initial.withPersisted(persisted);
                }));

    String originalKid = originalKid(frozen.issuance.signerReceipt);
    AcceptedSignerKey accepted = actors.readAcceptedSignerKey(originalKid);
    AccountControlUiActorService.requireAcceptedSignerKey(frozen.issuance, accepted);

    return redis.withInitialReceipt(
        frozen.binding,
        frozen.issuance.activeRegistry.clone(),
        qualified -> {
          qualified.requireCurrent();
          Admitted admitted =
              actors.withCurrentAccount(
                  exactCurrentAccountJwt,
                  actor -> admitOriginal(actor, frozen, qualified, accepted, capturedEnvironment));

          // This existing owner performs a second actual current-source capture and an independent
          // source-holder readback after SQL commit, while the Redis qualification is still held.
          AccountTenantCreationAuthorizationPreparation.Prepared sourceReadback =
              preparation.prepare(
                  frozen.binding.creationOperationId(), capturedEnvironment, frozen.reauthentication);
          qualified.requireCurrent();

          Persisted committed =
              Objects.requireNonNull(
                  readTransaction.execute(
                      ignored -> readPersisted(frozen.binding, true)));
          requireSameFrozen(frozen, committed, false);
          if (committed.admitted == null) throw denied();
          requireSameAdmitted(admitted, committed.admitted);
          return new PreparedResult(committed.prepared.binding(), sourceReadback);
        });
  }

  /**
   * Recovers only an already committed exact SQL admission and an already-existing qualified
   * Redis receipt. It accepts no JWT, does not reauthenticate a revoked token, and has no initial
   * receipt, admission-write, alternate-Redis, or negative-outcome branch.
   */
  public PreparedResult recover(
      UUID creationOperationId,
      CapturedEnvironmentBoundary capturedEnvironment,
      AccountRecentReauthenticationEvidence recentReauthentication) {
    outsideSql();
    requireNonNil(creationOperationId, "original creation operation");
    Objects.requireNonNull(capturedEnvironment, "Server-captured Account environment is required");
    Objects.requireNonNull(
        recentReauthentication, "Actual Account recent-reauthentication evidence is required");

    Persisted original =
        Objects.requireNonNull(
            readTransaction.execute(
                ignored -> {
                  Persisted stored = readPersisted(null, true, creationOperationId);
                  requireRecoveryEvidenceIdentity(stored.intent, recentReauthentication);
                  return stored;
                }));
    if (original.admitted == null) throw denied();
    if (!configuredNamespace.equals(original.intent.targetNamespace())) throw denied();

    // The persisted SQL admission is established above, before this existing-only Redis call.
    // A missing/expired/uncertain receipt therefore cannot take the initial creation arm.
    return redis.withExistingReceipt(
        original.prepared.binding(),
        qualified -> {
          qualified.requireCurrent();
          var currentSources =
              preparation.prepare(
                  creationOperationId, capturedEnvironment, recentReauthentication);
          qualified.requireCurrent();

          Persisted committed =
              Objects.requireNonNull(
                  readTransaction.execute(
                      ignored -> readPersisted(original.prepared.binding(), true)));
          requireSamePersisted(original, committed);
          if (committed.admitted == null) throw denied();
          return new PreparedResult(committed.prepared.binding(), currentSources);
        });
  }

  private Frozen prepareOriginal(
      AccountControlUiActorService.AccountAuthenticatedActor actor,
      AccountTenantCreationIntentRepository.Request request,
      AccountRecentReauthenticationEvidence presentedReauthentication) {
    if (!actor.accountId().equals(request.initiatingAccountId())) throw denied();

    OriginalIntent intent = intents.claim(request);
    requireRequest(intent, request);
    AccountRecentReauthenticationEvidence currentReauthentication =
        reauthentication.validatePendingCreationLocked(
            presentedReauthentication,
            actor.accountId(),
            AccountRecentReauthenticationPolicy.PURPOSE,
            request.creationOperationId());
    requireRecoveryEvidenceIdentity(intent, currentReauthentication);

    AccountControlUiIssuanceRepository.Stored issuance = issuances.findToken(actor.tokenHash());
    requireCurrentIssuance(issuance, actor);

    Optional<PreparedAdmission> existing = admissions.readPrepared(request.creationOperationId());
    AccountTenantCreationAdmissionBinding binding;
    if (existing.isPresent()) {
      binding = existing.orElseThrow().binding();
      requireBindingMatchesOriginal(binding, intent, currentReauthentication, actor, issuance);
    } else {
      binding = freeze(intent, actor, issuance, currentReauthentication);
    }
    PreparedAdmission prepared = admissions.prepareLocked(intent, actor, binding);
    requireSameBinding(binding, prepared.binding());
    Admitted admitted = admissions.readAdmitted(request.creationOperationId()).orElse(null);
    if (admitted != null) requireSameBinding(binding, admitted.binding());
    return new Frozen(intent, prepared, binding, issuance, currentReauthentication, admitted);
  }

  private Admitted admitOriginal(
      AccountControlUiActorService.AccountAuthenticatedActor actor,
      Frozen frozen,
      QualifiedReceipt qualified,
      AcceptedSignerKey accepted,
      CapturedEnvironmentBoundary environment) {
    if (!actor.accountId().equals(frozen.binding.actorAccountId())
        || !actor.issuanceOperationId().equals(frozen.binding.issuanceOperationId())
        || !actor.tokenHash().equals(frozen.binding.tokenHash())) {
      throw denied();
    }
    AccountControlUiIssuanceRepository.Stored current = issuances.findToken(actor.tokenHash());
    requireCurrentIssuance(current, actor);
    if (!sameIssuance(frozen.issuance, current, true)) throw denied();
    AccountControlUiActorService.requireAcceptedSignerKey(current, accepted);

    // withCurrentAccountSources has already captured and locked Account sources before entering
    // this callback. In particular, authority.captureAccount precedes admitLocked's issuance-row
    // trigger lock, preserving Account-before-issuer/issuance order.
    Admitted admitted = admissions.admitLocked(frozen.prepared, qualified);
    requireSameBinding(frozen.binding, admitted.binding());

    AccountTenantCreationOwnerSourceCapture.Captured actual =
        capture.captureLocked(frozen.intent, admitted, environment, frozen.reauthentication);
    if (!Arrays.equals(actual.originalClaims(), current.claims)
        || !Arrays.equals(actual.originalSignerReceipt(), current.signerReceipt)
        || !actual.issuanceOperationId().equals(current.operationId)
        || !actual.tokenJti().equals(current.jti)
        || !actual.tokenHash().equals(current.tokenHash)) {
      throw denied();
    }

    List<SourceEvidence> sources = actual.sources();
    requireExactSourceVector(sources);
    AccountTenantCreationSourceOrderBinding holder =
        orders.acquireLocked(frozen.intent, admitted, sources);
    requireSourceHolder(holder, frozen.intent, admitted);
    if (orders.readSettlement(frozen.binding.creationOperationId()).isPresent()) throw denied();

    // The external qualification must still be live after owner capture and source-order work.
    // The surrounding Redis client retains it until this SQL transaction has committed.
    qualified.requireCurrent();
    return admitted;
  }

  private AccountTenantCreationAdmissionBinding freeze(
      OriginalIntent intent,
      AccountControlUiActorService.AccountAuthenticatedActor actor,
      AccountControlUiIssuanceRepository.Stored issuance,
      AccountRecentReauthenticationEvidence recent) {
    Record clock = dsl.fetchOne("SELECT clock_timestamp() AS prepared_at");
    if (clock == null) throw denied();
    OffsetDateTime observed = clock.get("prepared_at", OffsetDateTime.class);
    if (observed == null) throw denied();
    long preparedAt = observed.toInstant().toEpochMilli();
    long tokenExpiresAt = issuance.expiresAt.toEpochMilli();
    long operationDeadline;
    long cleanupDeadline;
    try {
      operationDeadline =
          Math.min(
              tokenExpiresAt,
              Math.addExact(preparedAt, AccountTenantCreationAdmissionBinding.MAX_OPERATION_MILLIS));
      cleanupDeadline =
          Math.addExact(
              operationDeadline,
              AccountTenantCreationAdmissionBinding.RECEIPT_RETENTION_MILLIS);
    } catch (ArithmeticException overflow) {
      throw denied();
    }
    return new AccountTenantCreationAdmissionBinding(
        intent.requestId(),
        intent.creationOperationId(),
        issuance.operationId,
        actor.accountId(),
        configuredNamespace,
        intent.gameDesignCreationRequestId(),
        intent.gameDesignRequestDigest(),
        intent.requestDigest(),
        AccountControlUiIssuanceRepository.hash(issuance.activeRegistry),
        recent.evidenceId(),
        issuance.tokenHash,
        issuance.expiryMillis(),
        tokenExpiresAt,
        preparedAt,
        operationDeadline,
        cleanupDeadline);
  }

  private Persisted readPersisted(
      AccountTenantCreationAdmissionBinding expected, boolean requireAdmitted) {
    return readPersisted(expected, requireAdmitted, expected.creationOperationId());
  }

  private Persisted readPersisted(
      AccountTenantCreationAdmissionBinding expected,
      boolean requireAdmitted,
      UUID creationOperationId) {
    requireOwnerTransaction();
    OriginalIntent intent =
        intents
            .readOriginal(creationOperationId)
            .orElseThrow(AccountTenantCreationAdmissionOwner::denied);
    PreparedAdmission prepared =
        admissions
            .readPrepared(creationOperationId)
            .orElseThrow(AccountTenantCreationAdmissionOwner::denied);
    AccountTenantCreationAdmissionBinding binding = prepared.binding();
    requireBindingMatchesIntent(binding, intent);
    if (expected != null) requireSameBinding(expected, binding);

    AccountControlUiIssuanceRepository.Stored issuance = issuances.findToken(binding.tokenHash());
    requireStoredBinding(binding, intent, issuance, false);
    Admitted admitted = admissions.readAdmitted(creationOperationId).orElse(null);
    if (requireAdmitted && admitted == null) throw denied();
    if (admitted != null) requireSameBinding(binding, admitted.binding());
    return new Persisted(intent, prepared, binding, issuance, admitted);
  }

  private void requireCurrentIssuance(
      AccountControlUiIssuanceRepository.Stored stored,
      AccountControlUiActorService.AccountAuthenticatedActor actor) {
    if (stored == null
        || !stored.accountId.equals(actor.accountId())
        || !stored.operationId.equals(actor.issuanceOperationId())
        || !stored.tokenHash.equals(actor.tokenHash())
        || stored.scopeKind() != AccountControlUiAuthority.ScopeKind.ACCOUNT
        || stored.optionalTenantId().isPresent()
        || !"COMMITTED".equals(stored.status)
        || stored.activeRegistry == null
        || stored.activeRegistry.length == 0
        || stored.claims == null
        || stored.signerReceipt == null) {
      throw denied();
    }
    stored.expiryMillis();
  }

  private void requireStoredBinding(
      AccountTenantCreationAdmissionBinding binding,
      OriginalIntent intent,
      AccountControlUiIssuanceRepository.Stored stored,
      boolean requireCommitted) {
    if (stored == null
        || !stored.operationId.equals(binding.issuanceOperationId())
        || !stored.accountId.equals(binding.actorAccountId())
        || !stored.tokenHash.equals(binding.tokenHash())
        || stored.scopeKind() != AccountControlUiAuthority.ScopeKind.ACCOUNT
        || stored.optionalTenantId().isPresent()
        || !List.of("COMMITTED", "REVOKING", "REVOKED").contains(stored.status)
        || requireCommitted && !"COMMITTED".equals(stored.status)
        || stored.activeRegistry == null
        || !AccountControlUiIssuanceRepository.hash(stored.activeRegistry)
            .equals(binding.activeRegistryDigest())
        || stored.expiryMillis() != binding.tokenCleanupDeadlineEpochMillis()
        || stored.expiresAt.toEpochMilli() != binding.tokenExpiresAtEpochMillis()
        || !intent.initiatingAccountId().equals(stored.accountId)) {
      throw denied();
    }
  }

  private void requireBindingMatchesOriginal(
      AccountTenantCreationAdmissionBinding binding,
      OriginalIntent intent,
      AccountRecentReauthenticationEvidence recent,
      AccountControlUiActorService.AccountAuthenticatedActor actor,
      AccountControlUiIssuanceRepository.Stored issuance) {
    requireBindingMatchesIntent(binding, intent);
    if (!binding.actorAccountId().equals(actor.accountId())
        || !binding.issuanceOperationId().equals(actor.issuanceOperationId())
        || !binding.tokenHash().equals(actor.tokenHash())
        || !binding.reauthenticationEvidenceId().equals(recent.evidenceId())) {
      throw denied();
    }
    requireStoredBinding(binding, intent, issuance, true);
  }

  private static void requireBindingMatchesIntent(
      AccountTenantCreationAdmissionBinding binding, OriginalIntent intent) {
    if (!binding.creationOperationId().equals(intent.creationOperationId())
        || !binding.intentRequestId().equals(intent.requestId())
        || !binding.actorAccountId().equals(intent.initiatingAccountId())
        || !binding.targetNamespace().equals(intent.targetNamespace())
        || !binding.gameDesignCreationRequestId().equals(intent.gameDesignCreationRequestId())
        || !binding.gameDesignRequestDigest().equals(intent.gameDesignRequestDigest())
        || !binding.intentDigest().equals(intent.requestDigest())
        || !binding.reauthenticationEvidenceId().equals(intent.recentReauthenticationEvidenceId())) {
      throw denied();
    }
  }

  private static void requireRequest(OriginalIntent intent, AccountTenantCreationIntentRepository.Request request) {
    AccountTenantCreationIntentRepository.Request storedRequest =
        new AccountTenantCreationIntentRepository.Request(
            intent.requestId(),
            intent.creationOperationId(),
            intent.gameDesignCreationRequestId(),
            intent.initiatingAccountId(),
            intent.targetNamespace(),
            intent.sourceGameTenantKey(),
            intent.name(),
            intent.description(),
            intent.recentReauthenticationEvidenceId(),
            intent.creatorPartyId(),
            intent.consentOperationId(),
            intent.consentStatementVersion(),
            intent.affirmativeConsent());
    if (!storedRequest.equals(request)) throw denied();
  }

  private static void requireRecoveryEvidenceIdentity(
      OriginalIntent intent, AccountRecentReauthenticationEvidence recent) {
    if (!recent.evidenceId().equals(intent.recentReauthenticationEvidenceId())
        || !recent.accountId().equals(intent.initiatingAccountId())
        || !recent.creationOperationId().equals(intent.creationOperationId())
        || !recent.purpose().equals(AccountRecentReauthenticationPolicy.PURPOSE)) {
      throw denied();
    }
  }

  private static void requireSameFrozen(
      Frozen expected, Persisted actual, boolean requireSameStatus) {
    if (!sameIntent(expected.intent, actual.intent)
        || !Arrays.equals(
            expected.prepared.binding().canonicalReceipt(), actual.prepared.binding().canonicalReceipt())
        || !sameIssuance(expected.issuance, actual.issuance, requireSameStatus)) {
      throw denied();
    }
  }

  private static void requireSamePersisted(Persisted expected, Persisted actual) {
    if (!sameIntent(expected.intent, actual.intent)
        || !Arrays.equals(
            expected.prepared.binding().canonicalReceipt(), actual.prepared.binding().canonicalReceipt())
        || !sameIssuance(expected.issuance, actual.issuance, false)
        || expected.admitted == null
        || actual.admitted == null) {
      throw denied();
    }
    requireSameAdmitted(expected.admitted, actual.admitted);
  }

  private static boolean sameIntent(OriginalIntent first, OriginalIntent second) {
    return first.requestId().equals(second.requestId())
        && first.creationOperationId().equals(second.creationOperationId())
        && first.gameDesignCreationRequestId().equals(second.gameDesignCreationRequestId())
        && first.initiatingAccountId().equals(second.initiatingAccountId())
        && first.targetNamespace().equals(second.targetNamespace())
        && first.sourceGameTenantKey().equals(second.sourceGameTenantKey())
        && first.name().equals(second.name())
        && Objects.equals(first.description(), second.description())
        && first.recentReauthenticationEvidenceId().equals(second.recentReauthenticationEvidenceId())
        && first.creatorPartyId().equals(second.creatorPartyId())
        && first.consentOperationId().equals(second.consentOperationId())
        && first.consentStatementVersion() == second.consentStatementVersion()
        && first.affirmativeConsent() == second.affirmativeConsent()
        && first.gameDesignRequestDigest().equals(second.gameDesignRequestDigest())
        && Arrays.equals(first.requestPayload(), second.requestPayload())
        && first.requestDigest().equals(second.requestDigest())
        && first.createdAt().equals(second.createdAt());
  }

  private static boolean sameIssuance(
      AccountControlUiIssuanceRepository.Stored first,
      AccountControlUiIssuanceRepository.Stored second,
      boolean requireSameStatus) {
    return second != null
        && first.requestId.equals(second.requestId)
        && first.operationId.equals(second.operationId)
        && first.jti.equals(second.jti)
        && first.accountId.equals(second.accountId)
        && first.scopeKind() == second.scopeKind()
        && first.optionalTenantId().equals(second.optionalTenantId())
        && first.callerContextId.equals(second.callerContextId)
        && first.caller.equals(second.caller)
        && first.requestMacKeyId.equals(second.requestMacKeyId)
        && first.requestDigest.equals(second.requestDigest)
        && (!requireSameStatus || first.status.equals(second.status))
        && Objects.equals(first.tokenHash, second.tokenHash)
        && Arrays.equals(first.claims, second.claims)
        && Arrays.equals(first.sources, second.sources)
        && Arrays.equals(first.bundle, second.bundle)
        && Arrays.equals(first.signerReceipt, second.signerReceipt)
        && Arrays.equals(first.pendingRegistry, second.pendingRegistry)
        && Arrays.equals(first.activeRegistry, second.activeRegistry)
        && first.issuedAt.equals(second.issuedAt)
        && first.expiresAt.equals(second.expiresAt)
        && first.recoveryExpiry.equals(second.recoveryExpiry)
        && Objects.equals(first.registryCleanupDeadlineEpochMillis, second.registryCleanupDeadlineEpochMillis);
  }

  private static void requireSameAdmitted(Admitted expected, Admitted actual) {
    if (!Arrays.equals(expected.binding().canonicalReceipt(), actual.binding().canonicalReceipt())
        || !Arrays.equals(expected.receiptPayload(), actual.receiptPayload())
        || !expected.receiptDigest().equals(actual.receiptDigest())
        || expected.localAofCount() != actual.localAofCount()
        || expected.replicaAofCount() != actual.replicaAofCount()
        || !expected.protectedBindingId().equals(actual.protectedBindingId())
        || expected.qualificationFence() != actual.qualificationFence()
        || !expected.admittedAt().equals(actual.admittedAt())) {
      throw denied();
    }
  }

  private static void requireSameBinding(
      AccountTenantCreationAdmissionBinding expected,
      AccountTenantCreationAdmissionBinding actual) {
    if (actual == null
        || !Arrays.equals(expected.canonicalReceipt(), actual.canonicalReceipt())) {
      throw denied();
    }
  }

  private static void requireSourceHolder(
      AccountTenantCreationSourceOrderBinding holder, OriginalIntent intent, Admitted admitted) {
    if (holder == null
        || !Arrays.equals(holder.intentPayload(), intent.requestPayload())
        || !holder.intentDigest().equals(intent.requestDigest())
        || !Arrays.equals(holder.admissionPayload(), admitted.binding().canonicalReceipt())
        || !holder.admissionDigest().equals(admitted.receiptDigest())
        || !holder.creationOperationId().equals(intent.creationOperationId())) {
      throw denied();
    }
    byte[] bytes = holder.canonicalBytes();
    if (bytes.length == 0
        || !holder.authorizationDigest().equals(DraftAuthorizationFenceBinding.digest(bytes))) {
      throw denied();
    }
  }

  private static void requireExactSourceVector(List<SourceEvidence> sources) {
    if (sources == null || sources.size() != AccountTenantCreationSourceOrderBinding.SOURCE_COUNT) {
      throw denied();
    }
    List<SourceEvidence> sorted =
        sources.stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
    if (!sources.equals(sorted)
        || sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()
        || sources.stream()
            .anyMatch(
                source ->
                    source.kind() == SourceKind.TENANT || source.kind() == SourceKind.MEMBERSHIP)) {
      throw denied();
    }
  }

  private static String originalKid(byte[] signerReceipt) {
    Object kid = AccountControlUiIssuanceRepository.object(signerReceipt).get("kid");
    if (!(kid instanceof String text) || text.isBlank()) throw denied();
    return text;
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw denied();
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Tenant-creation Redis and signer work must start outside SQL");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact current original Account tenant-creation admission is unavailable");
  }

  private record Frozen(
      OriginalIntent intent,
      PreparedAdmission prepared,
      AccountTenantCreationAdmissionBinding binding,
      AccountControlUiIssuanceRepository.Stored issuance,
      AccountRecentReauthenticationEvidence reauthentication,
      Admitted admitted) {
    private Frozen withPersisted(Persisted persisted) {
      return new Frozen(
          persisted.intent,
          persisted.prepared,
          persisted.binding,
          persisted.issuance,
          reauthentication,
          persisted.admitted);
    }
  }

  private record Persisted(
      OriginalIntent intent,
      PreparedAdmission prepared,
      AccountTenantCreationAdmissionBinding binding,
      AccountControlUiIssuanceRepository.Stored issuance,
      Admitted admitted) {}

  /** Independent data receipt only; it contains neither a JWT nor active registry bytes. */
  public static final class PreparedResult {
    private final AccountTenantCreationAdmissionBinding admission;
    private final byte[] sourceOrderBinding;
    private final String authorizationDigest;

    private PreparedResult(
        AccountTenantCreationAdmissionBinding admission,
        AccountTenantCreationAuthorizationPreparation.Prepared sourceReadback) {
      this.admission = Objects.requireNonNull(admission);
      this.sourceOrderBinding = sourceReadback.binding();
      this.authorizationDigest = sourceReadback.digest();
    }

    public AccountTenantCreationAdmissionBinding admission() {
      return admission;
    }

    public byte[] sourceOrderBinding() {
      return sourceOrderBinding.clone();
    }

    public String authorizationDigest() {
      return authorizationDigest;
    }

    @Override
    public String toString() {
      return "AccountTenantCreationAdmissionOwner.PreparedResult[data-only, redacted]";
    }
  }
}
