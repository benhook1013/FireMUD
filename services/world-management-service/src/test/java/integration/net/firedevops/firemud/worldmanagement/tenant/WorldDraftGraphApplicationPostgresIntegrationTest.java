package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Stipulated held Account ordering proves component atomicity, not authenticated Gameplay handoff.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDraftGraphApplicationPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long GAME_DESIGN_VERSION = 9_000_000_000_000_001L;

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
  @Autowired private PlatformTransactionManager manager;
  @Autowired private WorldAuthoredSourceIntakeRepository intakeRepository;
  @Autowired private WorldDesignPublicationFenceRepository fence;
  @Autowired private ObjectMapper mapper;
  @Autowired private WorldAuthoredGraphSnapshotRepository snapshots;
  @Autowired private WorldLifecycleCommandService lifecycleCommandService;

  @Autowired
  private net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService
      digestService;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void releaseSelectorMaterializesItsOriginalRoomAndExactlyRetriesWithSeparateRuntimeMapping() {
    Fixture f = fixture();
    var original = application(generationFreePlan(f));
    var lastRoom =
        original.plan().graph().nodes().stream()
            .filter(
                node ->
                    node.mutation().getAggregateType()
                        == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .map(node -> node.templateId())
            .toList()
            .getLast();
    var application = withStartRoom(original, lastRoom);
    appliedComponent().apply(application);
    var frozen = capture(application.plan());
    var selector =
        publishedEvidence(
            publishedSelectors().readCommitted(frozen.request().freeze()).orElseThrow());
    var input = preparationInput(f, frozen, selector);
    var retainedEpoch =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT jsonb_typeof(release_attestation_json::jsonb->'worldStartLocationEvidence'->'request'->'versionStateEpoch') AS epoch_type,"
                    + "release_attestation_json::jsonb->'worldStartLocationEvidence'->'request'->>'versionStateEpoch' AS epoch_value "
                    + "FROM world_complete_launch_binding WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                input.gameSessionReadEvidence().targetNamespace(),
                input.gameSessionReadEvidence().canonicalTenantId(),
                input.gameSessionReadEvidence().controlPlaneRequestId()));
    assertThat(retainedEpoch.get("epoch_type", String.class)).isEqualTo("number");
    String retainedEpochValue = retainedEpoch.get("epoch_value", String.class);
    assertThat(retainedEpochValue).isEqualTo(Long.toString(selector.request().versionStateEpoch()));
    var selectorRequestJson = mapper.readTree(selector.canonicalBytes()).get("request");
    assertThat(selectorRequestJson.get("versionStateEpoch").isTextual()).isTrue();
    assertThat(selectorRequestJson.get("versionStateEpoch").textValue())
        .isEqualTo(retainedEpochValue);
    var repository = preparationRepository();
    var service = preparationComponent(repository);
    var result = service.prepare(input);
    assertThat(result.startLocation().roomTemplateId()).isEqualTo(lastRoom);
    assertThat(result.runtimeRoomInstanceId()).isPositive();
    assertThat(result.storageStatus()).isEqualTo("MATERIALIZED_UNVERIFIED");
    assertThat(result.startLocation().roomTemplateId())
        .isNotEqualTo(input.topologyPlan().rooms().getFirst().identity().templateId());
    var mapped =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT m.template_id,r.room_instance_id FROM world_canonical_instance_topology_identity m "
                    + "JOIN room_instance r ON r.id=m.runtime_row_id WHERE m.world_instance_id=? AND m.family='ROOM' AND m.template_id=?",
                result.association().worldInstanceId(),
                lastRoom));
    assertThat(mapped.get("room_instance_id", Long.class))
        .isEqualTo(result.runtimeRoomInstanceId());
    byte[] before = preparationRows(input.canonicalGameInstanceId());
    assertThat(preparationComponent(preparationRepository()).prepare(input)).isEqualTo(result);
    assertThat(preparationRepository().readOwnerPreparation(input)).contains(result);
    assertThat(preparationRows(input.canonicalGameInstanceId())).containsExactly(before);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_canonical_preparation_start_location SET runtime_room_instance_id=runtime_room_instance_id+1 WHERE canonical_game_instance_id=?",
                    input.canonicalGameInstanceId()))
        .hasMessageContaining("immutable and retained");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_canonical_preparation_start_location WHERE canonical_game_instance_id=?",
                    input.canonicalGameInstanceId()))
        .hasMessageContaining("immutable and retained");
    assertOrigin();
  }

  @Test
  void lifecycleReadFollowsCurrentWorldEpochAndReturnsExactCanonicalServiceEcho() {
    Fixture f = fixture();
    var original = application(generationFreePlan(f));
    var roomTemplateId =
        original.plan().graph().nodes().stream()
            .filter(
                node ->
                    node.mutation().getAggregateType()
                        == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .map(node -> node.templateId())
            .toList()
            .getLast();
    var application = withStartRoom(original, roomTemplateId);
    appliedComponent().apply(application);
    var frozen = capture(application.plan());
    var selector =
        publishedEvidence(
            publishedSelectors().readCommitted(frozen.request().freeze()).orElseThrow());
    var input = preparationInput(f, frozen, selector);
    var preparation = preparationComponent(preparationRepository());
    var materialized = preparation.prepare(input);
    var associationRepository = associationRepository();
    var lifecycleRepository =
        new WorldCanonicalInstanceLifecycleReadRepository(dsl, manager, associationRepository);
    var request = lifecycleReadRequest(input);

    var preparing = lifecycleRepository.read(request).orElseThrow();
    assertThat(preparing.request()).isEqualTo(request);
    assertThat(preparing.startLocation())
        .isEqualTo(
            WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes())
                .startLocation());
    assertThat(preparing.startLocation().roomTemplateId()).isEqualTo(roomTemplateId);
    assertThat(preparing.runtimeRoomInstanceId()).isEqualTo(materialized.runtimeRoomInstanceId());
    var mappedRoomOwnership =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT r.tenant_id AS room_tenant_id,r.game_instance_id AS room_game_instance_id,"
                    + "ri.tenant_id AS region_tenant_id,ri.game_instance_id AS region_game_instance_id,"
                    + "ri.world_instance_id FROM world_canonical_instance_topology_identity m "
                    + "JOIN room_instance r ON r.id=m.runtime_row_id "
                    + "JOIN region_instance ri ON ri.id=r.region_instance_id "
                    + "WHERE m.world_instance_id=? AND m.canonical_game_instance_id=? AND m.family='ROOM' AND m.template_id=?",
                materialized.association().worldInstanceId(),
                input.canonicalGameInstanceId(),
                roomTemplateId));
    assertThat(mappedRoomOwnership.get("room_tenant_id", Long.class))
        .isEqualTo(materialized.association().worldPrepareFields().privateTenantKey());
    assertThat(mappedRoomOwnership.get("room_game_instance_id", Long.class))
        .isEqualTo(materialized.association().worldPrepareFields().privateGameInstanceKey());
    assertThat(mappedRoomOwnership.get("region_tenant_id", Long.class))
        .isEqualTo(materialized.association().worldPrepareFields().privateTenantKey());
    assertThat(mappedRoomOwnership.get("region_game_instance_id", Long.class))
        .isEqualTo(materialized.association().worldPrepareFields().privateGameInstanceKey());
    assertThat(mappedRoomOwnership.get("world_instance_id", Long.class))
        .isEqualTo(materialized.association().worldInstanceId());
    assertThat(preparing.lifecycleStatus()).isEqualTo("PREPARING");
    assertThat(preparing.lifecycleEpoch()).isEqualTo(1L);
    assertThat(preparing.captureId()).isEqualTo(input.captureId());
    assertThat(preparing.graphSha256()).isEqualTo(materialized.graphSha256());
    assertThat(preparing.preparationInputDigest()).isEqualTo(materialized.inputDigest());

    TransactionTemplate ownerSnapshot = new TransactionTemplate(manager);
    ownerSnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    ownerSnapshot.setReadOnly(true);
    ownerSnapshot.execute(
        status -> {
          assertThatThrownBy(
                  () ->
                      associationRepository.readOwnerAssociation(request.canonicalGameInstanceId()))
              .hasMessageContaining("independent committed owner read");
          assertThatThrownBy(
                  () ->
                      new WorldCompleteLaunchBindingRepository(dsl)
                          .read(
                              input.gameSessionReadEvidence().targetNamespace(),
                              input.gameSessionReadEvidence().canonicalTenantId(),
                              input.gameSessionReadEvidence().controlPlaneRequestId()))
              .hasMessageContaining("independent committed owner read");
          assertThatThrownBy(
                  () ->
                      new WorldAuthoredSourceIntakeRepository(dsl)
                          .read(
                              input.gameSessionReadEvidence().targetNamespace(),
                              input.versionIdentity().sourceIntakeReceipt().intakeRequestId()))
              .hasMessageContaining("committed-outcome owner read");
          assertThatThrownBy(
                  () ->
                      new WorldAuthoredVersionIdentityRepository(dsl)
                          .readByCanonicalVersion(
                              input.gameSessionReadEvidence().targetNamespace(),
                              input.gameSessionReadEvidence().canonicalTenantId(),
                              input.gameSessionReadEvidence().worldSlug(),
                              input.versionIdentity().canonicalVersionId()))
              .hasMessageContaining("independent committed owner read");
          assertThat(
                  associationRepository.readOwnerAssociationInOwnerTransaction(
                      request.canonicalGameInstanceId()))
              .isPresent();
          return null;
        });

    assertThat(
            lifecycleRepository
                .read(
                    lifecycleReadRequest(
                        input,
                        "other",
                        request.canonicalTenantId(),
                        request.canonicalVersionId(),
                        request.expectedDescriptorRequestDigest()))
                .isEmpty())
        .isTrue();
    assertThat(
            lifecycleRepository
                .read(
                    lifecycleReadRequest(
                        input,
                        NAMESPACE,
                        UUID.randomUUID(),
                        request.canonicalVersionId(),
                        request.expectedDescriptorRequestDigest()))
                .isEmpty())
        .isTrue();
    assertThat(
            lifecycleRepository
                .read(
                    lifecycleReadRequest(
                        input,
                        NAMESPACE,
                        request.canonicalTenantId(),
                        UUID.randomUUID(),
                        request.expectedDescriptorRequestDigest()))
                .isEmpty())
        .isTrue();
    assertThat(
            lifecycleRepository
                .read(
                    lifecycleReadRequest(
                        input,
                        NAMESPACE,
                        request.canonicalTenantId(),
                        request.canonicalVersionId(),
                        "sha256:" + "d".repeat(64)))
                .isEmpty())
        .isTrue();
    assertThat(
            lifecycleRepository
                .read(
                    lifecycleReadRequest(
                        input,
                        NAMESPACE,
                        request.canonicalTenantId(),
                        request.canonicalVersionId(),
                        request.expectedDescriptorRequestDigest(),
                        "sha256:" + "e".repeat(64)))
                .isEmpty())
        .isTrue();

    var prepareFields = materialized.association().worldPrepareFields();
    lifecycleCommandService.failPreparedWorldInstance(
        prepareFields.privateTenantKey(),
        prepareFields.privateGameInstanceKey(),
        preparing.lifecycleEpoch(),
        "integration lifecycle read movement");
    var failedCurrent = lifecycleRepository.read(request).orElseThrow();
    assertThat(failedCurrent.lifecycleStatus()).isEqualTo("FAILED_PRE_ACTIVATION");
    assertThat(failedCurrent.lifecycleEpoch()).isEqualTo(2L);
    assertThat(failedCurrent.rowVersion()).isGreaterThan(preparing.rowVersion());

    // The real V35 retry preserves immutable preparation history and does not rewrite lifecycle.
    assertThat(preparation.prepare(input)).isEqualTo(materialized);
    var afterRetry = lifecycleRepository.read(request).orElseThrow();
    assertThat(afterRetry).isEqualTo(failedCurrent);

    // Explicitly injected peer identity is a transport double; this does not prove production mTLS.
    var adapter =
        new WorldCanonicalInstanceLifecycleReadGrpcService(lifecycleRepository, NAMESPACE);
    var response = new LifecycleReadCollector();
    Context context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
                    NAMESPACE,
                    "game-session-service"));
    Context previous = context.attach();
    try {
      adapter.readWorldCanonicalInstanceLifecycle(
          WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(request), response);
    } finally {
      context.detach(previous);
    }
    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(WorldCanonicalInstanceLifecycleGrpcCodec.fromResponse(request, response.value))
        .isEqualTo(afterRetry);
    assertOrigin();
  }

  @Test
  void releaseSelectorRejectsChangedFrozenRequestAndSqlSubstitutionWithoutRuntimeWrites() {
    Fixture f = fixture();
    var application = application(generationFreePlan(f));
    appliedComponent().apply(application);
    var frozen = capture(application.plan());
    var selector =
        publishedEvidence(
            publishedSelectors().readCommitted(frozen.request().freeze()).orElseThrow());
    var selected = selector.request();
    var changed =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                selected.targetNamespace(),
                selected.canonicalTenantId(),
                selected.canonicalVersionId(),
                selected.intakeRequestId(),
                UUID.randomUUID(),
                selected.publicationRequestId(),
                selected.requestDigest(),
                selected.versionStateEpoch(),
                selected.publishWorkflowId(),
                selected.appliedCommitId(),
                selected.contentDigest(),
                selected.digestSchemaVersion(),
                selected.worldAffectedTuples()),
            selector.selectorReceiptBytes(),
            selector.originalAccountBindingBytes(),
            selector.appliedResultBytes());
    assertThatThrownBy(() -> preparationInput(f, frozen, changed))
        .hasMessageContaining("exact frozen request");
    var input = preparationInput(f, frozen, selector);
    var encoded =
        (tools.jackson.databind.node.ObjectNode)
            mapper.readTree(WorldCanonicalInstancePreparationRepository.inputJson(input));
    encoded.put(
        "worldStartLocationEvidenceBase64",
        java.util.Base64.getEncoder().encodeToString(changed.canonicalBytes()));
    String substituted = mapper.writeValueAsString(encoded);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            dsl.fetchOne(
                                "SELECT * FROM world_prepare_canonical_instance(?,?)",
                                substituted,
                                WorldDraftGraphAppliedResult.digest(
                                    substituted.getBytes(StandardCharsets.UTF_8)))))
        .hasMessageContaining("complete frozen request");

    var changedEpoch =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                selected.targetNamespace(),
                selected.canonicalTenantId(),
                selected.canonicalVersionId(),
                selected.intakeRequestId(),
                selected.publicationFence(),
                selected.publicationRequestId(),
                selected.requestDigest(),
                selected.versionStateEpoch() + 1L,
                selected.publishWorkflowId(),
                selected.appliedCommitId(),
                selected.contentDigest(),
                selected.digestSchemaVersion(),
                selected.worldAffectedTuples()),
            selector.selectorReceiptBytes(),
            selector.originalAccountBindingBytes(),
            selector.appliedResultBytes());
    var changedEpochInput =
        (tools.jackson.databind.node.ObjectNode)
            mapper.readTree(WorldCanonicalInstancePreparationRepository.inputJson(input));
    changedEpochInput.put(
        "worldStartLocationEvidenceBase64",
        java.util.Base64.getEncoder().encodeToString(changedEpoch.canonicalBytes()));
    String substitutedEpoch = mapper.writeValueAsString(changedEpochInput);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            dsl.fetchOne(
                                "SELECT * FROM world_prepare_canonical_instance(?,?)",
                                substitutedEpoch,
                                WorldDraftGraphAppliedResult.digest(
                                    substitutedEpoch.getBytes(StandardCharsets.UTF_8)))))
        .hasMessageContaining("complete frozen request");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM world_instance WHERE tenant_id=?",
                        f.intake().localTenantKey()))
                .get(0, Long.class))
        .isZero();
    assertThat(preparationRepository().readOwnerPreparation(input)).isEmpty();
    assertOrigin();
  }

  @Test
  void inertV1PreparationRetainsOriginalBytesAndCannotGainSelectorOnRetry() {
    Fixture f = fixture();
    var application = application(generationFreePlan(f));
    appliedComponent().apply(application);
    var frozen = capture(application.plan());
    var input = preparationInput(f, frozen, null);
    var repository = preparationRepository();
    var first = preparationComponent(repository).prepare(input);
    assertThat(first.startLocation()).isNull();
    assertThat(first.runtimeRoomInstanceId()).isNull();
    byte[] before = preparationRows(input.canonicalGameInstanceId());
    assertThat(preparationComponent(preparationRepository()).prepare(input)).isEqualTo(first);
    assertThat(preparationRows(input.canonicalGameInstanceId())).containsExactly(before);
    assertThat(WorldCanonicalInstancePreparationRepository.inputJson(input))
        .doesNotContain("worldStartLocationEvidence");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM world_canonical_preparation_start_location WHERE canonical_game_instance_id=?",
                        input.canonicalGameInstanceId()))
                .get(0, Long.class))
        .isZero();
    // A new direct selector write cannot reinterpret the existing V1 preparation.
    var receipt =
        appliedRepository()
            .readCommitted(application)
            .orElseThrow()
            .startLocationReceipt()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO world_canonical_preparation_start_location (canonical_game_instance_id,world_instance_id,canonical_tenant_id,canonical_version_id,room_template_id,"
                        + "runtime_room_instance_id,receipt_digest,graph_digest,evidence_bytes) VALUES (?,?,?,?,?,1,?,?,?)",
                    input.canonicalGameInstanceId(),
                    first.association().worldInstanceId(),
                    receipt.startLocation().tenantId(),
                    receipt.startLocation().versionId(),
                    receipt.startLocation().roomTemplateId(),
                    receipt.receiptDigest(),
                    receipt.graphDigest(),
                    receipt.canonicalBytes()))
        .isInstanceOf(RuntimeException.class);
    assertThat(preparationRows(input.canonicalGameInstanceId())).containsExactly(before);
    assertOrigin();
  }

  @Test
  void selectorPreparationLateFenceLossRollsBackSelectorRuntimeAndAssociationTogether() {
    Fixture f = fixture();
    var application = application(generationFreePlan(f));
    appliedComponent().apply(application);
    var frozen = capture(application.plan());
    var selector =
        publishedEvidence(
            publishedSelectors().readCommitted(frozen.request().freeze()).orElseThrow());
    var input = preparationInput(f, frozen, selector);
    var component =
        new WorldCanonicalInstancePreparationService(
            preparationRepository(),
            ignored ->
                new WorldCanonicalInstancePreparationService.HeldCommitAuthority() {
                  private int calls;

                  public void requireHeld() {
                    calls++;
                    if (calls == 3) {
                      assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                          .isTrue();
                      assertThat(
                              Objects.requireNonNull(
                                      dsl.fetchOne(
                                          "SELECT count(*) FROM world_canonical_preparation_start_location WHERE canonical_game_instance_id=?",
                                          input.canonicalGameInstanceId()))
                                  .get(0, Long.class))
                          .isEqualTo(1L);
                      throw new IllegalStateException("stipulated late Account fence loss");
                    }
                  }

                  public void close() {}
                });
    assertThatThrownBy(() -> component.prepare(input))
        .hasMessageContaining("late Account fence loss");
    for (String table :
        List.of(
            "world_instance",
            "world_canonical_instance_association",
            "world_canonical_instance_preparation",
            "world_canonical_preparation_start_location")) {
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT count(*) FROM " + table + " WHERE canonical_game_instance_id=?",
                          input.canonicalGameInstanceId()))
                  .get(0, Long.class))
          .isZero();
    }
    assertThat(appliedRepository().readCommitted(application).orElseThrow().canonicalBytes())
        .containsExactly(selector.appliedResultBytes());
    assertOrigin();
  }

  private WorldDraftTopologyCommitPlan generationFreePlan(Fixture f) {
    var original = plan(f);
    var binding = original.binding();
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                Integer.toString(revisions.size()),
                revision.revisionId(),
                revision.owner(),
                revision.payload()));
        continue;
      }
      try {
        var value = WorldDesignMutationRevision.newBuilder();
        JsonFormat.parser().merge(revision.payload(), value);
        if (value.hasGenerationRule() || value.hasWorldEntitySpawnBinding()) continue;
        if (value.hasRegion())
          value.setRegion(
              value.getRegion().toBuilder()
                  .clearGenerationSeed()
                  .clearGeneratorType()
                  .clearGeneratorParams());
        if (value.hasFreshGraphDeclaration()) {
          var declaration = value.getFreshGraphDeclaration().toBuilder();
          for (int index = 0; index < declaration.getFamilyCountsCount(); index++) {
            var family = declaration.getFamilyCounts(index).getFamily();
            if (family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE
                || family
                    == WorldDesignAggregateType
                        .WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING) {
              declaration.setFamilyCounts(
                  index, declaration.getFamilyCounts(index).toBuilder().setCount(0));
            }
          }
          value.setFreshGraphDeclaration(declaration);
        }
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                Integer.toString(revisions.size()),
                revision.revisionId(),
                revision.owner(),
                JsonFormat.printer().print(value)));
      } catch (InvalidProtocolBufferException failure) {
        throw new IllegalStateException(failure);
      }
    }
    return WorldDraftTopologyCommitPlan.create(
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            revisions,
            binding.affectedUnits().stream()
                .filter(
                    unit ->
                        !unit.aggregateType().equals("GENERATION_RULE")
                            && !unit.aggregateType().equals("WORLD_ENTITY_SPAWN_BINDING"))
                .toList()),
        original.ownerBinding());
  }

  @Test
  void v41MaterializedV1HistoryRemainsByteExactAndRetryableAfterV42WithoutSelectorPromotion() {
    String schema = "world_v41_preparation_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_world_management_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("41"))
        .load()
        .migrate();
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    // Reuse exactly the same actual producer/preparation fixture against the historical schema.
    var historical = new WorldDraftGraphApplicationPostgresIntegrationTest();
    historical.dsl =
        DSL.using(
            new TransactionAwareDataSourceProxy(dataSource),
            SQLDialect.POSTGRES,
            new org.jooq.conf.Settings().withRenderSchema(false));
    historical.manager = new DataSourceTransactionManager(dataSource);
    historical.intakeRepository = proxiedIntakeRepository(historical.dsl, historical.manager);
    historical.fence =
        new WorldDesignPublicationFenceRepository(historical.dsl, historical.intakeRepository);
    historical.mapper = mapper;
    historical.snapshots = new WorldAuthoredGraphSnapshotRepository(historical.dsl);
    historical.digestService =
        new net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl(
            new net.firedevops.firemud.worldmanagement.repository.RegionRepository(historical.dsl),
            new net.firedevops.firemud.worldmanagement.repository.ZoneRepository(historical.dsl),
            new net.firedevops.firemud.worldmanagement.repository.RoomRepository(historical.dsl),
            new net.firedevops.firemud.worldmanagement.repository.RoomExitRepository(
                historical.dsl),
            new net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository(
                historical.dsl),
            new net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository(
                historical.dsl),
            mapper);
    Fixture f = historical.fixture();
    var application = historical.application(historical.generationFreePlan(f));
    var applied = historical.appliedComponent().apply(application);
    var frozen = historical.capture(application.plan());
    var input = historical.preparationInput(f, frozen, null);
    var first = historical.preparationComponent(historical.preparationRepository()).prepare(input);
    Map<String, String> before = historical.preparationHistoryRows();
    byte[] originalApplied = applied.canonicalBytes();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_world_management_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    assertThat(historical.preparationHistoryRows()).isEqualTo(before);
    assertThat(historical.preparationComponent(historical.preparationRepository()).prepare(input))
        .isEqualTo(first);
    assertThat(historical.preparationHistoryRows()).isEqualTo(before);
    assertThat(
            historical
                .appliedRepository()
                .readCommitted(application)
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(originalApplied);
    assertThat(first.startLocation()).isNull();
    assertThat(first.runtimeRoomInstanceId()).isNull();
    assertThat(
            Objects.requireNonNull(
                    historical.dsl.fetchOne(
                        "SELECT count(*) FROM world_canonical_preparation_start_location"))
                .get(0, Long.class))
        .isZero();
    historical.assertOrigin();
  }

  private Map<String, String> preparationHistoryRows() {
    Map<String, String> result = new LinkedHashMap<>();
    for (String table :
        List.of(
            "world_authored_source_intake",
            "world_authored_version_identity",
            "world_complete_launch_binding",
            "world_topology_draft_commit",
            "world_draft_graph_application",
            "world_draft_start_location_receipt",
            "world_canonical_frozen_topology",
            "world_instance",
            "region_instance",
            "zone_instance",
            "room_instance",
            "room_instance_exit",
            "world_canonical_instance_topology_identity",
            "world_canonical_instance_preparation",
            "world_canonical_instance_association")) {
      result.put(
          table,
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT coalesce(jsonb_agg(value ORDER BY value::text),'[]'::jsonb)::text "
                          + "FROM (SELECT to_jsonb(t) AS value FROM "
                          + table
                          + " t) rows"))
              .get(0, String.class));
    }
    return result;
  }

  private WorldPublishedStartLocationEvidence publishedEvidence(
      WorldPublishedStartLocationSource source) {
    var freeze = source.frozenTopology().request().freeze();
    return new WorldPublishedStartLocationEvidence(
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            freeze.suppliedOwnedAffectedTuples().stream()
                .map(
                    tuple ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            tuple.owner(),
                            tuple.aggregateType(),
                            tuple.aggregateId(),
                            tuple.scopeType(),
                            tuple.scopeId(),
                            tuple.expectedEpoch()))
                .toList()),
        source.selectorReceipt().canonicalBytes(),
        source.appliedResult().application().operation().accountBindingBytes(),
        source.appliedResult().canonicalBytes());
  }

  private WorldCanonicalInstancePreparation.Input preparationInput(
      Fixture f,
      WorldCanonicalFrozenTopology frozen,
      WorldPublishedStartLocationEvidence selector) {
    var source = f.intake().source();
    String control = "prepare-" + UUID.randomUUID();
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            new AuthoredWorldLaunchDescriptorEvidence.Request(
                NAMESPACE,
                control,
                source.canonicalTenantId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                71L,
                false,
                null,
                false,
                null,
                false,
                null,
                false,
                null),
            "ld-" + UUID.randomUUID(),
            GAME_DESIGN_VERSION,
            false,
            null,
            "{}",
            "generation-free",
            2L,
            97L,
            "release-" + UUID.randomUUID(),
            false,
            null);
    var freeze = frozen.request().freeze();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(GAME_DESIGN_VERSION),
                        false,
                        null,
                        freeze.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(owner) ? freeze.contentDigest() : "a".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "b".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        selector == null
            ? AuthoredWorldReleaseAttestationEvidence.create(
                NAMESPACE,
                descriptor.resultDigest(),
                source.canonicalTenantId(),
                f.version().canonicalVersionId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                descriptor.launchDescriptorId(),
                descriptor.publishedReleaseBundleRef(),
                descriptor.versionStateEpoch(),
                freeze.publishWorkflowId(),
                freeze.appliedCommitId(),
                participants,
                "sha256:" + "c".repeat(64),
                1,
                List.of(),
                List.of(),
                List.of("LOOK"),
                descriptor.generationConfigRevision())
            : AuthoredWorldReleaseAttestationEvidence.create(
                NAMESPACE,
                descriptor.resultDigest(),
                source.canonicalTenantId(),
                f.version().canonicalVersionId(),
                source.worldSlug(),
                source.operationId(),
                source.evidenceDigest(),
                descriptor.launchDescriptorId(),
                descriptor.publishedReleaseBundleRef(),
                descriptor.versionStateEpoch(),
                freeze.publishWorkflowId(),
                freeze.appliedCommitId(),
                participants,
                "sha256:" + "c".repeat(64),
                1,
                List.of(),
                List.of(),
                List.of("LOOK"),
                descriptor.generationConfigRevision(),
                selector);
    var evidence = new CompleteLaunchBindingEvidence(descriptor, release);
    var launch =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status ->
                        new WorldCompleteLaunchBindingRepository(dsl)
                            .acceptFresh(NAMESPACE, f.intake(), evidence)));
    UUID instance = UUID.randomUUID();
    UUID read = UUID.randomUUID();
    var request =
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            read,
            NAMESPACE,
            source.canonicalTenantId(),
            source.worldSlug(),
            instance,
            control,
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var response =
        new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
            read,
            NAMESPACE,
            source.canonicalTenantId(),
            source.worldSlug(),
            instance,
            control,
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest(),
            UUID.randomUUID(),
            "SHARED",
            true,
            "PREPARING",
            1L,
            descriptor,
            release);
    return new WorldCanonicalInstancePreparation.Input(
        request, response, launch, f.version(), WorldCanonicalInstanceTopologyPlan.create(frozen));
  }

  private WorldCanonicalInstancePreparationRepository preparationRepository() {
    return new WorldCanonicalInstancePreparationRepository(
        dsl,
        manager,
        new WorldCanonicalInstanceAssociationRepository(
            dsl,
            new WorldCompleteLaunchBindingRepository(dsl),
            new WorldAuthoredSourceIntakeRepository(dsl),
            new WorldAuthoredVersionIdentityRepository(dsl)),
        frozenRepository());
  }

  private WorldCanonicalInstanceAssociationRepository associationRepository() {
    return new WorldCanonicalInstanceAssociationRepository(
        dsl,
        new WorldCompleteLaunchBindingRepository(dsl),
        new WorldAuthoredSourceIntakeRepository(dsl),
        new WorldAuthoredVersionIdentityRepository(dsl));
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleReadRequest(
      WorldCanonicalInstancePreparation.Input input) {
    return lifecycleReadRequest(
        input,
        NAMESPACE,
        input.gameSessionReadEvidence().canonicalTenantId(),
        input.completeLaunchBinding().evidence().releaseAttestation().canonicalVersionId(),
        input.completeLaunchBinding().descriptor().requestDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleReadRequest(
      WorldCanonicalInstancePreparation.Input input,
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String descriptorRequestDigest) {
    return lifecycleReadRequest(
        input,
        targetNamespace,
        canonicalTenantId,
        canonicalVersionId,
        descriptorRequestDigest,
        input.completeLaunchBinding().evidence().releaseAttestation().evidenceDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleReadRequest(
      WorldCanonicalInstancePreparation.Input input,
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String descriptorRequestDigest,
      String expectedReleaseAttestationDigest) {
    var gameSession = input.gameSessionReadEvidence();
    var descriptor = input.completeLaunchBinding().descriptor();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        UUID.randomUUID(),
        targetNamespace,
        canonicalTenantId,
        gameSession.worldSlug(),
        gameSession.canonicalGameInstanceId(),
        gameSession.playableStateNamespaceId(),
        gameSession.playableStateScope(),
        gameSession.publicProduction(),
        gameSession.controlPlaneRequestId(),
        canonicalVersionId,
        descriptorRequestDigest,
        descriptor.resultDigest(),
        expectedReleaseAttestationDigest);
  }

  private WorldCanonicalInstancePreparationService preparationComponent(
      WorldCanonicalInstancePreparationRepository repository) {
    // Stipulated local verifier only: no production producer, currentness, lifecycle or admission
    // claim.
    return new WorldCanonicalInstancePreparationService(
        repository,
        input ->
            new WorldCanonicalInstancePreparationService.HeldCommitAuthority() {
              private boolean open = true;

              public void requireHeld() {
                if (!open) throw new IllegalStateException("closed fixture authority");
              }

              public void close() {
                open = false;
              }
            });
  }

  private byte[] preparationRows(UUID instance) {
    return Objects.requireNonNull(
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT convert_to(jsonb_build_object('preparation',to_jsonb(p),'selector',to_jsonb(s))::text,'UTF8') "
                        + "FROM world_canonical_instance_preparation p LEFT JOIN world_canonical_preparation_start_location s USING(canonical_game_instance_id) WHERE p.canonical_game_instance_id=?",
                    instance))
            .get(0, byte[].class));
  }

  @Test
  void publishedSelectorJoinsExactFrozenCheckpointToOriginalAppliedWithoutWrites() {
    Fixture f = fixture();
    var application = application(f);
    var applied = appliedComponent().apply(application);
    var capture = capture(application.plan());
    var request = capture.request().freeze();
    var source = publishedSelectors().readCommitted(request).orElseThrow();
    assertThat(source.selectorReceipt()).isEqualTo(applied.startLocationReceipt().orElseThrow());
    assertThat(source.appliedResult().canonicalBytes()).containsExactly(applied.canonicalBytes());
    assertThat(source.frozenTopology().resultBytes()).containsExactly(capture.resultBytes());
    assertThat(source.frozenTopology().status()).isEqualTo("CAPTURED_UNVERIFIED");
    assertThat(source.appliedResult().application().operation().accountBindingBytes())
        .containsExactly(application.operation().accountBindingBytes());
    var another = application(fixture());
    var anotherResult = appliedComponent().apply(another);
    var substitutedApplications =
        org.mockito.Mockito.mock(WorldDraftGraphApplicationRepository.class);
    org.mockito.Mockito.when(
            substitutedApplications.readCommitted(
                org.mockito.ArgumentMatchers.eq(NAMESPACE),
                org.mockito.ArgumentMatchers.any(byte[].class)))
        .thenReturn(java.util.Optional.of(anotherResult));
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationRepository(
                        dsl, frozenRepository(), substitutedApplications)
                    .readCommitted(request))
        .hasMessageContaining("differs from original frozen application");
    // A plausible result for a distinct Account operation on the same plan is also substitution.
    org.mockito.Mockito.when(
            substitutedApplications.readCommitted(
                org.mockito.ArgumentMatchers.eq(NAMESPACE),
                org.mockito.ArgumentMatchers.any(byte[].class)))
        .thenReturn(
            java.util.Optional.of(
                WorldDraftGraphAppliedResult.create(
                    changedAccountApplication(application), applied.graphBytes())));
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationRepository(
                        dsl, frozenRepository(), substitutedApplications)
                    .readCommitted(request))
        .hasMessageContaining("differs from original frozen application");
    byte[] before = retainedApplicationBytes(application);
    assertThat(publishedSelectors().readCommitted(request).orElseThrow().selectorReceipt())
        .isEqualTo(source.selectorReceipt());
    assertThat(retainedApplicationBytes(application)).containsExactly(before);
    assertThat(count(f, "world_draft_graph_application")).isEqualTo(1);
    assertThat(count(f, "world_draft_start_location_receipt")).isEqualTo(1);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM world_canonical_frozen_topology WHERE publication_fence=?",
                        request.publicationFence()))
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThatThrownBy(
            () ->
                publishedSelectors()
                    .readCommitted(
                        changedSelection(request, "changed-request", request.contentDigest())))
        .hasMessageContaining("canonical full-version workflow identity");
    assertThatThrownBy(
            () ->
                publishedSelectors()
                    .readCommitted(
                        changedSelection(request, request.publicationRequestId(), "c".repeat(64))))
        .hasMessageContaining("changed complete input or checkpoint");
    assertThatThrownBy(
            () -> ownerTransaction().execute(status -> publishedSelectors().readCommitted(request)))
        .hasMessageContaining("no caller transaction");
    assertOrigin();
  }

  @Test
  void permissionUnverifiedGraphAndFrozenHistoryCannotSupplyPublishedSelector() {
    var plan = plan(fixture());
    component().store(plan);
    var capture = capture(plan);
    assertThat(frozenRepository().readCommitted(capture.request().freeze())).isPresent();
    assertThatThrownBy(() -> publishedSelectors().readCommitted(capture.request().freeze()))
        .hasMessageContaining("lacks original APPLIED application");
    assertOrigin();
  }

  private byte[] retainedApplicationBytes(WorldDraftGraphApplication application) {
    return Objects.requireNonNull(
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT convert_to(to_jsonb(a)::text,'UTF8') FROM world_draft_graph_application a WHERE operation_id=?",
                    application.operation().operationId()))
            .get(0, byte[].class));
  }

  private WorldAuthoredGraphSnapshot.CaptureRequest changedSelection(
      WorldAuthoredGraphSnapshot.CaptureRequest request, String publicationRequest, String digest) {
    return new WorldAuthoredGraphSnapshot.CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        publicationRequest,
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        digest,
        request.digestSchemaVersion(),
        request.suppliedOwnedAffectedTuples());
  }

  private WorldCanonicalFrozenTopologyRepository frozenRepository() {
    return new WorldCanonicalFrozenTopologyRepository(dsl, snapshots, repository(), digestService);
  }

  private WorldPublishedStartLocationRepository publishedSelectors() {
    return new WorldPublishedStartLocationRepository(dsl, frozenRepository(), appliedRepository());
  }

  private WorldCanonicalFrozenTopology capture(WorldDraftTopologyCommitPlan plan) {
    var owner = plan.ownerBinding();
    String publicationRequest = "selector-" + UUID.randomUUID();
    var evidence =
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
            publicationRequest,
            "a".repeat(64),
            1,
            "publish:" + owner.canonicalTenantId() + ":publish-request:" + publicationRequest);
    var attempt =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status ->
                        fence.claimFreeze(
                            evidence,
                            () -> {
                              var identity =
                                  Objects.requireNonNull(
                                      dsl.fetchOne(
                                          "SELECT local_tenant_key,local_version_key FROM world_authored_version_identity WHERE operation_id=?",
                                          owner.versionIdentityOperationId()));
                              var digest =
                                  digestService.getDraftDesignDigest(
                                      identity.get("local_tenant_key", Long.class).toString(),
                                      identity.get("local_version_key", Long.class).toString());
                              return new WorldDesignPublicationFenceEvidence.Checkpoint(
                                  plan.binding().commitId().toString(), digest.contentDigest(), 3);
                            })));
    var tuples =
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var request =
        new WorldAuthoredGraphSnapshot.CaptureRequest(
            owner.targetNamespace(),
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            owner.intakeRequestId(),
            attempt.publicationFence(),
            publicationRequest,
            evidence.requestDigest(),
            evidence.versionStateEpoch(),
            evidence.publishWorkflowId(),
            attempt.checkpoint().appliedCommitId(),
            attempt.checkpoint().contentDigest(),
            attempt.checkpoint().digestSchemaVersion(),
            tuples);
    return new WorldCanonicalFrozenTopologyService(frozenRepository(), manager)
        .capture(new WorldCanonicalFrozenTopology.Request(plan, request));
  }

  @Test
  void freshApplicationRetainsCompleteExactGraphAndOriginalAccountReadbackAndRetriesAfterFreeze() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.appliedEpochs())
        .hasSize(application.operation().binding().affectedUnits(Owner.WORLD_MANAGEMENT).size());
    assertThat(result.appliedEpochs())
        .allMatch(epoch -> epoch.expectedEpoch().equals("0") && epoch.resultingEpoch().equals("1"));
    assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
    assertThat(count(f, "world_design_aggregate_epoch")).isEqualTo(7);
    assertThat(count(f, "world_design_scope_epoch")).isEqualTo(1);
    for (String family :
        List.of("region", "zone", "room_exit", "generation_rule", "world_entity_spawn_binding")) {
      assertThat(count(f, family)).isEqualTo(1);
    }
    assertThat(count(f, "room")).isEqualTo(2);
    var readback =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback
            .fromStored(result.ownerReadback().canonicalBytes());
    assertThat(readback.owner())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner.WORLD);
    assertThat(readback.outcome())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome
                .COMMITTED);
    readback.requireBinding(
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes()));
    assertThat(readback.fullBinding())
        .containsExactly(application.operation().accountBindingBytes());
    assertThat(readback.result()).containsExactly(result.canonicalBytes());
    assertThat(
            appliedRepository()
                .readCommitted(NAMESPACE, application.operation().accountBindingBytes())
                .orElseThrow()
                .ownerReadback()
                .canonicalBytes())
        .containsExactly(readback.canonicalBytes());
    var changed =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            changedAccountApplication(application).operation().accountBindingBytes());
    assertThatThrownBy(() -> readback.requireBinding(changed))
        .hasMessageContaining("complete Draft binding");
    assertThatThrownBy(() -> appliedRepository().readCommitted(NAMESPACE, changed.canonicalBytes()))
        .hasMessageContaining("full Account binding");
    assertThatThrownBy(
            () ->
                appliedRepository()
                    .readCommitted(
                        "another-workload", application.operation().accountBindingBytes()))
        .hasMessageContaining("namespace");
    freeze(f);
    // Exact replay reads immutable evidence and does not need a newly acquired authorization.
    var retry =
        new WorldDraftGraphApplicationService(appliedRepository(), manager).apply(application);
    assertThat(retry.canonicalBytes()).containsExactly(result.canonicalBytes());
    assertThat(retry.graphBytes()).containsExactly(result.graphBytes());
    assertThat(retry.startLocationReceipt()).contains(result.startLocationReceipt().orElseThrow());
    assertThat(
            appliedRepository()
                .readSynchronized(visibility(application, result, result.canonicalBytes())))
        .isPresent();
  }

  @Test
  void explicitEmptyOptionalFamiliesProduceAppliedGraphAndExactTypedStartSelector() {
    Fixture f = fixture();
    var application = application(plan(f, true));
    var result = appliedComponent().apply(application);

    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.startLocationReceipt()).isPresent();
    var receipt = result.startLocationReceipt().orElseThrow();
    assertThat(receipt.startLocation().tenantId())
        .isEqualTo(application.operation().canonicalTenantId());
    assertThat(receipt.startLocation().versionId())
        .isEqualTo(application.operation().canonicalVersionId());
    assertThat(receipt.startLocation().roomTemplateId())
        .isEqualTo(
            application
                .plan()
                .graph()
                .freshGraphDeclaration()
                .orElseThrow()
                .startLocation()
                .roomTemplateId());
    assertThat(application.plan().graph().freshGraphDeclaration().orElseThrow().familyCounts())
        .extracting(WorldDraftTopologyInputGraph.FamilyCount::count)
        .containsExactly(1, 1, 1, 0, 0, 0);
    assertThat(count(f, "region")).isEqualTo(1);
    assertThat(count(f, "zone")).isEqualTo(1);
    assertThat(count(f, "room")).isEqualTo(1);
    for (String family : List.of("room_exit", "generation_rule", "world_entity_spawn_binding")) {
      assertThat(count(f, family)).isZero();
    }
    assertThat(count(f, "world_draft_start_location_receipt")).isEqualTo(1);
    assertThat(appliedRepository().readCommitted(application).orElseThrow().startLocationReceipt())
        .contains(receipt);
    assertThat(
            appliedRepository()
                .readCommitted(NAMESPACE, application.operation().accountBindingBytes())
                .orElseThrow()
                .startLocationReceipt())
        .contains(receipt);
  }

  @Test
  void newAccountBoundGraphRequiresCompleteOriginalDeclaration() {
    Fixture f = fixture();
    var historicalPlan = withoutFreshGraphDeclaration(plan(f));
    var application = application(historicalPlan);

    assertThatThrownBy(() -> appliedComponent().apply(application))
        .hasMessageContaining("require the complete original fresh-graph declaration");
    for (String table :
        List.of(
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding",
            "world_authored_topology_identity",
            "world_topology_draft_commit",
            "world_draft_graph_application",
            "world_draft_start_location_receipt")) {
      assertThat(count(f, table)).isZero();
    }
  }

  @Test
  void authenticatedReceiverReadsGenuineCommittedReceiptAndReplaysAfterFreeze() {
    Fixture f = fixture();
    var application = application(f);
    var receiver =
        new WorldDraftTerminalReadGrpcService(
            new WorldDraftTerminalOutcomeRepository(dsl, fence, mapper),
            appliedRepository(),
            NAMESPACE);
    var request =
        net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request.create(
            NAMESPACE, application.operation().accountBindingBytes());
    assertThat(receiverRead(receiver, request).ownerReadback()).isEmpty();
    var result = appliedComponent().apply(application);
    var received = receiverRead(receiver, request).ownerReadback().orElseThrow();
    assertThat(received.outcome())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome
                .COMMITTED);
    assertThat(received.canonicalBytes()).containsExactly(result.ownerReadback().canonicalBytes());
    assertThat(received.result()).containsExactly(result.canonicalBytes());
    freeze(f);
    var retryRequest =
        net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request.create(
            NAMESPACE, application.operation().accountBindingBytes());
    assertThat(receiverRead(receiver, retryRequest).ownerReadback().orElseThrow().canonicalBytes())
        .containsExactly(received.canonicalBytes());
  }

  private net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence receiverRead(
      WorldDraftTerminalReadGrpcService receiver,
      net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request request) {
    var response =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse>();
    var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var completed = new java.util.concurrent.atomic.AtomicBoolean();
    var context =
        io.grpc.Context.current()
            .withValue(
                net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
                new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
                    NAMESPACE,
                    "account-service"));
    var previous = context.attach();
    try {
      receiver.readWorldDraftTerminalOutcome(
          net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toRequest(
              request),
          new io.grpc.stub.StreamObserver<>() {
            public void onNext(
                net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse
                    value) {
              response.set(value);
            }

            public void onError(Throwable value) {
              error.set(value);
            }

            public void onCompleted() {
              completed.set(true);
            }
          });
    } finally {
      context.detach(previous);
    }
    assertThat(error.get()).isNull();
    assertThat(completed.get()).isTrue();
    return net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.fromResponse(
        request, response.get());
  }

  @Test
  void oldPermissionUnverifiedRowsCannotBePromotedByWriterOrLateReceiptInsertion() {
    Fixture f = fixture();
    var application = application(f);
    var old = component().store(application.plan());
    assertThatThrownBy(() -> appliedComponent().apply(application))
        .hasMessageContaining("cannot be promoted");
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          insertReceipt(application, new byte[] {1});
                          return null;
                        }))
        .hasMessageContaining("same transaction");
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThat(repository().readCommitted(application.plan()).orElseThrow().graphBytes())
        .containsExactly(old.graphBytes());
    // Even plausible canonical fixture bytes cannot select old unverified history.
    var fabricated = WorldDraftGraphAppliedResult.create(application, old.graphBytes());
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(
                        visibility(application, fabricated, fabricated.canonicalBytes())))
        .hasMessageContaining("no canonical World APPLIED-result carrier");
  }

  @Test
  void failureAfterGraphAndReceiptInsertionRollsBackEveryFamilyEpochAndHistory() {
    Fixture f = fixture();
    var application = application(f);
    var failing =
        new WorldDraftGraphApplicationRepository(dsl, fence, mapper) {
          @Override
          WorldDraftGraphAppliedResult apply(
              WorldDraftGraphApplication value,
              WorldDraftGraphApplicationService.CommitOrderProof proof) {
            super.apply(value, proof);
            throw new IllegalStateException("after exact receipt insertion");
          }
        };
    assertThatThrownBy(() -> appliedComponent(failing).apply(application))
        .hasMessageContaining("after exact receipt");
    for (String table :
        List.of(
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding",
            "world_design_aggregate_epoch",
            "world_design_scope_epoch",
            "world_authored_topology_identity",
            "world_topology_draft_commit",
            "world_draft_start_location_receipt")) {
      assertThat(count(f, table)).isZero();
    }
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThat(aborts().readDefinitiveAbort(application.operation())).isEmpty();
    assertThat(appliedComponent().apply(application).status()).isEqualTo("APPLIED");
  }

  @Test
  void definitiveAbortExcludesDelayedTypedAndRawSqlGraphWrites() {
    Fixture f = fixture();
    var application = application(f);
    aborts().recordDefinitiveAbort(application.operation());
    assertThatThrownBy(() -> appliedComponent().apply(application))
        .hasMessageContaining("definitive no-commit");
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          dsl.fetch(
                              "SELECT world_store_guarded_uuid_topology(?::jsonb,?::jsonb,?::jsonb)",
                              mapper.writeValueAsString(application.plan().ownerBinding()),
                              application.plan().binding().canonicalJson(),
                              mapper.writeValueAsString(
                                  repository().executionRevisions(application.plan())));
                          return null;
                        }))
        .hasMessageContaining("no-commit evidence");
    assertThat(count(f, "world_authored_topology_identity")).isZero();
    assertThat(count(f, "world_topology_draft_commit")).isZero();
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
  }

  @Test
  void committedApplicationCannotBeRelabeledAsAbort() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    assertThatThrownBy(() -> aborts().recordDefinitiveAbort(application.operation()))
        .hasMessageContaining("cannot be relabeled");
    assertThat(aborts().readDefinitiveAbort(application.operation())).isEmpty();
    assertThat(appliedRepository().readCommitted(application).orElseThrow().canonicalBytes())
        .containsExactly(result.canonicalBytes());
  }

  @Test
  void originalAccountActorSourcesAndFenceArePartOfExactReplayIdentity() {
    Fixture f = fixture();
    var application = application(f);
    appliedComponent().apply(application);
    assertThatThrownBy(() -> appliedComponent().apply(changedAccountApplication(application)))
        .hasMessageContaining("changed complete binding");
    var a =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes());
    var changedFence =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            a.operationId(),
            a.requestId(),
            a.commitId(),
            UUID.randomUUID(),
            a.actorAccountId(),
            a.tenantId(),
            a.versionId(),
            a.baseCommitId(),
            a.expectedDraftEpoch(),
            a.gameDesignBinding(),
            a.normalizedInput(),
            a.inputDigest(),
            a.sources());
    assertThatThrownBy(
            () -> appliedComponent().apply(withAccount(application.plan(), changedFence)))
        .hasMessageContaining("changed complete binding");
  }

  @Test
  void changedStartSelectorChangesAccountInputDigestAndConflictsOnReusedIdentity() {
    Fixture f = fixture();
    var original = application(f);
    appliedComponent().apply(original);
    UUID anotherRoom =
        original
            .plan()
            .graph()
            .family(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .get(1)
            .templateId();
    var changed = withStartRoom(original, anotherRoom);

    assertThat(changed.operation().binding().digest())
        .isNotEqualTo(original.operation().binding().digest());
    assertThat(changed.operation().accountBindingDigest())
        .isNotEqualTo(original.operation().accountBindingDigest());
    assertThatThrownBy(() -> appliedRepository().readCommitted(changed))
        .hasMessageContaining("changed complete binding");
    assertThatThrownBy(() -> appliedComponent().apply(changed))
        .hasMessageContaining("changed complete binding");
    assertThat(count(f, "world_draft_start_location_receipt")).isEqualTo(1);
  }

  @Test
  void extraRetainedOptionalFamilyRowDeniesFreshGraphApplication() {
    Fixture f = fixture();
    var application = application(plan(f, true));
    var priorPlan = plan(f, true, true);
    assertThat(component().store(priorPlan).status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");

    assertThatThrownBy(() -> appliedComponent().apply(application))
        .hasMessageContaining("Fresh World topology conflicts with retained region");
    assertThat(appliedRepository().readCommitted(application)).isEmpty();
    assertThat(count(f, "world_authored_topology_identity")).isEqualTo(4);
    assertThat(count(f, "world_draft_start_location_receipt")).isZero();
  }

  @Test
  void v39AppliedV1HistoryRemainsByteExactAndRetryableAfterV41() throws Exception {
    String schema = "world_v39_history_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_world_management_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("39"))
        .load()
        .migrate();

    DriverManagerDataSource retainedDataSource = new DriverManagerDataSource();
    retainedDataSource.setUrl(postgres.getJdbcUrl());
    retainedDataSource.setUsername(postgres.getUsername());
    retainedDataSource.setPassword(postgres.getPassword());
    retainedDataSource.setSchema(schema);
    DSLContext retainedDsl =
        DSL.using(new TransactionAwareDataSourceProxy(retainedDataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager retainedManager =
        new DataSourceTransactionManager(retainedDataSource);
    WorldAuthoredSourceIntakeRepository retainedIntake =
        proxiedIntakeRepository(retainedDsl, retainedManager);
    Fixture f = fixture(retainedIntake, retainedManager, retainedDsl);
    WorldDraftTopologyCommitPlan historicalPlan = withoutFreshGraphDeclaration(plan(f));
    WorldDraftGraphApplication historicalApplication = application(historicalPlan);
    TransactionTemplate retainedTransaction = ownerTransaction(retainedManager);
    WorldDesignPublicationFenceRepository retainedFence =
        new WorldDesignPublicationFenceRepository(retainedDsl, retainedIntake);
    WorldDraftTopologyCommitRepository retainedTopology =
        new WorldDraftTopologyCommitRepository(retainedDsl, retainedFence, mapper);
    WorldDraftGraphAppliedResult legacyResult =
        retainedTransaction.execute(
            status -> {
              var legacyGraph = retainedTopology.store(historicalPlan);
              WorldDraftGraphAppliedResult result =
                  WorldDraftGraphAppliedResult.create(
                      historicalApplication, legacyGraph.graphBytes());
              retainedDsl.execute(
                  "INSERT INTO world_draft_graph_application (operation_id,request_id,commit_id,authorization_fence_id,"
                      + "operation_bytes,account_binding_bytes,account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
                  historicalApplication.operation().operationId(),
                  historicalApplication.operation().requestId(),
                  historicalApplication.operation().commitId(),
                  historicalApplication.operation().authorizationFenceId(),
                  historicalApplication.operation().canonicalBytes(),
                  historicalApplication.operation().accountBindingBytes(),
                  historicalApplication.operation().accountBindingDigest(),
                  result.canonicalBytes(),
                  result.digest());
              return result;
            });
    assertThat(legacyResult.resultIdentity()).contains("/v1:");
    assertThat(legacyResult.startLocationReceipt()).isEmpty();

    Map<String, byte[]> originalBytes =
        retainedV39ApplicationBytes(retainedDsl, historicalApplication);
    Map<String, String> originalRows =
        retainedV39ApplicationRows(retainedDsl, historicalApplication);
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_world_management_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    Map<String, byte[]> migratedBytes =
        retainedV39ApplicationBytes(retainedDsl, historicalApplication);
    originalBytes.forEach(
        (key, value) -> assertThat(migratedBytes.get(key)).containsExactly(value));
    assertThat(retainedV39ApplicationRows(retainedDsl, historicalApplication))
        .isEqualTo(originalRows);
    Long receiptsAfterMigration =
        Objects.requireNonNull(
                retainedDsl.fetchOne("SELECT count(*) FROM world_draft_start_location_receipt"))
            .get(0, Long.class);
    assertThat(receiptsAfterMigration).isZero();

    WorldDraftGraphApplicationRepository retainedApplications =
        new WorldDraftGraphApplicationRepository(retainedDsl, retainedFence, mapper);
    WorldDraftGraphAppliedResult exact =
        retainedApplications.readCommitted(historicalApplication).orElseThrow();
    assertThat(exact.canonicalBytes()).containsExactly(legacyResult.canonicalBytes());
    assertThat(exact.graphBytes()).containsExactly(legacyResult.graphBytes());
    assertThat(exact.startLocationReceipt()).isEmpty();
    // Negative source-reader proof uses real retained v1 APPLIED bytes, with a stipulated frozen
    // selection only. This is not positive capture or publication-checkpoint proof for v1 history.
    var historicalFreeze =
        new WorldAuthoredGraphSnapshot.CaptureRequest(
            NAMESPACE,
            historicalPlan.ownerBinding().canonicalTenantId(),
            historicalPlan.ownerBinding().canonicalVersionId(),
            historicalPlan.ownerBinding().intakeRequestId(),
            UUID.randomUUID(),
            "historical-selector-denial",
            "a".repeat(64),
            1,
            "publish:"
                + historicalPlan.ownerBinding().canonicalTenantId()
                + ":publish-request:historical-selector-denial",
            historicalPlan.binding().commitId().toString(),
            "b".repeat(64),
            3,
            historicalPlan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
                .map(
                    unit ->
                        new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                            unit.owner().name(),
                            unit.aggregateType(),
                            unit.aggregateId(),
                            unit.scopeType(),
                            unit.scopeId(),
                            unit.expectedEpoch()))
                .toList());
    var historicalSelection =
        new WorldCanonicalFrozenTopology(
            UUID.randomUUID(),
            new WorldCanonicalFrozenTopology.Request(historicalPlan, historicalFreeze),
            f.version(),
            new WorldCanonicalAuthoredGraphReader().read(historicalPlan, exact.graphBytes()),
            exact.graphBytes(),
            new byte[0],
            new byte[0]);
    var stipulatedFrozen = org.mockito.Mockito.mock(WorldCanonicalFrozenTopologyRepository.class);
    org.mockito.Mockito.when(stipulatedFrozen.readCommitted(historicalFreeze))
        .thenReturn(java.util.Optional.of(historicalSelection));
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationRepository(
                        retainedDsl, stipulatedFrozen, retainedApplications)
                    .readCommitted(historicalFreeze))
        .hasMessageContaining("lacks original selector receipt");
    assertThat(
            retainedApplications
                .readCommitted(NAMESPACE, historicalApplication.operation().accountBindingBytes())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(legacyResult.canonicalBytes());
    WorldDraftGraphAppliedResult retry =
        new WorldDraftGraphApplicationService(retainedApplications, retainedManager)
            .apply(historicalApplication);
    assertThat(retry.canonicalBytes()).containsExactly(legacyResult.canonicalBytes());
    assertThat(retry.startLocationReceipt()).isEmpty();
    Long receiptsAfterRetry =
        Objects.requireNonNull(
                retainedDsl.fetchOne("SELECT count(*) FROM world_draft_start_location_receipt"))
            .get(0, Long.class);
    assertThat(receiptsAfterRetry).isZero();
  }

  @Test
  void exactDuplicateRaceCommitsOneGraphAndOneImmutableResult() throws Exception {
    Fixture f = fixture();
    var application = application(f);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch begin = new CountDownLatch(1);
    try {
      Future<WorldDraftGraphAppliedResult> first =
          pool.submit(
              () -> {
                await(begin);
                return appliedComponent().apply(application);
              });
      Future<WorldDraftGraphAppliedResult> second =
          pool.submit(
              () -> {
                await(begin);
                return appliedComponent().apply(application);
              });
      begin.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS).canonicalBytes())
          .containsExactly(second.get(20, TimeUnit.SECONDS).canonicalBytes());
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_graph_application WHERE operation_id=?",
                              application.operation().operationId()))
                      .get(0, Long.class)))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void commitAbortRaceHasExactlyOneDefinitiveOutcomeAndNoLateWrite() throws Exception {
    Fixture f = fixture();
    var application = application(f);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch begin = new CountDownLatch(1);
    try {
      Future<Boolean> commit =
          pool.submit(
              () -> {
                await(begin);
                try {
                  appliedComponent().apply(application);
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
                  return false;
                }
              });
      Future<Boolean> abort =
          pool.submit(
              () -> {
                await(begin);
                try {
                  aborts().recordDefinitiveAbort(application.operation());
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
                  return false;
                }
              });
      begin.countDown();
      boolean committed = commit.get(20, TimeUnit.SECONDS);
      boolean aborted = abort.get(20, TimeUnit.SECONDS);
      assertThat(committed ^ aborted).isTrue();
      assertThat(appliedRepository().readCommitted(application).isPresent()).isEqualTo(committed);
      assertThat(aborts().readDefinitiveAbort(application.operation()).isPresent())
          .isEqualTo(aborted);
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(committed ? 7 : 0);
      if (aborted)
        assertThatThrownBy(() -> appliedComponent().apply(application))
            .hasMessageContaining("no-commit evidence");
      else
        assertThatThrownBy(() -> aborts().recordDefinitiveAbort(application.operation()))
            .hasMessageContaining("cannot be relabeled");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void synchronizedGraphRequiresExactCanonicalAppliedResultNotArbitraryBytes() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    assertThat(
            repository().readSynchronized(visibility(application, result, result.canonicalBytes())))
        .isPresent();
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(visibility(application, result, new byte[] {1, 2, 3})))
        .hasMessageContaining("exact canonical retained graph application");
    Fixture other = fixture();
    var otherApplication = application(other);
    var otherResult = appliedComponent().apply(otherApplication);
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(
                        visibility(application, result, otherResult.canonicalBytes())))
        .hasMessageContaining("exact canonical retained graph application");
  }

  @Test
  void changedTargetRaceCannotClaimTheSameOriginalOperationAndFenceForBothOutcomes()
      throws Exception {
    var application = application(fixture());
    var other = application(fixture());
    var original =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes());
    var changed =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            other.operation().accountBindingBytes());
    var conflicting =
        withAccount(
            other.plan(),
            new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
                original.operationId(),
                changed.requestId(),
                changed.commitId(),
                original.fenceId(),
                changed.actorAccountId(),
                changed.tenantId(),
                changed.versionId(),
                changed.baseCommitId(),
                changed.expectedDraftEpoch(),
                changed.gameDesignBinding(),
                changed.normalizedInput(),
                changed.inputDigest(),
                changed.sources()));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch begin = new CountDownLatch(1);
    try {
      Future<Boolean> commit =
          pool.submit(
              () -> {
                await(begin);
                try {
                  appliedComponent().apply(application);
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException
                    | org.springframework.dao.DataIntegrityViolationException conflict) {
                  return false;
                }
              });
      Future<Boolean> abort =
          pool.submit(
              () -> {
                await(begin);
                try {
                  aborts().recordDefinitiveAbort(conflicting.operation());
                  return true;
                } catch (WorldDesignPublicationFenceRepository.ConflictException
                    | org.springframework.dao.DataIntegrityViolationException conflict) {
                  return false;
                }
              });
      begin.countDown();
      boolean committed = commit.get(20, TimeUnit.SECONDS);
      boolean aborted = abort.get(20, TimeUnit.SECONDS);
      assertThat(committed ^ aborted).isTrue();
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_graph_terminal_identity WHERE operation_id=?",
                              original.operationId()))
                      .get(0, Long.class)))
          .isEqualTo(1);
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_graph_application WHERE operation_id=?",
                              original.operationId()))
                      .get(0, Long.class)))
          .isEqualTo(committed ? 1 : 0);
      assertThat(
              Objects.requireNonNull(
                  Objects.requireNonNull(
                          dsl.fetchOne(
                              "SELECT count(*) FROM world_draft_terminal_outcome WHERE operation_id=?",
                              original.operationId()))
                      .get(0, Long.class)))
          .isEqualTo(aborted ? 1 : 0);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void completeResultAndGraphSubstitutionFailImmutableReadback() {
    Fixture f = fixture();
    var application = application(f);
    var result = appliedComponent().apply(application);
    byte[] corrupt = result.canonicalBytes();
    corrupt[corrupt.length - 2] ^= 1;
    ownerTransaction()
        .execute(
            status -> {
              dsl.execute(
                  "ALTER TABLE world_draft_graph_application DISABLE TRIGGER trg_world_graph_application_guard");
              dsl.execute(
                  "UPDATE world_draft_graph_application SET result_bytes=?,result_digest=? WHERE operation_id=?",
                  corrupt,
                  WorldDraftGraphAppliedResult.digest(corrupt),
                  application.operation().operationId());
              dsl.execute(
                  "ALTER TABLE world_draft_graph_application ENABLE TRIGGER trg_world_graph_application_guard");
              return null;
            });
    assertThatThrownBy(() -> appliedRepository().readCommitted(application))
        .hasMessageContaining("immutable integrity readback");
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(visibility(application, result, result.canonicalBytes())))
        .hasMessageContaining("immutable integrity readback");

    Fixture other = fixture();
    var otherApplication = application(other);
    var otherResult = appliedComponent().apply(otherApplication);
    ownerTransaction()
        .execute(
            status -> {
              dsl.execute(
                  "ALTER TABLE world_topology_draft_commit DISABLE TRIGGER trg_world_topology_history_immutable");
              dsl.execute(
                  "UPDATE world_topology_draft_commit SET graph_bytes=?,graph_sha256=? WHERE request_id=?",
                  result.graphBytes(),
                  WorldDraftGraphAppliedResult.digest(result.graphBytes()).substring(7),
                  otherApplication.operation().requestId());
              dsl.execute(
                  "ALTER TABLE world_topology_draft_commit ENABLE TRIGGER trg_world_topology_history_immutable");
              return null;
            });
    assertThatThrownBy(() -> appliedRepository().readCommitted(otherApplication))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                repository()
                    .readSynchronized(
                        visibility(otherApplication, otherResult, otherResult.canonicalBytes())))
        .isInstanceOf(RuntimeException.class);
  }

  private WorldDraftGraphApplication application(Fixture f) {
    return application(plan(f));
  }

  private WorldDraftGraphApplication application(WorldDraftTopologyCommitPlan p) {
    var account =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            p.binding().requestId(),
            p.binding().commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            p.binding().target().canonicalTenantId(),
            p.binding().target().canonicalVersionId(),
            p.binding().baseCommitId(),
            "0",
            p.binding().canonicalBytes(),
            p.binding().canonicalBytes(),
            p.binding().digest(),
            List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.TENANT,
                    p.binding().target().canonicalTenantId().toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})));
    return withAccount(p, account);
  }

  private WorldDraftGraphApplication changedAccountApplication(
      WorldDraftGraphApplication application) {
    var a =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
            application.operation().accountBindingBytes());
    var changed =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            a.operationId(),
            a.requestId(),
            a.commitId(),
            a.fenceId(),
            UUID.randomUUID(),
            a.tenantId(),
            a.versionId(),
            a.baseCommitId(),
            a.expectedDraftEpoch(),
            a.gameDesignBinding(),
            a.normalizedInput(),
            a.inputDigest(),
            List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.TENANT,
                    a.tenantId().toString(),
                    null,
                    "2",
                    null,
                    null,
                    new byte[] {4, 5, 6})));
    return withAccount(application.plan(), changed);
  }

  private WorldDraftGraphApplication withAccount(
      WorldDraftTopologyCommitPlan p,
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding account) {
    var operation =
        new WorldDraftTerminalOperation(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId(),
            p.binding(),
            p.ownerBinding(),
            account.canonicalBytes());
    return new WorldDraftGraphApplication(operation, p);
  }

  private WorldDraftGraphApplication withStartRoom(
      WorldDraftGraphApplication original, UUID roomTemplateId) {
    var binding = original.operation().binding();
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>(binding.revisions());
    for (int index = 0; index < revisions.size(); index++) {
      var revision = revisions.get(index);
      if (revision.owner() != Owner.WORLD_MANAGEMENT) continue;
      try {
        var mutation = WorldDesignMutationRevision.newBuilder();
        JsonFormat.parser().merge(revision.payload(), mutation);
        if (!mutation.hasFreshGraphDeclaration()) continue;
        var declaration =
            mutation.getFreshGraphDeclaration().toBuilder()
                .setStartLocation(
                    mutation.getFreshGraphDeclaration().getStartLocation().toBuilder()
                        .setRoomTemplateId(roomTemplateId.toString()))
                .build();
        revisions.set(
            index,
            new DraftCommitBinding.RevisionPayload(
                revision.revisionOrder(),
                revision.revisionId(),
                revision.owner(),
                JsonFormat.printer()
                    .print(mutation.setFreshGraphDeclaration(declaration).build())));
        DraftCommitBinding changedBinding =
            DraftCommitBinding.create(
                binding.target(),
                binding.requestId(),
                binding.commitId(),
                binding.baseCommitId(),
                revisions,
                binding.affectedUnits());
        var account =
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.fromStored(
                original.operation().accountBindingBytes());
        var changedAccount =
            new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
                account.operationId(),
                account.requestId(),
                account.commitId(),
                account.fenceId(),
                account.actorAccountId(),
                account.tenantId(),
                account.versionId(),
                account.baseCommitId(),
                account.expectedDraftEpoch(),
                changedBinding.canonicalBytes(),
                changedBinding.canonicalBytes(),
                changedBinding.digest(),
                account.sources());
        return withAccount(
            WorldDraftTopologyCommitPlan.create(changedBinding, original.plan().ownerBinding()),
            changedAccount);
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(exception);
      }
    }
    throw new IllegalArgumentException("Original application has no start-location declaration");
  }

  private WorldDraftGraphApplicationRepository appliedRepository() {
    return new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
  }

  private WorldAuthoredSourceIntakeRepository proxiedIntakeRepository(
      DSLContext context, PlatformTransactionManager transactionManager) {
    var repository = new WorldAuthoredSourceIntakeRepository(context);
    TransactionInterceptor transactions = new TransactionInterceptor();
    transactions.setTransactionManager(transactionManager);
    transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    ProxyFactory proxy = new ProxyFactory(repository);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(transactions);
    return (WorldAuthoredSourceIntakeRepository) proxy.getProxy();
  }

  private WorldDraftGraphApplicationService appliedComponent() {
    return appliedComponent(appliedRepository());
  }

  private WorldDraftGraphApplicationService appliedComponent(
      WorldDraftGraphApplicationRepository repository) {
    return new WorldDraftGraphApplicationService(
        repository,
        manager,
        operation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          // STIPULATED fixture authority: original Account COMMIT_ORDER remains held until BOTH
          // owners settle.
          // No protected producer is supplied and this is not Gameplay handoff completion.
          return new WorldDraftGraphApplicationService.CommitOrderProof(operation);
        });
  }

  private WorldDraftTerminalOutcomeService aborts() {
    return new WorldDraftTerminalOutcomeService(
        new WorldDraftTerminalOutcomeRepository(dsl, fence, mapper), manager);
  }

  private void insertReceipt(WorldDraftGraphApplication application, byte[] result) {
    var operation = application.operation();
    dsl.execute(
        "INSERT INTO world_draft_graph_application (operation_id,request_id,commit_id,authorization_fence_id,"
            + "operation_bytes,account_binding_bytes,account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        operation.canonicalBytes(),
        operation.accountBindingBytes(),
        operation.accountBindingDigest(),
        result,
        WorldDraftGraphAppliedResult.digest(result));
  }

  private WorldDraftTopologyCommitPlan withoutFreshGraphDeclaration(
      WorldDraftTopologyCommitPlan original) {
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    for (var revision : original.binding().revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        revisions.add(revision);
        continue;
      }
      try {
        var mutation = WorldDesignMutationRevision.newBuilder();
        JsonFormat.parser().merge(revision.payload(), mutation);
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                revision.revisionOrder(),
                revision.revisionId(),
                revision.owner(),
                JsonFormat.printer()
                    .omittingInsignificantWhitespace()
                    .print(mutation.clearFreshGraphDeclaration().build())));
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(exception);
      }
    }
    var binding = original.binding();
    DraftCommitBinding historicalBinding =
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            revisions,
            binding.affectedUnits());
    return WorldDraftTopologyCommitPlan.create(historicalBinding, original.ownerBinding());
  }

  private Map<String, byte[]> retainedV39ApplicationBytes(
      DSLContext retained, WorldDraftGraphApplication application) {
    var operation = application.operation();
    var graph =
        Objects.requireNonNull(
            retained.fetchOne(
                "SELECT graph_bytes,result_bytes FROM world_topology_draft_commit WHERE request_id=? AND commit_id=?",
                operation.requestId(),
                operation.commitId()),
            "expected retained V39 graph row");
    var applied =
        Objects.requireNonNull(
            retained.fetchOne(
                "SELECT operation_bytes,account_binding_bytes,result_bytes FROM world_draft_graph_application WHERE operation_id=?",
                operation.operationId()),
            "expected retained V39 APPLIED row");
    var terminal =
        Objects.requireNonNull(
            retained.fetchOne(
                "SELECT account_binding_bytes FROM world_draft_graph_terminal_identity WHERE operation_id=?",
                operation.operationId()),
            "expected retained V39 terminal identity");
    return Map.of(
        "graphBytes", graph.get("graph_bytes", byte[].class),
        "graphResultBytes", graph.get("result_bytes", byte[].class),
        "operationBytes", applied.get("operation_bytes", byte[].class),
        "accountBindingBytes", applied.get("account_binding_bytes", byte[].class),
        "appliedResultBytes", applied.get("result_bytes", byte[].class),
        "terminalAccountBindingBytes", terminal.get("account_binding_bytes", byte[].class));
  }

  private Map<String, String> retainedV39ApplicationRows(
      DSLContext retained, WorldDraftGraphApplication application) {
    var operation = application.operation();
    var graph =
        Objects.requireNonNull(
            retained.fetchOne(
                "SELECT to_jsonb(t)::text FROM world_topology_draft_commit t WHERE request_id=? AND commit_id=?",
                operation.requestId(),
                operation.commitId()),
            "expected retained V39 graph row");
    var applied =
        Objects.requireNonNull(
            retained.fetchOne(
                "SELECT to_jsonb(t)::text FROM world_draft_graph_application t WHERE operation_id=?",
                operation.operationId()),
            "expected retained V39 APPLIED row");
    var terminal =
        Objects.requireNonNull(
            retained.fetchOne(
                "SELECT to_jsonb(t)::text FROM world_draft_graph_terminal_identity t WHERE operation_id=?",
                operation.operationId()),
            "expected retained V39 terminal identity");
    return Map.of(
        "graph", graph.get(0, String.class),
        "application", applied.get(0, String.class),
        "terminal", terminal.get(0, String.class));
  }

  private net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence visibility(
      WorldDraftGraphApplication application,
      WorldDraftGraphAppliedResult result,
      byte[] worldResultBytes) {
    var binding = application.operation().binding();
    List<Map<String, Object>> vector = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("owner", owner.name());
      value.put("status", "APPLIED");
      value.put("commitId", binding.commitId().toString());
      value.put("bindingDigest", binding.digest());
      value.put(
          "resultIdentity",
          owner == Owner.WORLD_MANAGEMENT ? result.resultIdentity() : "stipulated-" + owner);
      value.put(
          "resultBytesBase64",
          java.util.Base64.getEncoder()
              .encodeToString(owner == Owner.WORLD_MANAGEMENT ? worldResultBytes : new byte[] {1}));
      value.put(
          "appliedEpochs",
          binding.affectedUnits(owner).stream()
              .map(
                  unit -> {
                    Map<String, String> epoch = new LinkedHashMap<>();
                    epoch.put("aggregateType", unit.aggregateType());
                    epoch.put("aggregateId", unit.aggregateId());
                    epoch.put("scopeType", unit.scopeType());
                    epoch.put("scopeId", unit.scopeId());
                    epoch.put("expectedEpoch", unit.expectedEpoch());
                    epoch.put("resultingEpoch", "1");
                    return epoch;
                  })
              .toList());
      vector.add(value);
    }
    try {
      String json =
          new String(
              net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
                  mapper.writeValueAsString(vector)),
              StandardCharsets.UTF_8);
      return new net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence(
          new net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Request(
              1, NAMESPACE, UUID.randomUUID(), binding.target()),
          binding,
          "SYNCHRONIZED",
          new net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Fence(
              binding.requestId(),
              binding.commitId(),
              binding.digest(),
              json,
              java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private Fixture fixture() {
    return fixture(intakeRepository, manager, dsl);
  }

  private Fixture fixture(
      WorldAuthoredSourceIntakeRepository sourceIntake,
      PlatformTransactionManager transactionManager,
      DSLContext context) {
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    long sourceRow = Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceTenant = "gd-row-" + sourceRow;
    String digest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, worldSlug, "Synthetic component world");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            digest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic component world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            digest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic component world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW",
            evidenceDigest);
    UUID intakeRequest = UUID.randomUUID();
    ownerTransaction(transactionManager)
        .execute(status -> sourceIntake.acceptFresh(NAMESPACE, intakeRequest, source));
    WorldAuthoredSourceIntakeReceipt intake =
        sourceIntake.read(NAMESPACE, intakeRequest).orElseThrow();
    return Objects.requireNonNull(
        ownerTransaction(transactionManager)
            .execute(
                status -> {
                  UUID canonicalVersion = UUID.randomUUID();
                  AuthoredWorldVersionStateEvidence.Request stateRequest =
                      new AuthoredWorldVersionStateEvidence.Request(
                          1,
                          NAMESPACE,
                          UUID.randomUUID(),
                          tenant,
                          worldSlug,
                          sourceOperation,
                          evidenceDigest,
                          GAME_DESIGN_VERSION);
                  WorldAuthoredVersionIdentityReceipt version =
                      new WorldAuthoredVersionIdentityRepository(context)
                          .acceptFresh(
                              intake,
                              AuthoredWorldVersionStateEvidence.create(
                                  stateRequest,
                                  source,
                                  canonicalVersion,
                                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                                  1));
                  OwnerBinding owner =
                      new OwnerBinding(
                          NAMESPACE,
                          tenant,
                          canonicalVersion,
                          version.operationId(),
                          GAME_DESIGN_VERSION,
                          intakeRequest,
                          intake.operationId(),
                          intake.requestDigest(),
                          sourceOperation,
                          evidenceDigest,
                          intake.receiptDigest());
                  assertOrigin(context);
                  return new Fixture(intake, version, owner);
                }));
  }

  private void freeze(Fixture f) {
    OwnerBinding o = f.owner();
    String request = "freeze-" + UUID.randomUUID();
    WorldDesignPublicationFenceEvidence evidence =
        new WorldDesignPublicationFenceEvidence(
            o.targetNamespace(),
            o.canonicalTenantId(),
            o.canonicalVersionId(),
            o.versionIdentityOperationId(),
            o.gameDesignVersionId(),
            o.intakeRequestId(),
            o.intakeOperationId(),
            o.intakeRequestDigest(),
            o.sourceOperationId(),
            o.sourceEvidenceDigest(),
            o.intakeReceiptDigest(),
            request,
            "a".repeat(64),
            1,
            "publish:" + o.canonicalTenantId() + ":publish-request:" + request);
    ownerTransaction()
        .execute(
            status ->
                fence.claimFreeze(
                    evidence,
                    () ->
                        new WorldDesignPublicationFenceEvidence.Checkpoint(
                            "synthetic-unverified-checkpoint", "b".repeat(64), 2)));
  }

  private WorldDraftTopologyCommitPlan plan(Fixture f) {
    return plan(f, false);
  }

  private WorldDraftTopologyCommitPlan plan(Fixture f, boolean threeFamilyGraph) {
    return plan(f, threeFamilyGraph, false);
  }

  private WorldDraftTopologyCommitPlan plan(
      Fixture f, boolean threeFamilyGraph, boolean includeGenerationRule) {
    UUID logical = UUID.randomUUID();
    UUID secondRoom = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    List<WorldDesignMutationRevision> values = new ArrayList<>();
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("region")
                    .setWeather("rain")
                    .setShardId(7)
                    .setGenerationSeed(9_007_199_254_740_999L)
                    .setGeneratorType("synthetic")
                    .setGeneratorParams("{\"seed\":1}"))
            .build());
    values.add(
        mutation(
                commit, logical, logical, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder().setName("zone").setRegionId(logical.toString()))
            .build());
    values.add(
        mutation(
                commit, logical, logical, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("room")
                    .setZoneId(logical.toString())
                    .setDescription("description")
                    .setNameLocalizedVariantsJson("{\"en\":\"room\"}")
                    .setDescriptionLocalizedVariantsJson("{\"en\":\"description\"}"))
            .build());
    values.add(
        mutation(
                commit,
                secondRoom,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder().setName("second").setZoneId(logical.toString()))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT)
            .setRoomExit(
                RoomExitDesignMutation.newBuilder()
                    .setFromRoomId(logical.toString())
                    .setToRoomId(secondRoom.toString())
                    .setDirection("NORTH"))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
            .setGenerationRule(
                GenerationRuleDesignMutation.newBuilder().setName("rule").setValue("seeded"))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(logical.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                    .setEntityTemplateId(entity.toString())
                    .setRespawnDelaySeconds(17))
            .build());
    if (threeFamilyGraph) {
      values = new ArrayList<>(values.subList(0, 3));
      var region = values.getFirst();
      values.set(
          0,
          region.toBuilder()
              .setRegion(
                  region.getRegion().toBuilder()
                      .clearGenerationSeed()
                      .clearGeneratorType()
                      .clearGeneratorParams())
              .build());
    }
    if (includeGenerationRule) {
      values.add(
          mutation(
                  commit,
                  UUID.randomUUID(),
                  logical,
                  WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
              .setGenerationRule(
                  GenerationRuleDesignMutation.newBuilder()
                      .setName("retained-extra")
                      .setValue("must-deny"))
              .build());
    }
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(f.owner().canonicalTenantId().toString())
            .setVersionId(f.owner().canonicalVersionId().toString())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(f.owner().canonicalTenantId().toString())
                    .setVersionId(f.owner().canonicalVersionId().toString())
                    .setRoomTemplateId(logical.toString()))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT))
            .addFamilyCounts(
                count(values, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE))
            .addFamilyCounts(
                count(
                    values,
                    WorldDesignAggregateType
                        .WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING))
            .build();
    values.set(0, values.getFirst().toBuilder().setFreshGraphDeclaration(declaration).build());
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.ENTITY_MANAGEMENT,
            "synthetic opaque Entity input; existence and permission remain unverified"));
    units.add(
        new AffectedUnit(
            Owner.ENTITY_MANAGEMENT,
            "NPC",
            entity.toString(),
            "AGGREGATE",
            entity.toString(),
            "0"));
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "synthetic Game Design control-plane input"));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "VERSION",
            f.owner().canonicalVersionId().toString(),
            "AGGREGATE",
            f.owner().canonicalVersionId().toString(),
            "0"));
    // Original input order deliberately places children before their parents.
    for (var m : values.reversed()) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(m.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              json(m)));
      String family = m.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              m.getAggregateId(),
              "AGGREGATE",
              m.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              m.getAggregateId(),
              "REGION_SUBTREE",
              m.getScopeId(),
              "0"));
    }
    var s = f.intake().source();
    var target =
        new DraftCommitBinding.TargetProof(
            f.owner().canonicalTenantId(),
            f.owner().canonicalVersionId(),
            GAME_DESIGN_VERSION,
            s.sourceGameTenantKey(),
            s.sourceGameRowId(),
            s.sourceGameTenantKey(),
            s.provenanceKind());
    return WorldDraftTopologyCommitPlan.create(
        DraftCommitBinding.create(
            target, UUID.randomUUID(), commit, "retained-base", revisions, units),
        f.owner());
  }

  private WorldFreshGraphFamilyCount count(
      List<WorldDesignMutationRevision> mutations, WorldDesignAggregateType family) {
    int count =
        (int) mutations.stream().filter(mutation -> mutation.getAggregateType() == family).count();
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID id, UUID scope, WorldDesignAggregateType type) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(id.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(scope.toString());
  }

  private WorldDraftTopologyCommitService component() {
    return new WorldDraftTopologyCommitService(
        repository(),
        manager,
        p -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertOrigin();
          // Synthetic local-storage fixture only; no Account permission or Entity existence proof.
        });
  }

  private WorldDraftTopologyCommitRepository repository() {
    return new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
  }

  private TransactionTemplate ownerTransaction() {
    return ownerTransaction(manager);
  }

  private TransactionTemplate ownerTransaction(PlatformTransactionManager transactionManager) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private void assertOrigin() {
    assertOrigin(dsl);
  }

  private void assertOrigin(DSLContext context) {
    assertThat(
            context
                .resultQuery("SELECT current_setting('session_replication_role')")
                .fetchOne(0, String.class))
        .isEqualTo("origin");
  }

  private long count(Fixture f, String table) {
    if (table.equals("world_draft_graph_application")) {
      return Objects.requireNonNull(
          dsl.resultQuery(
                  "SELECT count(*) FROM world_draft_graph_application a "
                      + "JOIN world_topology_draft_commit c ON c.request_id=a.request_id AND c.commit_id=a.commit_id "
                      + "WHERE c.local_tenant_key=?",
                  f.intake().localTenantKey())
              .fetchOne(0, Long.class));
    }
    String tenant =
        switch (table) {
          case "world_topology_draft_commit" -> "local_tenant_key";
          case "world_draft_start_location_receipt" -> "canonical_tenant_id";
          default -> "tenant_id";
        };
    Object tenantId =
        table.equals("world_draft_start_location_receipt")
            ? f.owner().canonicalTenantId()
            : f.intake().localTenantKey();
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT count(*) FROM " + table + " WHERE " + tenant + "=?", tenantId)
            .fetchOne(0, Long.class));
  }

  private long privateKey(WorldDraftTopologyCommitPlan p, String family, UUID id) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT private_row_key FROM world_authored_topology_identity "
                    + "WHERE request_id=? AND family=? AND template_id=?",
                p.binding().requestId(),
                family,
                id)
            .fetchOne(0, Long.class));
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static final class LifecycleReadCollector
      implements StreamObserver<
          net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse> {
    private net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse
        value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(
        net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse
            response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}
}
