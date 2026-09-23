package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_JOIN_OPERATIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Serializes explicit JOIN on the global account row and retains exact operation outcomes. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountJoinOperationRepository {
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
        .fetchOptional(
            row ->
                new JoinOperation(
                    row.getAccountId(),
                    row.getTenantId(),
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
                    row.getOutcomeMembershipAuthorityGeneration()));
  }

  public boolean hasRetainedOperation(long accountId) {
    return dsl.fetchExists(
        ACCOUNT_JOIN_OPERATIONS, ACCOUNT_JOIN_OPERATIONS.ACCOUNT_ID.eq(accountId));
  }

  public boolean insertPending(
      String requestId,
      VerifiedJoinScope scope,
      String callerBinding,
      String requestDigest,
      String entitlementAuthorityAvailability,
      Long entitlementVersion,
      Boolean allowPublicJoin,
      boolean callerBoundAuthorityInvalidated) {
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
            .set(ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_VERSION, entitlementVersion)
            .set(ACCOUNT_JOIN_OPERATIONS.ALLOW_PUBLIC_JOIN, allowPublicJoin)
            .set(
                ACCOUNT_JOIN_OPERATIONS.ENTITLEMENT_AUTHORITY_AVAILABILITY,
                entitlementAuthorityAvailability)
            .set(
                ACCOUNT_JOIN_OPERATIONS.CALLER_BOUND_AUTHORITY_INVALIDATED,
                callerBoundAuthorityInvalidated)
            .set(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST_VERSION, 1)
            .set(ACCOUNT_JOIN_OPERATIONS.REQUEST_DIGEST, requestDigest)
            .set(ACCOUNT_JOIN_OPERATIONS.STATUS, "PENDING")
            .onConflict(ACCOUNT_JOIN_OPERATIONS.REQUEST_ID)
            .doNothing()
            .execute();
    return inserted == 1;
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
    int updated =
        dsl.update(ACCOUNT_JOIN_OPERATIONS)
            .set(ACCOUNT_JOIN_OPERATIONS.STATUS, status)
            .set(ACCOUNT_JOIN_OPERATIONS.OUTCOME, outcome)
            .set(ACCOUNT_JOIN_OPERATIONS.MEMBERSHIP_ID, membershipId)
            .set(ACCOUNT_JOIN_OPERATIONS.OUTCOME_MEMBERSHIP_VERSION, membershipVersion)
            .set(
                ACCOUNT_JOIN_OPERATIONS.OUTCOME_MEMBERSHIP_AUTHORITY_GENERATION,
                membershipAuthorityGeneration)
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
      long accountId,
      long tenantId,
      String callerBinding,
      String scopeTokenHash,
      String connectScopeDigest,
      String entitlementAuthorityAvailability,
      Boolean allowPublicJoin,
      Long entitlementVersion,
      int requestDigestVersion,
      String requestDigest,
      String status,
      String outcome,
      Long membershipId,
      Long membershipVersion,
      Long membershipAuthorityGeneration) {}
}
