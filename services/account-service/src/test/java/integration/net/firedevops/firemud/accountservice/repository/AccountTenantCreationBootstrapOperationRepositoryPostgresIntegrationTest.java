package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Claim;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Completion;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.OperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Database-only storage proof for immutable creator-bootstrap receipts; synthetic evidence here is
 * not proof of authenticated Account authorization or a production source participant.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantCreationBootstrapOperationRepositoryPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "creator_bootstrap_operation_";
  private static final String AUDIT_EVENT_TYPE = "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void exactImmutableInputReplaysOriginalReceiptAndChangedSnapshotConflicts() {
    TestContext context = newTestContext();
    FreshTenantCreatorEvidence evidence = evidence(UUID.randomUUID());
    RequestInput input = input(evidence);
    insertAccount(context.setupDsl(), evidence.initiatingAccountId());

    StoredOperation committed =
        inTransaction(
            context.transaction(),
            () -> {
              Claim claim = claim(context.repository(), input);
              assertThat(claim.claimed()).isTrue();
              return context
                  .repository()
                  .complete(evidence.accountAuthorizationOperationId(), completion(evidence));
            });

    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.result().roles()).containsExactly("tenantAdmin");
    assertThat(committed.result().gameplayAdmissionAllowed()).isFalse();
    StoredOperation retry =
        inTransaction(
            context.transaction(),
            () -> {
              Claim claim = claim(context.repository(), input);
              assertThat(claim.claimed()).isFalse();
              return claim.operation();
            });
    assertThat(retry.result()).isEqualTo(committed.result());
    assertThat(retry.resultPayload()).containsExactly(committed.resultPayload());

    byte[] changedSource =
        "same authorization ID, changed Account snapshot".getBytes(StandardCharsets.UTF_8);
    byte[] changedRequest =
        AccountTenantCreationBootstrapDigest.requestPayload(input.creatorPayload(), changedSource);
    String changedSourceDigest = AccountTenantCreationBootstrapDigest.sha256(changedSource);
    String changedRequestDigest = AccountTenantCreationBootstrapDigest.sha256(changedRequest);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .claim(
                                evidence,
                                input.creatorPayload(),
                                changedSource,
                                changedSourceDigest,
                                changedRequest,
                                changedRequestDigest,
                                1L,
                                1L)))
        .isInstanceOf(OperationConflictException.class)
        .hasMessageContaining("changed source or Account snapshot");

    RequestInput changedAuthorization =
        input(withAuthorizationDigest(evidence, "sha256:" + "9".repeat(64)));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> claim(context.repository(), changedAuthorization)))
        .isInstanceOf(OperationConflictException.class)
        .hasMessageContaining("changed source or Account snapshot");

    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "UPDATE account_tenant_creation_bootstrap_operations "
                            + "SET source_snapshot_payload = ? WHERE request_id = ?",
                        changedSource,
                        evidence.accountAuthorizationOperationId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_tenant_creation_bootstrap_operations WHERE request_id = ?",
                        evidence.accountAuthorizationOperationId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void claimedReceiptRollsBackWithItsAccountTransaction() {
    TestContext context = newTestContext();
    FreshTenantCreatorEvidence evidence = evidence(UUID.randomUUID());
    RequestInput input = input(evidence);
    insertAccount(context.setupDsl(), evidence.initiatingAccountId());

    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status -> {
                          claim(context.repository(), input);
                          context
                              .repository()
                              .complete(
                                  evidence.accountAuthorizationOperationId(), completion(evidence));
                          throw new IllegalStateException(
                              "injected after immutable receipt readback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected after immutable receipt readback");
    assertThat(
            java.util.Objects.requireNonNull(
                    context
                        .setupDsl()
                        .fetchOne(
                            "SELECT count(*) FROM account_tenant_creation_bootstrap_operations "
                                + "WHERE request_id = ?",
                            evidence.accountAuthorizationOperationId()),
                    "Rollback count query returned no row")
                .get(0, Long.class))
        .isEqualTo(0L);
  }

  @Test
  void concurrentExactClaimsConvergeOnOneCommittedReceipt() throws Exception {
    TestContext context = newTestContext();
    FreshTenantCreatorEvidence evidence = evidence(UUID.randomUUID());
    RequestInput input = input(evidence);
    insertAccount(context.setupDsl(), evidence.initiatingAccountId());
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch firstCommittedInsideTransaction = new CountDownLatch(1);
    CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    try {
      Future<Boolean> first =
          workers.submit(
              () ->
                  context
                      .transaction()
                      .execute(
                          status -> {
                            Claim initial = claim(context.repository(), input);
                            if (!initial.claimed()) {
                              throw new IllegalStateException("First exact claim was not unique");
                            }
                            context
                                .repository()
                                .complete(
                                    evidence.accountAuthorizationOperationId(),
                                    completion(evidence));
                            firstCommittedInsideTransaction.countDown();
                            await(releaseFirstTransaction);
                            return initial.claimed();
                          }));
      assertThat(firstCommittedInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Boolean> second =
          workers.submit(
              () -> {
                secondStarted.countDown();
                return context
                    .transaction()
                    .execute(status -> claim(context.repository(), input).claimed());
              });
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      releaseFirstTransaction.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
      assertThat(
              java.util.Objects.requireNonNull(
                      context
                          .setupDsl()
                          .fetchOne(
                              "SELECT count(*) FROM account_tenant_creation_bootstrap_operations "
                                  + "WHERE request_id = ? AND status = 'COMMITTED'",
                              evidence.accountAuthorizationOperationId()),
                      "Committed count query returned no row")
                  .get(0, Long.class))
          .isEqualTo(1L);
    } finally {
      releaseFirstTransaction.countDown();
      workers.shutdownNow();
    }
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
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
    return new TestContext(
        dataSource,
        setupDsl,
        new AccountTenantCreationBootstrapOperationRepository(transactionDsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private static UUID insertAccount(DSLContext dsl, UUID accountUuid) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    return dsl.resultQuery(
            "INSERT INTO accounts (account_uuid, username, email, password_hash) "
                + "VALUES (?, ?, ?, ?) RETURNING account_uuid",
            accountUuid,
            "creator_" + suffix.substring(0, 12),
            suffix + "@example.test",
            "test-hash")
        .fetchOne(0, UUID.class);
  }

  private static RequestInput input(FreshTenantCreatorEvidence evidence) {
    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
    byte[] sourcePayload =
        AccountTenantCreationBootstrapDigest.sourceSnapshotPayload(
            snapshot(
                evidence.initiatingAccountId(), evidence.creationEvidence().canonicalTenantId()));
    String sourceDigest = AccountTenantCreationBootstrapDigest.sha256(sourcePayload);
    byte[] requestPayload =
        AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, sourcePayload);
    return new RequestInput(
        evidence,
        creatorPayload,
        sourcePayload,
        sourceDigest,
        requestPayload,
        AccountTenantCreationBootstrapDigest.sha256(requestPayload));
  }

  private static Claim claim(
      AccountTenantCreationBootstrapOperationRepository repository, RequestInput input) {
    return repository.claim(
        input.evidence(),
        input.creatorPayload(),
        input.sourcePayload(),
        input.sourceDigest(),
        input.requestPayload(),
        input.requestDigest(),
        1L,
        1L);
  }

  private static Completion completion(FreshTenantCreatorEvidence evidence) {
    UUID tenantUuid = evidence.creationEvidence().canonicalTenantId();
    String requestId = evidence.accountAuthorizationOperationId().toString();
    String stream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + evidence.initiatingAccountId()
            + "/"
            + tenantUuid;
    byte[] event =
        "synthetic event bytes for repository storage proof".getBytes(StandardCharsets.UTF_8);
    byte[] audit =
        "synthetic audit bytes for repository storage proof".getBytes(StandardCharsets.UTF_8);
    byte[] result = "synthetic immutable result bytes".getBytes(StandardCharsets.UTF_8);
    return new Completion(
        51L,
        2L,
        1L,
        "ACTIVE",
        false,
        "[\"tenantAdmin\"]".getBytes(StandardCharsets.UTF_8),
        stream,
        requestId,
        1L,
        "creator-bootstrap-event-1",
        digest(1),
        false,
        event,
        UUID.randomUUID(),
        AUDIT_EVENT_TYPE,
        Instant.parse("2026-10-02T00:00:00Z"),
        digest(2),
        audit,
        result,
        AccountTenantCreationBootstrapDigest.sha256(result));
  }

  private static NeverJoinedMembershipSnapshot snapshot(UUID accountUuid, UUID tenantUuid) {
    String account = accountUuid.toString();
    String tenant = tenantUuid.toString();
    String stream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + account
            + "/"
            + tenant;
    return new NeverJoinedMembershipSnapshot(
        account,
        tenant,
        Map.of(tenant, "1"),
        "1",
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenant, "1"),
            Map.of(tenant, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty()),
        "1",
        Instant.parse("2026-10-01T00:00:00Z"),
        stream,
        List.of(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + account, "0"),
            new OutboxCheckpointEntry(
                "account:auth-authority:v1:issuer/firemud-account-service", "0"),
            new OutboxCheckpointEntry(stream, "0"),
            new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + tenant, "0")),
        List.of());
  }

  private static FreshTenantCreatorEvidence evidence(UUID creatorUuid) {
    UUID tenantUuid = UUID.randomUUID();
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    UUID authorizationOperationId = UUID.randomUUID();
    String namespace = "firemud-test";
    String tenantKey = "repository-proof-17";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, creationRequestId, tenantKey, "Example", null);
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            namespace,
            creationRequestId,
            creationOperationId,
            requestDigest,
            tenantUuid,
            17L,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                namespace,
                creationRequestId,
                creationOperationId,
                requestDigest,
                tenantUuid,
                17L,
                tenantKey,
                "NEW_GAME_ROW"));
    String authorizationDigest = "sha256:" + "7".repeat(64);
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        creatorUuid,
        authorizationOperationId,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, creatorUuid, authorizationOperationId, authorizationDigest));
  }

  private static FreshTenantCreatorEvidence withAuthorizationDigest(
      FreshTenantCreatorEvidence original, String authorizationDigest) {
    return new FreshTenantCreatorEvidence(
        1,
        original.creationEvidence(),
        original.initiatingAccountId(),
        original.accountAuthorizationOperationId(),
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1,
            original.creationEvidence(),
            original.initiatingAccountId(),
            original.accountAuthorizationOperationId(),
            authorizationDigest));
  }

  private static String digest(int marker) {
    return "sha256:" + Integer.toHexString(marker).repeat(64).substring(0, 64);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent exact-claim proof timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent exact-claim proof was interrupted", interrupted);
    }
  }

  private static <T> T inTransaction(
      TransactionTemplate transaction, java.util.function.Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  private record RequestInput(
      FreshTenantCreatorEvidence evidence,
      byte[] creatorPayload,
      byte[] sourcePayload,
      String sourceDigest,
      byte[] requestPayload,
      String requestDigest) {}

  private record TestContext(
      DataSource dataSource,
      DSLContext setupDsl,
      AccountTenantCreationBootstrapOperationRepository repository,
      TransactionTemplate transaction) {}
}
