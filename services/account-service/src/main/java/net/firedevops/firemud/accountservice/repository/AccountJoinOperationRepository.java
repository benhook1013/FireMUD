package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_JOIN_OPERATIONS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Serializes explicit JOIN on the global account row and retains exact operation outcomes. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountJoinOperationRepository {
  private static final int MAX_RECONCILIATION_PAGE_SIZE = 100;

  private final DSLContext dsl;

  public AccountJoinOperationRepository(DSLContext dsl) {
    this.dsl = dsl;
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
        .where(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID.eq(requestId))
        .fetchOptional(AccountJoinOperationRepository::toJoinOperation);
  }

  public Optional<JoinOperation> findForUpdate(String requestId) {
    return dsl.selectFrom(ACCOUNT_JOIN_OPERATIONS)
        .where(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID.eq(requestId))
        .forUpdate()
        .fetchOptional(AccountJoinOperationRepository::toJoinOperation);
  }

  /** Returns a stable, bounded page of due PENDING operations below the caller's attempt cap. */
  public List<JoinOperation> findDuePendingReconciliation(Instant now, int limit, int maxAttempts) {
    if (now == null) {
      throw new IllegalArgumentException("JOIN reconciliation time is required");
    }
    if (limit < 1 || limit > MAX_RECONCILIATION_PAGE_SIZE) {
      throw new IllegalArgumentException("JOIN reconciliation page size must be between 1 and 100");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("JOIN reconciliation attempt limit must be positive");
    }
    return dsl.selectFrom(ACCOUNT_JOIN_OPERATIONS)
        .where(
            ACCOUNT_JOIN_OPERATIONS
                .STATUS
                .eq("PENDING")
                .and(ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.lt(maxAttempts))
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
   * and remains below the caller's cap. A false result means another worker changed it.
   */
  public boolean recordReconciliationAttempt(
      String requestId,
      int expectedAttemptCount,
      int maxAttempts,
      Instant attemptedAt,
      String reason,
      Instant nextAttemptAt) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("JOIN request ID is required");
    }
    if (expectedAttemptCount < 0 || maxAttempts < 1 || expectedAttemptCount >= maxAttempts) {
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
                ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.plus(1))
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
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING"))
                    .and(
                        ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.eq(
                            expectedAttemptCount))
                    .and(ACCOUNT_JOIN_OPERATIONS.RECONCILIATION_ATTEMPT_COUNT.lt(maxAttempts)))
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
                    .and(ACCOUNT_JOIN_OPERATIONS.STATUS.eq("PENDING")))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("JOIN operation changed concurrently");
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
