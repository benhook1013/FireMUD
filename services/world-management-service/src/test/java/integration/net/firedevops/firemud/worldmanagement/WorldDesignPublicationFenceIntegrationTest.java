package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDesignPublicationFenceIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final String FULL_REQUEST_DIGEST = "f".repeat(64);
  private static final Checkpoint SYNTHETIC_CHECKPOINT =
      new Checkpoint("synthetic-commit-42", "c".repeat(64), 2);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private WorldAuthoredSourceIntakeRepository intakeRepository;
  @Autowired private WorldDesignPublicationFenceRepository repository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void rollbackAndCommittedExactReadbackRetainOneImmutableAttempt() {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request = evidence(receipt, "request-7", 42, 9L);

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          repository.lockOpen(request.ownerBinding());
                          throw new ForcedRollbackException();
                        }))
        .isInstanceOf(ForcedRollbackException.class);
    assertThat(ownerCount(receipt.canonicalTenantId(), 42)).isZero();

    FrozenAttempt frozen =
        ownerTransaction()
            .execute(status -> repository.claimFreeze(request, () -> SYNTHETIC_CHECKPOINT));
    assertThat(frozen).isNotNull();
    assertThat(repository.readAttempt(request)).contains(frozen);
    assertThat(ownerPhase(receipt.canonicalTenantId(), 42)).isEqualTo("FROZEN");

    AtomicBoolean retryRecapturedCheckpoint = new AtomicBoolean();
    FrozenAttempt retry =
        ownerTransaction()
            .execute(
                status ->
                    repository.claimFreeze(
                        request,
                        () -> {
                          retryRecapturedCheckpoint.set(true);
                          return new Checkpoint("synthetic-new-commit", "d".repeat(64), 3);
                        }));
    assertThat(retry).isEqualTo(frozen);
    assertThat(retryRecapturedCheckpoint).isFalse();
    assertThat(attemptCount(receipt.canonicalTenantId(), 42)).isEqualTo(1L);

    assertDatabaseTriggerRejects(
        () ->
            dsl.execute(
                "UPDATE world_design_publication_fence_attempt SET content_digest = ? "
                    + "WHERE publication_fence = ?",
                "d".repeat(64),
                frozen.publicationFence()),
        "World publication-fence attempt history is immutable");
    assertDatabaseTriggerRejects(
        () -> dsl.execute("TRUNCATE world_design_publication_fence_attempt CASCADE"),
        "World publication-fence attempt history is immutable");
    assertDatabaseTriggerRejects(
        () -> dsl.execute("TRUNCATE world_design_publication_fence_owner"),
        "World publication-fence attempt history is immutable");
    assertDatabaseTriggerRejects(
        () ->
            dsl.execute(
                "UPDATE world_design_publication_fence_owner "
                    + "SET current_publication_fence = NULL WHERE canonical_tenant_id = ? "
                    + "AND version_id = ?",
                receipt.canonicalTenantId(),
                42L),
        "World publication-fence owner binding or phase transition is immutable");

    assertThat(repository.readAttempt(request)).contains(frozen);
    assertThat(ownerCount(receipt.canonicalTenantId(), 42)).isEqualTo(1L);
    assertThat(attemptCount(receipt.canonicalTenantId(), 42)).isEqualTo(1L);
    assertThat(ownerPhase(receipt.canonicalTenantId(), 42)).isEqualTo("FROZEN");
  }

  @Test
  void sameCanonicalTenantAndVersionRowCanBeLockedThroughDifferentIntakes() {
    UUID canonicalTenantId = UUID.randomUUID();
    String tenantSlug = "tenant-" + canonicalTenantId.toString().replace("-", "");
    WorldAuthoredSourceIntakeReceipt first =
        intake(canonicalTenantId, "violet-wilds", tenantSlug, 6_001L);
    WorldAuthoredSourceIntakeReceipt second =
        intake(canonicalTenantId, "brighter-coast", tenantSlug, 6_001L);

    ownerTransaction()
        .execute(
            status -> {
              repository.lockOpen(ownerBinding(first, 42));
              return null;
            });
    ownerTransaction()
        .execute(
            status -> {
              repository.lockOpen(ownerBinding(second, 42));
              return null;
            });

    assertThat(ownerCount(canonicalTenantId, 42)).isEqualTo(1L);
    assertThat(ownerPhase(canonicalTenantId, 42)).isEqualTo("OPEN");
  }

  @Test
  void concurrentExactFreezeRetryReturnsTheSameFenceAndCheckpoint() throws Exception {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request = evidence(receipt, "request-concurrent", 42, 9L);
    CountDownLatch checkpointCaptured = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicBoolean secondCapturedCheckpoint = new AtomicBoolean();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FrozenAttempt> first =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status ->
                              repository.claimFreeze(
                                  request,
                                  () -> {
                                    checkpointCaptured.countDown();
                                    await(releaseFirst);
                                    return SYNTHETIC_CHECKPOINT;
                                  })));
      await(checkpointCaptured);
      Future<FrozenAttempt> second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                return ownerTransaction()
                    .execute(
                        status ->
                            repository.claimFreeze(
                                request,
                                () -> {
                                  secondCapturedCheckpoint.set(true);
                                  return new Checkpoint("synthetic-second", "d".repeat(64), 3);
                                }));
              });
      await(secondStarted);
      awaitOwnerRowLockWait();
      releaseFirst.countDown();

      FrozenAttempt firstResult = first.get(10, TimeUnit.SECONDS);
      FrozenAttempt secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(secondResult).isEqualTo(firstResult);
      assertThat(secondCapturedCheckpoint).isFalse();
      assertThat(attemptCount(receipt.canonicalTenantId(), 42)).isEqualTo(1L);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void writerHoldingTheOpenLockCommitsBeforeFreezeCapturesItsCheckpoint() throws Exception {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request = evidence(receipt, "request-writer-first", 42, 9L);
    CountDownLatch writerLocked = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CountDownLatch freezeStarted = new CountDownLatch(1);
    AtomicBoolean checkpointCaptured = new AtomicBoolean();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> writer =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            repository.lockOpen(request.ownerBinding());
                            writerLocked.countDown();
                            await(releaseWriter);
                            return null;
                          }));
      await(writerLocked);
      Future<FrozenAttempt> freeze =
          executor.submit(
              () -> {
                freezeStarted.countDown();
                return ownerTransaction()
                    .execute(
                        status ->
                            repository.claimFreeze(
                                request,
                                () -> {
                                  checkpointCaptured.set(true);
                                  return SYNTHETIC_CHECKPOINT;
                                }));
              });
      await(freezeStarted);
      awaitOwnerRowLockWait();
      assertThat(checkpointCaptured).isFalse();
      releaseWriter.countDown();
      writer.get(10, TimeUnit.SECONDS);

      FrozenAttempt frozen = freeze.get(10, TimeUnit.SECONDS);
      assertThat(checkpointCaptured).isTrue();
      assertThat(frozen.checkpoint()).isEqualTo(SYNTHETIC_CHECKPOINT);
      assertThat(ownerPhase(receipt.canonicalTenantId(), 42)).isEqualTo("FROZEN");
    } finally {
      releaseWriter.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void freezeHoldingTheLockMakesAWaitingOrdinaryWriterFailClosed() throws Exception {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request = evidence(receipt, "request-freeze-first", 42, 9L);
    CountDownLatch freezeLocked = new CountDownLatch(1);
    CountDownLatch releaseFreeze = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FrozenAttempt> freeze =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            FrozenAttempt result =
                                repository.claimFreeze(
                                    request,
                                    () -> {
                                      freezeLocked.countDown();
                                      return SYNTHETIC_CHECKPOINT;
                                    });
                            await(releaseFreeze);
                            return result;
                          }));
      await(freezeLocked);
      Future<Boolean> writer =
          executor.submit(
              () -> {
                writerStarted.countDown();
                try {
                  ownerTransaction()
                      .execute(
                          status -> {
                            repository.lockOpen(request.ownerBinding());
                            return null;
                          });
                  return false;
                } catch (WorldDesignPublicationFenceRepository.ConflictException expected) {
                  return true;
                }
              });
      await(writerStarted);
      awaitOwnerRowLockWait();
      releaseFreeze.countDown();

      freeze.get(10, TimeUnit.SECONDS);
      assertThat(writer.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(ownerPhase(receipt.canonicalTenantId(), 42)).isEqualTo("FROZEN");
      assertThat(attemptCount(receipt.canonicalTenantId(), 42)).isEqualTo(1L);
    } finally {
      releaseFreeze.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void changedAssociationSourceRequestDigestEpochAndMissingCheckpointDoNotMutate() {
    UUID canonicalTenantId = UUID.randomUUID();
    String tenantSlug = "tenant-" + canonicalTenantId.toString().replace("-", "");
    WorldAuthoredSourceIntakeReceipt first =
        intake(canonicalTenantId, "violet-wilds", tenantSlug, 6_002L);
    WorldAuthoredSourceIntakeReceipt second =
        intake(canonicalTenantId, "brighter-coast", tenantSlug, 6_002L);
    WorldDesignPublicationFenceEvidence request = evidence(first, "request-7", 42, 9L);
    FrozenAttempt frozen =
        ownerTransaction()
            .execute(status -> repository.claimFreeze(request, () -> SYNTHETIC_CHECKPOINT));

    assertNoStateChange(
        canonicalTenantId,
        42,
        () ->
            ownerTransaction()
                .execute(
                    status ->
                        repository.claimFreeze(
                            evidence(second, "request-7", 42, 9L), () -> SYNTHETIC_CHECKPOINT)));

    UUID changedTenantId = UUID.randomUUID();
    WorldDesignPublicationFenceEvidence changedAssociation =
        evidence(
            changedTenantId,
            first,
            "request-7",
            42,
            9L,
            PublicationDigestRequestBinding.full(changedTenantId.toString(), "42", "request-7"));
    assertNoStateChange(
        canonicalTenantId,
        42,
        () ->
            ownerTransaction()
                .execute(
                    status ->
                        repository.claimFreeze(changedAssociation, () -> SYNTHETIC_CHECKPOINT)));
    assertThat(ownerCount(changedTenantId, 42)).isZero();

    WorldDesignPublicationFenceEvidence changedRequest = evidence(first, "request-8", 42, 9L);
    assertNoStateChange(
        canonicalTenantId,
        42,
        () ->
            ownerTransaction()
                .execute(
                    status -> repository.claimFreeze(changedRequest, () -> SYNTHETIC_CHECKPOINT)));

    WorldDesignPublicationFenceEvidence changedEpoch = evidence(first, "request-7", 42, 10L);
    assertNoStateChange(
        canonicalTenantId,
        42,
        () ->
            ownerTransaction()
                .execute(
                    status -> repository.claimFreeze(changedEpoch, () -> SYNTHETIC_CHECKPOINT)));

    WorldDesignPublicationFenceEvidence changedDigest =
        evidence(first, "request-7", 42, 9L, "0".repeat(64), request.publishWorkflowId());
    assertNoStateChange(
        canonicalTenantId,
        42,
        () ->
            ownerTransaction()
                .execute(
                    status -> repository.claimFreeze(changedDigest, () -> SYNTHETIC_CHECKPOINT)));
    assertThatThrownBy(() -> repository.readAttempt(changedDigest))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);

    assertThat(repository.readAttempt(request)).contains(frozen);
    assertThatThrownBy(
            () -> evidence(first, "request-7", 42, 9L, "F".repeat(64), request.publishWorkflowId()))
        .isInstanceOf(IllegalArgumentException.class);

    WorldAuthoredSourceIntakeReceipt missingCheckpointReceipt =
        intake(UUID.randomUUID(), "uncheckpointed-world");
    WorldDesignPublicationFenceEvidence missingCheckpointRequest =
        evidence(missingCheckpointReceipt, "request-missing-checkpoint", 43, 1L);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> repository.claimFreeze(missingCheckpointRequest, () -> null)))
        .isInstanceOf(NullPointerException.class);
    assertThat(ownerCount(missingCheckpointReceipt.canonicalTenantId(), 43)).isZero();
    assertThat(ownerPhase(canonicalTenantId, 42)).isEqualTo("FROZEN");
    assertThat(attemptCount(canonicalTenantId, 42)).isEqualTo(1L);
  }

  private void assertNoStateChange(UUID canonicalTenantId, long versionId, Runnable action) {
    long ownersBefore = ownerCount(canonicalTenantId, versionId);
    long attemptsBefore = attemptCount(canonicalTenantId, versionId);
    String phaseBefore = ownerPhase(canonicalTenantId, versionId);
    assertThatThrownBy(action::run)
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThat(ownerCount(canonicalTenantId, versionId)).isEqualTo(ownersBefore);
    assertThat(attemptCount(canonicalTenantId, versionId)).isEqualTo(attemptsBefore);
    assertThat(ownerPhase(canonicalTenantId, versionId)).isEqualTo(phaseBefore);
  }

  private WorldAuthoredSourceIntakeReceipt intake(UUID tenantId, String worldSlug) {
    return intake(
        tenantId,
        worldSlug,
        "tenant-" + tenantId.toString().replace("-", ""),
        sourceRowId(tenantId));
  }

  private WorldAuthoredSourceIntakeReceipt intake(
      UUID tenantId, String worldSlug, String tenantSlug, long sourceRowId) {
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source = source(tenantId, tenantSlug, worldSlug, sourceRowId);
    return ownerTransaction()
        .execute(status -> intakeRepository.acceptFresh(NAMESPACE, requestId, source));
  }

  private AuthoredWorldSourceEvidence source(
      UUID tenantId, String tenantSlug, String worldSlug, long sourceRowId) {
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String displayName = "Synthetic authored world";
    String sourceTenantKey = "gd-row-" + sourceRowId;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRowId,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        tenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceRowId,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private WorldDesignPublicationFenceEvidence evidence(
      WorldAuthoredSourceIntakeReceipt receipt,
      String publishRequestId,
      long versionId,
      long versionStateEpoch) {
    return evidence(
        receipt.canonicalTenantId(),
        receipt,
        publishRequestId,
        versionId,
        versionStateEpoch,
        PublicationDigestRequestBinding.full(
            receipt.canonicalTenantId().toString(), Long.toString(versionId), publishRequestId));
  }

  private OwnerBinding ownerBinding(WorldAuthoredSourceIntakeReceipt receipt, long versionId) {
    return new OwnerBinding(
        NAMESPACE,
        receipt.canonicalTenantId(),
        versionId,
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest());
  }

  private WorldDesignPublicationFenceEvidence evidence(
      UUID tenantId,
      WorldAuthoredSourceIntakeReceipt receipt,
      String publishRequestId,
      long versionId,
      long versionStateEpoch,
      PublicationDigestRequestBinding binding) {
    return new WorldDesignPublicationFenceEvidence(
        NAMESPACE,
        tenantId,
        versionId,
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        publishRequestId,
        FULL_REQUEST_DIGEST,
        versionStateEpoch,
        binding.derivedWorkflowIdentity());
  }

  private WorldDesignPublicationFenceEvidence evidence(
      WorldAuthoredSourceIntakeReceipt receipt,
      String publishRequestId,
      long versionId,
      long versionStateEpoch,
      String requestDigest,
      String workflowId) {
    return new WorldDesignPublicationFenceEvidence(
        NAMESPACE,
        receipt.canonicalTenantId(),
        versionId,
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        publishRequestId,
        requestDigest,
        versionStateEpoch,
        workflowId);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private long ownerCount(UUID tenantId, long versionId) {
    return count(
        "SELECT COUNT(*) FROM world_design_publication_fence_owner "
            + "WHERE canonical_tenant_id = ? AND version_id = ?",
        tenantId,
        versionId);
  }

  private long attemptCount(UUID tenantId, long versionId) {
    return count(
        "SELECT COUNT(*) FROM world_design_publication_fence_attempt "
            + "WHERE canonical_tenant_id = ? AND version_id = ?",
        tenantId,
        versionId);
  }

  private String ownerPhase(UUID tenantId, long versionId) {
    var row =
        dsl.fetchOne(
            "SELECT owner_freeze_phase FROM world_design_publication_fence_owner "
                + "WHERE canonical_tenant_id = ? AND version_id = ?",
            tenantId,
            versionId);
    if (row == null) {
      throw new IllegalStateException("World publication-fence owner phase query returned no row");
    }
    return row.get(0, String.class);
  }

  private long count(String sql, UUID tenantId, long versionId) {
    return Objects.requireNonNull(dsl.fetchOne(sql, tenantId, versionId)).get(0, Long.class);
  }

  private void awaitOwnerRowLockWait() {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNanos) {
      var waiterRow =
          dsl.fetchOne(
              "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event_type = 'Lock' AND lower(query) "
                  + "LIKE '%world_design_publication_fence_owner%'");
      if (waiterRow == null) {
        throw new IllegalStateException("PostgreSQL owner-row waiter query returned no row");
      }
      Long waiters = waiterRow.get(0, Long.class);
      if (waiters != null && waiters > 0L) {
        return;
      }
      pauseBriefly();
    }
    throw new IllegalStateException("No PostgreSQL waiter was observed on the World owner row");
  }

  private void assertDatabaseTriggerRejects(Runnable action, String expectedTriggerMessage) {
    RuntimeException failure = null;
    try {
      action.run();
    } catch (RuntimeException exception) {
      failure = exception;
    }
    assertThat(failure).isInstanceOf(DataAccessException.class);

    SQLException sqlException = null;
    Throwable cause = failure;
    while (cause != null) {
      if (cause instanceof SQLException) {
        sqlException = (SQLException) cause;
      }
      cause = cause.getCause();
    }
    assertThat((Throwable) sqlException).isNotNull();
    assertThat(sqlException.getSQLState()).isEqualTo("55000");
    assertThat(sqlException.getMessage()).contains(expectedTriggerMessage);
  }

  private long sourceRowId(UUID tenantId) {
    long positive = tenantId.getLeastSignificantBits() & Long.MAX_VALUE;
    return positive == 0L ? 1L : positive;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for the publication-fence test gate");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Publication-fence test was interrupted", exception);
    }
  }

  private static void pauseBriefly() {
    try {
      TimeUnit.MILLISECONDS.sleep(25);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Publication-fence test wait was interrupted", exception);
    }
  }

  private static final class ForcedRollbackException extends RuntimeException {}
}
