package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
  private final Set<String> schemas = new HashSet<>();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void dropTestOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(postgres.dataSource());
    for (String schema : schemas) {
      if (!(schema.matches("canonical_join_scope_[a-f0-9]{32}")
          || schema.matches("canonical_scope_cleanup_[a-f0-9]{32}"))) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void migrationRetainsV1AndStoresExactV2ScopeAndPendingIntentWithoutMembershipOrEvent() {
    String schema = createSchema("canonical_join_scope_");
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
            Objects.requireNonNull(
                    setupDsl.fetchOne(
                        "SELECT tenant_id, game_instance_id, playable_state_namespace_id, status, "
                            + "entitlement_authority_availability, request_digest "
                            + "FROM account_join_operations WHERE request_id = ?",
                        requestId))
                .intoMap())
        .containsEntry("tenant_id", null)
        .containsEntry("game_instance_id", null)
        .containsEntry("playable_state_namespace_id", null)
        .containsEntry("status", "PENDING")
        .containsEntry("entitlement_authority_availability", "AVAILABLE");
    assertThat(
            Objects.requireNonNull(
                    setupDsl.fetchOne(
                        "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ?",
                        privateAccountId))
                .get(0, Long.class))
        .isZero();
    assertThat(
            Objects.requireNonNull(setupDsl.fetchOne("SELECT COUNT(*) FROM account_audit_outbox"))
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void firstInvoiceAndCapacityUpgradeFromParent1036Through1041PreservesCanonicalRowsAndHistory() {
    String schema = createSchema("canonical_join_scope_");
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway parent = accountFlyway(dataSource, schema, "1036");
    parent.migrate();
    parent.validate();
    assertThat(parent.info().current().getVersion())
        .isEqualTo(MigrationVersion.fromVersion("1036"));

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Record account =
        setupDsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING id, account_uuid",
            "canonical-migration-upgrade-account",
            "canonical-migration-upgrade@example.test",
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
    CanonicalJoinScopeV2 scope = canonicalScope(accountUuid, tenantSource.canonicalTenantId());
    VerifiedTenantProvenance tenantProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantSource.operationId(),
            tenantSource.evidenceDigest());
    String requestId = "canonical-migration-upgrade-pending";
    String callerBinding = "canonical-migration-upgrade-caller";
    transaction.executeWithoutResult(
        status -> {
          scopes.insertCanonical(privateAccountId, scope, tenantProvenance);
          assertThat(operations.insertCanonicalIntent(requestId, scope, callerBinding)).isTrue();
        });
    CanonicalJoinOperationEvidence pending =
        transaction.execute(
            status -> operations.findCanonicalEvidenceByRequestId(requestId).orElseThrow());
    assertThat(pending.status()).isEqualTo("PENDING");

    String scopeTokenHash = AccountJoinDigest.tokenHash(scope.connectScopeId());
    RetainedPhysicalRow retainedScope =
        retainedPhysicalRow(
            setupDsl,
            "SELECT to_jsonb(scope_row)::text, scope_row.xmin::text "
                + "FROM account_connect_scope_records scope_row WHERE scope_token_hash = ?",
            scopeTokenHash);
    RetainedPhysicalRow retainedIntent =
        retainedPhysicalRow(
            setupDsl,
            "SELECT to_jsonb(operation_row)::text, operation_row.xmin::text "
                + "FROM account_join_operations operation_row WHERE request_id = ?",
            requestId);
    List<Map<String, Object>> parentHistory = flywayHistory(setupDsl);
    assertThat(parentHistory)
        .extracting(row -> row.get("script"))
        .contains("V1036__hosting_subscription_terminal_resolution.sql")
        .doesNotContain(
            "V1037__canonical_join_failure_and_attempt_diagnostics.sql",
            "V1038__canonical_join_existing_membership_terminal.sql",
            "V1040__account_tenant_capacity_owner.sql",
            "V1040.1__account_capacity_origin_decisions.sql",
            "V1040.2__bound_capacity_origin_refusals.sql",
            "V1041__hosting_first_invoice_completion.sql");

    Flyway current = accountFlyway(dataSource, schema, "1041");
    current.migrate();
    current.validate();
    assertThat(current.info().current().getVersion())
        .isEqualTo(MigrationVersion.fromVersion("1041"));

    List<Map<String, Object>> upgradedHistory = flywayHistory(setupDsl);
    assertThat(upgradedHistory).hasSize(parentHistory.size() + 7);
    assertThat(upgradedHistory.subList(0, parentHistory.size()))
        .containsExactlyElementsOf(parentHistory);
    List<Map<String, Object>> addedHistory =
        upgradedHistory.subList(parentHistory.size(), upgradedHistory.size());
    assertThat(addedHistory)
        .extracting(row -> row.get("version"))
        .containsExactly("1037", "1038", "1039", "1040", "1040.1", "1040.2", "1041");
    assertThat(addedHistory)
        .extracting(row -> row.get("script"))
        .containsExactly(
            "V1037__canonical_join_failure_and_attempt_diagnostics.sql",
            "V1038__canonical_join_existing_membership_terminal.sql",
            "V1039__hosting_unclaimed_wallet_settlement.sql",
            "V1040__account_tenant_capacity_owner.sql",
            "V1040.1__account_capacity_origin_decisions.sql",
            "V1040.2__bound_capacity_origin_refusals.sql",
            "V1041__hosting_first_invoice_completion.sql");
    assertThat(addedHistory)
        .allSatisfy(
            row -> {
              assertThat(row.get("checksum")).isNotNull();
              assertThat(row.get("success")).isEqualTo(true);
            });
    assertThat(
            retainedPhysicalRow(
                setupDsl,
                "SELECT to_jsonb(scope_row)::text, scope_row.xmin::text "
                    + "FROM account_connect_scope_records scope_row WHERE scope_token_hash = ?",
                scopeTokenHash))
        .isEqualTo(retainedScope);
    assertThat(
            retainedPhysicalRow(
                setupDsl,
                "SELECT to_jsonb(operation_row)::text, operation_row.xmin::text "
                    + "FROM account_join_operations operation_row WHERE request_id = ?",
                requestId))
        .isEqualTo(retainedIntent);

    List<String> installedConstraints =
        setupDsl
            .fetch(
                "SELECT constraint_row.conname FROM pg_constraint constraint_row "
                    + "JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid "
                    + "JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace "
                    + "WHERE schema_row.nspname = ? AND table_row.relname = 'account_join_operations' "
                    + "AND constraint_row.contype = 'c' AND constraint_row.convalidated",
                schema)
            .getValues(0, String.class);
    assertThat(installedConstraints)
        .contains(
            "account_join_v2_attempt_diagnostics_check",
            "account_join_exact_first_transition_check",
            "account_join_operation_representation_check",
            "account_join_canonical_terminal_proof_check");
    assertThat(
            Objects.requireNonNull(
                    setupDsl.fetchOne(
                        "SELECT count(*) FROM pg_trigger trigger_row "
                            + "JOIN pg_class table_row ON table_row.oid = trigger_row.tgrelid "
                            + "JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace "
                            + "WHERE schema_row.nspname = ? AND table_row.relname = 'account_join_operations' "
                            + "AND trigger_row.tgname = 'account_join_operation_identity_guard' "
                            + "AND trigger_row.tgenabled = 'O' AND NOT trigger_row.tgisinternal",
                        schema))
                .get(0, Long.class))
        .isEqualTo(1L);
    String guardDefinition =
        Objects.requireNonNull(
                setupDsl.fetchOne(
                    "SELECT pg_get_functiondef(?::regprocedure)",
                    schema + ".guard_account_join_operation_identity()"))
            .get(0, String.class);
    assertThat(guardDefinition)
        .contains("ALREADY_ACTIVE", "account_tenant_membership", "last_attempt_failure_code");

    CanonicalJoinOperationEvidence diagnosed =
        transaction.execute(
            status ->
                operations.recordCanonicalAttemptFailure(
                    requestId, scope, callerBinding, "NOT_EVALUATED", "AUTH_UNAVAILABLE"));
    assertThat(diagnosed.status()).isEqualTo("PENDING");
    assertThat(diagnosed.lastAttemptAuthorityAvailability()).isEqualTo("NOT_EVALUATED");
    assertThat(diagnosed.lastAttemptFailureCode()).isEqualTo("AUTH_UNAVAILABLE");
    RetainedPhysicalRow diagnosedIntent =
        retainedPhysicalRow(
            setupDsl,
            "SELECT to_jsonb(operation_row)::text, operation_row.xmin::text "
                + "FROM account_join_operations operation_row WHERE request_id = ?",
            requestId);
    assertThatThrownBy(
            () ->
                setupDsl.execute(
                    "UPDATE account_join_operations SET verified_caller_binding = ? WHERE request_id = ?",
                    "changed-migration-caller",
                    requestId))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
    assertThat(
            retainedPhysicalRow(
                setupDsl,
                "SELECT to_jsonb(operation_row)::text, operation_row.xmin::text "
                    + "FROM account_join_operations operation_row WHERE request_id = ?",
                requestId))
        .isEqualTo(diagnosedIntent);
  }

  @Test
  void expiredScopeCleanupSkipsImmutableV2BeforeLimitingV1Batch() {
    String schema = createSchema("canonical_scope_cleanup_");
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
    Record v2Account =
        setupDsl.fetchOne(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING id, account_uuid",
            "scope-cleanup-v2-account",
            "scope-cleanup-v2@example.test",
            "test-hash");
    long v2AccountId = Objects.requireNonNull(v2Account).get("id", Long.class);
    UUID v2AccountUuid = Objects.requireNonNull(v2Account).get("account_uuid", UUID.class);
    Long v1AccountId =
        Objects.requireNonNull(
                setupDsl.fetchOne(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "scope-cleanup-v1-account",
                    "scope-cleanup-v1@example.test",
                    "test-hash"))
            .get(0, Long.class);

    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(transactionDsl, "prod");
    FreshTenantCreationEvidence tenantSource = freshTenantEvidence();
    transaction.executeWithoutResult(status -> freshTenants.importVerified(tenantSource));
    AccountConnectScopeRepository scopes =
        new AccountConnectScopeRepository(
            transactionDsl, mock(AccountTenantIdentityResolver.class), freshTenants);

    VerifiedJoinScope v1Scope = retainedV1Scope(v1AccountId);
    scopes.insert(v1Scope);
    setupDsl.execute(
        "UPDATE account_connect_scope_records SET connect_scope_expires_at = ? "
            + "WHERE scope_token_hash = ?",
        java.time.Instant.now().minusSeconds(60).toString(),
        AccountJoinDigest.tokenHash(v1Scope.connectScopeId()));

    CanonicalJoinScopeV2 v2Scope =
        new CanonicalJoinScopeV2(
            "scope-cleanup-v2-token",
            v2AccountUuid,
            tenantSource.canonicalTenantId(),
            UUID.fromString("00ae2e76-b686-4a25-9b07-52b8bd3ee147"),
            "canonical-tenant",
            "demo",
            "production",
            UUID.fromString("fc085a0d-1180-4a10-8c54-04378645f0d5"),
            "SHARED",
            UUID.fromString("ce41c1f3-94a2-498c-94a2-20b763d7b3c2"),
            7L,
            3L,
            java.time.Instant.now().minusSeconds(120).toString(),
            java.time.Instant.now().minusSeconds(60).toString());
    VerifiedTenantProvenance v2Provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantSource.operationId(),
            tenantSource.evidenceDigest());
    transaction.executeWithoutResult(
        status -> scopes.insertCanonical(v2AccountId, v2Scope, v2Provenance));

    assertThat(scopes.deleteExpiredUnreferenced(java.time.Instant.now(), 1)).isEqualTo(1);
    assertThat(scopeRowCount(setupDsl, v1Scope.connectScopeId())).isZero();
    assertThat(scopeRowCount(setupDsl, v2Scope.connectScopeId())).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void definitiveFailureRetainsOriginalIntentAndOptionalPolicyAndExactlyReplaysWithoutRewrite(
      boolean bound) {
    JournalFixture f = journalFixture();
    if (bound)
      f.transaction()
          .execute(
              status ->
                  f.operations()
                      .bindCanonicalPolicyEvidence(f.requestId(), f.scope(), f.caller(), true, 23));
    else
      f.transaction()
          .execute(
              status ->
                  f.operations()
                      .recordCanonicalPolicyUnavailable(
                          f.requestId(), f.scope(), f.caller(), "ENTITLEMENT_UNAVAILABLE"));
    var original = f.read();
    String outcome = bound ? "IDEMPOTENCY_CONFLICT" : "CONNECT_SCOPE_INVALID";
    var failed =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .finishCanonicalFailure(f.requestId(), f.scope(), f.caller(), outcome));
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.outcome()).isEqualTo(outcome);
    assertThat(failed.intentDigest()).isEqualTo(original.intentDigest());
    assertThat(failed.scopeEvidence()).isEqualTo(original.scopeEvidence());
    assertThat(failed.requestDigest()).isEqualTo(original.requestDigest());
    assertThat(failed.requestDigestVersion()).isEqualTo(original.requestDigestVersion());
    assertThat(failed.allowPublicJoin()).isEqualTo(original.allowPublicJoin());
    assertThat(failed.entitlementVersion()).isEqualTo(original.entitlementVersion());
    assertThat(failed.entitlementAuthorityAvailability())
        .isEqualTo(bound ? "AVAILABLE" : "NOT_EVALUATED");
    assertThat(failed.lastAttemptFailureCode()).isNull();
    assertThat(failed.membershipId()).isNull();
    assertThat(failed.terminalProof()).isNull();
    String retained = f.bytes();
    var replay =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .finishCanonicalFailure(f.requestId(), f.scope(), f.caller(), outcome));
    assertThat(replay).isEqualTo(failed);
    assertThat(f.bytes()).isEqualTo(retained);
    f.transaction()
        .executeWithoutResult(
            status ->
                assertThat(
                        f.operations().insertCanonicalIntent(f.requestId(), f.scope(), f.caller()))
                    .isFalse());
    if (bound) {
      var policyReplay =
          f.transaction()
              .execute(
                  status ->
                      f.operations()
                          .bindCanonicalPolicyEvidence(
                              f.requestId(), f.scope(), f.caller(), true, 23));
      assertThat(policyReplay).isEqualTo(failed);
    } else {
      assertThatThrownBy(
              () ->
                  f.transaction()
                      .execute(
                          status ->
                              f.operations()
                                  .bindCanonicalPolicyEvidence(
                                      f.requestId(), f.scope(), f.caller(), true, 23)))
          .isInstanceOf(
              AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    }
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status ->
                            f.operations()
                                .finishCanonicalFailure(
                                    f.requestId(),
                                    f.scope(),
                                    f.caller(),
                                    "TENANT_BILLING_BLOCKED")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status ->
                            f.operations()
                                .recordCanonicalPolicyUnavailable(
                                    f.requestId(),
                                    f.scope(),
                                    f.caller(),
                                    "ENTITLEMENT_UNAVAILABLE")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                f.dsl()
                    .execute(
                        "UPDATE account_join_operations SET updated_at = CURRENT_TIMESTAMP WHERE request_id = ?",
                        f.requestId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(f.bytes()).isEqualTo(retained);
    f.assertNoSuccessEffects();
  }

  @Test
  void
      boundPendingUnavailableDiagnosticsNeverDowngradePolicyAndExactRecoveryOnlyClearsDiagnostics() {
    JournalFixture f = journalFixture();
    var bound =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .bindCanonicalPolicyEvidence(
                            f.requestId(), f.scope(), f.caller(), true, 23));
    var unavailable =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .recordCanonicalPolicyUnavailable(
                            f.requestId(), f.scope(), f.caller(), "ENTITLEMENT_UNAVAILABLE"));
    assertThat(unavailable.entitlementAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(unavailable.requestDigest()).isEqualTo(bound.requestDigest());
    assertThat(unavailable.entitlementVersion()).isEqualTo(23);
    assertThat(unavailable.allowPublicJoin()).isTrue();
    assertThat(unavailable.lastAttemptAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(unavailable.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    var targetUnavailable =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .recordCanonicalAttemptFailure(
                            f.requestId(),
                            f.scope(),
                            f.caller(),
                            "NOT_EVALUATED",
                            "AUTH_UNAVAILABLE"));
    assertThat(targetUnavailable.requestDigest()).isEqualTo(bound.requestDigest());
    assertThat(targetUnavailable.lastAttemptFailureCode()).isEqualTo("AUTH_UNAVAILABLE");
    String beforeConflict = f.bytes();
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status ->
                            f.operations()
                                .bindCanonicalPolicyEvidence(
                                    f.requestId(), f.scope(), f.caller(), true, 24)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThat(f.bytes()).isEqualTo(beforeConflict);
    var recovered =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .bindCanonicalPolicyEvidence(
                            f.requestId(), f.scope(), f.caller(), true, 23));
    assertThat(recovered.requestDigest()).isEqualTo(bound.requestDigest());
    assertThat(recovered.entitlementVersion()).isEqualTo(bound.entitlementVersion());
    assertThat(recovered.allowPublicJoin()).isEqualTo(bound.allowPublicJoin());
    assertThat(recovered.lastAttemptAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(recovered.lastAttemptFailureCode()).isNull();
    String recoveredBytes = f.bytes();
    f.transaction()
        .execute(
            status ->
                f.operations()
                    .bindCanonicalPolicyEvidence(f.requestId(), f.scope(), f.caller(), true, 23));
    assertThat(f.bytes()).isEqualTo(recoveredBytes);
    f.assertNoSuccessEffects();
  }

  @Test
  void definitivePublicJoinDenialRetainsItsAlreadyBoundNegativePolicy() {
    JournalFixture f = journalFixture();
    var deniedPolicy =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .bindCanonicalPolicyEvidence(
                            f.requestId(), f.scope(), f.caller(), false, 23));
    var failed =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .finishCanonicalFailure(
                            f.requestId(),
                            f.scope(),
                            f.caller(),
                            "PUBLIC_PRODUCTION_ADMISSION_DENIED"));
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.allowPublicJoin()).isFalse();
    assertThat(failed.entitlementAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(failed.requestDigest()).isEqualTo(deniedPolicy.requestDigest());
    assertThat(failed.entitlementVersion()).isEqualTo(deniedPolicy.entitlementVersion());
    f.assertNoSuccessEffects();
  }

  @Test
  void failedJournalRejectsConflictingCallerRequestAndRetainedScopeWithoutRewritingReceipt() {
    JournalFixture f = journalFixture();
    f.transaction()
        .execute(
            status ->
                f.operations()
                    .finishCanonicalFailure(
                        f.requestId(), f.scope(), f.caller(), "CONNECT_SCOPE_INVALID"));
    String retained = f.bytes();
    var alternate =
        canonicalScope("different-canonical-bearer", f.scope().accountId(), f.scope().tenantId());
    f.transaction()
        .executeWithoutResult(
            status ->
                f.scopes()
                    .insertCanonical(
                        f.accountId(), alternate, f.read().scopeEvidence().tenantProvenance()));
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status ->
                            f.operations()
                                .finishCanonicalFailure(
                                    f.requestId(),
                                    f.scope(),
                                    "different-caller",
                                    "CONNECT_SCOPE_INVALID")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status ->
                            f.operations()
                                .finishCanonicalFailure(
                                    "different-global-request",
                                    f.scope(),
                                    f.caller(),
                                    "CONNECT_SCOPE_INVALID")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status ->
                            f.operations()
                                .finishCanonicalFailure(
                                    f.requestId(), alternate, f.caller(), "CONNECT_SCOPE_INVALID")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThat(f.bytes()).isEqualTo(retained);
  }

  @Test
  void sqlGuardsRefuseRetryableTerminalOutcomesPolicyDowngradeInventedProofAndTerminalMutation() {
    JournalFixture f = journalFixture();
    String original = f.bytes();
    for (String change :
        java.util.List.of(
            "status = 'FAILED', outcome = 'ENTITLEMENT_UNAVAILABLE'",
            "status = 'FAILED', outcome = 'CONNECT_SCOPE_INVALID', membership_id = 1",
            "status = 'COMMITTED', outcome = 'JOINED'",
            "intent_digest = 'sha256:" + "b".repeat(64) + "'")) {
      assertThatThrownBy(
              () ->
                  f.dsl()
                      .execute(
                          "UPDATE account_join_operations SET " + change + " WHERE request_id = ?",
                          f.requestId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(f.bytes()).isEqualTo(original);
    }
    f.transaction()
        .execute(
            status ->
                f.operations()
                    .bindCanonicalPolicyEvidence(f.requestId(), f.scope(), f.caller(), true, 23));
    String bound = f.bytes();
    for (String change :
        java.util.List.of(
            "entitlement_authority_availability = 'UNAVAILABLE'",
            "entitlement_version = 24",
            "request_digest = NULL",
            "last_attempt_authority_availability = 'UNAVAILABLE', last_attempt_failure_code = NULL")) {
      assertThatThrownBy(
              () ->
                  f.dsl()
                      .execute(
                          "UPDATE account_join_operations SET " + change + " WHERE request_id = ?",
                          f.requestId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(f.bytes()).isEqualTo(bound);
    }
    f.transaction()
        .execute(
            status ->
                f.operations()
                    .finishCanonicalFailure(
                        f.requestId(), f.scope(), f.caller(), "CONNECT_SCOPE_INVALID"));
    String terminal = f.bytes();
    assertThatThrownBy(
            () ->
                f.dsl()
                    .execute(
                        "DELETE FROM account_join_operations WHERE request_id = ?", f.requestId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(() -> f.dsl().execute("TRUNCATE account_join_operations"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(f.bytes()).isEqualTo(terminal);
    f.assertNoSuccessEffects();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void journalDiagnosticsAndDefinitiveFailureRollBackAsOneTransaction(boolean bound) {
    JournalFixture f = journalFixture();
    if (bound)
      f.transaction()
          .execute(
              status ->
                  f.operations()
                      .bindCanonicalPolicyEvidence(f.requestId(), f.scope(), f.caller(), true, 23));
    String original = f.bytes();
    assertThatThrownBy(
            () ->
                f.transaction()
                    .execute(
                        status -> {
                          f.operations()
                              .recordCanonicalPolicyUnavailable(
                                  f.requestId(), f.scope(), f.caller(), "ENTITLEMENT_UNAVAILABLE");
                          f.operations()
                              .finishCanonicalFailure(
                                  f.requestId(), f.scope(), f.caller(), "CONNECT_SCOPE_INVALID");
                          throw new IllegalStateException("controlled canonical journal rollback");
                        }))
        .hasMessageContaining("controlled canonical journal rollback");
    assertThat(f.bytes()).isEqualTo(original);
    var failed =
        f.transaction()
            .execute(
                status ->
                    f.operations()
                        .finishCanonicalFailure(
                            f.requestId(), f.scope(), f.caller(), "CONNECT_SCOPE_INVALID"));
    assertThat(failed.status()).isEqualTo("FAILED");
    f.assertNoSuccessEffects();
  }

  private JournalFixture journalFixture() {
    String schema = createSchema("canonical_join_scope_");
    var source = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext setup = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext owner = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    var account =
        Objects.requireNonNull(
            setup.fetchOne(
                "INSERT INTO accounts (username, email, password_hash) VALUES ('journal-player', 'journal@example.test', 'controlled-hash') RETURNING id, account_uuid"));
    long privateAccountId = account.get("id", Long.class);
    UUID accountId = account.get("account_uuid", UUID.class);
    var fresh = new FreshTenantIdentityAssociationRepository(owner, "prod");
    var tenant = freshTenantEvidence();
    transaction.executeWithoutResult(status -> fresh.importVerified(tenant));
    var scopes =
        new AccountConnectScopeRepository(owner, mock(AccountTenantIdentityResolver.class), fresh);
    var operations = new AccountJoinOperationRepository(owner, scopes);
    var scope = canonicalScope(accountId, tenant.canonicalTenantId());
    String requestId = "journal-" + UUID.randomUUID();
    String caller = "controlled-journal-caller";
    transaction.executeWithoutResult(
        status -> {
          scopes.insertCanonical(
              privateAccountId,
              scope,
              new VerifiedTenantProvenance(
                  null,
                  TenantProvenanceKind.FRESH_GAME_DESIGN,
                  tenant.operationId(),
                  tenant.evidenceDigest()));
          operations.insertCanonicalIntent(requestId, scope, caller);
        });
    return new JournalFixture(
        setup, transaction, operations, scopes, scope, privateAccountId, requestId, caller);
  }

  /** Physical journal storage proof only; supplied caller/scope selectors do not authenticate. */
  private record JournalFixture(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountJoinOperationRepository operations,
      AccountConnectScopeRepository scopes,
      CanonicalJoinScopeV2 scope,
      long accountId,
      String requestId,
      String caller) {
    private CanonicalJoinOperationEvidence read() {
      return Objects.requireNonNull(
          transaction.execute(
              status -> operations.findCanonicalEvidenceByRequestId(requestId).orElseThrow()));
    }

    private String bytes() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT row_to_json(j)::text FROM account_join_operations j WHERE request_id = ?",
                  requestId))
          .get(0, String.class);
    }

    private void assertNoSuccessEffects() {
      for (String table :
          java.util.List.of(
              "account_tenant_membership",
              "account_authority_outbox_events",
              "account_audit_outbox")) assertThat(dsl.fetchCount(DSL.table(table))).isZero();
      assertThat(read().membershipId()).isNull();
      assertThat(read().terminalProof()).isNull();
    }
  }

  private static Flyway accountFlyway(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration", "classpath:db/migration/saga");
    if (targetVersion != null) {
      configuration.target(MigrationVersion.fromVersion(targetVersion));
    }
    return configuration.load();
  }

  private static List<Map<String, Object>> flywayHistory(DSLContext dsl) {
    return dsl.fetch(
            "SELECT installed_rank, version, description, type, script, checksum, installed_by, "
                + "installed_on, execution_time, success FROM flyway_schema_history "
                + "ORDER BY installed_rank")
        .intoMaps();
  }

  private static RetainedPhysicalRow retainedPhysicalRow(
      DSLContext dsl, String query, Object... bindings) {
    Record row = Objects.requireNonNull(dsl.fetchOne(query, bindings));
    return new RetainedPhysicalRow(row.get(0, String.class), row.get(1, String.class));
  }

  private record RetainedPhysicalRow(String rowJson, String xmin) {}

  private static long scopeRowCount(DSLContext dsl, String connectScopeId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM account_connect_scope_records WHERE scope_token_hash = ?",
                AccountJoinDigest.tokenHash(connectScopeId)))
        .get(0, Long.class);
  }

  private String createSchema(String prefix) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    if (!(schema.matches("canonical_join_scope_[a-f0-9]{32}")
        || schema.matches("canonical_scope_cleanup_[a-f0-9]{32}"))) {
      throw new IllegalStateException("Generated PostgreSQL schema name is invalid");
    }
    schemas.add(schema);
    return schema;
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
