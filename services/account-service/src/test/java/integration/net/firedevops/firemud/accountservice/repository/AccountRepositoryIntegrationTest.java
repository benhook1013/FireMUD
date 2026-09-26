package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountRepositoryIntegrationTest {
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String MIGRATION_PROOF_SCHEMA = "account_migration_proof";
  private static final String COLLISION_MIGRATION_PROOF_SCHEMA =
      "account_migration_collision_proof";
  private static final String PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA =
      "account_profile_identity_migration_proof";
  private static final String GLOBAL_REGISTRATION_MIGRATION_PROOF_SCHEMA =
      "account_global_registration_migration_proof";
  private static final String ACCOUNT_UUID_MIGRATION_PROOF_SCHEMA = "account_uuid_migration_proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private AccountRepository repository;

  @BeforeAll
  void setUpRepository() {
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new AccountRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute("TRUNCATE TABLE account_audit_outbox");
    dsl.execute("TRUNCATE TABLE accounts RESTART IDENTITY CASCADE");
  }

  @Test
  void joinIntentCommitsBeforePolicyAndMembershipAuditOutcomeCommitAtomically() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    DSLContext transactionAwareDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    AccountJoinOperationRepository joinOperations =
        new AccountJoinOperationRepository(transactionAwareDsl);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(transactionAwareDsl);
    AccountAuditOutboxRepository outbox = new AccountAuditOutboxRepository(transactionAwareDsl);
    long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "two-phase-join",
                "two-phase-join@example.com",
                "hash"));
    VerifiedJoinScope scope = joinScope(accountId);
    String callerBinding = "bootstrap-jti-two-phase";
    String requestId = "join-two-phase-1";
    String intentDigest = AccountJoinDigest.intent(requestId, scope, callerBinding);

    transaction.executeWithoutResult(
        status -> joinOperations.insertIntent(requestId, scope, callerBinding, intentDigest));

    assertThat(joinOperation(joinOperations, requestId).status()).isEqualTo("PENDING");
    assertThat(joinOperation(joinOperations, requestId).realmId()).isEqualTo(REALM_ID);
    assertThat(joinOperation(joinOperations, requestId).outcome()).isNull();
    assertThat(joinOperation(joinOperations, requestId).intentDigest()).isEqualTo(intentDigest);
    assertThat(
            jdbc.queryForObject(
                "SELECT realm_id FROM account_join_operations WHERE request_id = ?",
                UUID.class,
                requestId))
        .isEqualTo(REALM_ID);
    assertThat(joinOperation(joinOperations, requestId).requestDigest()).isNull();
    assertThat(joinOperation(joinOperations, requestId).entitlementVersion()).isNull();
    assertThat(joinOperation(joinOperations, requestId).allowPublicJoin()).isNull();

    String policyDigest = AccountJoinDigest.request(scope, callerBinding, true, 1L);
    UUID auditEventId = UUID.randomUUID();
    Account account = new Account();
    account.setId(accountId);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        commitJoinOutcome(
                            joinOperations,
                            memberships,
                            outbox,
                            account,
                            requestId,
                            scope,
                            policyDigest,
                            auditEventId,
                            true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulate second-transaction rollback");

    assertThat(joinOperation(joinOperations, requestId).status()).isEqualTo("PENDING");
    assertThat(joinOperation(joinOperations, requestId).outcome()).isNull();
    assertThat(joinOperation(joinOperations, requestId).requestDigest()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                Long.class,
                accountId,
                scope.tenantId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ?",
                Long.class,
                auditEventId))
        .isZero();

    transaction.executeWithoutResult(
        status ->
            commitJoinOutcome(
                joinOperations,
                memberships,
                outbox,
                account,
                requestId,
                scope,
                policyDigest,
                auditEventId,
                false));

    assertThat(joinOperation(joinOperations, requestId).status()).isEqualTo("COMMITTED");
    assertThat(joinOperation(joinOperations, requestId).outcome()).isEqualTo("JOINED");
    assertThat(joinOperation(joinOperations, requestId).intentDigest()).isEqualTo(intentDigest);
    assertThat(joinOperation(joinOperations, requestId).requestDigest()).isEqualTo(policyDigest);
    assertThat(joinOperation(joinOperations, requestId).entitlementVersion()).isEqualTo(1L);
    assertThat(joinOperation(joinOperations, requestId).allowPublicJoin()).isEqualTo(true);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ? AND lifecycle_state = 'ACTIVE' AND authority_provenance = 'EXPLICIT_JOIN'",
                Long.class,
                accountId,
                scope.tenantId()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND delivery_status = 'PENDING'",
                Long.class,
                auditEventId))
        .isEqualTo(1L);
  }

  @Test
  void minimizedAuditReceiptClearsPayloadAndRetainsCompactIdentity() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AccountAuditOutboxRepository outbox = new AccountAuditOutboxRepository(dsl);
    UUID minimizedEventId = UUID.randomUUID();
    String minimizedPayload = "{\"accountId\":42,\"requestId\":\"join-42\"}";
    String minimizedDigest = AccountAuditDigest.ofPayload(minimizedPayload);
    UUID committedEventId = UUID.randomUUID();
    String committedPayload = "{\"accountId\":43}";

    outbox.append(
        minimizedEventId, "tenant", 7L, "ACCOUNT_JOINED_PUBLIC_PRODUCTION", minimizedPayload);
    outbox.markDelivered(minimizedEventId, "receipt-minimized", "log-minimized", true);
    outbox.append(committedEventId, "platform", null, "ACCOUNT_REGISTERED", committedPayload);
    outbox.markDelivered(committedEventId, "receipt-committed", "log-committed", false);

    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                minimizedEventId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT scope FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                minimizedEventId))
        .isEqualTo("tenant");
    assertThat(
            jdbc.queryForObject(
                "SELECT audit_event_id FROM account_audit_outbox WHERE audit_event_id = ?",
                UUID.class,
                minimizedEventId))
        .isEqualTo(minimizedEventId);
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM account_audit_outbox WHERE audit_event_id = ?",
                Long.class,
                minimizedEventId))
        .isEqualTo(7L);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload_digest FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                minimizedEventId))
        .isEqualTo(minimizedDigest);
    assertThat(
            jdbc.queryForObject(
                "SELECT receiver_receipt_id FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                minimizedEventId))
        .isEqualTo("receipt-minimized");
    assertThat(
            jdbc.queryForObject(
                "SELECT receiver_log_event_id FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                minimizedEventId))
        .isEqualTo("log-minimized");
    assertThat(
            jdbc.queryForObject(
                "SELECT delivery_status FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                minimizedEventId))
        .isEqualTo("MINIMIZED");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                committedEventId))
        .isEqualTo(committedPayload);
    assertThat(
            jdbc.queryForObject(
                "SELECT delivery_status FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                committedEventId))
        .isEqualTo("COMMITTED");
    assertThat(outbox.pending(10))
        .noneMatch(envelope -> envelope.auditEventId().equals(minimizedEventId))
        .noneMatch(envelope -> envelope.auditEventId().equals(committedEventId));
  }

  @Test
  void connectScopeRepositoryRetainsCanonicalRealmUuidAndDetectsTampering() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "scope-uuid",
                "scope-uuid@example.com",
                "hash"));
    AccountConnectScopeRepository scopes = new AccountConnectScopeRepository(dsl);
    VerifiedJoinScope scope = joinScope(accountId);

    scopes.insert(scope);

    assertThat(scopes.find(scope.connectScopeId()).orElseThrow().realmId()).isEqualTo(REALM_ID);
    UUID changedRealmId = UUID.fromString("57c58f36-c5ea-4aa8-8ef7-91a45e407f01");
    jdbc.update(
        "UPDATE account_connect_scope_records SET realm_id = ? WHERE scope_token_hash = ?",
        changedRealmId,
        AccountJoinDigest.tokenHash(scope.connectScopeId()));

    assertThatThrownBy(() -> scopes.find(scope.connectScopeId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JOIN scope digest mismatch");
  }

  private void commitJoinOutcome(
      AccountJoinOperationRepository joinOperations,
      AccountTenantMembershipRepository memberships,
      AccountAuditOutboxRepository outbox,
      Account account,
      String requestId,
      VerifiedJoinScope scope,
      String policyDigest,
      UUID auditEventId,
      boolean simulateRollback) {
    joinOperations.bindPolicyEvidence(requestId, policyDigest, 1L, true);
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setAccount(account);
    membership.setTenantId(scope.tenantId());
    membership.setGameplayAdmissionAllowed(true);
    membership.setLifecycleState("ACTIVE");
    membership.setMembershipVersion(1L);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    memberships.save(membership);
    outbox.append(
        auditEventId,
        "tenant",
        scope.tenantId(),
        "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
        "{\"requestId\":\"" + requestId + "\"}");
    joinOperations.finish(
        requestId,
        "COMMITTED",
        "JOINED",
        membership.getId(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration());
    if (simulateRollback) {
      throw new IllegalStateException("simulate second-transaction rollback");
    }
  }

  private AccountJoinOperationRepository.JoinOperation joinOperation(
      AccountJoinOperationRepository joinOperations, String requestId) {
    return joinOperations.find(requestId).orElseThrow();
  }

  private static VerifiedJoinScope joinScope(long accountId) {
    VerifiedJoinScope base =
        new VerifiedJoinScope(
            "two-phase-connect-scope",
            accountId,
            7L,
            REALM_ID,
            "demo",
            "production",
            "namespace-44",
            "SHARED",
            44L,
            23L,
            17L,
            "2026-09-24T10:00:00Z",
            "2026-09-24T10:02:00Z",
            "unused");
    String digest = AccountJoinDigest.scope(base);
    return new VerifiedJoinScope(
        base.connectScopeId(),
        base.accountId(),
        base.tenantId(),
        base.realmId(),
        base.worldSlug(),
        base.realmSlug(),
        base.playableStateNamespaceId(),
        base.playableStateScope(),
        base.gameInstanceId(),
        base.catalogRevision(),
        base.pointerVersion(),
        base.evaluatedAt(),
        base.connectScopeExpiresAt(),
        digest);
  }

  @ParameterizedTest
  @EnumSource(
      value = AccountLifecycleState.class,
      names = {"SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED"})
  void genericUpdatePreservesProtectedLifecycleState(AccountLifecycleState lifecycleState) {
    Account persisted = account("original", "original@example.com", lifecycleState);
    Account saved = repository.save(persisted);

    Account staleUpdate = account("updated", "updated@example.com", AccountLifecycleState.ACTIVE);
    staleUpdate.setId(saved.getId());
    repository.save(staleUpdate);

    Account loaded = repository.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getUsername()).isEqualTo("updated");
    assertThat(loaded.getLifecycleState()).isEqualTo(lifecycleState);
  }

  @Test
  void saveCanonicalizesEmail() {
    Account saved =
        repository.save(
            account("canonical", "  Player@Example.COM ", AccountLifecycleState.ACTIVE));

    assertThat(saved.getEmail()).isEqualTo("player@example.com");
    assertThat(repository.findByEmail("  PLAYER@EXAMPLE.COM ")).isPresent();
  }

  @Test
  void repositoryPersistsAndReadsBackUniqueAccountUuidAndProvenance() {
    Account saved =
        repository.save(
            account("uuid-account", "uuid-account@example.com", AccountLifecycleState.ACTIVE));
    UUID accountUuid = saved.getAccountUuid();

    assertThat(accountUuid).isNotNull();
    assertThat(saved.getAccountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(saved.getAccountUuidSourceNumericId()).isEqualTo(saved.getId());

    Account foundByNumericId = repository.findById(saved.getId()).orElseThrow();
    Account foundByUuid = repository.findByAccountUuid(accountUuid).orElseThrow();
    assertThat(foundByNumericId.getAccountUuid()).isEqualTo(accountUuid);
    assertThat(foundByUuid.getId()).isEqualTo(saved.getId());
    assertThat(foundByUuid.getAccountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(foundByUuid.getAccountUuidSourceNumericId()).isEqualTo(saved.getId());

    saved.setUsername("uuid-account-updated");
    Account updated = repository.save(saved);
    assertThat(updated.getAccountUuid()).isEqualTo(accountUuid);
    assertThat(updated.getAccountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(updated.getAccountUuidSourceNumericId()).isEqualTo(updated.getId());

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO accounts "
                        + "(username, email, password_hash, account_uuid, account_uuid_provenance) "
                        + "VALUES (?, ?, ?, ?, ?)",
                    "duplicate-uuid-account",
                    "duplicate-uuid-account@example.com",
                    "hash",
                    accountUuid,
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT.name()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("accounts_account_uuid_unique");

    saved.setAccountUuid(UUID.randomUUID());
    assertThatThrownBy(() -> repository.save(saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Failed to update accounts id=");
    assertThat(repository.findById(saved.getId()).orElseThrow().getAccountUuid())
        .isEqualTo(accountUuid);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void saveRejectsMissingEmailBeforeStorage(String email) {
    assertThatThrownBy(
            () -> repository.save(account("missing-email", email, AccountLifecycleState.ACTIVE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flywayCanonicalizesLegacyEmailAndRejectsNonCanonicalValues() {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(MIGRATION_PROOF_SCHEMA)
        .defaultSchema(MIGRATION_PROOF_SCHEMA)
        .target("21")
        .load()
        .migrate();

    dsl.execute(
        "INSERT INTO "
            + MIGRATION_PROOF_SCHEMA
            + ".accounts (username, email, password_hash) "
            + "VALUES ('legacy-migration', ' Legacy@Example.COM ', 'hash')");

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(MIGRATION_PROOF_SCHEMA)
        .defaultSchema(MIGRATION_PROOF_SCHEMA)
        .load()
        .migrate();

    assertThat(
            dsl.fetchValue(
                "SELECT email FROM "
                    + MIGRATION_PROOF_SCHEMA
                    + ".accounts WHERE username = 'legacy-migration'"))
        .isEqualTo("legacy@example.com");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO "
                        + MIGRATION_PROOF_SCHEMA
                        + ".accounts (username, email, password_hash) "
                        + "VALUES ('noncanonical', ' Another@Example.COM ', 'hash')"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void flywayRejectsCanonicalEmailCollisionsBeforeRewriting() {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(COLLISION_MIGRATION_PROOF_SCHEMA)
        .defaultSchema(COLLISION_MIGRATION_PROOF_SCHEMA)
        .target("21")
        .load()
        .migrate();

    dsl.execute(
        "INSERT INTO "
            + COLLISION_MIGRATION_PROOF_SCHEMA
            + ".accounts (username, email, password_hash) "
            + "VALUES ('collision-first', 'Player@Example.COM', 'hash')");
    dsl.execute(
        "INSERT INTO "
            + COLLISION_MIGRATION_PROOF_SCHEMA
            + ".accounts (username, email, password_hash) "
            + "VALUES ('collision-second', ' player@example.com ', 'hash')");

    assertThatThrownBy(
            () ->
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations(MIGRATION_LOCATION)
                    .schemas(COLLISION_MIGRATION_PROOF_SCHEMA)
                    .defaultSchema(COLLISION_MIGRATION_PROOF_SCHEMA)
                    .load()
                    .migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("accounts_email_canonicalization_collision");

    assertThat(
            dsl.fetchValue(
                "SELECT email FROM "
                    + COLLISION_MIGRATION_PROOF_SCHEMA
                    + ".accounts WHERE username = 'collision-first'"))
        .isEqualTo("Player@Example.COM");
    assertThat(
            dsl.fetchValue(
                "SELECT email FROM "
                    + COLLISION_MIGRATION_PROOF_SCHEMA
                    + ".accounts WHERE username = 'collision-second'"))
        .isEqualTo(" player@example.com ");
  }

  @Test
  void profilesAllowOneGlobalAccountAcrossTenantsButRejectDuplicateTenantIdentity() {
    Number accountIdValue =
        Objects.requireNonNull(
            (Number)
                dsl.fetchValue(
                    "INSERT INTO accounts (username, email, password_hash) "
                        + "VALUES ('profile-owner', 'profile-owner@example.com', 'hash') "
                        + "RETURNING id"));
    long accountId = accountIdValue.longValue();

    dsl.execute(
        "INSERT INTO profiles (account_id, tenant_id, display_name) VALUES (?, ?, ?)",
        accountId,
        101L,
        "Tenant One");
    dsl.execute(
        "INSERT INTO profiles (account_id, tenant_id, display_name) VALUES (?, ?, ?)",
        accountId,
        202L,
        "Tenant Two");

    assertThat(
            dsl.resultQuery("SELECT COUNT(*) FROM profiles WHERE account_id = ?", accountId)
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO profiles (account_id, tenant_id, display_name) "
                        + "VALUES (?, ?, ?)",
                    accountId,
                    101L,
                    "Tenant One Duplicate"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("profiles_tenant_account_unique");
  }

  @Test
  void flywayRejectsRetainedDuplicateProfileTenantIdentityBeforeAddingConstraint() {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA)
        .defaultSchema(PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA)
        .target("24")
        .load()
        .migrate();

    Number accountIdValue =
        Objects.requireNonNull(
            (Number)
                dsl.fetchValue(
                    "INSERT INTO "
                        + PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA
                        + ".accounts (username, email, password_hash) "
                        + "VALUES ('duplicate-profile-owner', 'duplicate-profile-owner@example.com', 'hash') "
                        + "RETURNING id"));
    long accountId = accountIdValue.longValue();
    dsl.execute(
        "INSERT INTO "
            + PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA
            + ".profiles (account_id, tenant_id, display_name) VALUES (?, ?, ?)",
        accountId,
        303L,
        "Retained First");
    dsl.execute(
        "INSERT INTO "
            + PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA
            + ".profiles (account_id, tenant_id, display_name) VALUES (?, ?, ?)",
        accountId,
        303L,
        "Retained Duplicate");

    assertThatThrownBy(
            () ->
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations(MIGRATION_LOCATION)
                    .schemas(PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA)
                    .defaultSchema(PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA)
                    .load()
                    .migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("profiles_tenant_account_identity_collision");

    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM "
                        + PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA
                        + ".profiles WHERE account_id = ? AND tenant_id = ?",
                    accountId,
                    303L)
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
  }

  @Test
  void globalRegistrationMigrationPreservesAndQuarantinesUnprovenTenantRows() {
    String schema = GLOBAL_REGISTRATION_MIGRATION_PROOF_SCHEMA;
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("25")
        .load()
        .migrate();

    long legacyAccountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO "
                            + schema
                            + ".accounts (username, email, password_hash, tenant_id) "
                            + "VALUES ('legacy-global', 'legacy-global@example.com', 'hash', 1) RETURNING id"))
            .longValue();
    dsl.execute(
        "INSERT INTO " + schema + ".profiles (account_id, tenant_id) VALUES (?, ?)",
        legacyAccountId,
        7L);
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_tenant_membership (account_id, tenant_id, gameplay_admission_allowed) "
            + "VALUES (?, ?, TRUE)",
        legacyAccountId,
        7L);

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();

    assertThat(
            dsl.fetchValue(
                "SELECT tenant_id FROM " + schema + ".accounts WHERE id = ?", legacyAccountId))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchValue(
                "SELECT disposition FROM "
                    + schema
                    + ".account_legacy_tenant_sources "
                    + "WHERE account_id = ?",
                legacyAccountId))
        .isEqualTo("TENANT_MISMATCH");
    assertThat(
            dsl.fetchValue(
                "SELECT original_gameplay_admission_allowed FROM "
                    + schema
                    + ".account_legacy_membership_sources WHERE account_id = ?",
                legacyAccountId))
        .isEqualTo(true);
    assertThat(
            dsl.fetchValue(
                "SELECT gameplay_admission_allowed FROM "
                    + schema
                    + ".account_tenant_membership WHERE account_id = ?",
                legacyAccountId))
        .isEqualTo(false);
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM " + schema + ".profiles WHERE account_id = ?",
                legacyAccountId))
        .isEqualTo(1L);

    long newAccountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO "
                            + schema
                            + ".accounts (username, email, password_hash) "
                            + "VALUES ('new-global', 'new-global@example.com', 'hash') RETURNING id"))
            .longValue();
    assertThat(
            dsl.fetchValue(
                "SELECT tenant_id FROM " + schema + ".accounts WHERE id = ?", newAccountId))
        .isNull();
    assertThat(
            dsl.fetchValue("SELECT role FROM " + schema + ".accounts WHERE id = ?", newAccountId))
        .isNull();
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM "
                    + schema
                    + ".account_tenant_membership WHERE account_id = ?",
                newAccountId))
        .isEqualTo(0L);
  }

  @Test
  void accountUuidMigrationPreservesRetainedRowsAndRecordsExactSourceIdentity() {
    String schema = ACCOUNT_UUID_MIGRATION_PROOF_SCHEMA;
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("28")
        .load()
        .migrate();

    long firstAccountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO "
                            + schema
                            + ".accounts (username, email, password_hash, tenant_id) "
                            + "VALUES ('uuid-retained-one', 'uuid-retained-one@example.com', 'hash-one', 41) "
                            + "RETURNING id"))
            .longValue();
    long secondAccountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO "
                            + schema
                            + ".accounts (username, email, password_hash, tenant_id) "
                            + "VALUES ('uuid-retained-two', 'uuid-retained-two@example.com', 'hash-two', 42) "
                            + "RETURNING id"))
            .longValue();
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".profiles (account_id, tenant_id, display_name, bio) VALUES (?, ?, ?, ?)",
        firstAccountId,
        41L,
        "Retained profile",
        "retained bio");
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED')",
        firstAccountId,
        41L);
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, 42, TRUE, 'ACTIVE', 2, 3, 'EXPLICIT_JOIN')",
        secondAccountId);
    long secondMembershipId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "SELECT id FROM "
                            + schema
                            + ".account_tenant_membership WHERE account_id = ? AND tenant_id = 42",
                        secondAccountId))
            .longValue();

    UUID joinRealmId = UUID.fromString("61d40f5d-2d59-4cc5-8774-54f7445d144b");
    String pendingJoinRequestId = "retained-v28-pending-join";
    String scopeDigest = AccountJoinDigest.tokenHash("retained-connect-scope");
    String intentDigest = AccountJoinDigest.tokenHash(pendingJoinRequestId + ":intent");
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_join_operations "
            + "(request_id, account_id, tenant_id, verified_caller_binding, scope_token_hash, "
            + "connect_scope_digest, world_slug, realm_slug, realm_id, playable_state_namespace_id, "
            + "playable_state_scope, game_instance_id, catalog_revision, pointer_version, "
            + "intent_digest_version, intent_digest, caller_bound_authority_invalidated, status) "
            + "VALUES (?, ?, 42, 'retained-caller', ?, ?, 'retained-world', 'retained-realm', ?, "
            + "'retained-namespace', 'REALM', 7, 11, 13, 1, ?, FALSE, 'PENDING')",
        pendingJoinRequestId,
        secondAccountId,
        AccountJoinDigest.tokenHash("retained-scope-token"),
        scopeDigest,
        joinRealmId,
        intentDigest);

    String receiptStreamKey =
        "account:membership-transition-receipt:v1:membership/" + secondAccountId + "/42";
    String receiptRequestId = "retained-v28-membership-transition";
    UUID receiptId = UUID.fromString("92b9c1a4-2840-4c48-9622-782b9aa83a07");
    String receiptPayload = "retained membership transition receipt";
    String receiptDigest = AccountAuditDigest.ofPayload(receiptPayload);
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_membership_transition_receipt_stream_heads "
            + "(account_id, tenant_id, receipt_stream_key, last_receipt_sequence) "
            + "VALUES (?, 42, ?, 1)",
        secondAccountId,
        receiptStreamKey);
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_membership_transition_receipts "
            + "(receipt_stream_key, receipt_sequence, account_id, tenant_id, evidence_status, "
            + "transition_type, request_id, membership_id, membership_lifecycle_state, "
            + "gameplay_admission_allowed, membership_version, membership_authority_generation, "
            + "authority_provenance, receipt_id, receipt_digest) "
            + "VALUES (?, 1, ?, 42, 'PROVISIONAL_TRANSITION_RECEIPT', 'MEMBERSHIP_JOINED', ?, ?, "
            + "'ACTIVE', TRUE, 2, 3, 'EXPLICIT_JOIN', ?, ?)",
        receiptStreamKey,
        secondAccountId,
        receiptRequestId,
        secondMembershipId,
        receiptId,
        receiptDigest);

    UUID auditEventId = UUID.fromString("1596dcce-a52c-4c39-86bf-138a06069012");
    String payload = "{\"accountId\":" + firstAccountId + ",\"event\":\"retained\"}";
    String payloadDigest = AccountAuditDigest.ofPayload(payload);
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_audit_outbox "
            + "(audit_event_id, scope, producer_service, event_type, occurred_at, schema_version, "
            + "payload_digest_version, payload_digest, payload) "
            + "VALUES (?, 'platform', 'account-service', 'ACCOUNT_REGISTERED', "
            + "TIMESTAMP '2026-09-01 12:00:00', 1, 1, ?, ?)",
        auditEventId,
        payloadDigest,
        payload);

    String firstAccountBefore =
        jsonRow(
            "SELECT to_jsonb(a)::text FROM " + schema + ".accounts a WHERE id = ?", firstAccountId);
    String secondAccountBefore =
        jsonRow(
            "SELECT to_jsonb(a)::text FROM " + schema + ".accounts a WHERE id = ?",
            secondAccountId);
    String profileBefore =
        jsonRow(
            "SELECT to_jsonb(p)::text FROM " + schema + ".profiles p WHERE account_id = ?",
            firstAccountId);
    String membershipBefore =
        jsonRow(
            "SELECT to_jsonb(m)::text FROM "
                + schema
                + ".account_tenant_membership m WHERE account_id = ?",
            firstAccountId);
    String explicitMembershipBefore =
        jsonRow(
            "SELECT to_jsonb(m)::text FROM "
                + schema
                + ".account_tenant_membership m WHERE account_id = ?",
            secondAccountId);
    String pendingJoinBefore =
        jsonRow(
            "SELECT to_jsonb(j)::text FROM "
                + schema
                + ".account_join_operations j WHERE request_id = ?",
            pendingJoinRequestId);
    String receiptStreamHeadBefore =
        jsonRow(
            "SELECT to_jsonb(h)::text FROM "
                + schema
                + ".account_membership_transition_receipt_stream_heads h "
                + "WHERE account_id = ? AND tenant_id = 42",
            secondAccountId);
    String membershipTransitionReceiptBefore =
        jsonRow(
            "SELECT to_jsonb(r)::text FROM "
                + schema
                + ".account_membership_transition_receipts r WHERE receipt_id = ?",
            receiptId);
    String outboxBefore =
        jsonRow(
            "SELECT to_jsonb(o)::text FROM "
                + schema
                + ".account_audit_outbox o WHERE audit_event_id = ?",
            auditEventId);

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();

    String accountProjection =
        "(to_jsonb(a) - 'account_uuid' - 'account_uuid_provenance' "
            + "- 'account_uuid_source_numeric_id')::text";
    assertThat(
            jsonRow(
                "SELECT " + accountProjection + " FROM " + schema + ".accounts a WHERE id = ?",
                firstAccountId))
        .isEqualTo(firstAccountBefore);
    assertThat(
            jsonRow(
                "SELECT " + accountProjection + " FROM " + schema + ".accounts a WHERE id = ?",
                secondAccountId))
        .isEqualTo(secondAccountBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(p)::text FROM " + schema + ".profiles p WHERE account_id = ?",
                firstAccountId))
        .isEqualTo(profileBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(m)::text FROM "
                    + schema
                    + ".account_tenant_membership m WHERE account_id = ?",
                firstAccountId))
        .isEqualTo(membershipBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(m)::text FROM "
                    + schema
                    + ".account_tenant_membership m WHERE account_id = ?",
                secondAccountId))
        .isEqualTo(explicitMembershipBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(j)::text FROM "
                    + schema
                    + ".account_join_operations j WHERE request_id = ?",
                pendingJoinRequestId))
        .isEqualTo(pendingJoinBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(h)::text FROM "
                    + schema
                    + ".account_membership_transition_receipt_stream_heads h "
                    + "WHERE account_id = ? AND tenant_id = 42",
                secondAccountId))
        .isEqualTo(receiptStreamHeadBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(r)::text FROM "
                    + schema
                    + ".account_membership_transition_receipts r WHERE receipt_id = ?",
                receiptId))
        .isEqualTo(membershipTransitionReceiptBefore);
    assertThat(
            jsonRow(
                "SELECT to_jsonb(o)::text FROM "
                    + schema
                    + ".account_audit_outbox o WHERE audit_event_id = ?",
                auditEventId))
        .isEqualTo(outboxBefore);

    UUID firstUuid =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT account_uuid FROM " + schema + ".accounts WHERE id = ?", firstAccountId)
                .fetchOne(0, UUID.class));
    UUID secondUuid =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT account_uuid FROM " + schema + ".accounts WHERE id = ?",
                    secondAccountId)
                .fetchOne(0, UUID.class));
    assertThat(firstUuid).isNotEqualTo(secondUuid);
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_provenance FROM " + schema + ".accounts WHERE id = ?",
                    firstAccountId)
                .fetchOne(0, String.class))
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION.name());
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid_source_numeric_id FROM "
                        + schema
                        + ".accounts WHERE id = ?",
                    firstAccountId)
                .fetchOne(0, Long.class))
        .isEqualTo(firstAccountId);
    assertThat(
            dsl.resultQuery("SELECT COUNT(DISTINCT account_uuid) FROM " + schema + ".accounts")
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM "
                        + schema
                        + ".account_audit_outbox WHERE payload_digest = ?",
                    payloadDigest)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE " + schema + ".accounts SET account_uuid = ? WHERE id = ?",
                    UUID.randomUUID(),
                    firstAccountId))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("accounts_identity_immutable");
    assertThat(
            dsl.resultQuery(
                    "SELECT account_uuid FROM " + schema + ".accounts WHERE id = ?", firstAccountId)
                .fetchOne(0, UUID.class))
        .isEqualTo(firstUuid);
  }

  private Account account(String username, String email, AccountLifecycleState lifecycleState) {
    Account account = new Account();
    account.setUsername(username);
    account.setEmail(email);
    account.setPasswordHash("hash");
    account.setRole("player");
    account.setLoginAuthModes("PASSWORD");
    account.setLifecycleState(lifecycleState);
    return account;
  }

  private String jsonRow(String query, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchOne(query, bindings)).get(0, String.class);
  }
}
