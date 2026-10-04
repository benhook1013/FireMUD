package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_JOIN_OPERATIONS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest.EntitlementAvailabilityV2;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes explicit JOIN on the global account row and retains exact operation outcomes. */
@Repository
public class AccountJoinOperationRepository {
  private static final int MAX_RECONCILIATION_PAGE_SIZE = 100;
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final String CANONICAL_SELECT_COLUMNS =
      "SELECT request_id, account_id, tenant_id, verified_caller_binding, scope_token_hash, "
          + "connect_scope_digest, intent_digest_version, intent_digest, "
          + "entitlement_authority_availability, allow_public_join, entitlement_version, "
          + "request_digest_version, request_digest, last_attempt_failure_code, "
          + "last_attempt_authority_availability, caller_bound_authority_invalidated, status, "
          + "operation_representation_version, scope_digest_version, target_class, account_uuid, "
          + "tenant_uuid, tenant_slug, realm_id, world_slug, realm_slug, "
          + "playable_state_namespace_id, playable_state_namespace_uuid, playable_state_scope, "
          + "game_instance_id, game_instance_uuid, catalog_revision, pointer_version "
          + "FROM account_join_operations";

  private final DSLContext dsl;
  private final AccountConnectScopeRepository connectScopes;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor validates injected internal collaborators; it neither publishes this "
              + "nor invokes overridable methods.")
  public AccountJoinOperationRepository(
      DSLContext dsl, AccountConnectScopeRepository connectScopes) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.connectScopes = Objects.requireNonNull(connectScopes, "Connect scope reader is required");
  }

  public void lockAccount(long accountId) {
    Long locked =
        dsl.select(ACCOUNTS.ID)
            .from(ACCOUNTS)
            .where(ACCOUNTS.ID.eq(accountId))
            .forUpdate()
            .fetchOne(ACCOUNTS.ID);
    if (locked == null) {
      throw new IllegalArgumentException("JOIN account does not exist");
    }
  }

  public Optional<JoinOperation> find(String requestId) {
    return dsl.selectFrom(ACCOUNT_JOIN_OPERATIONS)
        .where(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID.eq(requestId).and(retainedV1Representation()))
        .fetchOptional(AccountJoinOperationRepository::toJoinOperation);
  }

  public Optional<JoinOperation> findForUpdate(String requestId) {
    return dsl.selectFrom(ACCOUNT_JOIN_OPERATIONS)
        .where(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID.eq(requestId).and(retainedV1Representation()))
        .forUpdate()
        .fetchOptional(AccountJoinOperationRepository::toJoinOperation);
  }

  /** Returns a stable, bounded page of all due PENDING operations. */
  public List<JoinOperation> findDuePendingReconciliation(Instant now, int limit) {
    if (now == null) {
      throw new IllegalArgumentException("JOIN reconciliation time is required");
    }
    if (limit < 1 || limit > MAX_RECONCILIATION_PAGE_SIZE) {
      throw new IllegalArgumentException("JOIN reconciliation page size must be between 1 and 100");
    }
    return dsl.selectFrom(ACCOUNT_JOIN_OPERATIONS)
        .where(
            ACCOUNT_JOIN_OPERATIONS
                .STATUS
                .eq("PENDING")
                .and(retainedV1Representation())
                .and(
                    ACCOUNT_JOIN_OPERATIONS.NEXT_RECONCILIATION_ATTEMPT_AT.le(
                        toLocalDateTime(now))))
        .orderBy(
            ACCOUNT_JOIN_OPERATIONS.NEXT_RECONCILIATION_ATTEMPT_AT.asc(),
            ACCOUNT_JOIN_OPERATIONS.CREATED_AT.asc(),
            ACCOUNT_JOIN_OPERATIONS.REQUEST_ID.asc())
        .limit(limit)
        .fetch(AccountJoinOperationRepository::toJoinOperation);
  }

  /**
   * Records one reconciliation attempt only if the pending row still has the observed attempt count
   * and due time. The count saturates at the configured diagnostic threshold. A false result means
   * another worker changed or rescheduled the row.
   */
  public boolean recordReconciliationAttempt(
      String requestId,
      int expectedAttemptCount,
      int maxAttempts,
      Instant expectedNextAttemptAt,
      Instant attemptedAt,
      String reason,
      Instant nextAttemptAt) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("JOIN request ID is required");
    }
    if (expectedAttemptCount < 0 || maxAttempts < 1 || expectedNextAttemptAt == null) {
      throw new IllegalArgumentException("JOIN reconciliation attempt count is outside its limit");
    }
    if (attemptedAt == null || nextAttemptAt == null || nextAttemptAt.isBefore(attemptedAt)) {
      throw new IllegalArgumentException("JOIN reconciliation attempt times are invalid");
    }
    if (reason == null || reason.isBlank() || reason.trim().length() > 128) {
      throw new IllegalArgumentException(
          "JOIN reconciliation reason must contain 1 to 128 characters");
    }
    int updated =
        dsl.update(ACCOUNT_JOIN_OPERATIONS)
            .set(
                ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT,
                org.jooq
                    .impl
                    .DSL
                    .when(
                        ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.lt(maxAttempts),
                        ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.plus(1))
                    .otherwise(ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT))
            .set(
                ACCOUNT_JOIN_OPERATIONS.LAST_RECONCILIATION_ATTEMPT_AT,
                toLocalDateTime(attemptedAt))
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_RECONCILIATION_ATTEMPT_REASON, reason.trim())
            .set(
                ACCOUNT_JOIN_OPERATIONS.NEXT_RECONCILIATION_ATTEMPT_AT,
                toLocalDateTime(nextAttemptAt))
            .set(ACCOUNT_JOIN_OPERATIONS.UPDATED_AT, toLocalDateTime(attemptedAt))
            .where(
                ACCOUNT_JOIN_OPERATIONS
                    .REQUEST_ID
                    .eq(requestId)
                    .and(retainedV1Representation())
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING"))
                    .and(
                        ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.eq(
                            expectedAttemptCount))
                    .and(
                        ACCOUNT_JOIN_OPERATIONS.NEXT_RECONCILIATION_ATTEMPT_AT.eq(
                            toLocalDateTime(expectedNextAttemptAt))))
            .execute();
    return updated == 1;
  }

  public boolean hasRetainedOperation(long accountId) {
    return dsl.fetchExists(
        ACCOUNT_JOIN_OPERATIONS, ACCOUNT_JOIN_OPERATIONS.ACCOUNT_ID.eq(accountId));
  }

  public boolean insertIntent(
      String requestId, VerifiedJoinScope scope, String callerBinding, String intentDigest) {
    int inserted =
        dsl.insertInto(ACCOUNT_JOIN_OPERATIONS)
            .set(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID, requestId)
            .set(ACCOUNT_JOIN_OPERATIONS.ACCOUNT_ID, scope.accountId())
            .set(ACCOUNT_JOIN_OPERATIONS.TENANT_ID, scope.tenantId())
            .set(ACCOUNT_JOIN_OPERATIONS.VERIFIED_CALLER_BINDING, callerBinding)
            .set(
                ACCOUNT_JOIN_OPERATIONS.SCOPE_TOKEN_HASH,
                net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(
                    scope.connectScopeId()))
            .set(ACCOUNT_JOIN_OPERATIONS.CONNECT_SCOPE_DIGEST, scope.snapshotDigest())
            .set(ACCOUNT_JOIN_OPERATIONS.WORLD_SLUG, scope.worldSlug())
            .set(ACCOUNT_JOIN_OPERATIONS.REALM_SLUG, scope.realmSlug())
            .set(ACCOUNT_JOIN_OPERATIONS.REALM_ID, scope.realmId())
            .set(
                ACCOUNT_JOIN_OPERATIONS.PLAYABLE_STATE_NAMESPACE_ID,
                scope.playableStateNamespaceId())
            .set(ACCOUNT_JOIN_OPERATIONS.PLAYABLE_STATE_SCOPE, scope.playableStateScope())
            .set(ACCOUNT_JOIN_OPERATIONS.GAME_INSTANCE_ID, scope.gameInstanceId())
            .set(ACCOUNT_JOIN_OPERATIONS.CATALOG_REVISION, scope.catalogRevision())
            .set(ACCOUNT_JOIN_OPERATIONS.POINTER_VERSION, scope.pointerVersion())
            .set(ACCOUNT_JOIN_OPERATIONS.INTENT_DIGEST_VERSION, 1)
            .set(ACCOUNT_JOIN_OPERATIONS.INTENT_DIGEST, intentDigest)
            .set(ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY, "NOT_EVALUATED")
            .set(ACCOUNT_JOIN_OPERATIONS.CALLER_BOUND_AUTHORITY_INVALIDATED, false)
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_AUTHORITY_AVAILABILITY, "NOT_EVALUATED")
            .set(ACCOUNT_JOIN_OPERATIONS.STATUS, "PENDING")
            .onConflict(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID)
            .doNothing()
            .execute();
    return inserted == 1;
  }

  /**
   * Persists a policy-independent V2 intent in the shared global request-id journal. The exact
   * bearer is required here so the immutable V46 scope and V2 intent preimages can be recomputed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertCanonicalIntent(
      String requestId, CanonicalJoinScopeV2 scope, String callerBinding) {
    requireOwnerTransaction();
    requireCanonicalOperationInput(requestId, callerBinding);
    Objects.requireNonNull(scope, "Canonical JOIN scope is required");
    CanonicalConnectScopeEvidence scopeEvidence = requireIncomingScope(scope);
    String intentDigest = AccountJoinDigest.intentV2(requestId, scope, callerBinding);

    int inserted =
        dsl.execute(
            "INSERT INTO account_join_operations "
                + "(request_id, account_id, tenant_id, verified_caller_binding, "
                + "scope_token_hash, connect_scope_digest, world_slug, realm_slug, realm_id, "
                + "playable_state_namespace_id, playable_state_scope, game_instance_id, "
                + "catalog_revision, pointer_version, intent_digest_version, intent_digest, "
                + "entitlement_authority_availability, caller_bound_authority_invalidated, "
                + "last_attempt_authority_availability, status, operation_representation_version, "
                + "scope_digest_version, target_class, account_uuid, tenant_uuid, tenant_slug, "
                + "playable_state_namespace_uuid, game_instance_uuid) "
                + "VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, 2, ?, "
                + "'NOT_EVALUATED', FALSE, 'NOT_EVALUATED', 'PENDING', 2, 2, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (request_id) DO NOTHING",
            requestId,
            scopeEvidence.privateAccountId(),
            callerBinding,
            scopeEvidence.scopeTokenHash(),
            scopeEvidence.scopeDigest(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.realmId(),
            scope.playableStateScope(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            intentDigest,
            scopeEvidence.targetClass(),
            scopeEvidence.accountUuid(),
            scopeEvidence.tenantUuid(),
            scopeEvidence.tenantSlug(),
            scopeEvidence.playableStateNamespaceUuid(),
            scopeEvidence.gameInstanceUuid());
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Canonical JOIN intent insert was ambiguous");
    }

    CanonicalJoinOperationEvidence committed =
        readCanonicalEvidenceByRequestId(requestId, false)
            .orElseThrow(
                () -> new IllegalStateException("Canonical JOIN intent insert readback is absent"));
    requireCanonicalIntentMatch(committed, requestId, callerBinding, intentDigest, scopeEvidence);
    return inserted == 1;
  }

  /**
   * Reads immutable V2 operation and scope facts by request ID without reconstructing a bearer or
   * recomputing a token-dependent digest. This is structural recovery evidence, not authorization.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalJoinOperationEvidence> findCanonicalEvidenceByRequestId(
      String requestId) {
    requireOwnerTransaction();
    requireRequestId(requestId);
    return readCanonicalEvidenceByRequestId(requestId, false);
  }

  /** Binds or exactly replays available V2 policy evidence under the Account and journal fences. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalJoinOperationEvidence bindCanonicalPolicyEvidence(
      String requestId,
      CanonicalJoinScopeV2 scope,
      String callerBinding,
      boolean allowPublicJoin,
      long entitlementVersion) {
    requireOwnerTransaction();
    requireCanonicalOperationInput(requestId, callerBinding);
    Objects.requireNonNull(scope, "Canonical JOIN scope is required");
    CanonicalConnectScopeEvidence scopeEvidence = requireIncomingScope(scope);
    String intentDigest = AccountJoinDigest.intentV2(requestId, scope, callerBinding);
    String requestDigest =
        AccountJoinDigest.requestV2(
            scope,
            callerBinding,
            EntitlementAvailabilityV2.AVAILABLE,
            allowPublicJoin,
            entitlementVersion);
    CanonicalJoinOperationEvidence operation =
        lockCanonicalEvidenceByRequestId(requestId)
            .orElseThrow(
                () ->
                    new CanonicalJoinOperationConflictException("Canonical JOIN intent is absent"));
    requireCanonicalIntentMatch(operation, requestId, callerBinding, intentDigest, scopeEvidence);

    if (operation.requestDigest() != null) {
      requireCanonicalPolicyMatch(operation, requestDigest, allowPublicJoin, entitlementVersion);
      return operation;
    }

    int updated =
        dsl.execute(
            "UPDATE account_join_operations SET entitlement_authority_availability = 'AVAILABLE', "
                + "entitlement_version = ?, allow_public_join = ?, request_digest_version = 2, "
                + "request_digest = ?, last_attempt_authority_availability = 'AVAILABLE', "
                + "last_attempt_failure_code = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE request_id = ? AND operation_representation_version = 2 "
                + "AND status = 'PENDING' AND request_digest IS NULL",
            entitlementVersion,
            allowPublicJoin,
            requestDigest,
            requestId);
    if (updated != 1) {
      throw new CanonicalJoinOperationConflictException(
          "Canonical JOIN operation changed before policy binding");
    }

    CanonicalJoinOperationEvidence committed =
        readCanonicalEvidenceByRequestId(requestId, false)
            .orElseThrow(
                () ->
                    new IllegalStateException("Canonical JOIN policy binding readback is absent"));
    requireCanonicalIntentMatch(committed, requestId, callerBinding, intentDigest, scopeEvidence);
    requireCanonicalPolicyMatch(committed, requestDigest, allowPublicJoin, entitlementVersion);
    return committed;
  }

  /** Records unavailable policy without creating a policy result, entitlement version or digest. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalJoinOperationEvidence recordCanonicalPolicyUnavailable(
      String requestId, CanonicalJoinScopeV2 scope, String callerBinding, String failureCode) {
    requireOwnerTransaction();
    requireCanonicalOperationInput(requestId, callerBinding);
    Objects.requireNonNull(scope, "Canonical JOIN scope is required");
    if (failureCode == null || failureCode.isBlank() || failureCode.length() > 64) {
      throw new IllegalArgumentException(
          "JOIN attempt failure code must contain 1 to 64 characters");
    }
    CanonicalConnectScopeEvidence scopeEvidence = requireIncomingScope(scope);
    String intentDigest = AccountJoinDigest.intentV2(requestId, scope, callerBinding);
    CanonicalJoinOperationEvidence operation =
        lockCanonicalEvidenceByRequestId(requestId)
            .orElseThrow(
                () ->
                    new CanonicalJoinOperationConflictException("Canonical JOIN intent is absent"));
    requireCanonicalIntentMatch(operation, requestId, callerBinding, intentDigest, scopeEvidence);
    if (operation.requestDigest() != null) {
      throw new CanonicalJoinOperationConflictException(
          "Available canonical JOIN policy evidence cannot be downgraded");
    }

    int updated =
        dsl.execute(
            "UPDATE account_join_operations SET entitlement_authority_availability = 'UNAVAILABLE', "
                + "last_attempt_authority_availability = 'UNAVAILABLE', "
                + "last_attempt_failure_code = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE request_id = ? AND operation_representation_version = 2 "
                + "AND status = 'PENDING' AND request_digest IS NULL",
            failureCode,
            requestId);
    if (updated != 1) {
      throw new CanonicalJoinOperationConflictException(
          "Canonical JOIN operation changed while recording policy unavailability");
    }

    CanonicalJoinOperationEvidence committed =
        readCanonicalEvidenceByRequestId(requestId, false)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical JOIN unavailable-attempt readback is absent"));
    requireCanonicalIntentMatch(committed, requestId, callerBinding, intentDigest, scopeEvidence);
    if (!"UNAVAILABLE".equals(committed.entitlementAuthorityAvailability())
        || committed.entitlementVersion() != null
        || committed.allowPublicJoin() != null
        || committed.requestDigestVersion() != null
        || committed.requestDigest() != null
        || !failureCode.equals(committed.lastAttemptFailureCode())) {
      throw new IllegalStateException(
          "Canonical JOIN unavailable attempt readback contains policy evidence");
    }
    return committed;
  }

  public void bindPolicyEvidence(
      String requestId, String requestDigest, Long entitlementVersion, Boolean allowPublicJoin) {
    int updated =
        dsl.update(ACCOUNT_JOIN_OPERATIONS)
            .set(ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY, "AVAILABLE")
            .set(ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_VERSION, entitlementVersion)
            .set(ACCOUNT_JOIN_OPERATIONS.ALLOW_PUBLIC_JOIN, allowPublicJoin)
            .set(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST_VERSION, 1)
            .set(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST, requestDigest)
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_AUTHORITY_AVAILABILITY, "AVAILABLE")
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_FAILURE_CODE, (String) null)
            .set(ACCOUNT_JOIN_OPERATIONS.UPDATED_AT, org.jooq.impl.DSL.currentLocalDateTime())
            .where(
                ACCOUNT_JOIN_OPERATIONS
                    .REQUEST_ID
                    .eq(requestId)
                    .and(retainedV1Representation())
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING"))
                    .and(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST.isNull()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("JOIN operation changed before policy binding");
    }
  }

  public void recordAttemptFailure(
      String requestId, String authorityAvailability, String failureCode) {
    if (failureCode == null || failureCode.isBlank()) {
      throw new IllegalArgumentException("JOIN attempt failure code is required");
    }
    if (!"AVAILABLE".equals(authorityAvailability)
        && !"UNAVAILABLE".equals(authorityAvailability)
        && !"NOT_EVALUATED".equals(authorityAvailability)) {
      throw new IllegalArgumentException("JOIN attempt authority availability is invalid");
    }
    int updated =
        dsl.update(ACCOUNT_JOIN_OPERATIONS)
            .set(
                ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY,
                org.jooq
                    .impl
                    .DSL
                    .when(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST.isNull(), authorityAvailability)
                    .otherwise(ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY))
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_AUTHORITY_AVAILABILITY, authorityAvailability)
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_FAILURE_CODE, failureCode)
            .set(ACCOUNT_JOIN_OPERATIONS.UPDATED_AT, org.jooq.impl.DSL.currentLocalDateTime())
            .where(
                ACCOUNT_JOIN_OPERATIONS
                    .REQUEST_ID
                    .eq(requestId)
                    .and(retainedV1Representation())
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING")))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("JOIN operation changed while recording attempt failure");
    }
  }

  public void finish(
      String requestId,
      String status,
      String outcome,
      Long membershipId,
      Long membershipVersion,
      Long membershipAuthorityGeneration) {
    if (!"COMMITTED".equals(status) && !"FAILED".equals(status)) {
      throw new IllegalArgumentException("JOIN terminal status is invalid");
    }
    if (outcome == null || outcome.isBlank()) {
      throw new IllegalArgumentException("JOIN terminal outcome is required");
    }
    int updated =
        dsl.update(ACCOUNT_JOIN_OPERATIONS)
            .set(ACCOUNT_JOIN_OPERATIONS.STATUS, status)
            .set(ACCOUNT_JOIN_OPERATIONS.OUTCOME, outcome)
            .set(
                ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY,
                org.jooq
                    .impl
                    .DSL
                    .when(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST.isNull(), "NOT_EVALUATED")
                    .otherwise(ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY))
            .set(
                ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_AUTHORITY_AVAILABILITY,
                org.jooq
                    .impl
                    .DSL
                    .when(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST.isNull(), "NOT_EVALUATED")
                    .otherwise(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_AUTHORITY_AVAILABILITY))
            .set(ACCOUNT_JOIN_OPERATIONS.MEMBERSHIP_ID, membershipId)
            .set(ACCOUNT_JOIN_OPERATIONS.OUTCOME_MEMBERSHIP_VERSION, membershipVersion)
            .set(
                ACCOUNT_JOIN_OPERATIONS.OUTCOME_MEMBERSHIP_AUTHORITY_GENERATION,
                membershipAuthorityGeneration)
            .set(ACCOUNT_JOIN_OPERATIONS.LAST_ATTEMPT_FAILURE_CODE, (String) null)
            .set(ACCOUNT_JOIN_OPERATIONS.UPDATED_AT, org.jooq.impl.DSL.currentLocalDateTime())
            .where(
                ACCOUNT_JOIN_OPERATIONS
                    .REQUEST_ID
                    .eq(requestId)
                    .and(retainedV1Representation())
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING")))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("JOIN operation changed concurrently");
    }
  }

  public void recordCallerBoundAuthorityInvalidation(String requestId) {
    int updated =
        dsl.update(ACCOUNT_JOIN_OPERATIONS)
            .set(ACCOUNT_JOIN_OPERATIONS.CALLER_BOUND_AUTHORITY_INVALIDATED, true)
            .where(
                ACCOUNT_JOIN_OPERATIONS
                    .REQUEST_ID
                    .eq(requestId)
                    .and(retainedV1Representation())
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING")))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("JOIN operation changed concurrently");
    }
  }

  private Optional<CanonicalJoinOperationEvidence> readCanonicalEvidenceByRequestId(
      String requestId, boolean forUpdate) {
    Record row =
        dsl.fetchOne(
            CANONICAL_SELECT_COLUMNS + " WHERE request_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            requestId);
    if (row == null) {
      return Optional.empty();
    }
    if (!Integer.valueOf(2).equals(row.get("operation_representation_version", Integer.class))) {
      throw new CanonicalJoinOperationConflictException(
          "JOIN request ID is retained in a different operation representation");
    }
    String scopeTokenHash = required(row, "scope_token_hash", String.class);
    CanonicalConnectScopeEvidence scopeEvidence =
        connectScopes
            .findCanonicalEvidenceByTokenHash(scopeTokenHash)
            .orElseThrow(
                () ->
                    new IllegalStateException("Canonical JOIN operation scope evidence is absent"));
    return Optional.of(toCanonicalOperationEvidence(row, scopeEvidence));
  }

  private Optional<CanonicalJoinOperationEvidence> lockCanonicalEvidenceByRequestId(
      String requestId) {
    // The source read locks the owning Account row before this operation-row lock, preserving the
    // Account -> operation ordering used by JOIN and preventing an inversion during retries.
    if (readCanonicalEvidenceByRequestId(requestId, false).isEmpty()) {
      return Optional.empty();
    }
    return readCanonicalEvidenceByRequestId(requestId, true);
  }

  private CanonicalConnectScopeEvidence requireIncomingScope(CanonicalJoinScopeV2 scope) {
    CanonicalJoinScopeV2 persisted =
        connectScopes
            .findCanonical(scope.connectScopeId())
            .orElseThrow(
                () ->
                    new CanonicalJoinOperationConflictException(
                        "Canonical JOIN scope evidence is absent"));
    if (!persisted.equals(scope)) {
      throw new CanonicalJoinOperationConflictException(
          "Canonical JOIN scope differs from immutable token evidence");
    }
    return connectScopes
        .findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(scope.connectScopeId()))
        .orElseThrow(
            () -> new IllegalStateException("Canonical JOIN scope source readback is absent"));
  }

  private static CanonicalJoinOperationEvidence toCanonicalOperationEvidence(
      Record row, CanonicalConnectScopeEvidence scopeEvidence) {
    Long accountId = required(row, "account_id", Long.class);
    if (accountId.longValue() != scopeEvidence.privateAccountId()
        || row.get("tenant_id", Long.class) != null
        || row.get("game_instance_id", Long.class) != null
        || row.get("playable_state_namespace_id", String.class) != null
        || !Objects.equals(row.get("target_class", String.class), scopeEvidence.targetClass())
        || !Objects.equals(row.get("account_uuid", UUID.class), scopeEvidence.accountUuid())
        || !Objects.equals(row.get("tenant_uuid", UUID.class), scopeEvidence.tenantUuid())
        || !Objects.equals(row.get("tenant_slug", String.class), scopeEvidence.tenantSlug())
        || !Objects.equals(row.get("realm_id", UUID.class), scopeEvidence.realmId())
        || !Objects.equals(row.get("world_slug", String.class), scopeEvidence.worldSlug())
        || !Objects.equals(row.get("realm_slug", String.class), scopeEvidence.realmSlug())
        || !Objects.equals(
            row.get("playable_state_namespace_uuid", UUID.class),
            scopeEvidence.playableStateNamespaceUuid())
        || !Objects.equals(
            row.get("playable_state_scope", String.class), scopeEvidence.playableStateScope())
        || !Objects.equals(
            row.get("game_instance_uuid", UUID.class), scopeEvidence.gameInstanceUuid())
        || !Objects.equals(row.get("catalog_revision", Long.class), scopeEvidence.catalogRevision())
        || !Objects.equals(row.get("pointer_version", Long.class), scopeEvidence.pointerVersion())
        || !Objects.equals(
            required(row, "scope_digest_version", Integer.class),
            scopeEvidence.scopeDigestVersion())
        || !Objects.equals(
            required(row, "connect_scope_digest", String.class), scopeEvidence.scopeDigest())) {
      throw new IllegalStateException(
          "Canonical JOIN operation contradicts its immutable scope source");
    }

    String status = required(row, "status", String.class);
    if (!"PENDING".equals(status)) {
      throw new IllegalStateException("Canonical JOIN operation has unsupported terminal evidence");
    }
    return new CanonicalJoinOperationEvidence(
        required(row, "request_id", String.class),
        accountId,
        required(row, "verified_caller_binding", String.class),
        required(row, "scope_token_hash", String.class),
        required(row, "connect_scope_digest", String.class),
        required(row, "operation_representation_version", Integer.class),
        required(row, "scope_digest_version", Integer.class),
        required(row, "intent_digest_version", Integer.class),
        required(row, "intent_digest", String.class),
        required(row, "entitlement_authority_availability", String.class),
        row.get("allow_public_join", Boolean.class),
        row.get("entitlement_version", Long.class),
        row.get("request_digest_version", Integer.class),
        row.get("request_digest", String.class),
        required(row, "last_attempt_authority_availability", String.class),
        row.get("last_attempt_failure_code", String.class),
        required(row, "caller_bound_authority_invalidated", Boolean.class),
        status,
        scopeEvidence);
  }

  private static <T> T required(Record row, String column, Class<T> type) {
    T value = row.get(column, type);
    if (value == null) {
      throw new IllegalStateException("Canonical JOIN operation is missing " + column);
    }
    return value;
  }

  private static void requireCanonicalIntentMatch(
      CanonicalJoinOperationEvidence operation,
      String requestId,
      String callerBinding,
      String intentDigest,
      CanonicalConnectScopeEvidence scopeEvidence) {
    if (!requestId.equals(operation.requestId())
        || !callerBinding.equals(operation.callerBinding())
        || !intentDigest.equals(operation.intentDigest())
        || !scopeEvidence.equals(operation.scopeEvidence())) {
      throw new CanonicalJoinOperationConflictException(
          "JOIN request ID conflicts with canonical caller or scope intent");
    }
  }

  private static void requireCanonicalPolicyMatch(
      CanonicalJoinOperationEvidence operation,
      String requestDigest,
      boolean allowPublicJoin,
      long entitlementVersion) {
    if (!"AVAILABLE".equals(operation.entitlementAuthorityAvailability())
        || !Objects.equals(operation.allowPublicJoin(), allowPublicJoin)
        || !Objects.equals(operation.entitlementVersion(), entitlementVersion)
        || !Integer.valueOf(2).equals(operation.requestDigestVersion())
        || !requestDigest.equals(operation.requestDigest())) {
      throw new CanonicalJoinOperationConflictException(
          "Canonical JOIN request ID conflicts with immutable available policy evidence");
    }
  }

  private static void requireCanonicalOperationInput(String requestId, String callerBinding) {
    requireRequestId(requestId);
    if (callerBinding == null
        || callerBinding.isBlank()
        || callerBinding.codePointCount(0, callerBinding.length()) > 128) {
      throw new IllegalArgumentException(
          "Verified canonical JOIN caller binding must contain 1 to 128 characters");
    }
  }

  private static void requireRequestId(String requestId) {
    if (requestId == null
        || requestId.isBlank()
        || requestId.codePointCount(0, requestId.length()) > 128) {
      throw new IllegalArgumentException("JOIN request ID must contain 1 to 128 characters");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical Account JOIN operation access requires an active owner transaction");
    }
  }

  private static void requireSha256(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
  }

  private static org.jooq.Condition retainedV1Representation() {
    return org.jooq.impl.DSL.field("operation_representation_version", Integer.class).eq(1);
  }

  /** Hash-only V2 operation plus exact owner scope evidence; it exposes no bearer. */
  public record CanonicalJoinOperationEvidence(
      String requestId,
      long privateAccountId,
      String callerBinding,
      String scopeTokenHash,
      String connectScopeDigest,
      int operationRepresentationVersion,
      int scopeDigestVersion,
      int intentDigestVersion,
      String intentDigest,
      String entitlementAuthorityAvailability,
      Boolean allowPublicJoin,
      Long entitlementVersion,
      Integer requestDigestVersion,
      String requestDigest,
      String lastAttemptAuthorityAvailability,
      String lastAttemptFailureCode,
      boolean callerBoundAuthorityInvalidated,
      String status,
      CanonicalConnectScopeEvidence scopeEvidence) {
    public CanonicalJoinOperationEvidence {
      if (requestId == null
          || requestId.isBlank()
          || privateAccountId <= 0L
          || callerBinding == null
          || callerBinding.isBlank()
          || scopeTokenHash == null
          || connectScopeDigest == null
          || operationRepresentationVersion != 2
          || scopeDigestVersion != 2
          || intentDigestVersion != 2
          || intentDigest == null
          || entitlementAuthorityAvailability == null
          || lastAttemptAuthorityAvailability == null
          || !"PENDING".equals(status)
          || callerBoundAuthorityInvalidated
          || scopeEvidence == null
          || scopeEvidence.privateAccountId() != privateAccountId
          || !scopeTokenHash.equals(scopeEvidence.scopeTokenHash())
          || !connectScopeDigest.equals(scopeEvidence.scopeDigest())) {
        throw new IllegalArgumentException("Canonical JOIN operation evidence is incomplete");
      }
      requireSha256(scopeTokenHash, "Canonical JOIN scope token hash");
      requireSha256(connectScopeDigest, "Canonical JOIN scope digest");
      requireSha256(intentDigest, "Canonical JOIN intent digest");
      if ("AVAILABLE".equals(entitlementAuthorityAvailability)) {
        if (allowPublicJoin == null
            || entitlementVersion == null
            || entitlementVersion <= 0L
            || !Integer.valueOf(2).equals(requestDigestVersion)
            || requestDigest == null
            || lastAttemptFailureCode != null
            || !"AVAILABLE".equals(lastAttemptAuthorityAvailability)) {
          throw new IllegalArgumentException(
              "Canonical JOIN available policy evidence is incomplete");
        }
        requireSha256(requestDigest, "Canonical JOIN request digest");
      } else if (("UNAVAILABLE".equals(entitlementAuthorityAvailability)
              || "NOT_EVALUATED".equals(entitlementAuthorityAvailability))
          && allowPublicJoin == null
          && entitlementVersion == null
          && requestDigestVersion == null
          && requestDigest == null
          && (("UNAVAILABLE".equals(entitlementAuthorityAvailability)
                  && "UNAVAILABLE".equals(lastAttemptAuthorityAvailability)
                  && lastAttemptFailureCode != null
                  && !lastAttemptFailureCode.isBlank())
              || ("NOT_EVALUATED".equals(entitlementAuthorityAvailability)
                  && "NOT_EVALUATED".equals(lastAttemptAuthorityAvailability)
                  && lastAttemptFailureCode == null))) {
        // An unavailable authority attempt has no synthetic result or digest.
      } else {
        throw new IllegalArgumentException("Canonical JOIN policy evidence is contradictory");
      }
    }
  }

  /** Cross-version or changed-input reuse of the shared request ID is a deterministic conflict. */
  public static final class CanonicalJoinOperationConflictException extends IllegalStateException {
    public CanonicalJoinOperationConflictException(String message) {
      super(message);
    }
  }

  public record JoinOperation(
      String requestId,
      long accountId,
      long tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      String playableStateNamespaceId,
      String playableStateScope,
      long gameInstanceId,
      long catalogRevision,
      long pointerVersion,
      String callerBinding,
      String scopeTokenHash,
      String connectScopeDigest,
      String entitlementAuthorityAvailability,
      Boolean allowPublicJoin,
      Long entitlementVersion,
      Integer requestDigestVersion,
      String requestDigest,
      String status,
      String outcome,
      Long membershipId,
      Long membershipVersion,
      Long membershipAuthorityGeneration,
      int intentDigestVersion,
      String intentDigest,
      String lastAttemptFailureCode,
      String lastAttemptAuthorityAvailability,
      int reconciliationAttemptCount,
      Instant lastReconciliationAttemptAt,
      String lastReconciliationAttemptReason,
      Instant nextReconciliationAttemptAt) {}

  private static JoinOperation toJoinOperation(
      net.firedevops.firemud.accountservice.jooq.tables.records.AccountJoinOperationsRecord row) {
    return new JoinOperation(
        row.getRequestId(),
        row.getAccountId(),
        row.getTenantId(),
        row.getRealmId(),
        row.getWorldSlug(),
        row.getRealmSlug(),
        row.getPlayableStateNamespaceId(),
        row.getPlayableStateScope(),
        row.getGameInstanceId(),
        row.getCatalogRevision(),
        row.getPointerVersion(),
        row.getVerifiedCallerBinding(),
        row.getScopeTokenHash(),
        row.getConnectScopeDigest(),
        row.getEntitlementAuthorityAvailability(),
        row.getAllowPublicJoin(),
        row.getEntitlementVersion(),
        row.getRequestDigestVersion(),
        row.getRequestDigest(),
        row.getStatus(),
        row.getOutcome(),
        row.getMembershipId(),
        row.getOutcomeMembershipVersion(),
        row.getOutcomeMembershipAuthorityGeneration(),
        row.getIntentDigestVersion(),
        row.getIntentDigest(),
        row.getLastAttemptFailureCode(),
        row.getLastAttemptAuthorityAvailability(),
        row.getReconciliationAttemptCount(),
        row.getLastReconciliationAttemptAt() == null
            ? null
            : toInstant(row.getLastReconciliationAttemptAt()),
        row.getLastReconciliationAttemptReason(),
        toInstant(row.getNextReconciliationAttemptAt()));
  }
}
