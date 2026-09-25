package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_CONNECT_SCOPE_RECORDS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Durable, exact-byte scope evidence; its token hash is only a lookup key, never authority. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountConnectScopeRepository {
  private final DSLContext dsl;

  public AccountConnectScopeRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void insert(VerifiedJoinScope scope) {
    dsl.insertInto(ACCOUNT_CONNECT_SCOPE_RECORDS)
        .set(
            ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH,
            AccountJoinDigest.tokenHash(scope.connectScopeId()))
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.ACCOUNT_ID, scope.accountId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.TARGET_CLASS, "PUBLIC_PRODUCTION")
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.TENANT_ID, scope.tenantId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_ID, scope.realmId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.WORLD_SLUG, scope.worldSlug())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_SLUG, scope.realmSlug())
        .set(
            ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_NAMESPACE_ID,
            scope.playableStateNamespaceId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_SCOPE, scope.playableStateScope())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.GAME_INSTANCE_ID, scope.gameInstanceId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.CATALOG_REVISION, scope.catalogRevision())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.POINTER_VERSION, scope.pointerVersion())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.EVALUATED_AT, scope.evaluatedAt())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.CONNECT_SCOPE_EXPIRES_AT, scope.connectScopeExpiresAt())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.SNAPSHOT_DIGEST, scope.snapshotDigest())
        .execute();
  }

  public Optional<VerifiedJoinScope> find(String connectScopeId) {
    return dsl.selectFrom(ACCOUNT_CONNECT_SCOPE_RECORDS)
        .where(
            ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH.eq(
                AccountJoinDigest.tokenHash(connectScopeId)))
        .fetchOptional(record -> toScope(record, connectScopeId));
  }

  /**
   * Reads retained target evidence by its one-way token hash. This record cannot be used as a
   * connect-scope bearer and deliberately does not reconstruct or expose the plaintext token.
   */
  public Optional<ConnectScopeEvidence> findEvidenceByTokenHash(String scopeTokenHash) {
    if (scopeTokenHash == null || scopeTokenHash.isBlank()) {
      throw new IllegalArgumentException("JOIN scope token hash is required");
    }
    return dsl.selectFrom(ACCOUNT_CONNECT_SCOPE_RECORDS)
        .where(ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH.eq(scopeTokenHash))
        .fetchOptional(AccountConnectScopeRepository::toEvidence);
  }

  private static ConnectScopeEvidence toEvidence(Record record) {
    return new ConnectScopeEvidence(
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.ACCOUNT_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TARGET_CLASS),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TENANT_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.WORLD_SLUG),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_SLUG),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_NAMESPACE_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_SCOPE),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.GAME_INSTANCE_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CATALOG_REVISION),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.POINTER_VERSION),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.EVALUATED_AT),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CONNECT_SCOPE_EXPIRES_AT),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.SNAPSHOT_DIGEST));
  }

  private VerifiedJoinScope toScope(Record record, String connectScopeId) {
    if (!"PUBLIC_PRODUCTION".equals(record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TARGET_CLASS))) {
      throw new IllegalStateException("JOIN scope is not public production");
    }
    VerifiedJoinScope scope =
        new VerifiedJoinScope(
            connectScopeId,
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.ACCOUNT_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TENANT_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.WORLD_SLUG),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_SLUG),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_NAMESPACE_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_SCOPE),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.GAME_INSTANCE_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CATALOG_REVISION),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.POINTER_VERSION),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.EVALUATED_AT),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CONNECT_SCOPE_EXPIRES_AT),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.SNAPSHOT_DIGEST));
    if (!scope.snapshotDigest().equals(AccountJoinDigest.scope(scope))) {
      throw new IllegalStateException("JOIN scope digest mismatch");
    }
    return scope;
  }

  public record ConnectScopeEvidence(
      String scopeTokenHash,
      long accountId,
      String targetClass,
      long tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      String playableStateNamespaceId,
      String playableStateScope,
      long gameInstanceId,
      long catalogRevision,
      long pointerVersion,
      String evaluatedAt,
      String connectScopeExpiresAt,
      String snapshotDigest) {}
}
