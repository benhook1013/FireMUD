package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof of the typed Account audit storage and V49 retained-row boundary only. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountAuditCanonicalIdentityPostgresIntegrationTest {
  private static final long RETAINED_TENANT_ID = 73L;
  private static final String CANONICAL_TENANT_UUID = "33333333-3333-4333-8333-333333333333";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void v49BackfillsRetainedV1AndReadsBackTheExactCanonicalEnvelope() {
    TestContext context = testContext("48");
    UUID retainedEventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String retainedPayload = "{\"retained\":\"exact V1 bytes\"}";
    insertPreV49TenantEnvelope(context.setupDsl(), retainedEventId, retainedPayload);
    Map<String, Object> retainedBefore = auditEnvelopeSnapshot(context.setupDsl(), retainedEventId);

    flyway(context.dataSource(), context.schema(), "49").migrate();

    assertThat(auditEnvelopeSnapshot(context.setupDsl(), retainedEventId))
        .containsExactlyEntriesOf(retainedBefore);
    assertThat(identityVersion(context.setupDsl(), retainedEventId)).isEqualTo(1);
    assertThat(tenantUuid(context.setupDsl(), retainedEventId)).isNull();

    AccountAuditOutboxRepository repository = new AccountAuditOutboxRepository(context.dsl());
    String canonicalPayload = "{\"join\":\"immutable UTF-8 payload\"}";
    UUID canonicalEventId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    AccountAuditEnvelope appended =
        inTransaction(
            context,
            () ->
                repository.appendCanonicalTenant(
                    canonicalEventId,
                    CANONICAL_TENANT_UUID,
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    canonicalPayload));

    assertThat(appended.tenantIdentityVersion()).isEqualTo(2);
    assertThat(appended.tenantId()).isNull();
    assertThat(appended.tenantUuid()).isEqualTo(UUID.fromString(CANONICAL_TENANT_UUID));
    assertThat(identityVersion(context.setupDsl(), canonicalEventId)).isEqualTo(2);
    assertThat(tenantId(context.setupDsl(), canonicalEventId)).isNull();
    assertThat(tenantUuid(context.setupDsl(), canonicalEventId))
        .isEqualTo(UUID.fromString(CANONICAL_TENANT_UUID));

    Optional<AccountAuditEnvelope> exactReadback =
        inTransaction(
            context, () -> repository.findExactCanonicalTenantEnvelopeForUpdate(appended));
    assertThat(exactReadback).contains(appended);

    String changedPayload = canonicalPayload + " ";
    AccountAuditEnvelope changedEnvelope =
        new AccountAuditEnvelope(
            appended.auditEventId(),
            appended.scope(),
            appended.tenantIdentity(),
            appended.producerService(),
            appended.eventType(),
            appended.occurredAt(),
            appended.schemaVersion(),
            appended.payloadDigestVersion(),
            AccountAuditDigest.ofPayload(changedPayload),
            changedPayload);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> repository.findExactCanonicalTenantEnvelopeForUpdate(changedEnvelope)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable original envelope");
    assertThat(auditCount(context.setupDsl())).isEqualTo(2L);

    UUID retainedAppendId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    AccountAuditEnvelope retainedAppend =
        inTransaction(
            context,
            () ->
                repository.append(
                    retainedAppendId,
                    "tenant",
                    RETAINED_TENANT_ID,
                    "ACCOUNT_AUDIT_V1_FIXTURE",
                    "{\"retained\":true}"));
    assertThat(retainedAppend.tenantIdentityVersion()).isEqualTo(1);
    assertThat(tenantId(context.setupDsl(), retainedAppendId)).isEqualTo(RETAINED_TENANT_ID);
    assertThat(tenantUuid(context.setupDsl(), retainedAppendId)).isNull();

    long countBeforeInvalidWrites = auditCount(context.setupDsl());
    assertMissingIdentityVersionRejected(context, UUID.randomUUID());
    assertInvalidInsert(context, UUID.randomUUID(), "tenant", null, 1, null);
    assertInvalidInsert(
        context, UUID.randomUUID(), "tenant", RETAINED_TENANT_ID, 2, CANONICAL_TENANT_UUID);
    assertInvalidInsert(context, UUID.randomUUID(), "tenant", null, 2, null);
    assertInvalidInsert(
        context, UUID.randomUUID(), "tenant", null, 2, "00000000-0000-0000-0000-000000000000");
    assertInvalidInsert(context, UUID.randomUUID(), "tenant", null, 3, CANONICAL_TENANT_UUID);
    assertInvalidInsert(context, UUID.randomUUID(), "platform", null, 2, null);
    assertThat(auditCount(context.setupDsl())).isEqualTo(countBeforeInvalidWrites);
  }

  @Test
  void canonicalAppendRollsBackAndRejectsReadOnlyOwnerTransactions() {
    TestContext context = testContext("49");
    AccountAuditOutboxRepository repository = new AccountAuditOutboxRepository(context.dsl());
    UUID rolledBackEventId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          repository.appendCanonicalTenant(
                              rolledBackEventId,
                              CANONICAL_TENANT_UUID,
                              "ACCOUNT_AUDIT_ROLLBACK_TEST",
                              "{}");
                          throw new IllegalStateException("simulate owner transaction rollback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulate owner transaction rollback");
    assertThat(auditCount(context.setupDsl())).isZero();

    TransactionTemplate readOnly =
        new TransactionTemplate(new DataSourceTransactionManager(context.dataSource()));
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(
                    status ->
                        repository.appendCanonicalTenant(
                            UUID.randomUUID(),
                            CANONICAL_TENANT_UUID,
                            "ACCOUNT_AUDIT_READ_ONLY_TEST",
                            "{}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active read-write owner transaction");
    assertThat(auditCount(context.setupDsl())).isZero();
  }

  @Test
  void concurrentExactLockedReadbacksReturnTheSameOriginalEnvelope() throws Exception {
    TestContext context = testContext("49");
    AccountAuditOutboxRepository repository = new AccountAuditOutboxRepository(context.dsl());
    String payload = "{\"request\":\"stable\"}";
    AccountAuditEnvelope appended =
        inTransaction(
            context,
            () ->
                repository.appendCanonicalTenant(
                    UUID.randomUUID(),
                    CANONICAL_TENANT_UUID,
                    "ACCOUNT_AUDIT_CONCURRENT_READBACK_TEST",
                    payload));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier bothTransactionsStarted = new CyclicBarrier(2);
    try {
      Future<Optional<AccountAuditEnvelope>> first =
          executor.submit(
              () ->
                  inTransaction(
                      context,
                      () -> {
                        await(bothTransactionsStarted);
                        return repository.findExactCanonicalTenantEnvelopeForUpdate(appended);
                      }));
      Future<Optional<AccountAuditEnvelope>> second =
          executor.submit(
              () ->
                  inTransaction(
                      context,
                      () -> {
                        await(bothTransactionsStarted);
                        return repository.findExactCanonicalTenantEnvelopeForUpdate(appended);
                      }));

      assertThat(first.get(10, TimeUnit.SECONDS)).contains(appended);
      assertThat(second.get(10, TimeUnit.SECONDS)).contains(appended);
      assertThat(auditCount(context.setupDsl())).isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void insertPreV49TenantEnvelope(
      DSLContext dsl, UUID auditEventId, String payload) {
    dsl.execute(
        "INSERT INTO account_audit_outbox "
            + "(audit_event_id, scope, tenant_id, producer_service, event_type, occurred_at, "
            + "schema_version, payload_digest_version, payload_digest, payload, delivery_status) "
            + "VALUES (?, 'tenant', ?, 'account-service', 'ACCOUNT_AUDIT_RETAINED_TEST', "
            + "TIMESTAMP '2026-10-01 12:34:56.123456', 1, 1, ?, ?, 'PENDING')",
        auditEventId,
        RETAINED_TENANT_ID,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static Map<String, Object> auditEnvelopeSnapshot(DSLContext dsl, UUID auditEventId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT audit_event_id, scope, tenant_id, producer_service, event_type, "
                    + "occurred_at, schema_version, payload_digest_version, payload_digest, "
                    + "payload, receiver_receipt_id, receiver_log_event_id, delivery_status, "
                    + "last_attempt_at, created_at FROM account_audit_outbox "
                    + "WHERE audit_event_id = ?",
                auditEventId))
        .intoMap();
  }

  private static int identityVersion(DSLContext dsl, UUID auditEventId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT tenant_identity_version FROM account_audit_outbox WHERE audit_event_id = ?",
                auditEventId));
    return Objects.requireNonNull(row.getValue(0, Integer.class));
  }

  private static Long tenantId(DSLContext dsl, UUID auditEventId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT tenant_id FROM account_audit_outbox WHERE audit_event_id = ?",
                auditEventId));
    return row.getValue(0, Long.class);
  }

  private static UUID tenantUuid(DSLContext dsl, UUID auditEventId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT tenant_uuid FROM account_audit_outbox WHERE audit_event_id = ?",
                auditEventId));
    return row.getValue(0, UUID.class);
  }

  private static long auditCount(DSLContext dsl) {
    var row = Objects.requireNonNull(dsl.fetchOne("SELECT COUNT(*) FROM account_audit_outbox"));
    return Objects.requireNonNull(row.getValue(0, Long.class));
  }

  private static void assertInvalidInsert(
      TestContext context,
      UUID auditEventId,
      String scope,
      Long tenantId,
      int identityVersion,
      String tenantUuid) {
    String payload = "{}";
    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "INSERT INTO account_audit_outbox "
                            + "(audit_event_id, scope, tenant_id, tenant_identity_version, "
                            + "tenant_uuid, producer_service, event_type, occurred_at, "
                            + "schema_version, payload_digest_version, payload_digest, payload, "
                            + "delivery_status) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS UUID), 'account-service', "
                            + "'ACCOUNT_AUDIT_INVALID_TEST', TIMESTAMP '2026-10-01 12:34:56', "
                            + "1, 1, ?, ?, 'PENDING')",
                        auditEventId,
                        scope,
                        tenantId,
                        identityVersion,
                        tenantUuid,
                        AccountAuditDigest.ofPayload(payload),
                        payload))
        .isInstanceOf(DataAccessException.class);
  }

  private static void assertMissingIdentityVersionRejected(TestContext context, UUID auditEventId) {
    String payload = "{}";
    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "INSERT INTO account_audit_outbox "
                            + "(audit_event_id, scope, tenant_id, tenant_uuid, producer_service, "
                            + "event_type, occurred_at, schema_version, payload_digest_version, "
                            + "payload_digest, payload, delivery_status) "
                            + "VALUES (?, 'tenant', ?, NULL, 'account-service', "
                            + "'ACCOUNT_AUDIT_MISSING_VERSION_TEST', "
                            + "TIMESTAMP '2026-10-01 12:34:56', 1, 1, ?, ?, 'PENDING')",
                        auditEventId,
                        RETAINED_TENANT_ID,
                        AccountAuditDigest.ofPayload(payload),
                        payload))
        .isInstanceOf(DataAccessException.class);
  }

  private static TestContext testContext(String targetVersion) {
    String schema = "account_audit_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    flyway(dataSource, schema, targetVersion).migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    return new TestContext(schema, dataSource, setupDsl, transactionDsl, transaction);
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target(targetVersion)
        .load();
  }

  private static <T> T inTransaction(TestContext context, Supplier<T> operation) {
    return Objects.requireNonNull(context.transaction().execute(status -> operation.get()));
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new IllegalStateException("Concurrent audit readback did not synchronize", ex);
    }
  }

  private record TestContext(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
