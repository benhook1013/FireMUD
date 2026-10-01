package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.AssociationConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.AssociationReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.InvalidAssociationEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshotRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
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

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionRetainedTenantAssociationRepositoryIntegrationTest {
  private static final String NAMESPACE = "retained-tenant-association-test";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final Table<?> ASSOCIATIONS =
      DSL.table(DSL.name("game_session_retained_tenant_association"));

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void exactRetryReturnsHistoricalReceiptWithoutRecapturingChangedSource() {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-before-association");
    String sourceXminBefore = fixture.instanceXmin(420L);
    GameSessionRetainedTenantSnapshot snapshot = fixture.capture(42L);
    LegacyGameSessionTenantAssociationReceipt approval =
        fixture.approval(uuid(101), uuid(201), 42L, snapshot);

    AssociationReceipt original = fixture.register(uuid(301), approval);
    String associationXmin = fixture.associationXmin(uuid(301));
    String sourceXminAfter = fixture.instanceXmin(420L);
    assertThat(sourceXminAfter).isEqualTo(sourceXminBefore);

    fixture.dsl.execute(
        "UPDATE game_instances SET runtime_version = ? WHERE id = ?",
        "runtime-after-association",
        420L);
    assertThat(fixture.capture(42L).evidenceDigest())
        .isNotEqualTo(original.snapshot().evidenceDigest());

    AssociationReceipt historicalRetry = fixture.register(uuid(301), approval);
    assertThat(historicalRetry).isEqualTo(original);
    assertThat(fixture.associationXmin(uuid(301))).isEqualTo(associationXmin);
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
    assertThat(
            fixture.repository.read(original.operationId(), uuid(301), uuid(201), 42L, NAMESPACE))
        .contains(original);
    assertThatThrownBy(
            () -> fixture.repository.read(uuid(999), uuid(998), uuid(997), 42L, NAMESPACE))
        .isInstanceOf(InvalidAssociationEvidenceException.class)
        .hasMessageContaining("contradictory local identity");
    assertThat(fixture.repository.read(uuid(999), uuid(998), uuid(997), 999L, NAMESPACE)).isEmpty();
    assertThatThrownBy(
            () ->
                fixture.repository.read(
                    original.operationId(), uuid(301), uuid(202), 42L, NAMESPACE))
        .isInstanceOf(InvalidAssociationEvidenceException.class);
    assertThatThrownBy(
            () ->
                fixture.repository.read(
                    original.operationId(), uuid(302), uuid(201), 42L, NAMESPACE))
        .isInstanceOf(InvalidAssociationEvidenceException.class);
    assertThatThrownBy(
            () ->
                fixture.repository.read(
                    original.operationId(), uuid(301), uuid(201), 43L, NAMESPACE))
        .isInstanceOf(InvalidAssociationEvidenceException.class);
    assertThatThrownBy(
            () ->
                fixture.transactions.execute(
                    status ->
                        fixture.repository.read(
                            original.operationId(), uuid(301), uuid(201), 42L, NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed-outcome owner read");
  }

  @Test
  void committedReadRejectsForeignNamespaceBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionRetainedTenantSnapshotRepository snapshots =
        mock(GameSessionRetainedTenantSnapshotRepository.class);
    GameSessionRetainedTenantAssociationRepository repository =
        new GameSessionRetainedTenantAssociationRepository(dsl, snapshots, NAMESPACE);

    assertThatThrownBy(
            () -> repository.read(uuid(901), uuid(902), uuid(903), 42L, "other-namespace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured Game Session workload namespace");

    verifyNoInteractions(dsl, snapshots);
  }

  @Test
  void changedRequestAndOneToOneClaimConflictsLeaveNoPartialRows() {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-42");
    fixture.seedInstance(43L, 430L, "runtime-43");
    GameSessionRetainedTenantSnapshot snapshot42 = fixture.capture(42L);
    GameSessionRetainedTenantSnapshot snapshot43 = fixture.capture(43L);
    UUID firstRequest = uuid(401);
    LegacyGameSessionTenantAssociationReceipt firstApproval =
        fixture.approval(uuid(101), uuid(201), 42L, snapshot42);
    AssociationReceipt first = fixture.register(firstRequest, firstApproval);
    String originalXmin = fixture.associationXmin(firstRequest);

    LegacyGameSessionTenantAssociationReceipt changedApproval =
        fixture.approval(uuid(102), uuid(202), 42L, snapshot42);
    assertThatThrownBy(() -> fixture.register(firstRequest, changedApproval))
        .isInstanceOf(AssociationConflictException.class);

    assertThatThrownBy(() -> fixture.register(uuid(402), firstApproval))
        .isInstanceOf(AssociationConflictException.class);

    LegacyGameSessionTenantAssociationReceipt changedCanonicalClaim =
        fixture.approval(uuid(103), uuid(201), 43L, snapshot43);
    assertThatThrownBy(() -> fixture.register(uuid(403), changedCanonicalClaim))
        .isInstanceOf(AssociationConflictException.class);

    LegacyGameSessionTenantAssociationReceipt changedLegacyClaim =
        fixture.approval(uuid(104), uuid(202), 42L, snapshot42);
    assertThatThrownBy(() -> fixture.register(uuid(404), changedLegacyClaim))
        .isInstanceOf(AssociationConflictException.class);

    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
    assertThat(fixture.associationXmin(firstRequest)).isEqualTo(originalXmin);
    assertThat(fixture.storedRequestCount(firstRequest)).isEqualTo(1L);
    assertThat(fixture.storedRequestCount(uuid(402))).isZero();
    assertThat(fixture.storedRequestCount(uuid(403))).isZero();
    assertThat(fixture.storedRequestCount(uuid(404))).isZero();
    assertThat(
            fixture.repository.read(first.operationId(), firstRequest, uuid(201), 42L, NAMESPACE))
        .contains(first);
  }

  @Test
  void changedMissingAndExtraRetainedRowsRejectFirstCommit() {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-original");
    GameSessionRetainedTenantSnapshot originalSnapshot = fixture.capture(42L);
    LegacyGameSessionTenantAssociationReceipt wrongDigestApproval =
        fixture.approval(uuid(101), uuid(201), 42L, originalSnapshot, "sha256:" + "0".repeat(64));

    assertThatThrownBy(() -> fixture.register(uuid(501), wrongDigestApproval))
        .isInstanceOf(InvalidAssociationEvidenceException.class)
        .hasMessageContaining("does not match the approved owner evidence");
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isZero();

    LegacyGameSessionTenantAssociationReceipt originalApproval =
        fixture.approval(uuid(102), uuid(202), 42L, originalSnapshot);
    fixture.dsl.execute(
        "UPDATE game_instances SET runtime_version = ? WHERE id = ?", "runtime-changed", 420L);
    assertThatThrownBy(() -> fixture.register(uuid(502), originalApproval))
        .isInstanceOf(InvalidAssociationEvidenceException.class)
        .hasMessageContaining("does not match the approved owner evidence");
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isZero();

    fixture.seedInstance(42L, 421L, "runtime-extra");
    assertThatThrownBy(() -> fixture.register(uuid(503), originalApproval))
        .isInstanceOf(InvalidAssociationEvidenceException.class)
        .hasMessageContaining("does not match the approved owner evidence");
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isZero();

    fixture.dsl.execute("DELETE FROM game_instances WHERE tenant_id = ?", 42L);
    assertThatThrownBy(() -> fixture.register(uuid(504), originalApproval))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no retained tenant evidence");
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isZero();
  }

  @Test
  void lostAcknowledgementExactRetryReturnsSameOriginalOperationAndBytesWithoutMutation() {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-42");
    LegacyGameSessionTenantAssociationReceipt approval =
        fixture.approval(uuid(101), uuid(201), 42L, fixture.capture(42L));
    UUID associationRequestId = uuid(601);

    // Model a committed response lost after the transaction: retain only the database baseline.
    fixture.register(associationRequestId, approval);
    Record committed = fixture.storedByRequest(associationRequestId);
    UUID originalOperation = committed.get("operation_id", UUID.class);
    String originalRequestDigest = committed.get("request_digest", String.class);
    String originalManifestDigest = committed.get("approval_manifest_digest", String.class);
    String originalSignature = committed.get("approval_signature", String.class);
    assertThat(committed.get("target_namespace", String.class)).isEqualTo(NAMESPACE);
    assertThat(originalManifestDigest).isEqualTo(approval.manifestDigest());
    assertThat(originalSignature).isEqualTo(approval.ed25519Signature());
    assertThat(originalRequestDigest)
        .isEqualTo(
            independentlyComputedRequestDigest(
                NAMESPACE,
                associationRequestId,
                approval.manifestDigest(),
                approval.ed25519Signature()));
    String originalSnapshot = committed.get("snapshot_canonical_json", String.class);
    String originalSnapshotDigest = committed.get("snapshot_evidence_digest", String.class);
    String originalReceiptDigest = committed.get("receipt_digest", String.class);
    byte[] originalSnapshotBytes = originalSnapshot.getBytes(StandardCharsets.UTF_8);
    String originalXmin = fixture.associationXmin(associationRequestId);

    AssociationReceipt recovered = fixture.register(associationRequestId, approval);

    assertThat(recovered.operationId()).isEqualTo(originalOperation);
    assertThat(recovered.requestDigest()).isEqualTo(originalRequestDigest);
    assertThat(recovered.approvalManifestDigest()).isEqualTo(originalManifestDigest);
    assertThat(recovered.approvalSignature()).isEqualTo(originalSignature);
    assertThat(recovered.snapshot().canonicalJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(originalSnapshotBytes);
    assertThat(recovered.snapshot().evidenceDigest()).isEqualTo(originalSnapshotDigest);
    assertThat(recovered.receiptDigest()).isEqualTo(originalReceiptDigest);
    assertThat(fixture.associationXmin(associationRequestId)).isEqualTo(originalXmin);
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  @Test
  void rollbackLeavesNoAssociationClaimAndNewAttemptSucceeds() {
    Fixture fixture = fixture();
    fixture.seedInstance(43L, 430L, "runtime-43");
    UUID associationRequestId = uuid(602);
    LegacyGameSessionTenantAssociationReceipt approval =
        fixture.approval(uuid(103), uuid(203), 43L, fixture.capture(43L));

    AssociationReceipt rolledBack =
        fixture.transactions.execute(
            status -> {
              AssociationReceipt result =
                  fixture.repository.register(associationRequestId, approval);
              status.setRollbackOnly();
              return result;
            });
    assertThat(rolledBack).isNotNull();
    assertThat(fixture.storedRequestCount(associationRequestId)).isZero();
    assertThat(
            fixture.repository.read(
                rolledBack.operationId(), associationRequestId, uuid(203), 43L, NAMESPACE))
        .isEmpty();

    AssociationReceipt retry = fixture.register(associationRequestId, approval);
    assertThat(retry.operationId()).isNotEqualTo(rolledBack.operationId());
    assertThat(
            fixture.repository.read(
                retry.operationId(), associationRequestId, uuid(203), 43L, NAMESPACE))
        .contains(retry);
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  @Test
  void concurrentExactFirstWritersReturnSameImmutableReceipt() throws Exception {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-42");
    UUID associationRequestId = uuid(701);
    LegacyGameSessionTenantAssociationReceipt approval =
        fixture.approval(uuid(101), uuid(201), 42L, fixture.capture(42L));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AssociationReceipt> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start, "concurrent registration start");
                return fixture.register(associationRequestId, approval);
              });
      Future<AssociationReceipt> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start, "concurrent registration start");
                return fixture.register(associationRequestId, approval);
              });
      await(ready, "concurrent registration workers");
      start.countDown();

      AssociationReceipt firstReceipt = first.get(15, TimeUnit.SECONDS);
      AssociationReceipt secondReceipt = second.get(15, TimeUnit.SECONDS);
      assertThat(secondReceipt).isEqualTo(firstReceipt);
      assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
      assertThat(
              fixture.repository.read(
                  firstReceipt.operationId(), associationRequestId, uuid(201), 42L, NAMESPACE))
          .contains(firstReceipt);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void sourceWriterWaitsUntilAssociationCommitReleasesRetainedRowFence() throws Exception {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-fenced");
    UUID associationRequestId = uuid(702);
    LegacyGameSessionTenantAssociationReceipt approval =
        fixture.approval(uuid(101), uuid(201), 42L, fixture.capture(42L));
    CountDownLatch snapshotCaptured = new CountDownLatch(1);
    CountDownLatch allowAssociationToContinue = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    GameSessionRetainedTenantSnapshotRepository delayedSnapshots = spy(fixture.snapshotRepository);
    doAnswer(
            invocation -> {
              var snapshot = invocation.callRealMethod();
              snapshotCaptured.countDown();
              await(allowAssociationToContinue, "association commit release");
              return snapshot;
            })
        .when(delayedSnapshots)
        .capture(eq(NAMESPACE), eq("42"));
    GameSessionRetainedTenantAssociationRepository delayedRepository =
        new GameSessionRetainedTenantAssociationRepository(
            fixture.dsl, delayedSnapshots, NAMESPACE);
    Fixture delayedFixture =
        new Fixture(fixture.dsl, delayedSnapshots, delayedRepository, fixture.transactions);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AssociationReceipt> association =
          executor.submit(() -> delayedFixture.register(associationRequestId, approval));
      await(snapshotCaptured, "fenced snapshot capture");
      Future<?> sourceWriter =
          executor.submit(
              () -> {
                writerStarted.countDown();
                fixture.dsl.execute(
                    "UPDATE game_instances SET runtime_version = ? WHERE id = ?",
                    "runtime-after-fence",
                    420L);
              });
      await(writerStarted, "source writer attempt");
      assertThatThrownBy(() -> sourceWriter.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      allowAssociationToContinue.countDown();
      AssociationReceipt committed = association.get(10, TimeUnit.SECONDS);
      sourceWriter.get(10, TimeUnit.SECONDS);
      assertThat(committed.snapshot().evidenceDigest())
          .isNotEqualTo(fixture.capture(42L).evidenceDigest());
      assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
    } finally {
      allowAssociationToContinue.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void persistedRowsRejectUpdateDeleteAndTruncateThroughTheirOwnImmutableTriggers() {
    Fixture fixture = fixture();
    fixture.seedInstance(42L, 420L, "runtime-42");
    UUID associationRequestId = uuid(801);
    LegacyGameSessionTenantAssociationReceipt approval =
        fixture.approval(uuid(101), uuid(201), 42L, fixture.capture(42L));
    fixture.register(associationRequestId, approval);
    UUID operationId =
        fixture.storedByRequest(associationRequestId).get("operation_id", UUID.class);

    assertCheckViolation(
        () ->
            fixture.dsl.execute(
                "UPDATE game_session_retained_tenant_association SET approved_by = ? "
                    + "WHERE operation_id = ?",
                "changed",
                operationId));
    assertCheckViolation(
        () ->
            fixture.dsl.execute(
                "DELETE FROM game_session_retained_tenant_association WHERE operation_id = ?",
                operationId));
    assertCheckViolation(
        () -> fixture.dsl.execute("TRUNCATE game_session_retained_tenant_association"));
    assertThat(fixture.dsl.fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  private Fixture fixture() {
    String schema = "gs_retained_tenant_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionRetainedTenantSnapshotRepository snapshotRepository =
        new GameSessionRetainedTenantSnapshotRepository(dsl);
    GameSessionRetainedTenantAssociationRepository repository =
        new GameSessionRetainedTenantAssociationRepository(dsl, snapshotRepository, NAMESPACE);
    return new Fixture(dsl, snapshotRepository, repository, transactions);
  }

  private static void assertCheckViolation(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    Throwable failure = catchThrowable(action);
    assertThat(failure).isInstanceOf(DataAccessException.class);
    SQLException sqlException = findSqlException(failure);
    assertThat((Throwable) sqlException).isNotNull();
    assertThat(sqlException.getSQLState()).isEqualTo("23514");
  }

  private static SQLException findSqlException(Throwable failure) {
    Throwable current = failure;
    while (current != null && !(current instanceof SQLException)) {
      current = current.getCause();
    }
    return (SQLException) current;
  }

  private static void await(CountDownLatch latch, String label) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + label);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + label, exception);
    }
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static String independentlyComputedRequestDigest(
      String exactNamespace, UUID associationRequestId, String manifestDigest, String signature) {
    ByteArrayOutputStream framedPreimage = new ByteArrayOutputStream();
    for (String segment :
        new String[] {
          "game-session/retained-tenant-association-request/v1",
          exactNamespace,
          associationRequestId.toString(),
          manifestDigest,
          signature
        }) {
      byte[] segmentBytes = segment.getBytes(StandardCharsets.UTF_8);
      framedPreimage.writeBytes(
          Integer.toString(segmentBytes.length).getBytes(StandardCharsets.US_ASCII));
      framedPreimage.write(':');
      framedPreimage.writeBytes(segmentBytes);
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(framedPreimage.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }

  private record Fixture(
      DSLContext dsl,
      GameSessionRetainedTenantSnapshotRepository snapshotRepository,
      GameSessionRetainedTenantAssociationRepository repository,
      TransactionTemplate transactions) {
    void seedInstance(long tenantId, long instanceId, String runtimeVersion) {
      dsl.execute(
          "INSERT INTO game_instances "
              + "(id, tenant_id, runtime_version, owner_account_id, status, row_version) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          instanceId,
          tenantId,
          runtimeVersion,
          9L,
          "STOPPED",
          0L);
    }

    GameSessionRetainedTenantSnapshot capture(long legacyTenantId) {
      return Objects.requireNonNull(
          transactions.execute(
              status -> snapshotRepository.capture(NAMESPACE, Long.toString(legacyTenantId))));
    }

    LegacyGameSessionTenantAssociationReceipt approval(
        UUID approvalOperationId,
        UUID canonicalTenantId,
        long legacyTenantId,
        GameSessionRetainedTenantSnapshot snapshot) {
      return approval(
          approvalOperationId,
          canonicalTenantId,
          legacyTenantId,
          snapshot,
          snapshot.evidenceDigest());
    }

    LegacyGameSessionTenantAssociationReceipt approval(
        UUID approvalOperationId,
        UUID canonicalTenantId,
        long legacyTenantId,
        GameSessionRetainedTenantSnapshot snapshot,
        String evidenceDigest) {
      GameSessionTenantAssociationEvidence evidence =
          new GameSessionTenantAssociationEvidence(
              1,
              approvalOperationId,
              NAMESPACE,
              "fixture-owner-key",
              "owner-reviewer",
              "approval-" + approvalOperationId,
              "2026-01-01T00:00:00Z",
              Long.toString(legacyTenantId),
              canonicalTenantId,
              "501",
              "source-game-501",
              "RETAINED_GAME_V30",
              evidenceDigest);
      String signature = Base64.getEncoder().encodeToString(new byte[64]);
      return new LegacyGameSessionTenantAssociationReceipt(
          evidence, evidence.manifestDigest(), signature);
    }

    AssociationReceipt register(
        UUID associationRequestId, LegacyGameSessionTenantAssociationReceipt approval) {
      return Objects.requireNonNull(
          transactions.execute(status -> repository.register(associationRequestId, approval)));
    }

    Record storedByRequest(UUID associationRequestId) {
      return dsl.fetchOne(
          "SELECT * FROM game_session_retained_tenant_association "
              + "WHERE target_namespace = ? AND association_request_id = ?",
          NAMESPACE,
          associationRequestId);
    }

    long storedRequestCount(UUID associationRequestId) {
      Record row =
          dsl.fetchOne(
              "SELECT count(*) FROM game_session_retained_tenant_association "
                  + "WHERE target_namespace = ? AND association_request_id = ?",
              NAMESPACE,
              associationRequestId);
      return Objects.requireNonNull(
          Objects.requireNonNull(row, "COUNT query must return a row").get(0, Long.class),
          "COUNT query returned null");
    }

    String associationXmin(UUID associationRequestId) {
      Record row =
          dsl.fetchOne(
              "SELECT xmin::text AS xmin FROM game_session_retained_tenant_association "
                  + "WHERE target_namespace = ? AND association_request_id = ?",
              NAMESPACE,
              associationRequestId);
      return Objects.requireNonNull(row).get("xmin", String.class);
    }

    String instanceXmin(long instanceId) {
      Record row =
          dsl.fetchOne("SELECT xmin::text AS xmin FROM game_instances WHERE id = ?", instanceId);
      return Objects.requireNonNull(row).get("xmin", String.class);
    }
  }
}
