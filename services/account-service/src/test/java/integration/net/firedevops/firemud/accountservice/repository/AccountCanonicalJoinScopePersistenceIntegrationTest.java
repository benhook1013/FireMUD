package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL migration and exact V2 scope/PENDING-intent persistence proof. */
@SuppressWarnings("resource")
class AccountCanonicalJoinScopePersistenceIntegrationTest {
  private static final String CONNECT_SCOPE_ID = "canonical-connect-scope-integration";
  private static final String OTHER_ACCOUNT_SCOPE_ID = "canonical-connect-scope-other-account";
  private static final UUID TENANT_ID = UUID.fromString("9b91be60-2c1b-4f7c-8522-2f5ef4d7a7c2");

  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void migrationRetainsV1AndStoresExactV2ScopeAndPendingIntentWithoutMembershipOrEvent() {
    String schema = "canonical_join_scope_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Record account =
        setupDsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING id, account_uuid",
            "canonical-join-account",
            "canonical-join@example.test",
            "test-hash");
    long privateAccountId = Objects.requireNonNull(account).get("id", Long.class);
    UUID accountUuid = Objects.requireNonNull(account).get("account_uuid", UUID.class);

    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(transactionDsl, "prod");
    FreshTenantCreationEvidence tenantSource = freshTenantEvidence();
    transaction.executeWithoutResult(status -> freshTenants.importVerified(tenantSource));
    AccountConnectScopeRepository scopes =
        new AccountConnectScopeRepository(
            transactionDsl, mock(AccountTenantIdentityResolver.class), freshTenants);
    AccountJoinOperationRepository operations =
        new AccountJoinOperationRepository(transactionDsl, scopes);

    VerifiedJoinScope v1Scope = retainedV1Scope(privateAccountId);
    transaction.executeWithoutResult(
        status -> {
          scopes.insert(v1Scope);
          assertThat(
                  operations.insertIntent(
                      "retained-v1-request",
                      v1Scope,
                      "legacy-caller-binding",
                      AccountJoinDigest.intent(
                          "retained-v1-request", v1Scope, "legacy-caller-binding")))
              .isTrue();
        });
    Optional<AccountJoinOperationRepository.JoinOperation> retainedV1Operation =
        transaction.execute(status -> operations.find("retained-v1-request"));
    assertThat(retainedV1Operation).isPresent();

    CanonicalJoinScopeV2 scope = canonicalScope(accountUuid, tenantSource.canonicalTenantId());
    VerifiedTenantProvenance tenantProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantSource.operationId(),
            tenantSource.evidenceDigest());
    transaction.executeWithoutResult(
        status -> scopes.insertCanonical(privateAccountId, scope, tenantProvenance));

    CanonicalConnectScopeEvidence scopeEvidence =
        transaction.execute(
            status ->
                scopes
                    .findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID))
                    .orElseThrow());
    Optional<CanonicalJoinScopeV2> scopeReadback =
        transaction.execute(status -> scopes.findCanonical(CONNECT_SCOPE_ID));
    assertThat(scopeReadback).contains(scope);
    Optional<VerifiedJoinScope> numericScopeReadback =
        transaction.execute(status -> scopes.find(CONNECT_SCOPE_ID));
    assertThat(numericScopeReadback).isEmpty();
    assertThat(scopeEvidence.scopeDigest()).isEqualTo(AccountJoinDigest.scopeV2(scope));
    assertThat(scopeEvidence.accountUuid()).isEqualTo(accountUuid);
    assertThat(scopeEvidence.tenantUuid()).isEqualTo(TENANT_ID);
    assertThat(scopeEvidence.tenantProvenance()).isEqualTo(tenantProvenance);
    assertThat(scopeEvidence.scopeDigestVersion()).isEqualTo(2);

    String requestId = "canonical-join-request-1";
    String callerBinding = "authenticated-bootstrap-jti";
    CanonicalJoinOperationEvidence pending =
        transaction.execute(
            status -> {
              assertThat(operations.insertCanonicalIntent(requestId, scope, callerBinding))
                  .isTrue();
              assertThat(operations.insertCanonicalIntent(requestId, scope, callerBinding))
                  .isFalse();
              return operations.findCanonicalEvidenceByRequestId(requestId).orElseThrow();
            });
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.entitlementAuthorityAvailability()).isEqualTo("NOT_EVALUATED");
    assertThat(pending.requestDigest()).isNull();
    assertThat(pending.intentDigest())
        .isEqualTo(AccountJoinDigest.intentV2(requestId, scope, callerBinding));
    Optional<AccountJoinOperationRepository.JoinOperation> numericOperationReadback =
        transaction.execute(status -> operations.find(requestId));
    assertThat(numericOperationReadback).isEmpty();

    CanonicalJoinOperationEvidence policyBound =
        transaction.execute(
            status ->
                operations.bindCanonicalPolicyEvidence(requestId, scope, callerBinding, true, 23L));
    assertThat(policyBound.entitlementAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(policyBound.requestDigestVersion()).isEqualTo(2);
    assertThat(policyBound.requestDigest())
        .isEqualTo(
            AccountJoinDigest.requestV2(
                scope,
                callerBinding,
                AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE,
                true,
                23L));
    CanonicalJoinOperationEvidence policyReplay =
        transaction.execute(
            status ->
                operations.bindCanonicalPolicyEvidence(requestId, scope, callerBinding, true, 23L));
    assertThat(policyReplay).isEqualTo(policyBound);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> operations.insertCanonicalIntent(requestId, scope, "changed-caller")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);

    Record otherAccount =
        setupDsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING id, account_uuid",
            "canonical-join-other-account",
            "canonical-join-other@example.test",
            "test-hash");
    long otherPrivateAccountId = Objects.requireNonNull(otherAccount).get("id", Long.class);
    UUID otherAccountUuid = Objects.requireNonNull(otherAccount).get("account_uuid", UUID.class);
    CanonicalJoinScopeV2 otherAccountScope =
        canonicalScope(OTHER_ACCOUNT_SCOPE_ID, otherAccountUuid, tenantSource.canonicalTenantId());
    transaction.executeWithoutResult(
        status ->
            scopes.insertCanonical(otherPrivateAccountId, otherAccountScope, tenantProvenance));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        operations.insertCanonicalIntent(
                            requestId, otherAccountScope, callerBinding)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class)
        .hasMessage("JOIN request ID conflicts with canonical caller or scope intent");

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        operations.insertCanonicalIntent(
                            "retained-v1-request", scope, callerBinding)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);

    CanonicalJoinOperationEvidence unavailable =
        transaction.execute(
            status -> {
              operations.insertCanonicalIntent("canonical-join-unavailable", scope, callerBinding);
              return operations.recordCanonicalPolicyUnavailable(
                  "canonical-join-unavailable", scope, callerBinding, "ENTITLEMENT_UNAVAILABLE");
            });
    assertThat(unavailable.status()).isEqualTo("PENDING");
    assertThat(unavailable.entitlementAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(unavailable.entitlementVersion()).isNull();
    assertThat(unavailable.allowPublicJoin()).isNull();
    assertThat(unavailable.requestDigestVersion()).isNull();
    assertThat(unavailable.requestDigest()).isNull();
    assertThat(unavailable.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");

    Record aliases =
        setupDsl.fetchOne(
            "SELECT tenant_id, game_instance_id, playable_state_namespace_id "
                + "FROM account_connect_scope_records WHERE scope_token_hash = ?",
            AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID));
    assertThat(Objects.requireNonNull(aliases).intoMap())
        .containsEntry("tenant_id", null)
        .containsEntry("game_instance_id", null)
        .containsEntry("playable_state_namespace_id", null);
    assertThat(
            setupDsl
                .fetchOne(
                    "SELECT tenant_id, game_instance_id, playable_state_namespace_id, status, "
                        + "entitlement_authority_availability, request_digest "
                        + "FROM account_join_operations WHERE request_id = ?",
                    requestId)
                .intoMap())
        .containsEntry("tenant_id", null)
        .containsEntry("game_instance_id", null)
        .containsEntry("playable_state_namespace_id", null)
        .containsEntry("status", "PENDING")
        .containsEntry("entitlement_authority_availability", "AVAILABLE");
    assertThat(
            setupDsl
                .fetchOne(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ?",
                    privateAccountId)
                .get(0, Long.class))
        .isZero();
    assertThat(setupDsl.fetchOne("SELECT COUNT(*) FROM account_audit_outbox").get(0, Long.class))
        .isZero();
  }

  private static FreshTenantCreationEvidence freshTenantEvidence() {
    UUID requestId = UUID.fromString("650173d3-96f4-4a74-b2af-14920ac7ba31");
    UUID operationId = UUID.fromString("78cad081-53da-4dd7-a0ac-6e880c3a1004");
    UUID tenantId = TENANT_ID;
    String requestDigest = "sha256:" + "a".repeat(64);
    String tenantKey = "fresh-tenant-key";
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            tenantId,
            7001L,
            tenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        tenantId,
        7001L,
        tenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static VerifiedJoinScope retainedV1Scope(long accountId) {
    VerifiedJoinScope draft =
        new VerifiedJoinScope(
            "retained-v1-scope",
            accountId,
            42L,
            UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901"),
            "demo",
            "production",
            "namespace-v1",
            "SHARED",
            99L,
            7L,
            3L,
            "2026-10-03T00:00:00Z",
            "2026-10-03T00:02:00Z",
            "unused");
    return new VerifiedJoinScope(
        draft.connectScopeId(),
        draft.accountId(),
        draft.tenantId(),
        draft.realmId(),
        draft.worldSlug(),
        draft.realmSlug(),
        draft.playableStateNamespaceId(),
        draft.playableStateScope(),
        draft.gameInstanceId(),
        draft.catalogRevision(),
        draft.pointerVersion(),
        draft.evaluatedAt(),
        draft.connectScopeExpiresAt(),
        AccountJoinDigest.scope(draft));
  }

  private static CanonicalJoinScopeV2 canonicalScope(UUID accountUuid, UUID tenantUuid) {
    return canonicalScope(CONNECT_SCOPE_ID, accountUuid, tenantUuid);
  }

  private static CanonicalJoinScopeV2 canonicalScope(
      String connectScopeId, UUID accountUuid, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        connectScopeId,
        accountUuid,
        tenantUuid,
        UUID.fromString("00ae2e76-b686-4a25-9b07-52b8bd3ee147"),
        "canonical-tenant",
        "demo",
        "production",
        UUID.fromString("fc085a0d-1180-4a10-8c54-04378645f0d5"),
        "SHARED",
        UUID.fromString("ce41c1f3-94a2-498c-94a2-20b763d7b3c2"),
        7L,
        3L,
        "2026-10-03T00:00:00Z",
        "2026-10-03T00:02:00Z");
  }
}
