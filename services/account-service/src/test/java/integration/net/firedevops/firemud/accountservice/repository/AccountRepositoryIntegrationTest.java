package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.service.ExpiredConnectScopeCleanupJob;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class AccountRepositoryIntegrationTest {
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private final String SCHEMA_SUFFIX =
      UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  private final String TEST_SCHEMA = "account_repository_" + SCHEMA_SUFFIX;
  private final String MIGRATION_PROOF_SCHEMA = "account_migration_proof_" + SCHEMA_SUFFIX;
  private final String COLLISION_MIGRATION_PROOF_SCHEMA =
      "account_migration_collision_proof_" + SCHEMA_SUFFIX;
  private final String PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA =
      "account_profile_identity_migration_proof_" + SCHEMA_SUFFIX;
  private final String GLOBAL_REGISTRATION_MIGRATION_PROOF_SCHEMA =
      "account_global_registration_migration_proof_" + SCHEMA_SUFFIX;
  private final String ACCOUNT_UUID_MIGRATION_PROOF_SCHEMA =
      "account_uuid_migration_proof_" + SCHEMA_SUFFIX;

  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private AccountRepository repository;

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @BeforeEach
  void setUpRepository() {
    dataSource = postgres.dataSource(TEST_SCHEMA);

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(TEST_SCHEMA)
        .defaultSchema(TEST_SCHEMA)
        .placeholders(Map.of("serviceSchema", TEST_SCHEMA))
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();

    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    repository = new AccountRepository(dsl);
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void dropTestOwnedSchemas() {
    // Every invocation owns new schemas; never reset retained authority inside a live schema.
    JdbcTemplate jdbc = new JdbcTemplate(postgres.dataSource());
    for (String schema :
        List.of(
            TEST_SCHEMA,
            MIGRATION_PROOF_SCHEMA,
            COLLISION_MIGRATION_PROOF_SCHEMA,
            PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA,
            GLOBAL_REGISTRATION_MIGRATION_PROOF_SCHEMA,
            ACCOUNT_UUID_MIGRATION_PROOF_SCHEMA)) {
      if (!schema.matches("account_[a-z_]+_[a-f0-9]{12}")
          || !schema.endsWith("_" + SCHEMA_SUFFIX)) {
        throw new IllegalStateException("Refusing to dispose an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void joinIntentCommitsBeforePolicyAndMembershipAuditOutcomeCommitAtomically() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    DSLContext transactionAwareDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    AccountRepository accounts = new AccountRepository(transactionAwareDsl);
    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(transactionAwareDsl, "prod");
    AccountMembershipPairAuthorityRepository pairAuthority =
        new AccountMembershipPairAuthorityRepository(transactionAwareDsl);
    AccountJoinOperationRepository joinOperations =
        new AccountJoinOperationRepository(transactionAwareDsl);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(
            transactionAwareDsl, accounts, freshTenants, pairAuthority);
    AccountAuditOutboxRepository outbox = new AccountAuditOutboxRepository(transactionAwareDsl);
    AccountConnectScopeRepository scopes = new AccountConnectScopeRepository(transactionAwareDsl);
    long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "two-phase-join",
                "two-phase-join@example.com",
                "hash"));
    VerifiedJoinScope scope = joinScope(accountId);
    scopes.insert(scope);
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
    outbox.markDelivered(minimizedEventId, "receipt-minimized", "log-minimized", true);
    outbox.markDelivered(committedEventId, "receipt-committed", "log-committed", false);

    assertThatThrownBy(
            () -> outbox.markDelivered(minimizedEventId, "other-receipt", "log-minimized", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Audit delivery state changed concurrently");
    assertThatThrownBy(
            () -> outbox.markDelivered(minimizedEventId, "receipt-minimized", "other-log", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Audit delivery state changed concurrently");
    assertThatThrownBy(
            () ->
                outbox.markDelivered(minimizedEventId, "receipt-minimized", "log-minimized", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Audit delivery state changed concurrently");
    assertThatThrownBy(
            () -> outbox.markDelivered(UUID.randomUUID(), "receipt-missing", "log-missing", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Audit delivery state changed concurrently");

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
    assertThat(outbox.pending(10, Instant.now()))
        .noneMatch(envelope -> envelope.auditEventId().equals(minimizedEventId))
        .noneMatch(envelope -> envelope.auditEventId().equals(committedEventId));
    assertThat(
            jdbc.queryForObject(
                "SELECT next_attempt_at FROM account_audit_outbox WHERE audit_event_id = ?",
                LocalDateTime.class,
                minimizedEventId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT next_attempt_at FROM account_audit_outbox WHERE audit_event_id = ?",
                LocalDateTime.class,
                committedEventId))
        .isNull();
  }

  @Test
  void auditRetryUsesCappedExponentialBackoffAndDoesNotStarveNewDueEvents() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AccountAuditOutboxRepository outbox = new AccountAuditOutboxRepository(dsl);
    UUID failingEventId = UUID.randomUUID();
    UUID laterEventId = UUID.randomUUID();
    outbox.append(failingEventId, "platform", null, "ACCOUNT_REGISTERED", "{\"id\":1}");
    assertThat(outbox.pending(50, Instant.now()))
        .extracting(envelope -> envelope.auditEventId())
        .contains(failingEventId);

    List<Integer> expectedDelays = List.of(5, 10, 20, 40, 80, 160, 300, 300);
    for (int attempt = 0; attempt < expectedDelays.size(); attempt++) {
      outbox.recordAttempt(failingEventId);

      assertThat(
              jdbc.queryForObject(
                  "SELECT attempt_count FROM account_audit_outbox WHERE audit_event_id = ?",
                  Integer.class,
                  failingEventId))
          .isEqualTo(attempt + 1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT EXTRACT(EPOCH FROM (next_attempt_at - last_attempt_at))::INTEGER "
                      + "FROM account_audit_outbox WHERE audit_event_id = ?",
                  Integer.class,
                  failingEventId))
          .isEqualTo(expectedDelays.get(attempt));
    }

    outbox.append(laterEventId, "platform", null, "ACCOUNT_REGISTERED", "{\"id\":2}");

    assertThat(outbox.pending(50, Instant.now()))
        .extracting(envelope -> envelope.auditEventId())
        .containsExactly(laterEventId);
    assertThat(
            jdbc.queryForObject(
                "SELECT delivery_status FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                failingEventId))
        .isEqualTo("PENDING");
  }

  @Test
  void pendingLegacyAuditDeliverySkipsCanonicalRowsBeforeApplyingLimit() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AccountAuditOutboxRepository outbox = new AccountAuditOutboxRepository(dsl);
    UUID canonicalEventId = UUID.randomUUID();
    UUID legacyEventId = UUID.randomUUID();
    String canonicalPayload = "{\"requestId\":\"canonical-held\"}";
    String legacyPayload = "{\"accountId\":51}";

    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.executeWithoutResult(
        status ->
            outbox.appendCanonicalTenant(
                canonicalEventId,
                "ba819905-1a20-48a0-b9de-7f34f8b8ad3d",
                "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                canonicalPayload));
    outbox.append(legacyEventId, "platform", null, "ACCOUNT_REGISTERED", legacyPayload);

    assertThat(outbox.pending(1, Instant.now()))
        .extracting(envelope -> envelope.auditEventId())
        .containsExactly(legacyEventId);
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_identity_version FROM account_audit_outbox WHERE audit_event_id = ?",
                Integer.class,
                canonicalEventId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT delivery_status FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                canonicalEventId))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM account_audit_outbox WHERE audit_event_id = ?",
                String.class,
                canonicalEventId))
        .isEqualTo(canonicalPayload);
  }

  @Test
  void auditAttemptTimesUseUtcLocalDateTimeUnderNonUtcDatabaseSession() {
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    TransactionAwareDataSourceProxy transactionAwareDataSource =
        new TransactionAwareDataSourceProxy(dataSource);
    JdbcTemplate transactionJdbc = new JdbcTemplate(transactionAwareDataSource);
    DSLContext transactionDsl = DSL.using(transactionAwareDataSource, SQLDialect.POSTGRES);
    AccountAuditOutboxRepository outbox = new AccountAuditOutboxRepository(transactionDsl);
    UUID appendedEventId = UUID.randomUUID();
    UUID defaultedEventId = UUID.randomUUID();

    transaction.executeWithoutResult(
        status -> {
          transactionJdbc.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
          assertThat(
                  transactionJdbc.queryForObject(
                      "SELECT current_setting('TimeZone')", String.class))
              .isEqualTo("Pacific/Auckland");

          outbox.append(appendedEventId, "platform", null, "ACCOUNT_REGISTERED", "{}");
          LocalDateTime occurredAt =
              transactionJdbc.queryForObject(
                  "SELECT occurred_at FROM account_audit_outbox WHERE audit_event_id = ?",
                  LocalDateTime.class,
                  appendedEventId);
          LocalDateTime retryAt =
              transactionJdbc.queryForObject(
                  "SELECT next_attempt_at FROM account_audit_outbox WHERE audit_event_id = ?",
                  LocalDateTime.class,
                  appendedEventId);
          assertThat(retryAt).isEqualTo(occurredAt);

          Instant beforeDefaultInsert = Instant.now();
          transactionJdbc.update(
              "INSERT INTO account_audit_outbox "
                  + "(audit_event_id, scope, producer_service, event_type, occurred_at, "
                  + "tenant_identity_version, schema_version, payload_digest_version, payload_digest, payload, delivery_status) "
                  + "VALUES (?, 'platform', 'account-service', 'ACCOUNT_REGISTERED', ?, 1, 1, 1, ?, '{}', 'PENDING')",
              defaultedEventId,
              LocalDateTime.ofInstant(beforeDefaultInsert, ZoneOffset.UTC),
              AccountAuditDigest.ofPayload("{}"));
          LocalDateTime defaultRetryAt =
              Objects.requireNonNull(
                  transactionJdbc.queryForObject(
                      "SELECT next_attempt_at FROM account_audit_outbox WHERE audit_event_id = ?",
                      LocalDateTime.class,
                      defaultedEventId),
                  "next_attempt_at database default should be populated");
          assertThat(
                  Duration.between(beforeDefaultInsert, defaultRetryAt.toInstant(ZoneOffset.UTC)))
              .isLessThan(Duration.ofSeconds(10));
          assertThat(
                  Duration.between(defaultRetryAt, LocalDateTime.now(ZoneId.of("Pacific/Auckland")))
                      .abs())
              .isGreaterThan(Duration.ofHours(1));
        });
  }

  @Test
  void subscriptionForUpdateLocksOnlySubscriptionAndKeepsThatLockThroughTransaction()
      throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "subscription-lock",
                "subscription-lock@example.com",
                "hash"));
    long tenantId = 7654321L;
    jdbc.update(
        "INSERT INTO subscription (account_id, plan_id, status, tenant_id, entitlement_version) "
            + "VALUES (?, 'test-plan', 'active', ?, 1)",
        accountId,
        tenantId);

    TransactionAwareDataSourceProxy transactionAwareDataSource =
        new TransactionAwareDataSourceProxy(dataSource);
    DSLContext transactionDsl = DSL.using(transactionAwareDataSource, SQLDialect.POSTGRES);
    SubscriptionRepository subscriptions = new SubscriptionRepository(transactionDsl);
    TransactionTemplate lockTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    CountDownLatch subscriptionLocked = new CountDownLatch(1);
    CountDownLatch releaseSubscriptionLock = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> lockHolder =
        executor.submit(
            () ->
                lockTransaction.executeWithoutResult(
                    status -> {
                      assertThat(subscriptions.findByTenantIdForUpdate(tenantId)).hasSize(1);
                      subscriptionLocked.countDown();
                      try {
                        if (!releaseSubscriptionLock.await(10, TimeUnit.SECONDS)) {
                          throw new IllegalStateException(
                              "Timed out holding subscription row lock");
                        }
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while holding row lock", ex);
                      }
                    }));

    try {
      assertThat(subscriptionLocked.await(5, TimeUnit.SECONDS)).isTrue();

      TransactionTemplate conflictingTransaction =
          new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      JdbcTemplate conflictingJdbc = new JdbcTemplate(transactionAwareDataSource);
      assertThatThrownBy(
              () ->
                  conflictingTransaction.executeWithoutResult(
                      status -> {
                        conflictingJdbc.execute("SET LOCAL lock_timeout = '250ms'");
                        conflictingJdbc.update(
                            "UPDATE subscription SET status = status WHERE tenant_id = ?",
                            tenantId);
                      }))
          .hasMessageContaining("lock timeout");

      Integer updatedAccounts =
          conflictingTransaction.execute(
              status -> {
                conflictingJdbc.execute("SET LOCAL lock_timeout = '2s'");
                return conflictingJdbc.update(
                    "UPDATE accounts SET email = ? WHERE id = ?",
                    "subscription-lock-updated@example.com",
                    accountId);
              });
      assertThat(updatedAccounts).isEqualTo(1);
    } finally {
      releaseSubscriptionLock.countDown();
      try {
        lockHolder.get(5, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void privateRealmGrantRevocationRetainsMonotonicCurrentnessForRevalidation() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "realm-grant-currentness",
                "realm-grant-currentness@example.com",
                "hash"));
    DSLContext transactionAwareDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    AccountRealmAccessGrantRepository grants =
        new AccountRealmAccessGrantRepository(transactionAwareDsl);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AccountServiceImpl accountService =
        new AccountServiceImpl(
            new AccountRepository(transactionAwareDsl),
            null,
            null,
            null,
            null,
            grants,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            transactionManager);
    Account account = new Account();
    account.setId(accountId);
    AccountRealmAccessGrant grant = new AccountRealmAccessGrant();
    grant.setAccount(account);
    grant.setTenantId(7654323L);
    grant.setWorldSlug("world");
    grant.setRealmSlug("private");
    grant.setGrantVersion(1L);
    grant.setGrantedBy("test");
    grant.setGrantReason("test grant");
    grant.setCreatedAt(Instant.now());
    grant.setUpdatedAt(Instant.now());
    grants.save(grant);

    AccountRealmAccessGrant original =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, 7654323L, "world", "private")
            .orElseThrow();
    UUID originalGeneration = original.getGrantAuthorityGeneration();
    assertThat(grant.getGrantAuthorityGeneration()).isEqualTo(originalGeneration);
    transaction.executeWithoutResult(
        status -> accountService.revokeRealmAccess(accountId, 7654323L, "world", "private"));
    AccountRealmAccessGrant revoked =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, 7654323L, "world", "private")
            .orElseThrow();

    assertThat(originalGeneration).isNotNull();
    assertThat(revoked.isGranted()).isFalse();
    assertThat(revoked.getGrantVersion()).isEqualTo(2L);
    assertThat(revoked.getGrantAuthorityGeneration()).isNotEqualTo(originalGeneration);

    UUID revokedGeneration = revoked.getGrantAuthorityGeneration();
    var regrantResult =
        transaction.execute(
            status ->
                accountService.grantRealmAccess(
                    new RealmAccessGrantRequest(
                        accountId,
                        7654323L,
                        "world",
                        "private",
                        "test",
                        "regrant",
                        "req-regrant-1")));
    AccountRealmAccessGrant regranted =
        grants
            .findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
                accountId, 7654323L, "world", "private")
            .orElseThrow();
    assertThat(regranted.isGranted()).isTrue();
    assertThat(regranted.getGrantVersion()).isEqualTo(3L);
    assertThat(regranted.getGrantAuthorityGeneration()).isNotEqualTo(revokedGeneration);
    assertThat(regrantResult).isNotNull();
    assertThat(regrantResult.granted()).isTrue();
    assertThat(regrantResult.grantVersion()).isEqualTo(regranted.getGrantVersion());
    assertThat(regranted.getGrantAuthorityGeneration()).isNotEqualTo(originalGeneration);
  }

  private Account saveInTransaction(Account account) {
    return Objects.requireNonNull(
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
            .execute(status -> repository.save(account)));
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

  @Test
  void expiredConnectScopeCleanupPreservesEveryReferencedStatusAndMalformedEvidence() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AccountConnectScopeRepository scopes = new AccountConnectScopeRepository(dsl);
    AccountJoinOperationRepository joinOperations = new AccountJoinOperationRepository(dsl);
    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(dsl, "prod");
    AccountMembershipPairAuthorityRepository pairAuthority =
        new AccountMembershipPairAuthorityRepository(dsl);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(dsl, repository, freshTenants, pairAuthority);
    long accountId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "scope-cleanup",
                "scope-cleanup@example.com",
                "hash"));
    Instant capturedNow = Instant.now();
    String expiredAt = capturedNow.minusSeconds(60).toString();
    VerifiedJoinScope unreferenced = joinScope(accountId, "scope-cleanup-free", expiredAt);
    VerifiedJoinScope pending = joinScope(accountId, "scope-cleanup-pending", expiredAt);
    VerifiedJoinScope committed = joinScope(accountId, "scope-cleanup-committed", expiredAt);
    VerifiedJoinScope failed = joinScope(accountId, "scope-cleanup-failed", expiredAt);
    VerifiedJoinScope future =
        joinScope(accountId, "scope-cleanup-future", capturedNow.plusSeconds(86_400).toString());
    VerifiedJoinScope malformed = joinScope(accountId, "scope-cleanup-malformed", expiredAt);
    VerifiedJoinScope invalidCalendarDate =
        joinScope(accountId, "scope-cleanup-invalid-date", expiredAt);
    for (VerifiedJoinScope scope :
        List.of(unreferenced, pending, committed, failed, future, malformed, invalidCalendarDate)) {
      scopes.insert(scope);
    }
    jdbc.update(
        "UPDATE account_connect_scope_records SET connect_scope_expires_at = ? WHERE scope_token_hash = ?",
        "not-an-instant",
        AccountJoinDigest.tokenHash(malformed.connectScopeId()));
    jdbc.update(
        "UPDATE account_connect_scope_records SET connect_scope_expires_at = ? WHERE scope_token_hash = ?",
        "2026-02-30T10:02:00Z",
        AccountJoinDigest.tokenHash(invalidCalendarDate.connectScopeId()));

    insertJoinIntent(joinOperations, pending, "join-scope-pending");
    insertJoinIntent(joinOperations, committed, "join-scope-committed");
    insertJoinIntent(joinOperations, failed, "join-scope-failed");
    joinOperations.finish("join-scope-failed", "FAILED", "CONNECT_SCOPE_INVALID", null, null, null);
    joinOperations.bindPolicyEvidence(
        "join-scope-committed",
        AccountJoinDigest.request(committed, "scope-cleanup-committed-caller", true, 1L),
        1L,
        true);
    Account account = new Account();
    account.setId(accountId);
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setAccount(account);
    membership.setTenantId(committed.tenantId());
    membership.setLifecycleState("ACTIVE");
    membership.setGameplayAdmissionAllowed(true);
    membership.setMembershipVersion(1L);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    memberships.save(membership);
    joinOperations.finish(
        "join-scope-committed",
        "COMMITTED",
        "JOINED",
        membership.getId(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration());

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExpiredConnectScopeCleanupJob cleanupJob =
        new ExpiredConnectScopeCleanupJob(scopes, meters, 100, 60_000);
    cleanupJob.cleanupExpiredConnectScopes();

    assertThat(meters.get("account.connect_scopes.cleanup.deleted").counter().count()).isEqualTo(1);
    assertThat(meters.get("account.connect_scopes.cleanup.failure").counter().count()).isZero();
    assertThat(scopeCount(jdbc, unreferenced)).isZero();
    assertThat(scopeCount(jdbc, pending)).isEqualTo(1);
    assertThat(scopeCount(jdbc, committed)).isEqualTo(1);
    assertThat(scopeCount(jdbc, failed)).isEqualTo(1);
    assertThat(scopeCount(jdbc, future)).isEqualTo(1);
    assertThat(scopeCount(jdbc, malformed)).isEqualTo(1);
    assertThat(scopeCount(jdbc, invalidCalendarDate)).isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM account_connect_scope_records WHERE scope_token_hash = ?",
                    AccountJoinDigest.tokenHash(pending.connectScopeId())))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> insertJoinIntent(joinOperations, unreferenced, "join-scope-after-cleanup"))
        .isInstanceOf(DataAccessException.class);
    assertThat(joinOperations.find("join-scope-after-cleanup")).isEmpty();
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

  private void insertJoinIntent(
      AccountJoinOperationRepository joinOperations, VerifiedJoinScope scope, String requestId) {
    joinOperations.insertIntent(
        requestId,
        scope,
        "scope-cleanup-committed-caller",
        AccountJoinDigest.intent(requestId, scope, "scope-cleanup-committed-caller"));
  }

  private static long scopeCount(JdbcTemplate jdbc, VerifiedJoinScope scope) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM account_connect_scope_records WHERE scope_token_hash = ?",
            Long.class,
            AccountJoinDigest.tokenHash(scope.connectScopeId())));
  }

  private AccountJoinOperationRepository.JoinOperation joinOperation(
      AccountJoinOperationRepository joinOperations, String requestId) {
    return joinOperations.find(requestId).orElseThrow();
  }

  private static VerifiedJoinScope joinScope(long accountId) {
    return joinScope(accountId, "two-phase-connect-scope", "2026-09-24T10:02:00Z");
  }

  private static VerifiedJoinScope joinScope(
      long accountId, String connectScopeId, String expiresAt) {
    VerifiedJoinScope base =
        new VerifiedJoinScope(
            connectScopeId,
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
            expiresAt,
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
  @EnumSource(AccountLifecycleState.class)
  void genericUpdatePreservesPersistedLifecycleState(AccountLifecycleState lifecycleState) {
    Account persisted = account("original", "original@example.com", lifecycleState);
    Account saved = saveInTransaction(persisted);

    AccountLifecycleState requestedLifecycleState =
        lifecycleState == AccountLifecycleState.ACTIVE
            ? AccountLifecycleState.SECURITY_LOCKED
            : AccountLifecycleState.ACTIVE;
    Account staleUpdate = account("updated", "updated@example.com", requestedLifecycleState);
    staleUpdate.setId(saved.getId());
    saveInTransaction(staleUpdate);

    Account loaded = repository.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getUsername()).isEqualTo("updated");
    assertThat(loaded.getLifecycleState()).isEqualTo(lifecycleState);
    assertThat(staleUpdate.getLifecycleState()).isEqualTo(lifecycleState);
  }

  @Test
  void saveCanonicalizesEmail() {
    Account saved =
        saveInTransaction(
            account("canonical", "  Player@Example.COM ", AccountLifecycleState.ACTIVE));

    assertThat(saved.getEmail()).isEqualTo("player@example.com");
    assertThat(repository.findByEmail("  PLAYER@EXAMPLE.COM ")).isPresent();
  }

  @Test
  void repositoryPersistsAndReadsBackUniqueAccountUuidAndProvenance() {
    Account saved =
        saveInTransaction(
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
    Account updated = saveInTransaction(saved);
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

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO accounts "
                        + "(username, email, password_hash, account_uuid, account_uuid_provenance) "
                        + "VALUES (?, ?, ?, ?, ?)",
                    "nil-uuid-account",
                    "nil-uuid-account@example.com",
                    "hash",
                    new UUID(0L, 0L),
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT.name()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("accounts_account_uuid_non_nil_check");

    var databaseInserted =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash) "
                    + "VALUES ('database-uuid-account', 'database-uuid@example.com', 'hash') "
                    + "RETURNING id, account_uuid, account_uuid_provenance, "
                    + "account_uuid_source_numeric_id"));
    assertThat(databaseInserted.get("account_uuid", UUID.class)).isNotNull();
    assertThat(databaseInserted.get("account_uuid_provenance", String.class))
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT.name());
    assertThat(databaseInserted.get("account_uuid_source_numeric_id", Long.class))
        .isEqualTo(databaseInserted.get("id", Long.class));

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO accounts "
                        + "(username, email, password_hash, account_uuid, "
                        + "account_uuid_provenance) VALUES (?, ?, ?, ?, ?)",
                    "migration-origin-account",
                    "migration-origin@example.com",
                    "hash",
                    UUID.randomUUID(),
                    AccountIdentityProvenance.ACCOUNT_V29_MIGRATION.name()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ACCOUNT_V29_MIGRATION provenance is reserved for retained rows")
        .satisfies(
            failure -> {
              Throwable rootCause = failure;
              while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
              }
              assertThat(rootCause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23514");
            });
    assertThat(
            dsl.fetchValue(
                "SELECT COUNT(*) FROM accounts WHERE username = 'migration-origin-account'",
                Long.class))
        .isEqualTo(0L);

    saved.setAccountUuid(UUID.randomUUID());
    assertThatThrownBy(() -> saveInTransaction(saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Failed to update accounts id=");
    assertThat(repository.findById(saved.getId()).orElseThrow().getAccountUuid())
        .isEqualTo(accountUuid);
  }

  @Test
  void rejectedHardDeleteRetainsAccountRowAndSourceIdentityEvidence() {
    Account saved =
        saveInTransaction(
            account(
                "retained-delete", "retained-delete@example.com", AccountLifecycleState.ACTIVE));
    UUID accountUuid = saved.getAccountUuid();

    assertThatThrownBy(() -> repository.delete(saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Account hard deletion is unavailable until the pending-deletion retention workflow exists");

    var retained =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT account_uuid, account_uuid_provenance, account_uuid_source_numeric_id "
                    + "FROM accounts WHERE id = ?",
                saved.getId()));
    assertThat(retained.get("account_uuid", UUID.class)).isEqualTo(accountUuid);
    assertThat(retained.get("account_uuid_provenance", String.class))
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name());
    assertThat(retained.get("account_uuid_source_numeric_id", Long.class)).isEqualTo(saved.getId());
    assertThat(
            dsl.resultQuery("SELECT COUNT(*) FROM accounts WHERE id = ?", saved.getId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
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
        .placeholders(Map.of("serviceSchema", MIGRATION_PROOF_SCHEMA))
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
        .placeholders(Map.of("serviceSchema", MIGRATION_PROOF_SCHEMA))
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
        .placeholders(Map.of("serviceSchema", COLLISION_MIGRATION_PROOF_SCHEMA))
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
                    .placeholders(Map.of("serviceSchema", COLLISION_MIGRATION_PROOF_SCHEMA))
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
        .placeholders(Map.of("serviceSchema", PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA))
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
                    .placeholders(Map.of("serviceSchema", PROFILE_IDENTITY_MIGRATION_PROOF_SCHEMA))
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
        .placeholders(Map.of("serviceSchema", schema))
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
        .placeholders(Map.of("serviceSchema", schema))
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
        .placeholders(Map.of("serviceSchema", schema))
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
                            + "VALUES ('uuid-retained-one', 'uuid-retained-one@example.com', "
                            + "'hash-one', 41) RETURNING id"))
            .longValue();
    long secondAccountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO "
                            + schema
                            + ".accounts (username, email, password_hash, tenant_id) "
                            + "VALUES ('uuid-retained-two', 'uuid-retained-two@example.com', "
                            + "'hash-two', 42) RETURNING id"))
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
    UUID joinRealmId = REALM_ID;
    String pendingJoinRequestId = "retained-v28-pending-join";
    String retainedConnectScopeId = "retained-scope-token";
    String worldSlug = "retained-world";
    String realmSlug = "retained-realm";
    String namespaceId = "retained-namespace";
    String playableStateScope = "REALM";
    long gameInstanceId = 7L;
    long catalogRevision = 11L;
    long pointerVersion = 13L;
    String evaluatedAt = "2026-10-02T12:00:00Z";
    String connectScopeExpiresAt = "2099-10-02T12:00:00Z";
    VerifiedJoinScope retainedScopeBase =
        new VerifiedJoinScope(
            retainedConnectScopeId,
            secondAccountId,
            42L,
            joinRealmId,
            worldSlug,
            realmSlug,
            namespaceId,
            playableStateScope,
            gameInstanceId,
            catalogRevision,
            pointerVersion,
            evaluatedAt,
            connectScopeExpiresAt,
            "unused");
    String scopeDigest = AccountJoinDigest.scope(retainedScopeBase);
    VerifiedJoinScope retainedScope =
        new VerifiedJoinScope(
            retainedScopeBase.connectScopeId(),
            retainedScopeBase.accountId(),
            retainedScopeBase.tenantId(),
            retainedScopeBase.realmId(),
            retainedScopeBase.worldSlug(),
            retainedScopeBase.realmSlug(),
            retainedScopeBase.playableStateNamespaceId(),
            retainedScopeBase.playableStateScope(),
            retainedScopeBase.gameInstanceId(),
            retainedScopeBase.catalogRevision(),
            retainedScopeBase.pointerVersion(),
            retainedScopeBase.evaluatedAt(),
            retainedScopeBase.connectScopeExpiresAt(),
            scopeDigest);
    String scopeTokenHash = AccountJoinDigest.tokenHash(retainedConnectScopeId);
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_connect_scope_records "
            + "(scope_token_hash, account_id, target_class, tenant_id, realm_id, world_slug, "
            + "realm_slug, playable_state_namespace_id, playable_state_scope, game_instance_id, "
            + "catalog_revision, pointer_version, evaluated_at, connect_scope_expires_at, "
            + "snapshot_digest) "
            + "VALUES (?, ?, 'PUBLIC_PRODUCTION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        scopeTokenHash,
        retainedScope.accountId(),
        retainedScope.tenantId(),
        retainedScope.realmId(),
        retainedScope.worldSlug(),
        retainedScope.realmSlug(),
        retainedScope.playableStateNamespaceId(),
        retainedScope.playableStateScope(),
        retainedScope.gameInstanceId(),
        retainedScope.catalogRevision(),
        retainedScope.pointerVersion(),
        retainedScope.evaluatedAt(),
        retainedScope.connectScopeExpiresAt(),
        retainedScope.snapshotDigest());
    String intentDigest =
        AccountJoinDigest.intent(pendingJoinRequestId, retainedScope, "retained-caller");
    dsl.execute(
        "INSERT INTO "
            + schema
            + ".account_join_operations "
            + "(request_id, account_id, tenant_id, verified_caller_binding, scope_token_hash, "
            + "connect_scope_digest, world_slug, realm_slug, realm_id, playable_state_namespace_id, "
            + "playable_state_scope, game_instance_id, catalog_revision, pointer_version, "
            + "intent_digest_version, intent_digest, caller_bound_authority_invalidated, status) "
            + "VALUES (?, ?, ?, 'retained-caller', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, FALSE, "
            + "'PENDING')",
        pendingJoinRequestId,
        retainedScope.accountId(),
        retainedScope.tenantId(),
        scopeTokenHash,
        scopeDigest,
        retainedScope.worldSlug(),
        retainedScope.realmSlug(),
        retainedScope.realmId(),
        retainedScope.playableStateNamespaceId(),
        retainedScope.playableStateScope(),
        retainedScope.gameInstanceId(),
        retainedScope.catalogRevision(),
        retainedScope.pointerVersion(),
        intentDigest);

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
    String retainedConnectScopeBefore =
        jsonRow(
            "SELECT to_jsonb(s)::text FROM "
                + schema
                + ".account_connect_scope_records s WHERE scope_token_hash = ?",
            scopeTokenHash);
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
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();

    String accountProjection =
        "(to_jsonb(a) - 'account_uuid' - 'account_uuid_provenance' "
            + "- 'account_uuid_source_numeric_id' - 'account_repository_insert_transaction_id')::text";
    String membershipProjection =
        "(to_jsonb(m) - 'tenant_uuid' - 'tenant_provenance_kind' "
            + "- 'tenant_source_operation_id' - 'tenant_provenance_digest')::text";
    String joinOperationProjection =
        "(to_jsonb(j) - 'operation_representation_version' - 'scope_digest_version' "
            + "- 'target_class' - 'account_uuid' - 'tenant_uuid' - 'tenant_slug' "
            + "- 'playable_state_namespace_uuid' - 'game_instance_uuid' "
            + "- 'membership_authority_outbox_stream_key' - 'membership_authority_outbox_sequence' "
            + "- 'membership_authority_event_id' - 'membership_authority_event_digest' "
            + "- 'join_audit_event_id' - 'join_audit_payload_digest' "
            + "- 'join_audit_occurred_at')::text";
    String connectScopeProjection =
        "(to_jsonb(s) - 'scope_digest_version' - 'account_uuid' - 'tenant_uuid' "
            + "- 'tenant_slug' - 'playable_state_namespace_uuid' - 'game_instance_uuid' "
            + "- 'tenant_provenance_kind' - 'tenant_provenance_legacy_tenant_id' "
            + "- 'tenant_source_operation_id' - 'tenant_provenance_digest')::text";
    String auditOutboxProjection =
        "(to_jsonb(o) - 'tenant_identity_version' - 'tenant_uuid')::text";
    assertThat(
            jsonRow(
                "SELECT " + accountProjection + " FROM " + schema + ".accounts a WHERE id = ?",
                firstAccountId))
        .isEqualTo(firstAccountBefore);
    for (long retainedAccountId : List.of(firstAccountId, secondAccountId)) {
      assertThat(
              dsl.resultQuery(
                      "SELECT account_repository_insert_transaction_id FROM "
                          + schema
                          + ".accounts WHERE id = ?",
                      retainedAccountId)
                  .fetchOne(0, Long.class))
          .isNull();
    }
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
                "SELECT "
                    + membershipProjection
                    + " FROM "
                    + schema
                    + ".account_tenant_membership m WHERE account_id = ?",
                firstAccountId))
        .isEqualTo(membershipBefore);
    assertThat(
            jsonRow(
                "SELECT "
                    + membershipProjection
                    + " FROM "
                    + schema
                    + ".account_tenant_membership m WHERE account_id = ?",
                secondAccountId))
        .isEqualTo(explicitMembershipBefore);
    assertThat(
            jsonRow(
                "SELECT "
                    + joinOperationProjection
                    + " FROM "
                    + schema
                    + ".account_join_operations j WHERE request_id = ?",
                pendingJoinRequestId))
        .isEqualTo(pendingJoinBefore);
    assertThat(
            jsonRow(
                "SELECT "
                    + connectScopeProjection
                    + " FROM "
                    + schema
                    + ".account_connect_scope_records s WHERE scope_token_hash = ?",
                scopeTokenHash))
        .isEqualTo(retainedConnectScopeBefore);
    assertThat(
            jsonRow(
                "SELECT "
                    + auditOutboxProjection
                    + " FROM "
                    + schema
                    + ".account_audit_outbox o WHERE audit_event_id = ?",
                auditEventId))
        .isEqualTo(outboxBefore);

    for (long accountId : List.of(firstAccountId, secondAccountId)) {
      var retainedMembershipIdentity =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT tenant_uuid, tenant_provenance_kind, tenant_source_operation_id, "
                      + "tenant_provenance_digest FROM "
                      + schema
                      + ".account_tenant_membership WHERE account_id = ?",
                  accountId));
      assertThat(retainedMembershipIdentity.get("tenant_uuid", UUID.class)).isNull();
      assertThat(retainedMembershipIdentity.get("tenant_provenance_kind", String.class))
          .isEqualTo("UNBRIDGED_RETAINED");
      assertThat(retainedMembershipIdentity.get("tenant_source_operation_id", UUID.class)).isNull();
      assertThat(retainedMembershipIdentity.get("tenant_provenance_digest", String.class)).isNull();
    }
    assertThat(
            dsl.resultQuery(
                    "SELECT lifecycle_state FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id = ?",
                    firstAccountId)
                .fetchOne(0, String.class))
        .isEqualTo("LEGACY_UNVERIFIED");
    assertThat(
            dsl.resultQuery(
                    "SELECT gameplay_admission_allowed FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id = ?",
                    firstAccountId)
                .fetchOne(0, Boolean.class))
        .isFalse();
    assertThat(
            dsl.resultQuery(
                    "SELECT authority_provenance FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id = ?",
                    firstAccountId)
                .fetchOne(0, String.class))
        .isEqualTo("LEGACY_UNVERIFIED");
    assertThat(
            dsl.resultQuery(
                    "SELECT lifecycle_state FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id = ?",
                    secondAccountId)
                .fetchOne(0, String.class))
        .isEqualTo("ACTIVE");
    assertThat(
            dsl.resultQuery(
                    "SELECT gameplay_admission_allowed FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id = ?",
                    secondAccountId)
                .fetchOne(0, Boolean.class))
        .isTrue();
    assertThat(
            dsl.resultQuery(
                    "SELECT authority_provenance FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id = ?",
                    secondAccountId)
                .fetchOne(0, String.class))
        .isEqualTo("EXPLICIT_JOIN");

    var retainedJoinRepresentation =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT operation_representation_version, scope_digest_version, target_class, "
                    + "account_uuid, tenant_uuid, tenant_slug, playable_state_namespace_uuid, "
                    + "game_instance_uuid, membership_authority_outbox_stream_key, "
                    + "membership_authority_outbox_sequence, membership_authority_event_id, "
                    + "membership_authority_event_digest, join_audit_event_id, "
                    + "join_audit_payload_digest, join_audit_occurred_at FROM "
                    + schema
                    + ".account_join_operations WHERE request_id = ?",
                pendingJoinRequestId));
    assertThat(retainedJoinRepresentation.get("operation_representation_version", Integer.class))
        .isEqualTo(1);
    assertThat(retainedJoinRepresentation.get("scope_digest_version", Integer.class)).isEqualTo(1);
    assertThat(retainedJoinRepresentation.get("target_class", String.class)).isNull();
    assertThat(retainedJoinRepresentation.get("account_uuid", UUID.class)).isNull();
    assertThat(retainedJoinRepresentation.get("tenant_uuid", UUID.class)).isNull();
    assertThat(retainedJoinRepresentation.get("tenant_slug", String.class)).isNull();
    assertThat(retainedJoinRepresentation.get("playable_state_namespace_uuid", UUID.class))
        .isNull();
    assertThat(retainedJoinRepresentation.get("game_instance_uuid", UUID.class)).isNull();
    assertThat(
            retainedJoinRepresentation.get("membership_authority_outbox_stream_key", String.class))
        .isNull();
    assertThat(retainedJoinRepresentation.get("membership_authority_outbox_sequence", Long.class))
        .isNull();
    assertThat(retainedJoinRepresentation.get("membership_authority_event_id", String.class))
        .isNull();
    assertThat(retainedJoinRepresentation.get("membership_authority_event_digest", String.class))
        .isNull();
    assertThat(retainedJoinRepresentation.get("join_audit_event_id", UUID.class)).isNull();
    assertThat(retainedJoinRepresentation.get("join_audit_payload_digest", String.class)).isNull();
    assertThat(retainedJoinRepresentation.get("join_audit_occurred_at", LocalDateTime.class))
        .isNull();

    var retainedScopeIdentity =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT scope_digest_version, account_uuid, tenant_uuid, tenant_slug, "
                    + "playable_state_namespace_uuid, game_instance_uuid, "
                    + "tenant_provenance_kind, tenant_provenance_legacy_tenant_id, "
                    + "tenant_source_operation_id, tenant_provenance_digest FROM "
                    + schema
                    + ".account_connect_scope_records WHERE scope_token_hash = ?",
                scopeTokenHash));
    assertThat(retainedScopeIdentity.get("scope_digest_version", Integer.class)).isEqualTo(1);
    assertThat(retainedScopeIdentity.get("account_uuid", UUID.class)).isNull();
    assertThat(retainedScopeIdentity.get("tenant_uuid", UUID.class)).isNull();
    assertThat(retainedScopeIdentity.get("tenant_slug", String.class)).isNull();
    assertThat(retainedScopeIdentity.get("playable_state_namespace_uuid", UUID.class)).isNull();
    assertThat(retainedScopeIdentity.get("game_instance_uuid", UUID.class)).isNull();
    assertThat(retainedScopeIdentity.get("tenant_provenance_kind", String.class)).isNull();
    assertThat(retainedScopeIdentity.get("tenant_provenance_legacy_tenant_id", Long.class))
        .isNull();
    assertThat(retainedScopeIdentity.get("tenant_source_operation_id", UUID.class)).isNull();
    assertThat(retainedScopeIdentity.get("tenant_provenance_digest", String.class)).isNull();

    var retainedAuditIdentity =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT tenant_identity_version, tenant_uuid FROM "
                    + schema
                    + ".account_audit_outbox WHERE audit_event_id = ?",
                auditEventId));
    assertThat(retainedAuditIdentity.get("tenant_identity_version", Integer.class)).isEqualTo(1);
    assertThat(retainedAuditIdentity.get("tenant_uuid", UUID.class)).isNull();
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM "
                        + schema
                        + ".account_tenant_membership_role_snapshots WHERE membership_id IN "
                        + "(SELECT id FROM "
                        + schema
                        + ".account_tenant_membership WHERE account_id IN (?, ?))",
                    firstAccountId,
                    secondAccountId)
                .fetchOne(0, Long.class))
        .isZero();

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
    assertThat(firstUuid).isNotEqualTo(new UUID(0L, 0L));
    assertThat(secondUuid).isNotEqualTo(new UUID(0L, 0L));
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
        .hasMessageContaining("Account identity cannot be reassigned")
        .satisfies(
            failure -> {
              Throwable rootCause = failure;
              while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
              }
              assertThat(rootCause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23514");
            });
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE " + schema + ".accounts SET account_uuid_provenance = ? WHERE id = ?",
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT.name(),
                    firstAccountId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Account identity cannot be reassigned")
        .satisfies(
            failure -> {
              Throwable rootCause = failure;
              while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
              }
              assertThat(rootCause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23514");
            });
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE "
                        + schema
                        + ".accounts SET account_uuid_source_numeric_id = ? WHERE id = ?",
                    firstAccountId + 10,
                    firstAccountId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Account identity cannot be reassigned")
        .satisfies(
            failure -> {
              Throwable rootCause = failure;
              while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
              }
              assertThat(rootCause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23514");
            });
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
