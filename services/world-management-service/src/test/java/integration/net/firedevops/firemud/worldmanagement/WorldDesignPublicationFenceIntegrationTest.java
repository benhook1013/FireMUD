package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
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
  private static final long GAME_DESIGN_VERSION_A = 9_000_000_042L;
  private static final long GAME_DESIGN_VERSION_B = 9_000_000_043L;
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

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void rollbackAndCommittedExactReadbackRetainOneImmutableAttempt() {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request =
        evidence(receipt, "request-7", GAME_DESIGN_VERSION_A, 9L);

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          repository.lockOpen(request.ownerBinding());
                          throw new ForcedRollbackException();
                        }))
        .isInstanceOf(ForcedRollbackException.class);
    assertThat(ownerCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isZero();

    FrozenAttempt frozen =
        ownerTransaction()
            .execute(status -> repository.claimFreeze(request, () -> SYNTHETIC_CHECKPOINT));
    assertThat(frozen).isNotNull();
    assertThat(repository.readAttempt(request)).contains(frozen);
    assertThat(ownerPhase(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo("FROZEN");
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, GAME_DESIGN_VERSION_A);
    assertThat(identity.localVersionKey()).isNotEqualTo(GAME_DESIGN_VERSION_A);
    var storedBinding =
        dsl.fetchOne(
            "SELECT owner_binding_schema_version, version_id, canonical_version_id, "
                + "version_identity_operation_id, game_design_version_id, intake_request_digest "
                + "FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
            frozen.publicationFence());
    assertThat(storedBinding).isNotNull();
    assertThat(storedBinding.get("owner_binding_schema_version", Short.class)).isEqualTo((short) 1);
    assertThat(storedBinding.get("version_id", Long.class)).isEqualTo(identity.localVersionKey());
    assertThat(storedBinding.get("canonical_version_id", UUID.class))
        .isEqualTo(identity.canonicalVersionId());
    assertThat(storedBinding.get("version_identity_operation_id", UUID.class))
        .isEqualTo(identity.operationId());
    assertThat(storedBinding.get("game_design_version_id", Long.class))
        .isEqualTo(GAME_DESIGN_VERSION_A);
    assertThat(storedBinding.get("intake_request_digest", String.class))
        .isEqualTo(receipt.requestDigest());

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
    assertThat(attemptCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo(1L);

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
                localVersionKey(identity(receipt, GAME_DESIGN_VERSION_A))),
        "World publication-fence owner binding or phase transition is immutable");

    assertThat(repository.readAttempt(request)).contains(frozen);
    assertThat(ownerCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo(1L);
    assertThat(attemptCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo(1L);
    assertThat(ownerPhase(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo("FROZEN");
  }

  @Test
  void schemaZeroAttemptAtResolvedLocalKeyCannotBePromotedByNumericCoincidence() {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "legacy-world");
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, GAME_DESIGN_VERSION_A);
    WorldDesignPublicationFenceEvidence request =
        evidence(receipt, "legacy-request", GAME_DESIGN_VERSION_A, 3L);
    assertThat(identity.localVersionKey()).isNotEqualTo(GAME_DESIGN_VERSION_A);

    dsl.execute(
        "INSERT INTO world_design_publication_fence_attempt ("
            + "publication_fence, target_namespace, canonical_tenant_id, local_tenant_key, "
            + "version_id, intake_operation_id, intake_request_id, source_operation_id, "
            + "source_evidence_digest, intake_receipt_digest, publication_request_id, "
            + "request_digest, version_state_epoch, publish_workflow_id, applied_commit_id, "
            + "content_digest, digest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        NAMESPACE,
        receipt.canonicalTenantId(),
        receipt.localTenantKey(),
        identity.localVersionKey(),
        receipt.operationId(),
        receipt.intakeRequestId(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.receiptDigest(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        "legacy-commit",
        "e".repeat(64),
        2);

    assertThatThrownBy(() -> repository.readAttempt(request))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("schema-0");
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(status -> repository.claimFreeze(request, () -> SYNTHETIC_CHECKPOINT)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("schema-0");
    assertThat(ownerCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isZero();
    assertThat(attemptCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo(1L);
  }

  @Test
  void sameGameDesignSelectorUsesDistinctWorldLocalVersionRowsAcrossIntakes() {
    UUID canonicalTenantId = UUID.randomUUID();
    String tenantSlug = "tenant-" + canonicalTenantId.toString().replace("-", "");
    WorldAuthoredSourceIntakeReceipt first =
        intake(canonicalTenantId, "violet-wilds", tenantSlug, 6_001L);
    WorldAuthoredSourceIntakeReceipt second =
        intake(canonicalTenantId, "brighter-coast", tenantSlug, 6_001L);

    ownerTransaction()
        .execute(
            status -> {
              repository.lockOpen(ownerBinding(first, GAME_DESIGN_VERSION_A));
              return null;
            });
    ownerTransaction()
        .execute(
            status -> {
              repository.lockOpen(ownerBinding(second, GAME_DESIGN_VERSION_A));
              return null;
            });

    assertThat(identity(first, GAME_DESIGN_VERSION_A).localVersionKey())
        .isNotEqualTo(identity(second, GAME_DESIGN_VERSION_A).localVersionKey());
    assertThat(ownerCount(canonicalTenantId, GAME_DESIGN_VERSION_A)).isEqualTo(2L);
    assertThat(ownerPhase(canonicalTenantId, GAME_DESIGN_VERSION_A)).isEqualTo("OPEN");
  }

  @Test
  void identicalRequestAcrossVersionsUsesOnlyEachResolvedWorldLocalKey() {
    UUID canonicalTenantId = UUID.randomUUID();
    String tenantSlug = "tenant-" + canonicalTenantId.toString().replace("-", "");
    WorldAuthoredSourceIntakeReceipt first =
        intake(canonicalTenantId, "violet-wilds", tenantSlug, 8_101L);
    WorldAuthoredVersionIdentityReceipt firstIdentity = identity(first, GAME_DESIGN_VERSION_A);
    long secondGameDesignVersionId = firstIdentity.localVersionKey();
    WorldAuthoredSourceIntakeReceipt second =
        intake(canonicalTenantId, "brighter-coast", tenantSlug, 8_101L);
    WorldAuthoredVersionIdentityReceipt secondIdentity =
        identity(second, secondGameDesignVersionId);
    assertThat(secondGameDesignVersionId).isEqualTo(firstIdentity.localVersionKey());
    assertThat(secondIdentity.localVersionKey()).isNotEqualTo(secondGameDesignVersionId);
    assertThatThrownBy(() -> intake(canonicalTenantId, "contradictory-world", tenantSlug, 8_103L))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class)
        .hasMessageContaining("Canonical tenant source or stable tenant selector conflicts");

    WorldDesignPublicationFenceEvidence firstRequest =
        evidence(first, "shared-publication-request", GAME_DESIGN_VERSION_A, 5L);
    WorldDesignPublicationFenceEvidence secondRequest =
        evidence(second, "shared-publication-request", secondGameDesignVersionId, 5L);
    FrozenAttempt firstFrozen =
        ownerTransaction()
            .execute(status -> repository.claimFreeze(firstRequest, () -> SYNTHETIC_CHECKPOINT));
    FrozenAttempt secondFrozen =
        ownerTransaction()
            .execute(status -> repository.claimFreeze(secondRequest, () -> SYNTHETIC_CHECKPOINT));

    assertThat(secondFrozen.publicationFence()).isNotEqualTo(firstFrozen.publicationFence());
    long firstOwnerCount = ownerCount(canonicalTenantId, GAME_DESIGN_VERSION_A);
    long secondOwnerCount = ownerCount(canonicalTenantId, secondGameDesignVersionId);
    long firstAttemptCount = attemptCount(canonicalTenantId, GAME_DESIGN_VERSION_A);
    long secondAttemptCount = attemptCount(canonicalTenantId, secondGameDesignVersionId);
    assertThat(repository.readAttempt(firstRequest)).contains(firstFrozen);
    assertThat(repository.readAttempt(secondRequest)).contains(secondFrozen);
    assertThat(ownerCount(canonicalTenantId, GAME_DESIGN_VERSION_A)).isEqualTo(firstOwnerCount);
    assertThat(ownerCount(canonicalTenantId, secondGameDesignVersionId))
        .isEqualTo(secondOwnerCount);
    assertThat(attemptCount(canonicalTenantId, GAME_DESIGN_VERSION_A)).isEqualTo(firstAttemptCount);
    assertThat(attemptCount(canonicalTenantId, secondGameDesignVersionId))
        .isEqualTo(secondAttemptCount);
  }

  @Test
  void concurrentExactFreezeRetryReturnsTheSameFenceAndCheckpoint() throws Exception {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request =
        evidence(receipt, "request-concurrent", GAME_DESIGN_VERSION_A, 9L);
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
      assertThat(attemptCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo(1L);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void writerHoldingTheOpenLockCommitsBeforeFreezeCapturesItsCheckpoint() throws Exception {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request =
        evidence(receipt, "request-writer-first", GAME_DESIGN_VERSION_A, 9L);
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
      assertThat(ownerPhase(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A))
          .isEqualTo("FROZEN");
    } finally {
      releaseWriter.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void freezeHoldingTheLockMakesAWaitingOrdinaryWriterFailClosed() throws Exception {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "violet-wilds");
    WorldDesignPublicationFenceEvidence request =
        evidence(receipt, "request-freeze-first", GAME_DESIGN_VERSION_A, 9L);
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
      assertThat(ownerPhase(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A))
          .isEqualTo("FROZEN");
      assertThat(attemptCount(receipt.canonicalTenantId(), GAME_DESIGN_VERSION_A)).isEqualTo(1L);
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
    WorldDesignPublicationFenceEvidence request =
        evidence(first, "request-7", GAME_DESIGN_VERSION_A, 9L);
    FrozenAttempt frozen =
        ownerTransaction()
            .execute(status -> repository.claimFreeze(request, () -> SYNTHETIC_CHECKPOINT));

    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status ->
                        repository.claimFreeze(
                            new WorldDesignPublicationFenceEvidence(
                                NAMESPACE,
                                first.canonicalTenantId(),
                                identity(first, GAME_DESIGN_VERSION_A).canonicalVersionId(),
                                identity(first, GAME_DESIGN_VERSION_A).operationId(),
                                GAME_DESIGN_VERSION_A,
                                first.intakeRequestId(),
                                first.operationId(),
                                first.requestDigest(),
                                first.sourceOperationId(),
                                second.sourceEvidenceDigest(),
                                first.receiptDigest(),
                                "request-7",
                                FULL_REQUEST_DIGEST,
                                9L,
                                request.publishWorkflowId()),
                            () -> SYNTHETIC_CHECKPOINT)));

    UUID changedTenantId = UUID.randomUUID();
    WorldDesignPublicationFenceEvidence changedAssociation =
        evidence(
            changedTenantId,
            first,
            "request-7",
            identity(first, GAME_DESIGN_VERSION_A),
            GAME_DESIGN_VERSION_A,
            9L,
            PublicationDigestRequestBinding.full(
                changedTenantId.toString(), Long.toString(GAME_DESIGN_VERSION_A), "request-7"));
    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status ->
                        repository.claimFreeze(changedAssociation, () -> SYNTHETIC_CHECKPOINT)));
    assertThat(ownerCount(changedTenantId, GAME_DESIGN_VERSION_A)).isZero();

    WorldAuthoredVersionIdentityReceipt identity = identity(first, GAME_DESIGN_VERSION_A);
    WorldDesignPublicationFenceEvidence changedCanonicalVersion =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            canonicalTenantId,
            UUID.randomUUID(),
            identity.operationId(),
            GAME_DESIGN_VERSION_A,
            first.intakeRequestId(),
            first.operationId(),
            first.requestDigest(),
            first.sourceOperationId(),
            first.sourceEvidenceDigest(),
            first.receiptDigest(),
            "request-7",
            FULL_REQUEST_DIGEST,
            9L,
            request.publishWorkflowId());
    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status ->
                        repository.claimFreeze(
                            changedCanonicalVersion, () -> SYNTHETIC_CHECKPOINT)));

    String changedSelectorWorkflow =
        PublicationDigestRequestBinding.full(
                canonicalTenantId.toString(), Long.toString(GAME_DESIGN_VERSION_B), "request-7")
            .derivedWorkflowIdentity();
    WorldDesignPublicationFenceEvidence changedGameDesignSelector =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            canonicalTenantId,
            identity.canonicalVersionId(),
            identity.operationId(),
            GAME_DESIGN_VERSION_B,
            first.intakeRequestId(),
            first.operationId(),
            first.requestDigest(),
            first.sourceOperationId(),
            first.sourceEvidenceDigest(),
            first.receiptDigest(),
            "request-7",
            FULL_REQUEST_DIGEST,
            9L,
            changedSelectorWorkflow);
    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status ->
                        repository.claimFreeze(
                            changedGameDesignSelector, () -> SYNTHETIC_CHECKPOINT)));

    WorldDesignPublicationFenceEvidence changedRequest =
        evidence(first, "request-8", GAME_DESIGN_VERSION_A, 9L);
    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status -> repository.claimFreeze(changedRequest, () -> SYNTHETIC_CHECKPOINT)));

    WorldDesignPublicationFenceEvidence changedEpoch =
        evidence(first, "request-7", GAME_DESIGN_VERSION_A, 10L);
    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status -> repository.claimFreeze(changedEpoch, () -> SYNTHETIC_CHECKPOINT)));

    WorldDesignPublicationFenceEvidence changedDigest =
        evidence(
            first,
            "request-7",
            GAME_DESIGN_VERSION_A,
            9L,
            "0".repeat(64),
            request.publishWorkflowId());
    assertNoStateChange(
        canonicalTenantId,
        GAME_DESIGN_VERSION_A,
        () ->
            ownerTransaction()
                .execute(
                    status -> repository.claimFreeze(changedDigest, () -> SYNTHETIC_CHECKPOINT)));
    assertThatThrownBy(() -> repository.readAttempt(changedDigest))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);

    assertThat(repository.readAttempt(request)).contains(frozen);
    assertThatThrownBy(
            () ->
                evidence(
                    first,
                    "request-7",
                    GAME_DESIGN_VERSION_A,
                    9L,
                    "F".repeat(64),
                    request.publishWorkflowId()))
        .isInstanceOf(IllegalArgumentException.class);

    WorldAuthoredSourceIntakeReceipt missingCheckpointReceipt =
        intake(UUID.randomUUID(), "uncheckpointed-world");
    WorldDesignPublicationFenceEvidence missingCheckpointRequest =
        evidence(missingCheckpointReceipt, "request-missing-checkpoint", GAME_DESIGN_VERSION_B, 1L);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> repository.claimFreeze(missingCheckpointRequest, () -> null)))
        .isInstanceOf(NullPointerException.class);
    assertThat(ownerCount(missingCheckpointReceipt.canonicalTenantId(), GAME_DESIGN_VERSION_B))
        .isZero();
    assertThat(ownerPhase(canonicalTenantId, GAME_DESIGN_VERSION_A)).isEqualTo("FROZEN");
    assertThat(attemptCount(canonicalTenantId, GAME_DESIGN_VERSION_A)).isEqualTo(1L);
  }

  private void assertNoStateChange(
      UUID canonicalTenantId, long gameDesignVersionId, Runnable action) {
    long ownersBefore = ownerCount(canonicalTenantId, gameDesignVersionId);
    long attemptsBefore = attemptCount(canonicalTenantId, gameDesignVersionId);
    String phaseBefore = ownerPhase(canonicalTenantId, gameDesignVersionId);
    assertThatThrownBy(action::run)
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThat(ownerCount(canonicalTenantId, gameDesignVersionId)).isEqualTo(ownersBefore);
    assertThat(attemptCount(canonicalTenantId, gameDesignVersionId)).isEqualTo(attemptsBefore);
    assertThat(ownerPhase(canonicalTenantId, gameDesignVersionId)).isEqualTo(phaseBefore);
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
      long gameDesignVersionId,
      long versionStateEpoch) {
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, gameDesignVersionId);
    return evidence(
        receipt.canonicalTenantId(),
        receipt,
        publishRequestId,
        identity,
        gameDesignVersionId,
        versionStateEpoch,
        PublicationDigestRequestBinding.full(
            receipt.canonicalTenantId().toString(),
            Long.toString(gameDesignVersionId),
            publishRequestId));
  }

  private OwnerBinding ownerBinding(
      WorldAuthoredSourceIntakeReceipt receipt, long gameDesignVersionId) {
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, gameDesignVersionId);
    return new OwnerBinding(
        NAMESPACE,
        receipt.canonicalTenantId(),
        identity.canonicalVersionId(),
        identity.operationId(),
        gameDesignVersionId,
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.requestDigest(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.receiptDigest());
  }

  private static long localVersionKey(WorldAuthoredVersionIdentityReceipt identity) {
    return identity.localVersionKey();
  }

  private WorldDesignPublicationFenceEvidence evidence(
      UUID tenantId,
      WorldAuthoredSourceIntakeReceipt receipt,
      String publishRequestId,
      WorldAuthoredVersionIdentityReceipt identity,
      long gameDesignVersionId,
      long versionStateEpoch,
      PublicationDigestRequestBinding binding) {
    return new WorldDesignPublicationFenceEvidence(
        NAMESPACE,
        tenantId,
        identity.canonicalVersionId(),
        identity.operationId(),
        gameDesignVersionId,
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.requestDigest(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.receiptDigest(),
        publishRequestId,
        FULL_REQUEST_DIGEST,
        versionStateEpoch,
        binding.derivedWorkflowIdentity());
  }

  private WorldDesignPublicationFenceEvidence evidence(
      WorldAuthoredSourceIntakeReceipt receipt,
      String publishRequestId,
      long gameDesignVersionId,
      long versionStateEpoch,
      String requestDigest,
      String workflowId) {
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, gameDesignVersionId);
    return new WorldDesignPublicationFenceEvidence(
        NAMESPACE,
        receipt.canonicalTenantId(),
        identity.canonicalVersionId(),
        identity.operationId(),
        gameDesignVersionId,
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.requestDigest(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.receiptDigest(),
        publishRequestId,
        requestDigest,
        versionStateEpoch,
        workflowId);
  }

  private WorldAuthoredVersionIdentityReceipt identity(
      WorldAuthoredSourceIntakeReceipt receipt, long gameDesignVersionId) {
    UUID canonicalVersionId = stableId("canonical-version", receipt, gameDesignVersionId);
    UUID readRequestId = stableId("version-state-read", receipt, gameDesignVersionId);
    AuthoredWorldVersionStateEvidence.Request stateRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            readRequestId,
            receipt.canonicalTenantId(),
            receipt.worldSlug(),
            receipt.sourceOperationId(),
            receipt.sourceEvidenceDigest(),
            gameDesignVersionId);
    AuthoredWorldVersionStateEvidence stateEvidence =
        AuthoredWorldVersionStateEvidence.create(
            stateRequest,
            receipt.source(),
            canonicalVersionId,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    return ownerTransaction()
        .execute(
            status ->
                new WorldAuthoredVersionIdentityRepository(dsl)
                    .acceptFresh(receipt, stateEvidence));
  }

  private static UUID stableId(
      String domain, WorldAuthoredSourceIntakeReceipt receipt, long gameDesignVersionId) {
    return UUID.nameUUIDFromBytes(
        (domain + ":" + receipt.operationId() + ":" + Long.toString(gameDesignVersionId))
            .getBytes(StandardCharsets.UTF_8));
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private long ownerCount(UUID tenantId, long gameDesignVersionId) {
    return count(
        "SELECT COUNT(*) FROM world_design_publication_fence_owner fence_owner "
            + "JOIN world_authored_version_identity version_identity "
            + "ON version_identity.target_namespace = fence_owner.target_namespace "
            + "AND version_identity.canonical_tenant_id = fence_owner.canonical_tenant_id "
            + "AND version_identity.local_version_key = fence_owner.version_id "
            + "WHERE fence_owner.canonical_tenant_id = ? "
            + "AND version_identity.game_design_version_id = ?",
        tenantId,
        gameDesignVersionId);
  }

  private long attemptCount(UUID tenantId, long gameDesignVersionId) {
    return count(
        "SELECT COUNT(*) FROM world_design_publication_fence_attempt fence_attempt "
            + "JOIN world_authored_version_identity version_identity "
            + "ON version_identity.target_namespace = fence_attempt.target_namespace "
            + "AND version_identity.canonical_tenant_id = fence_attempt.canonical_tenant_id "
            + "AND version_identity.local_version_key = fence_attempt.version_id "
            + "WHERE fence_attempt.canonical_tenant_id = ? "
            + "AND version_identity.game_design_version_id = ?",
        tenantId,
        gameDesignVersionId);
  }

  private String ownerPhase(UUID tenantId, long gameDesignVersionId) {
    var row =
        dsl.fetchOne(
            "SELECT CASE WHEN COUNT(*) = 0 THEN NULL "
                + "WHEN BOOL_AND(fence_owner.owner_freeze_phase = 'OPEN') THEN 'OPEN' "
                + "WHEN BOOL_AND(fence_owner.owner_freeze_phase = 'FROZEN') THEN 'FROZEN' "
                + "ELSE 'MIXED' END "
                + "FROM world_design_publication_fence_owner fence_owner "
                + "JOIN world_authored_version_identity version_identity "
                + "ON version_identity.target_namespace = fence_owner.target_namespace "
                + "AND version_identity.canonical_tenant_id = fence_owner.canonical_tenant_id "
                + "AND version_identity.local_version_key = fence_owner.version_id "
                + "WHERE fence_owner.canonical_tenant_id = ? "
                + "AND version_identity.game_design_version_id = ?",
            tenantId,
            gameDesignVersionId);
    if (row == null) {
      throw new IllegalStateException("World publication-fence owner phase query returned no row");
    }
    return row.get(0, String.class);
  }

  private long count(String sql, UUID tenantId, long gameDesignVersionId) {
    return Objects.requireNonNull(dsl.fetchOne(sql, tenantId, gameDesignVersionId))
        .get(0, Long.class);
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
