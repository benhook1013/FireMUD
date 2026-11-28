package net.firedevops.firemud.accountservice.service;

import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.at;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.conflict;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.number;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.requireCurrentLifetime;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.unavailable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Deadline;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountCapacityOriginDecisionRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateExpiredException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.CapacityException;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Owner;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.PlayerContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered strict Account capacity authority. Caller identity must come from the bounded mTLS
 * receiver. No caller can register a bundle, initialize usage, attest an execution result, or cause
 * a final Game Session/World activation here. World capacity remains held without its current
 * lifecycle/evaluation producer. The scalar shape is deliberately restricted to an exact new player
 * binding; non-expanding recovery stays held until Account has authoritative prior-shape evidence.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Private Account owner collaborators.")
public final class AccountTenantCapacityOwner {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
  private static final int MAX_SERIALIZATION_ATTEMPTS = 4;
  private static final long INITIAL_RETRY_DELAY_MILLIS = 5;
  private static final String DEFERRED_EVALUATION_EXPIRED_SQLSTATE = "P1040";

  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final AccountCanonicalRuntimeMembershipSnapshotRepository memberships;
  private final AccountGameplayDelegationIssuanceRepository issuances;
  private final AccountConnectScopeRepository scopes;
  private final AccountTenantCapacityRepository capacity;
  private final AccountCapacityOriginDecisionRepository originDecisions;
  private final TransactionTemplate transaction;
  private final TransactionTemplate denialReadTransaction;
  private final Clock clock;
  private final String namespace;

  public AccountTenantCapacityOwner(
      DSLContext dsl,
      AccountRepository accounts,
      AccountCanonicalRuntimeMembershipSnapshotRepository memberships,
      AccountGameplayDelegationIssuanceRepository issuances,
      AccountConnectScopeRepository scopes,
      PlatformTransactionManager transactions,
      Clock clock,
      String namespace) {
    this.dsl = Objects.requireNonNull(dsl);
    this.accounts = Objects.requireNonNull(accounts);
    this.memberships = Objects.requireNonNull(memberships);
    this.issuances = Objects.requireNonNull(issuances);
    this.scopes = Objects.requireNonNull(scopes);
    this.capacity = new AccountTenantCapacityRepository(dsl);
    this.originDecisions = new AccountCapacityOriginDecisionRepository(dsl);
    this.clock = Objects.requireNonNull(clock);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    denialReadTransaction = new TransactionTemplate(transactions);
    denialReadTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    denialReadTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    denialReadTransaction.setReadOnly(true);
    denialReadTransaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
  }

  /** Reads an exact historical admission refusal without mutating the original attempt. */
  public Refusal readRetainedAdmissionDenial(Request request, String authenticatedCaller) {
    requireCaller(request, authenticatedCaller);
    if (!TenantCapacityAdmission.expectedWorkload(namespace, Owner.GAME_SESSION)
        .equals(authenticatedCaller)) throw new CapacityException(Failure.PERMISSION_DENIED);
    request.requireStructurallyValid();
    requireSupportedShape(request);
    if (request.capacityRearmFence() != 1 || !request.evidence().permitsCreation())
      throw unavailable();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || Context.current().isCancelled()) throw unavailable();
    try {
      Refusal denial =
          denialReadTransaction.execute(status -> {
            if (Context.current().isCancelled()) throw unavailable();
            dsl.connection(connection -> {
              if (connection.getAutoCommit() || !connection.isReadOnly()) throw unavailable();
            });
            return originDecisions.readRetainedAdmissionDenial(request);
          });
      if (denial == null) throw unavailable();
      return denial;
    } catch (CapacityException failure) {
      // A different original or decision is not settlement evidence on this read.
      if (failure.code() == Failure.IDEMPOTENCY_CONFLICT) throw unavailable();
      throw failure;
    } catch (IllegalArgumentException invalidEvidence) {
      throw unavailable();
    }
  }

  public Result commit(Request request, String authenticatedCaller) {
    requireCaller(request, authenticatedCaller);
    request.requireStructurallyValid();
    if (!(request.context() instanceof PlayerContext)) throw unavailable();
    CommitDecision decision =
        commitWithFreshSnapshot(() -> {
          // Replay precedes current sources and quota. A lost refusal response retains its
          // original decision even if capacity becomes available before the caller retries.
          Record existing = capacity.findForReplay(request.identity());
          if (existing != null) {
            return new CommitDecision(capacity.exact(request, existing), null);
          }
          if (request.capacityRearmFence() != 1) throw conflict();
          Refusal retainedDenial = originDecisions.retainedAdmissionDenial(request);
          if (retainedDenial != null) return new CommitDecision(null, retainedDenial);
          originDecisions.requireFreshAttemptBinding(request);
          lockPlayerAccount(request);
          var evidence = request.evidence();
          Instant retainedExpiry = capacity.requireRetained(evidence);
          Instant now = clock.instant();
          if (now.isBefore(evidence.evaluatedAt()))
            throw new CapacityException(Failure.CAPACITY_ADMISSION_STALE);
          if (!evidence.permitsCreation()) requireCurrentLifetime(evidence, now);
          var parsed = currentMembership(request);
          requireSupportedShape(request);
          EvaluationLifetime lifetime = observeLifetime(evidence, retainedExpiry);
          // An already elapsed original can only commit a denial. It binds the
          // exact retained evaluation and current membership without renewing
          // the historical delegation or connect scope.
          if (lifetime.expired() && evidence.permitsCreation()) return refuseExpired(request);
          boolean delegationElapsed = false;
          try {
            currentDelegation(request, lifetime.accountNow());
          } catch (CommittedCandidateExpiredException expired) {
            delegationElapsed = true;
          }
          currentScope(request, parsed, lifetime.accountNow());
          lifetime = observeLifetime(evidence, retainedExpiry);
          if (delegationElapsed && (!evidence.permitsCreation() || !lifetime.expired()))
            throw unavailable();
          if (lifetime.expired() && evidence.permitsCreation()) return refuseExpired(request);
          requireFresh(lifetime);
          if (!evidence.permitsCreation())
            throw new CapacityException(Failure.TENANT_BILLING_BLOCKED);

          Record usage = capacity.usage(request);
          if (observeLifetime(evidence, retainedExpiry).expired()) return refuseExpired(request);
          var reservation =
              capacity.createWithLockedUsageIfQuotaAvailable(
                  request, usage, evidence.expiresAt(), clock);
          // Expiry after a provisional write must roll back the transaction.
          lifetime = observeLifetime(evidence, retainedExpiry);
          if (reservation.isPresent()) {
            requireFresh(lifetime);
            return new CommitDecision(reservation.orElseThrow(), null);
          }
          if (lifetime.expired()) return refuseExpired(request);
          Refusal denial = originDecisions.refuse(request, Failure.CAPACITY_QUOTA_EXCEEDED);
          requireFresh(observeLifetime(evidence, retainedExpiry));
          return new CommitDecision(null, denial);
        });
    // Throw only after the transaction has committed the exact denial.
    if (decision.denial() != null)
      throw new CapacityException(decision.denial().code(), decision.denial().durableDenialId());
    return decision.reservation();
  }

  private EvaluationLifetime observeLifetime(Evidence evidence, Instant retainedExpiry) {
    boolean databaseExpired = capacity.retainedEvaluationExpired(retainedExpiry);
    Instant accountNow = clock.instant();
    boolean accountExpired = !accountNow.isBefore(retainedExpiry);
    if (!databaseExpired && accountExpired) {
      // The PostgreSQL sample precedes the Account sample and can straddle the deadline.
      databaseExpired = capacity.retainedEvaluationExpired(retainedExpiry);
    }
    if (databaseExpired != accountExpired || accountNow.isBefore(evidence.evaluatedAt()))
      throw new CapacityException(Failure.CAPACITY_ADMISSION_STALE);
    return new EvaluationLifetime(accountNow, databaseExpired);
  }

  private static void requireFresh(EvaluationLifetime lifetime) {
    if (lifetime.expired()) throw new CapacityException(Failure.CAPACITY_ADMISSION_STALE);
  }

  private CommitDecision refuseExpired(Request request) {
    return new CommitDecision(
        null, originDecisions.refuse(request, Failure.CAPACITY_EVALUATION_EXPIRED));
  }

  private record EvaluationLifetime(Instant accountNow, boolean expired) {}

  private record CommitDecision(Result reservation, Refusal denial) {}

  /** Only the Account-owned recovery worker calls this after storing terminal no-commit proof. */
  Result rearm(Request prior, Request next, UUID recoveryOwner, long claimFence) {
    prior.requireCommittable();
    next.requireCommittable();
    if (!prior.identity().equals(next.identity())
        || prior.admissionAttemptId().equals(next.admissionAttemptId())
        || next.capacityRearmFence() != Math.incrementExact(prior.capacityRearmFence())
        || !prior.context().equals(next.context())
        || prior.operation() != next.operation()
        || !prior.priorShape().equals(next.priorShape())
        || !prior.requestedShape().equals(next.requestedShape())
        || !Objects.equals(prior.capacityDelta(), next.capacityDelta())
        || !Objects.equals(
            prior.evidence().fields().get("authorityTuple"),
            next.evidence().fields().get("authorityTuple"))) throw conflict();
    return commitWithFreshSnapshot(() -> {
      lockPlayerAccount(next);
      Record row = capacity.find(prior.identity());
      if (row == null) throw conflict();
      if (next.admissionAttemptId().equals(row.get("admission_attempt_id", UUID.class))) {
        capacity.requirePriorHistory(prior, next);
        return capacity.exact(next, row);
      }
      capacity.exact(prior, row);
      requireClaim(row, recoveryOwner, claimFence, clock.instant());
      if (!"RESERVED".equals(row.get("state", String.class))
          || !"NOT_EXECUTED_AFTER_AUTHORIZATION".equals(row.get("execution_status", String.class))
          || row.get("owner_readback", byte[].class) == null) throw conflict();
      Instant retainedExpiry = capacity.requireRetained(next.evidence());
      current(next, clock.instant());
      requireSupportedShape(next);
      // Match the fresh reservation/refusal path before arbitrating the attempt UUID.
      capacity.usage(next);
      requireFresh(observeLifetime(next.evidence(), retainedExpiry));
      originDecisions.accept(next);
      requireClaim(row, recoveryOwner, claimFence, clock.instant());
      requireFresh(observeLifetime(next.evidence(), retainedExpiry));
      int history =
          dsl.execute(
              "INSERT INTO account_tenant_capacity_attempt_history "
                  + "(tenant_uuid, request_id, admission_id, admission_attempt_id, capacity_rearm_fence, mutation_digest, canonical_payload, owner_readback) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
              prior.identity().tenantId(),
              prior.identity().requestId(),
              prior.identity().admissionId(),
              prior.admissionAttemptId(),
              prior.capacityRearmFence(),
              prior.mutationDigest(),
              CapacityAdmissionDigest.preimage(prior),
              row.get("owner_readback", byte[].class));
      if (history != 1) throw unavailable();
      requireClaim(row, recoveryOwner, claimFence, clock.instant());
      int changed =
          dsl.execute(
              "UPDATE account_tenant_capacity_reservations SET admission_attempt_id = ?, capacity_rearm_fence = ?, reservation_fence = ?, mutation_digest = ?, canonical_payload = ?, evaluation_identity = ?, expires_at = ?::timestamptz, reconcile_deadline = ?::timestamptz, next_reconcile_at = NULL, recovery_attempt_count = 0, execution_status = 'OWNER_EXECUTION_PENDING', owner_readback = NULL, owner_readback_digest = NULL "
                  + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND admission_attempt_id = ? AND capacity_rearm_fence = ? AND mutation_digest = ? AND recovery_owner = ? AND recovery_claim_fence = ? AND state = 'RESERVED' AND execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION' AND recovery_claim_expires_at > clock_timestamp() AND clock_timestamp() < ?::timestamptz",
              next.admissionAttemptId(),
              next.capacityRearmFence(),
              Math.incrementExact(number(row, "reservation_fence")),
              next.mutationDigest(),
              CapacityAdmissionDigest.preimage(next),
              next.evidence().evaluationIdentity(),
              at(next.evidence().expiresAt()),
              at(next.evidence().expiresAt().plusSeconds(60)),
              prior.identity().tenantId(),
              prior.identity().requestId(),
              prior.identity().admissionId(),
              prior.admissionAttemptId(),
              prior.capacityRearmFence(),
              prior.mutationDigest(),
              recoveryOwner,
              claimFence,
              at(retainedExpiry));
      requireFresh(observeLifetime(next.evidence(), retainedExpiry));
      if (changed != 1) throw conflict();
      // Reservation identity and both usage columns are deliberately untouched.
      return capacity.exact(next, Objects.requireNonNull(capacity.find(next.identity())));
    });
  }

  private void current(Request request, Instant now) {
    var evidence = request.evidence();
    requireCurrentLifetime(evidence, now);
    var parsed = currentMembership(request);
    try {
      currentDelegation(request, now);
    } catch (CommittedCandidateExpiredException expired) {
      throw unavailable();
    }
    currentScope(request, parsed, now);
    requireCurrentLifetime(evidence, clock.instant());
  }

  private RuntimePlayerAdmissionEntitlementEvidence currentMembership(Request request) {
    var evidence = request.evidence();
    if (!(request.context() instanceof PlayerContext player)) throw unavailable();
    var parsed =
        RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(
            request.evidence().canonicalBytes());
    var current =
        qualifiedSource(() -> memberships.readCurrent(player.accountId(), player.tenantId()));
    var fields = evidence.fields();
    if (!current.equals(parsed.membershipSource())) {
      var billing =
          current
              .authorityTuple()
              .tenantBillingCutoff()
              .map(cutoffs -> cutoffs.get(current.tenantId()))
              .orElseThrow(AccountTenantCapacityRepository::unavailable);
      if (Long.parseLong(billing.tenantBillingSequence())
          > Long.parseLong((String) fields.get("tenantBillingSequence")))
        throw new CapacityException(Failure.CAPACITY_ADMISSION_STALE);
      throw unavailable();
    }
    if (!current.membershipExists()
        || !current.gameplayAdmissionAllowed()
        || !"ACTIVE".equals(current.membershipLifecycleState())) throw unavailable();
    return parsed;
  }

  private void currentDelegation(Request request, Instant now) {
    var source = sourceReferences(request.evidence());
    var tokenJti = UUID.fromString((String) source.get("callerTokenJti"));
    CommittedCandidateVerificationData issuance;
    try {
      issuance =
          qualifiedSource(() -> issuances.readCurrentCommittedCandidateByJti(tokenJti))
              .orElseThrow(AccountTenantCapacityRepository::unavailable);
    } catch (CommittedCandidateExpiredException expired) {
      requireExactDelegation(request, source, tokenJti, expired.verifiedCandidate());
      throw expired;
    }
    requireExactDelegation(request, source, tokenJti, issuance);
    if (now.getEpochSecond() < issuance.identity().notBeforeEpochSecond()
        || now.getEpochSecond() >= issuance.identity().expiresAtEpochSecond()) throw unavailable();
  }

  private static void requireExactDelegation(
      Request request,
      Map<?, ?> source,
      UUID tokenJti,
      CommittedCandidateVerificationData issuance) {
    if (!(request.context() instanceof PlayerContext player)) throw unavailable();
    if (!issuance.identity().accountId().equals(player.accountId())
        || !issuance.identity().callerWorkload().equals(request.evidence().callerWorkload())
        || !issuance.identity().callerContextId().toString().equals(source.get("callerContextId"))
        || !issuance.identity().operationId().toString().equals(source.get("callerOperationId"))
        || !issuance.identity().tokenJti().equals(tokenJti)
        || !Instant.ofEpochSecond(issuance.identity().expiresAtEpochSecond())
            .equals(Instant.parse((String) source.get("callerTokenExpiresAt")))
        || !issuance.tokenSha256().equals(source.get("callerTokenSha256"))
        || !issuance
            .canonicalRegistryRecordSha256()
            .equals(source.get("callerRegistryRecordSha256"))
        || !issuance.commitProofSha256().equals(source.get("callerCommitProofSha256")))
      throw unavailable();
  }

  private void currentScope(
      Request request, RuntimePlayerAdmissionEntitlementEvidence parsed, Instant now) {
    if (!(request.context() instanceof PlayerContext player)) throw unavailable();
    var source = sourceReferences(request.evidence());
    var scope =
        qualifiedSource(() ->
                scopes.findCanonicalEvidenceByTokenHash(parsed.verifiedConnectScopeTokenHash()))
            .orElseThrow(AccountTenantCapacityRepository::unavailable);
    if (!scope.accountUuid().equals(player.accountId())
        || !scope.tenantUuid().equals(player.tenantId())
        || !scope.scopeDigest().equals(source.get("scopeDigest"))
        || !scope.gameInstanceUuid().equals(player.gameInstanceId())
        || !scope.playableStateNamespaceUuid().equals(player.playableStateNamespaceId())
        || !scope.realmId().equals(player.realmId())
        || !scope.playableStateScope().equals(player.playableStateScope())
        || !now.isBefore(Instant.parse(scope.connectScopeExpiresAt()))) throw unavailable();
  }

  private static Map<?, ?> sourceReferences(Evidence evidence) {
    if (!(evidence.fields().get("sourceReferences") instanceof Map<?, ?> source))
      throw unavailable();
    return source;
  }

  private static <T> T qualifiedSource(Supplier<T> read) {
    try {
      return read.get();
    } catch (CommittedCandidateExpiredException expired) {
      throw expired;
    } catch (IllegalArgumentException | IllegalStateException unqualifiedSource) {
      throw unavailable();
    }
  }

  private static void requireSupportedShape(Request request) {
    if (request.context().owner() != TenantCapacityAdmission.Owner.GAME_SESSION
        || request.operation() != TenantCapacityAdmission.Operation.CAPACITY_CREATING
        || !request.priorShape().equals(new TenantCapacityAdmission.Shape(0, 0, 0))
        || !request.requestedShape().equals(new TenantCapacityAdmission.Shape(1, 0, 0))
        || !Long.valueOf(1).equals(request.capacityDelta())) throw unavailable();
  }

  private void lockPlayerAccount(Request request) {
    if (!(request.context() instanceof PlayerContext player)) throw unavailable();
    accounts
        .findByAccountUuidForUpdate(player.accountId())
        .orElseThrow(AccountTenantCapacityRepository::unavailable);
  }

  private void requireCaller(Request request, String caller) {
    if (request == null || !namespace.equals(request.targetNamespace()))
      throw new CapacityException(Failure.INVALID_ARGUMENT);
    if (!TenantCapacityAdmission.expectedWorkload(namespace, request.context().owner())
            .equals(caller)
        || !request.evidence().callerWorkload().equals(caller))
      throw new CapacityException(Failure.PERMISSION_DENIED);
  }

  static void requireClaim(Record row, UUID owner, long fence, Instant now) {
    if (owner == null
        || !owner.equals(row.get("recovery_owner", UUID.class))
        || fence != number(row, "recovery_claim_fence")
        || !now.isBefore(AccountTenantCapacityRepository.time(row, "recovery_claim_expires_at")))
      throw conflict();
  }

  private <T> T commitWithFreshSnapshot(Supplier<T> action) {
    Context caller = Context.current();
    Deadline localDeadline = Deadline.after(TRANSACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Deadline incoming = caller.getDeadline();
    Deadline retryDeadline = incoming == null ? localDeadline : localDeadline.minimum(incoming);
    if (caller.isCancelled() || retryDeadline.isExpired()) throw unavailable();
    for (int attempt = 1; ; attempt++) {
      try {
        return inTransaction(() -> {
          if (caller.isCancelled() || retryDeadline.isExpired()) throw unavailable();
          return action.get();
        });
      } catch (RuntimeException failure) {
        if (hasSqlState(failure, DEFERRED_EVALUATION_EXPIRED_SQLSTATE))
          throw new CapacityException(Failure.CAPACITY_ADMISSION_STALE);
        // A PostgreSQL serialization failure rolls back the whole transaction. Retry outside
        // that transaction so a concurrent original can become exact replay or conflict.
        if (attempt >= MAX_SERIALIZATION_ATTEMPTS
            || !serializationFailure(failure)
            || !waitForFreshSnapshot(caller, retryDeadline, attempt)) throw failure;
      }
    }
  }

  private static boolean waitForFreshSnapshot(Context caller, Deadline deadline, int attempt) {
    long minimumDelay = INITIAL_RETRY_DELAY_MILLIS << (attempt - 1);
    long delay = ThreadLocalRandom.current().nextLong(minimumDelay, 2 * minimumDelay);
    if (caller.isCancelled() || deadline.timeRemaining(TimeUnit.MILLISECONDS) <= delay) {
      return false;
    }
    try {
      TimeUnit.MILLISECONDS.sleep(delay);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
    return !caller.isCancelled() && !deadline.isExpired();
  }

  private static boolean serializationFailure(RuntimeException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CannotSerializeTransactionException) return true;
    }
    return hasSqlState(failure, "40001");
  }

  private static boolean hasSqlState(Throwable failure, String sqlState) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof org.jooq.exception.DataAccessException jooq
              && sqlState.equals(jooq.sqlState())
          || cause instanceof SQLException sql && sqlState.equals(sql.getSQLState())) return true;
    }
    return false;
  }

  <T> T inTransaction(Supplier<T> action) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
    return Objects.requireNonNull(
        transaction.execute(status -> {
          AccountTenantCapacityRepository.transaction();
          dsl.connection(connection -> {
            if (connection.getAutoCommit()
                || connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE)
              throw unavailable();
          });
          return action.get();
        }));
  }
}
