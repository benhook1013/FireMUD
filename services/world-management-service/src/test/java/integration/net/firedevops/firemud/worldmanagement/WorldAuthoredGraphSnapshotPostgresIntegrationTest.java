package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService.WorldDraftDesignDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotCapture;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.MissingOwnerHistoryException;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionGraphStager.UnverifiedStagedContent;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionStagingService;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.jooq.DSLContext;
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
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldAuthoredGraphSnapshotPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final long LARGE_VALUE = 9_007_199_254_740_999L;
  private static final long GAME_DESIGN_VERSION_ID = 9_000_000_000_000_001L;
  private static final List<String> SYNTHETIC_RETENTION_TABLES =
      List.of(
          "region",
          "zone",
          "room",
          "room_exit",
          "generation_rule",
          "world_entity_spawn_binding",
          "world_design_revision_ledger");

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

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private WorldAuthoredSourceIntakeRepository intakeRepository;
  @Autowired private WorldDesignPublicationFenceRepository publicationFenceRepository;
  @Autowired private WorldAuthoredGraphSnapshotRepository snapshotRepository;
  @Autowired private WorldDraftDesignDigestService draftDigestService;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void exactSnapshotSurvivesRollbackAndConcurrentRetryWithFullGraphReadback() throws Exception {
    Fixture fixture = fixture(false);
    WorldAuthoredGraphSnapshotCapture capture = captureComponent();

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          captureWithGuardedSessionRole(capture, fixture.request());
                          throw new ForcedRollbackException();
                        }))
        .isInstanceOf(ForcedRollbackException.class);
    assertThat(snapshotCount(fixture.request().publicationFence())).isZero();

    CountDownLatch firstCaptured = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<WorldAuthoredGraphSnapshot> first =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            WorldAuthoredGraphSnapshot snapshot =
                                captureWithGuardedSessionRole(capture, fixture.request());
                            firstCaptured.countDown();
                            await(allowFirstCommit);
                            return snapshot;
                          }));
      assertThat(firstCaptured.await(15, TimeUnit.SECONDS)).isTrue();
      Future<WorldAuthoredGraphSnapshot> second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                return ownerTransaction()
                    .execute(status -> captureWithGuardedSessionRole(capture, fixture.request()));
              });
      assertThat(secondStarted.await(15, TimeUnit.SECONDS)).isTrue();
      allowFirstCommit.countDown();

      WorldAuthoredGraphSnapshot firstSnapshot = first.get(20, TimeUnit.SECONDS);
      WorldAuthoredGraphSnapshot retrySnapshot = second.get(20, TimeUnit.SECONDS);
      assertThat(retrySnapshot.snapshotId()).isEqualTo(firstSnapshot.snapshotId());
      assertThat(retrySnapshot.graphBytes()).containsExactly(firstSnapshot.graphBytes());
      assertThat(retrySnapshot.ownerCommitProofStatus())
          .isEqualTo(WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED);
      assertThat(retrySnapshot.gameDesignVersionId()).isEqualTo(fixture.gameDesignVersionId());
      assertThat(retrySnapshot.localVersionKey()).isEqualTo(fixture.localVersionKey());
      assertThat(retrySnapshot.localVersionKey()).isNotEqualTo(GAME_DESIGN_VERSION_ID);
      assertThat(retrySnapshot.versionIdentityOperationId())
          .isEqualTo(fixture.versionIdentityOperationId());
      String graph = new String(retrySnapshot.graphBytes(), StandardCharsets.UTF_8);
      assertThat(graph)
          .contains(
              "\"regions\"",
              "\"zones\"",
              "\"rooms\"",
              "\"roomExits\"",
              "\"generationRules\"",
              "\"worldEntitySpawnBindings\"",
              Long.toString(LARGE_VALUE));
      assertThat(snapshotCount(fixture.request().publicationFence())).isEqualTo(1L);

      CaptureRequest changedTuples =
          copyRequest(
              fixture.request(),
              List.of(
                  new OwnedAffectedTuple(
                      "WORLD_MANAGEMENT", "ROOM", "8", "ZONE_SUBTREE", "7", "12")));
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(status -> captureWithGuardedSessionRole(capture, changedTuples)))
          .isInstanceOf(SnapshotConflictException.class)
          .hasMessageContaining("changed source or request binding");

      CaptureRequest changedCommit =
          new CaptureRequest(
              fixture.request().targetNamespace(),
              fixture.request().canonicalTenantId(),
              fixture.request().canonicalVersionId(),
              fixture.request().intakeRequestId(),
              fixture.request().publicationFence(),
              fixture.request().publicationRequestId(),
              fixture.request().requestDigest(),
              fixture.request().versionStateEpoch(),
              fixture.request().publishWorkflowId(),
              "different-commit",
              fixture.request().contentDigest(),
              fixture.request().digestSchemaVersion(),
              fixture.request().suppliedOwnedAffectedTuples());
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(status -> captureWithGuardedSessionRole(capture, changedCommit)))
          .isInstanceOf(SnapshotConflictException.class)
          .hasMessageContaining("complete immutable V25 freeze attempt");

      CaptureRequest wrongSource =
          new CaptureRequest(
              NAMESPACE,
              fixture.request().canonicalTenantId(),
              UUID.randomUUID(),
              fixture.request().intakeRequestId(),
              fixture.request().publicationFence(),
              fixture.request().publicationRequestId(),
              fixture.request().requestDigest(),
              fixture.request().versionStateEpoch(),
              fixture.request().publishWorkflowId(),
              fixture.request().appliedCommitId(),
              fixture.request().contentDigest(),
              fixture.request().digestSchemaVersion(),
              fixture.request().suppliedOwnedAffectedTuples());
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(status -> captureWithGuardedSessionRole(capture, wrongSource)))
          .isInstanceOf(MissingOwnerHistoryException.class);

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE world_authored_graph_snapshot SET graph_sha256 = ? WHERE snapshot_id = ?",
                      "b".repeat(64),
                      firstSnapshot.snapshotId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      WorldAuthoredGraphSnapshot immutableReadback =
          ownerTransaction()
              .execute(
                  status -> snapshotRepository.findByFence(fixture.request().publicationFence()));
      assertThat(immutableReadback.graphSha256()).isEqualTo(firstSnapshot.graphSha256());
    } finally {
      allowFirstCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void wrongTenantVersionParentEdgeDeniesCaptureBeforeSnapshotRetention() {
    Fixture fixture = fixture(true);
    WorldAuthoredGraphSnapshotCapture capture = captureComponent();

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(status -> captureWithGuardedSessionRole(capture, fixture.request())))
        .isInstanceOf(SnapshotConflictException.class)
        .hasMessageContaining("zone parent is missing or belongs to another tenant/Version");
    assertThat(snapshotCount(fixture.request().publicationFence())).isZero();
  }

  @Test
  void schemaOneAttemptCannotOmitExactIntakeRequestDigestOrMutateOwnerHistory() {
    Fixture fixture = fixture(false);
    WorldAuthoredSourceIntakeReceipt intake = fixture.intakeReceipt();
    long ownerCountBefore = ownerCount(intake.canonicalTenantId(), fixture.localVersionKey());
    long attemptCountBefore = attemptCount(intake.canonicalTenantId(), fixture.localVersionKey());

    String publicationRequestId = "missing-intake-request-digest-" + UUID.randomUUID();
    String workflowId =
        PublicationDigestRequestBinding.full(
                intake.canonicalTenantId().toString(),
                Long.toString(fixture.gameDesignVersionId()),
                publicationRequestId)
            .derivedWorkflowIdentity();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO world_design_publication_fence_attempt ("
                        + "publication_fence, target_namespace, canonical_tenant_id, "
                        + "local_tenant_key, version_id, owner_binding_schema_version, "
                        + "canonical_version_id, version_identity_operation_id, "
                        + "game_design_version_id, intake_operation_id, intake_request_id, "
                        + "intake_request_digest, source_operation_id, source_evidence_digest, "
                        + "intake_receipt_digest, publication_request_id, request_digest, "
                        + "version_state_epoch, publish_workflow_id, applied_commit_id, "
                        + "content_digest, digest_schema_version) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2)",
                    UUID.randomUUID(),
                    NAMESPACE,
                    intake.canonicalTenantId(),
                    intake.localTenantKey(),
                    fixture.localVersionKey(),
                    fixture.request().canonicalVersionId(),
                    fixture.versionIdentityOperationId(),
                    fixture.gameDesignVersionId(),
                    intake.operationId(),
                    intake.intakeRequestId(),
                    intake.sourceOperationId(),
                    intake.sourceEvidenceDigest(),
                    intake.receiptDigest(),
                    publicationRequestId,
                    REQUEST_DIGEST,
                    fixture.request().versionStateEpoch(),
                    workflowId,
                    "unbound-commit",
                    fixture.request().contentDigest()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);

    assertThat(ownerCount(intake.canonicalTenantId(), fixture.localVersionKey()))
        .isEqualTo(ownerCountBefore);
    assertThat(attemptCount(intake.canonicalTenantId(), fixture.localVersionKey()))
        .isEqualTo(attemptCountBefore);
    assertThat(snapshotCount(fixture.request().publicationFence())).isZero();
  }

  @Test
  void canonicalAuthoredRowsStillRejectLegacyWritesAfterSyntheticFixtureSetup() {
    Fixture fixture = fixture(false);
    WorldAuthoredGraphSnapshotCapture capture = captureComponent();
    WorldAuthoredGraphSnapshot retainedSnapshot =
        ownerTransaction()
            .execute(status -> captureWithGuardedSessionRole(capture, fixture.request()));
    List<Long> retainedRowsBefore = syntheticRetentionRowCounts(fixture);

    assertThat(retainedRowsBefore).containsExactly(1L, 1L, 1L, 1L, 1L, 1L, 1L);
    assertThat(retainedSnapshot.ownerCommitProofStatus())
        .isEqualTo(WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED);
    assertThat(snapshotCount(fixture.request().publicationFence())).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          assertGuardedSessionReplicationRole();
                          dsl.execute(
                              "INSERT INTO region (name, tenant_id, version_id, generation_seed) "
                                  + "VALUES ('legacy bypass attempt', ?, ?, ?)",
                              fixture.intakeReceipt().localTenantKey(),
                              fixture.localVersionKey(),
                              LARGE_VALUE);
                          return null;
                        }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .satisfies(this::assertV24CanonicalKeyDenial);

    assertThat(syntheticRetentionRowCounts(fixture)).isEqualTo(retainedRowsBefore);
    assertThat(snapshotCount(fixture.request().publicationFence())).isEqualTo(1L);
    WorldAuthoredGraphSnapshot readback =
        ownerTransaction()
            .execute(
                status -> snapshotRepository.findByFence(fixture.request().publicationFence()));
    assertThat(readback.snapshotId()).isEqualTo(retainedSnapshot.snapshotId());
    assertThat(readback.graphBytes()).containsExactly(retainedSnapshot.graphBytes());
    assertThat(readback.graphSha256()).isEqualTo(retainedSnapshot.graphSha256());
    assertThat(readback.captureRequestDigest()).isEqualTo(retainedSnapshot.captureRequestDigest());
    assertThat(readback.suppliedOwnedAffectedTuplesJson())
        .isEqualTo(retainedSnapshot.suppliedOwnedAffectedTuplesJson());
    assertThat(readback.ownerRevisionEvidenceJson())
        .isEqualTo(retainedSnapshot.ownerRevisionEvidenceJson());
    assertThat(readback.ownerCommitProofStatus())
        .isEqualTo(WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED);
  }

  @Test
  void sourceQualifiedOpenStagingReadsAllSixFamiliesWithoutChangingDatabaseContent() {
    Fixture fixture = fixture(false, false);
    WorldDraftRegionCommitPlan plan = stagingPlan(fixture, ownerBinding(fixture), false);
    List<Long> before = syntheticRetentionRowCounts(fixture);
    UnverifiedStagedContent staged =
        ownerTransaction()
            .execute(
                status ->
                    new WorldDraftRegionStagingService(dsl, publicationFenceRepository)
                        .stage(plan));

    assertThat(staged.binding()).isSameAs(plan.binding());
    assertThat(staged.ownerBinding()).isSameAs(plan.ownerBinding());
    assertThat(fixture.localVersionKey()).isNotEqualTo(fixture.gameDesignVersionId());
    assertThat(fixture.intakeReceipt().localTenantKey())
        .isNotEqualTo(fixture.intakeReceipt().source().sourceGameRowId());
    assertThat(staged.graph().regions()).hasSize(2);
    assertThat(staged.graph().regions().get(0)).containsEntry("name", "Staged region");
    assertThat(staged.graph().regions().get(1)).containsEntry("name", "Untouched region");
    assertThat(staged.graph().zones()).hasSize(1);
    assertThat(staged.graph().rooms())
        .singleElement()
        .satisfies(room -> assertThat(room).containsEntry("description", null));
    assertThat(staged.graph().roomExits()).hasSize(1);
    assertThat(staged.graph().generationRules()).hasSize(1);
    assertThat(staged.graph().spawnBindings())
        .singleElement()
        .satisfies(binding -> assertThat(binding).containsEntry("entityTemplateId", LARGE_VALUE));
    assertThat(syntheticRetentionRowCounts(fixture)).isEqualTo(before);
    assertThat(snapshotCount(fixture.request().publicationFence())).isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT owner_freeze_phase FROM world_design_publication_fence_owner "
                            + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND version_id = ?",
                        NAMESPACE,
                        fixture.request().canonicalTenantId(),
                        fixture.localVersionKey()),
                    "Staging must retain its exact OPEN owner row")
                .get(0, String.class))
        .isEqualTo("OPEN");

    // Both components must retain identical field/order/null/number encoding. This synthetic
    // snapshot fixture proves representation equality, not synchronized owner application.
    WorldAuthoredGraphSnapshot original =
        ownerTransaction()
            .execute(status -> captureComponent().capture(freezeOpenFixture(fixture)));
    String expected =
        new String(original.graphBytes(), StandardCharsets.UTF_8)
            .replace("\"name\":\"authored region\"", "\"name\":\"Staged region\"");
    assertThat(new String(staged.graph().encode(objectMapper), StandardCharsets.UTF_8))
        .isEqualTo(expected);
  }

  @Test
  void sourceQualifiedStagingRejectsChangedIntakeEvidenceBeforeOwnerCreation() {
    Fixture fixture = fixture(false, false);
    WorldDesignPublicationFenceEvidence.OwnerBinding exact = ownerBinding(fixture);
    WorldDesignPublicationFenceEvidence.OwnerBinding changed =
        new WorldDesignPublicationFenceEvidence.OwnerBinding(
            exact.targetNamespace(),
            exact.canonicalTenantId(),
            exact.canonicalVersionId(),
            exact.versionIdentityOperationId(),
            exact.gameDesignVersionId(),
            exact.intakeRequestId(),
            exact.intakeOperationId(),
            exact.intakeRequestDigest(),
            exact.sourceOperationId(),
            "sha256:" + "f".repeat(64),
            exact.intakeReceiptDigest());
    WorldDraftRegionCommitPlan plan = stagingPlan(fixture, changed, false);
    List<Long> before = syntheticRetentionRowCounts(fixture);

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            new WorldDraftRegionStagingService(dsl, publicationFenceRepository)
                                .stage(plan)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM world_design_publication_fence_owner "
                            + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND version_id = ?",
                        NAMESPACE,
                        fixture.request().canonicalTenantId(),
                        fixture.localVersionKey()),
                    "PostgreSQL must return the owner row count")
                .get(0, Long.class))
        .isZero();
    assertThat(syntheticRetentionRowCounts(fixture)).isEqualTo(before);
  }

  @Test
  void sourceQualifiedStagingRejectsFrozenOwnerBeforeReadingAnInvalidPriorGraph() {
    Fixture fixture = fixture(true);
    WorldDraftRegionCommitPlan plan = stagingPlan(fixture, ownerBinding(fixture), false);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            new WorldDraftRegionStagingService(dsl, publicationFenceRepository)
                                .stage(plan)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("not open");
  }

  @Test
  void sourceQualifiedStagingRejectsCrossVersionParentAndLateInvalidRevisionWithoutAnyApply() {
    Fixture invalidGraph = fixture(true, false);
    WorldDraftRegionCommitPlan graphPlan =
        stagingPlan(invalidGraph, ownerBinding(invalidGraph), false);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            new WorldDraftRegionStagingService(dsl, publicationFenceRepository)
                                .stage(graphPlan)))
        .isInstanceOf(SnapshotConflictException.class)
        .hasMessageContaining("zone parent");

    Fixture fixture = fixture(false, false);
    List<Long> before = syntheticRetentionRowCounts(fixture);
    WorldDraftRegionCommitPlan lateInvalid = stagingPlan(fixture, ownerBinding(fixture), true);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            new WorldDraftRegionStagingService(dsl, publicationFenceRepository)
                                .stage(lateInvalid)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not exist");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT name FROM region WHERE tenant_id = ? AND version_id = ? AND id = ?",
                        fixture.intakeReceipt().localTenantKey(),
                        fixture.localVersionKey(),
                        Long.parseLong(
                            fixture.request().suppliedOwnedAffectedTuples().get(0).aggregateId())),
                    "Rejected staging must retain the original region row")
                .get(0, String.class))
        .isEqualTo("authored region");
    assertThat(syntheticRetentionRowCounts(fixture)).isEqualTo(before);
    assertThat(snapshotCount(fixture.request().publicationFence())).isZero();
  }

  private WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding(Fixture fixture) {
    WorldAuthoredSourceIntakeReceipt intake = fixture.intakeReceipt();
    return new WorldDesignPublicationFenceEvidence.OwnerBinding(
        NAMESPACE,
        intake.canonicalTenantId(),
        fixture.request().canonicalVersionId(),
        fixture.versionIdentityOperationId(),
        fixture.gameDesignVersionId(),
        intake.intakeRequestId(),
        intake.operationId(),
        intake.requestDigest(),
        intake.sourceOperationId(),
        intake.sourceEvidenceDigest(),
        intake.receiptDigest());
  }

  private WorldDraftRegionCommitPlan stagingPlan(
      Fixture fixture,
      WorldDesignPublicationFenceEvidence.OwnerBinding owner,
      boolean lateInvalid) {
    UUID commitId = UUID.randomUUID();
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    String regionId = fixture.request().suppliedOwnedAffectedTuples().get(0).aggregateId();
    List<String> ids =
        lateInvalid ? List.of(regionId, Long.toString(Long.MAX_VALUE)) : List.of(regionId);
    for (String id : ids) {
      UUID revisionId = UUID.randomUUID();
      WorldDesignMutationRevision mutation =
          WorldDesignMutationRevision.newBuilder()
              .setLogicalRevisionId(revisionId.toString())
              .setCommitId(commitId.toString())
              .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
              .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
              .setAggregateId(id)
              .setExpectedDraftRevisionEpoch(1L)
              .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
              .setScopeId(id)
              .setExpectedDraftScopeRevisionEpoch(1L)
              .setRegion(
                  RegionDesignMutation.newBuilder()
                      .setName("Staged region")
                      .setGenerationSeed(LARGE_VALUE)
                      .setSpacingMultiplier(1.0d))
              .build();
      try {
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                Integer.toString(revisions.size()),
                revisionId,
                Owner.WORLD_MANAGEMENT,
                JsonFormat.printer().print(mutation)));
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(exception);
      }
      units.add(new AffectedUnit(Owner.WORLD_MANAGEMENT, "REGION", id, "AGGREGATE", id, "1"));
      units.add(new AffectedUnit(Owner.WORLD_MANAGEMENT, "REGION", id, "REGION_SUBTREE", id, "1"));
    }
    AuthoredWorldSourceEvidence source = fixture.intakeReceipt().source();
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                owner.canonicalTenantId(),
                owner.canonicalVersionId(),
                owner.gameDesignVersionId(),
                source.tenantSlug(),
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind()),
            UUID.randomUUID(),
            commitId,
            "synthetic-base",
            revisions,
            units);
    return WorldDraftRegionCommitPlan.create(binding, owner);
  }

  private CaptureRequest freezeOpenFixture(Fixture fixture) {
    CaptureRequest request = fixture.request();
    WorldDesignPublicationFenceEvidence.OwnerBinding owner = ownerBinding(fixture);
    WorldDesignPublicationFenceEvidence evidence =
        new WorldDesignPublicationFenceEvidence(
            owner.targetNamespace(),
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            owner.versionIdentityOperationId(),
            owner.gameDesignVersionId(),
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest(),
            request.publicationRequestId(),
            request.requestDigest(),
            request.versionStateEpoch(),
            request.publishWorkflowId());
    FrozenAttempt frozen =
        publicationFenceRepository.claimFreeze(
            evidence,
            () ->
                new Checkpoint(
                    request.appliedCommitId(),
                    request.contentDigest(),
                    request.digestSchemaVersion()));
    return new CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        frozen.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        request.suppliedOwnedAffectedTuples());
  }

  private Fixture fixture(boolean wrongRoomParent) {
    return fixture(wrongRoomParent, true);
  }

  private Fixture fixture(boolean wrongRoomParent, boolean freeze) {
    UUID canonicalTenantId = UUID.randomUUID();
    String tenantSlug = "tenant-" + canonicalTenantId.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    // Synthetic authenticated-source prerequisite only. Persist the real intake in its own
    // transaction, then read its committed result before any owner resolution or freeze.
    AuthoredWorldSourceEvidence source =
        source(canonicalTenantId, tenantSlug, worldSlug, positiveLong());
    UUID intakeRequestId = UUID.randomUUID();
    WorldAuthoredSourceIntakeReceipt accepted =
        ownerTransaction()
            .execute(status -> intakeRepository.acceptFresh(NAMESPACE, intakeRequestId, source));
    WorldAuthoredSourceIntakeReceipt intake =
        intakeRepository.read(NAMESPACE, intakeRequestId).orElseThrow();
    assertThat(intake).isEqualTo(accepted);
    return ownerTransaction()
        .execute(
            status -> {
              long gameDesignVersionId = GAME_DESIGN_VERSION_ID;
              UUID canonicalVersion = UUID.randomUUID();
              UUID versionStateReadRequestId = UUID.randomUUID();
              AuthoredWorldVersionStateEvidence.Request versionStateRequest =
                  new AuthoredWorldVersionStateEvidence.Request(
                      1,
                      NAMESPACE,
                      versionStateReadRequestId,
                      canonicalTenantId,
                      worldSlug,
                      intake.sourceOperationId(),
                      intake.sourceEvidenceDigest(),
                      gameDesignVersionId);
              AuthoredWorldVersionStateEvidence versionStateEvidence =
                  AuthoredWorldVersionStateEvidence.create(
                      versionStateRequest,
                      source,
                      canonicalVersion,
                      VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                      1L);
              WorldAuthoredVersionIdentityReceipt versionIdentity =
                  new WorldAuthoredVersionIdentityRepository(dsl)
                      .acceptFresh(intake, versionStateEvidence);
              long localVersionKey = versionIdentity.localVersionKey();

              Long wrongParentZoneId = null;
              if (wrongRoomParent) {
                long legacyTenantId = positiveLong();
                long foreignRegionId =
                    Objects.requireNonNull(
                        dsl.resultQuery(
                                "INSERT INTO region (name, tenant_id, version_id) "
                                    + "VALUES ('foreign region', ?, 777777) RETURNING id",
                                legacyTenantId)
                            .fetchOne(0, Long.class),
                        "Foreign region insert did not return its generated ID");
                wrongParentZoneId =
                    Objects.requireNonNull(
                        dsl.resultQuery(
                                "INSERT INTO zone (region_id, name, tenant_id, version_id) "
                                    + "VALUES (?, 'foreign zone', ?, 777777) RETURNING id",
                                foreignRegionId,
                                legacyTenantId)
                            .fetchOne(0, Long.class),
                        "Foreign zone insert did not return its generated ID");
              }
              // This synthetic component fixture stipulates owner-applied rows; no authenticated
              // canonical World owner producer exists or is proved by this fixture.
              SyntheticRows syntheticRows =
                  seedSyntheticCanonicalRows(
                      intake.localTenantKey(), localVersionKey, wrongParentZoneId, !freeze);
              assertGuardedSessionReplicationRole();
              WorldDraftDesignDigest digest =
                  draftDigestService.getDraftDesignDigest(
                      Long.toString(intake.localTenantKey()), Long.toString(localVersionKey));

              String publicationRequestId = "request-" + UUID.randomUUID();
              String publishWorkflowId =
                  PublicationDigestRequestBinding.full(
                          canonicalTenantId.toString(),
                          Long.toString(gameDesignVersionId),
                          publicationRequestId)
                      .derivedWorkflowIdentity();
              long versionStateEpoch = 7L;
              assertGuardedSessionReplicationRole();
              WorldDesignPublicationFenceEvidence fenceRequest =
                  new WorldDesignPublicationFenceEvidence(
                      NAMESPACE,
                      canonicalTenantId,
                      canonicalVersion,
                      versionIdentity.operationId(),
                      gameDesignVersionId,
                      intake.intakeRequestId(),
                      intake.operationId(),
                      intake.requestDigest(),
                      intake.sourceOperationId(),
                      intake.sourceEvidenceDigest(),
                      intake.receiptDigest(),
                      publicationRequestId,
                      REQUEST_DIGEST,
                      versionStateEpoch,
                      publishWorkflowId);
              UUID publicationFence =
                  freeze
                      ? publicationFenceRepository
                          .claimFreeze(
                              fenceRequest,
                              () ->
                                  new Checkpoint(
                                      syntheticRows.commitId(),
                                      digest.contentDigest(),
                                      digest.digestSchemaVersion()))
                          .publicationFence()
                      : UUID.randomUUID();
              CaptureRequest request =
                  new CaptureRequest(
                      NAMESPACE,
                      canonicalTenantId,
                      canonicalVersion,
                      intakeRequestId,
                      publicationFence,
                      publicationRequestId,
                      REQUEST_DIGEST,
                      versionStateEpoch,
                      publishWorkflowId,
                      syntheticRows.commitId(),
                      digest.contentDigest(),
                      digest.digestSchemaVersion(),
                      List.of(
                          new OwnedAffectedTuple(
                              "WORLD_MANAGEMENT",
                              "WORLD_GENERATION_SUBTREE",
                              Long.toString(syntheticRows.regionId()),
                              "REGION_SUBTREE",
                              Long.toString(syntheticRows.regionId()),
                              "1")));
              return new Fixture(
                  request,
                  localVersionKey,
                  gameDesignVersionId,
                  versionIdentity.operationId(),
                  intake);
            });
  }

  private WorldAuthoredGraphSnapshotCapture captureComponent() {
    return new WorldAuthoredGraphSnapshotCapture(
        dsl, draftDigestService, snapshotRepository, objectMapper);
  }

  private WorldAuthoredGraphSnapshot captureWithGuardedSessionRole(
      WorldAuthoredGraphSnapshotCapture capture, CaptureRequest request) {
    assertGuardedSessionReplicationRole();
    return capture.capture(request);
  }

  private SyntheticRows seedSyntheticCanonicalRows(
      long localTenantKey, long localVersionKey, Long wrongParentZoneId, boolean untouchedRegion) {
    String originalRole = currentSessionReplicationRole();
    assertThat(originalRole).isEqualTo("origin");
    Throwable seedFailure = null;
    try {
      // Bypass V24 triggers only while inserting these synthetic six-family and ledger rows.
      dsl.execute("SET LOCAL session_replication_role = 'replica'");
      assertThat(currentSessionReplicationRole()).isEqualTo("replica");

      long regionId =
          Objects.requireNonNull(
              dsl.resultQuery(
                      "INSERT INTO region (name, tenant_id, version_id, generation_seed) "
                          + "VALUES ('authored region', ?, ?, ?) RETURNING id",
                      localTenantKey,
                      localVersionKey,
                      LARGE_VALUE)
                  .fetchOne(0, Long.class),
              "Region insert did not return its generated ID");
      if (untouchedRegion) {
        dsl.execute(
            "INSERT INTO region (name, tenant_id, version_id, generation_seed) "
                + "VALUES ('Untouched region', ?, ?, ?)",
            localTenantKey,
            localVersionKey,
            Long.MIN_VALUE);
      }
      long zoneId =
          Objects.requireNonNull(
              dsl.resultQuery(
                      "INSERT INTO zone (region_id, name, tenant_id, version_id) "
                          + "VALUES (?, 'authored zone', ?, ?) RETURNING id",
                      regionId,
                      localTenantKey,
                      localVersionKey)
                  .fetchOne(0, Long.class),
              "Zone insert did not return its generated ID");
      long roomParentZoneId = wrongParentZoneId == null ? zoneId : wrongParentZoneId;
      long roomId =
          Objects.requireNonNull(
              dsl.resultQuery(
                      "INSERT INTO room (zone_id, name, description, tenant_id, version_id) "
                          + "VALUES (?, 'authored room', NULL, ?, ?) RETURNING id",
                      roomParentZoneId,
                      localTenantKey,
                      localVersionKey)
                  .fetchOne(0, Long.class),
              "Room insert did not return its generated ID");
      dsl.execute(
          "INSERT INTO room_exit (tenant_id, version_id, from_room_id, to_room_id, direction, cost) "
              + "VALUES (?, ?, ?, ?, 'NORTH', 2)",
          localTenantKey,
          localVersionKey,
          roomId,
          roomId);
      dsl.execute(
          "INSERT INTO generation_rule (tenant_id, version_id, name, scope_type, scope_id, value) "
              + "VALUES (?, ?, 'dungeon', 'REGION_SUBTREE', ?, 'seeded')",
          localTenantKey,
          localVersionKey,
          Long.toString(regionId));
      dsl.execute(
          "INSERT INTO world_entity_spawn_binding (tenant_id, version_id, room_id, "
              + "entity_template_type, entity_template_id, spawn_count, respawn_delay_seconds) "
              + "VALUES (?, ?, ?, 'NPC', ?, 2, 30)",
          localTenantKey,
          localVersionKey,
          roomId,
          LARGE_VALUE);
      String commitId = "commit-" + UUID.randomUUID();
      dsl.execute(
          "INSERT INTO world_design_revision_ledger (tenant_id, version_id, commit_id, "
              + "revision_id, operation_type, aggregate_type, requested_aggregate_id, "
              + "applied_aggregate_id, result, aggregate_epoch_after, scope_epoch_after) "
              + "VALUES (?, ?, ?, 'revision-1', 'UPSERT', 'WORLD_GENERATION_SUBTREE', ?, ?, 'APPLIED', 1, 1)",
          localTenantKey,
          localVersionKey,
          commitId,
          Long.toString(regionId),
          regionId);
      return new SyntheticRows(regionId, commitId);
    } catch (RuntimeException | Error failure) {
      seedFailure = failure;
      throw failure;
    } finally {
      try {
        String restoredRole =
            Objects.requireNonNull(
                dsl.resultQuery(
                        "SELECT set_config('session_replication_role', ?, true)", originalRole)
                    .fetchOne(0, String.class),
                "PostgreSQL did not return restored session_replication_role");
        assertThat(restoredRole).isEqualTo(originalRole);
        assertThat(currentSessionReplicationRole()).isEqualTo(originalRole);
      } catch (RuntimeException | Error restoreFailure) {
        if (seedFailure == null) {
          throw restoreFailure;
        }
        // A PostgreSQL setup error aborts the transaction, so SET LOCAL will revert on rollback.
        // Preserve that original setup error if this explicit restore cannot run in the aborted tx.
        seedFailure.addSuppressed(restoreFailure);
      }
    }
  }

  private String currentSessionReplicationRole() {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT current_setting('session_replication_role')")
            .fetchOne(0, String.class),
        "PostgreSQL did not return session_replication_role");
  }

  private void assertGuardedSessionReplicationRole() {
    assertThat(currentSessionReplicationRole()).isEqualTo("origin");
  }

  private void assertV24CanonicalKeyDenial(Throwable throwable) {
    assertThat(throwable).hasStackTraceContaining("is reserved for a canonical authored source");
    Throwable cause = throwable;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(SQLException.class);
    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
  }

  private CaptureRequest copyRequest(CaptureRequest request, List<OwnedAffectedTuple> tuples) {
    return new CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        tuples);
  }

  private AuthoredWorldSourceEvidence source(
      UUID tenantId, String tenantSlug, String worldSlug, long sourceGameRowId) {
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String displayName = "Synthetic graph snapshot world";
    String sourceTenantKey = "gd-row-" + sourceGameRowId;
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
            sourceGameRowId,
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
        sourceGameRowId,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private long snapshotCount(UUID fence) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM world_authored_graph_snapshot WHERE publication_fence = ?",
                fence)
            .fetchOne(0, Long.class),
        "Snapshot count query returned no value");
  }

  private List<Long> syntheticRetentionRowCounts(Fixture fixture) {
    return SYNTHETIC_RETENTION_TABLES.stream()
        .map(
            table ->
                Objects.requireNonNull(
                    dsl.resultQuery(
                            "SELECT COUNT(*) FROM "
                                + table
                                + " WHERE tenant_id = ? AND version_id = ?",
                            fixture.intakeReceipt().localTenantKey(),
                            fixture.localVersionKey())
                        .fetchOne(0, Long.class),
                    table + " count query returned no value"))
        .toList();
  }

  private long ownerCount(UUID tenantId, long localVersionKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM world_design_publication_fence_owner "
                    + "WHERE canonical_tenant_id = ? AND version_id = ?",
                tenantId,
                localVersionKey)
            .fetchOne(0, Long.class),
        "Owner count query returned no value");
  }

  private long attemptCount(UUID tenantId, long localVersionKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM world_design_publication_fence_attempt "
                    + "WHERE canonical_tenant_id = ? AND version_id = ?",
                tenantId,
                localVersionKey)
            .fetchOne(0, Long.class),
        "Attempt count query returned no value");
  }

  private static long positiveLong() {
    return Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("World graph snapshot transaction barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "World graph snapshot transaction was interrupted", exception);
    }
  }

  private record Fixture(
      CaptureRequest request,
      long localVersionKey,
      long gameDesignVersionId,
      UUID versionIdentityOperationId,
      WorldAuthoredSourceIntakeReceipt intakeReceipt) {}

  private record SyntheticRows(long regionId, String commitId) {}

  private static final class ForcedRollbackException extends RuntimeException {}
}
