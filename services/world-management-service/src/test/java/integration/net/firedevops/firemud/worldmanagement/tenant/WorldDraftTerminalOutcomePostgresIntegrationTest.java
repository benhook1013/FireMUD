package integration.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
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
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOperation;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyCommitPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyCommitRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyCommitService;
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

/**
 * Synthetic Account bindings prove immutable World storage only, never authorization or APPLIED.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDraftTerminalOutcomePostgresIntegrationTest {
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
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void abortBlocksDelayedTopologyAndRawRegionWritesAndExactRetrySurvivesFreeze() {
    Fixture topology = fixture(false);
    WorldDraftTopologyCommitPlan topologyPlan =
        topologyPlan(topology, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTerminalOperation topologyOperation = operation(topology, topologyPlan.binding());
    WorldDraftTerminalOutcome original = terminalService().recordDefinitiveAbort(topologyOperation);
    assertThatThrownBy(() -> topologyComponent().store(topologyPlan))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no-commit");
    assertThat(count("world_topology_draft_commit", topologyPlan.binding().requestId())).isZero();

    Fixture region = fixture(true);
    WorldDraftRegionCommitPlan regionPlan =
        regionPlan(region, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTerminalOperation regionOperation = operation(region, regionPlan.binding());
    terminalService().recordDefinitiveAbort(regionOperation);
    assertThatThrownBy(() -> regionComponent().store(regionPlan))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no-commit");
    assertRawRegionFunctionDenied(region, regionPlan);
    assertThat(regionName(region.seededRegion())).isEqualTo("seeded");
    assertThat(count("world_region_draft_commit", regionPlan.binding().requestId())).isZero();

    freeze(topology);
    WorldDraftTerminalOutcome replay = terminalService().recordDefinitiveAbort(topologyOperation);
    assertThat(replay.canonicalBytes()).containsExactly(original.canonicalBytes());
    assertThat(replay.recordedAt()).isEqualTo(original.recordedAt());
  }

  @Test
  void v37FunctionReplacementPreservesCommittedAbortEvidenceAndBlocksDelayedWrites() {
    Fixture f = fixture(true);
    WorldDraftRegionCommitPlan priorRegion = regionPlan(f, UUID.randomUUID(), UUID.randomUUID());
    regionComponent().store(priorRegion);
    assertThat(regionEpochs(f)).containsExactly(1L, 1L);

    WorldDraftRegionCommitPlan abortedRegion =
        regionPlan(f, UUID.randomUUID(), UUID.randomUUID(), 1L);
    WorldDraftTerminalOperation regionOperation = operation(f, abortedRegion.binding());
    WorldDraftTerminalOutcome regionAbort =
        terminalService().recordDefinitiveAbort(regionOperation);
    assertThat(regionAbort.observedEpochs())
        .extracting(WorldDraftTerminalOutcome.ObservedEpoch::epoch)
        .containsOnly("1");

    Fixture topology = fixture(false);
    WorldDraftTopologyCommitPlan abortedTopology =
        topologyPlan(topology, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTerminalOperation topologyOperation = operation(topology, abortedTopology.binding());
    WorldDraftTerminalOutcome topologyAbort =
        terminalService().recordDefinitiveAbort(topologyOperation);
    assertThat(topologyAbort.observedEpochs())
        .extracting(WorldDraftTerminalOutcome.ObservedEpoch::epoch)
        .containsOnly("0");

    List<String> retainedBefore =
        List.of(
            retainedTerminalSourceFenceAndEpochSnapshot(regionOperation),
            retainedTerminalSourceFenceAndEpochSnapshot(topologyOperation));
    long guardFunctionOid = worldAbortedAttemptGuardFunctionOid();
    assertThat(worldAbortedAttemptGuardTriggerCount()).isEqualTo(4L);
    assertThat(worldAbortedAttemptGuardHasPublicExecute()).isFalse();

    // Controlled function-replacement proof against live retained rows, not a full Flyway upgrade.
    replaceV36TerminalGuardWithV37();

    assertThat(worldAbortedAttemptGuardFunctionOid()).isEqualTo(guardFunctionOid);
    assertThat(worldAbortedAttemptGuardTriggerCount()).isEqualTo(4L);
    assertThat(worldAbortedAttemptGuardHasPublicExecute()).isFalse();
    assertThat(
            List.of(
                retainedTerminalSourceFenceAndEpochSnapshot(regionOperation),
                retainedTerminalSourceFenceAndEpochSnapshot(topologyOperation)))
        .containsExactlyElementsOf(retainedBefore);
    WorldDraftTerminalOutcome regionReadback =
        terminalService().readDefinitiveAbort(regionOperation).orElseThrow();
    assertThat(regionReadback.canonicalBytes()).containsExactly(regionAbort.canonicalBytes());
    assertThat(regionReadback.observedEpochs())
        .containsExactlyElementsOf(regionAbort.observedEpochs());
    WorldDraftTerminalOutcome topologyReadback =
        terminalService().readDefinitiveAbort(topologyOperation).orElseThrow();
    assertThat(topologyReadback.canonicalBytes()).containsExactly(topologyAbort.canonicalBytes());
    assertThat(topologyReadback.observedEpochs())
        .containsExactlyElementsOf(topologyAbort.observedEpochs());

    assertThatThrownBy(() -> topologyComponent().store(abortedTopology))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no-commit");
    assertThatThrownBy(() -> regionComponent().store(abortedRegion))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no-commit");
    assertRawRegionFunctionDenied(f, abortedRegion);
    assertThat(count("world_topology_draft_commit", abortedTopology.binding().requestId()))
        .isZero();
    assertThat(count("world_region_draft_commit", abortedRegion.binding().requestId())).isZero();
    assertThat(regionName(f.seededRegion())).isEqualTo("committed");
    assertThat(regionEpochs(f)).containsExactly(1L, 1L);
    assertThat(draftRegionCount(topology)).isZero();
    assertThat(
            List.of(
                retainedTerminalSourceFenceAndEpochSnapshot(regionOperation),
                retainedTerminalSourceFenceAndEpochSnapshot(topologyOperation)))
        .containsExactlyElementsOf(retainedBefore);
  }

  @Test
  void retainedUnverifiedTopologyOrRegionOutputCannotBeRelabeledAsAborted() {
    Fixture topology = fixture(false);
    WorldDraftTopologyCommitPlan topologyPlan =
        topologyPlan(topology, UUID.randomUUID(), UUID.randomUUID());
    topologyComponent().store(topologyPlan);
    assertThatThrownBy(
            () ->
                terminalService()
                    .recordDefinitiveAbort(operation(topology, topologyPlan.binding())))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("history");
    assertThat(count("world_topology_draft_commit", topologyPlan.binding().requestId()))
        .isEqualTo(1L);
    assertThat(terminalService().readDefinitiveAbort(operation(topology, topologyPlan.binding())))
        .isEmpty();

    Fixture region = fixture(true);
    WorldDraftRegionCommitPlan regionPlan =
        regionPlan(region, UUID.randomUUID(), UUID.randomUUID());
    regionComponent().store(regionPlan);
    assertThatThrownBy(
            () -> terminalService().recordDefinitiveAbort(operation(region, regionPlan.binding())))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("history");
    assertThat(count("world_region_draft_commit", regionPlan.binding().requestId())).isEqualTo(1L);
  }

  @Test
  void topologyCommitAndAbortSerializeToExactlyOneImmutableTerminalResult() throws Exception {
    Fixture f = fixture(false);
    WorldDraftTopologyCommitPlan plan = topologyPlan(f, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTerminalOperation operation = operation(f, plan.binding());
    Race result =
        race(
            () -> topologyComponent().store(plan),
            () -> terminalService().recordDefinitiveAbort(operation));

    assertThat(result.commitWon()).isNotEqualTo(result.abortWon());
    assertThat(terminalService().readDefinitiveAbort(operation).isPresent())
        .isEqualTo(result.abortWon());
    assertThat(count("world_topology_draft_commit", plan.binding().requestId()) > 0L)
        .isEqualTo(result.commitWon());
    if (result.abortWon()) {
      assertThatThrownBy(() -> topologyComponent().store(plan))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void rawRegionCommitAndAbortSerializeOnTheSameV25Row() throws Exception {
    Fixture f = fixture(true);
    WorldDraftRegionCommitPlan plan = regionPlan(f, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTerminalOperation operation = operation(f, plan.binding());
    Race result =
        race(
            () -> regionComponent().store(plan),
            () -> terminalService().recordDefinitiveAbort(operation));

    assertThat(result.commitWon()).isNotEqualTo(result.abortWon());
    assertThat(terminalService().readDefinitiveAbort(operation).isPresent())
        .isEqualTo(result.abortWon());
    assertThat(count("world_region_draft_commit", plan.binding().requestId()) > 0L)
        .isEqualTo(result.commitWon());
    assertThat(regionName(f.seededRegion())).isEqualTo(result.commitWon() ? "committed" : "seeded");
  }

  @Test
  void absentReadbackIsUnknownChangedBindingsConflictAndRolledBackAbortLeavesNoRow() {
    Fixture f = fixture(false);
    WorldDraftTopologyCommitPlan plan = topologyPlan(f, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTerminalOperation operation = operation(f, plan.binding());
    WorldDraftTerminalOutcomeService service = terminalService();
    assertThat(service.readDefinitiveAbort(operation)).isEmpty();

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          terminalRepository().recordDefinitiveAbort(operation);
                          throw new IllegalStateException("rollback fixture");
                        }))
        .hasMessageContaining("rollback fixture");
    assertThat(service.readDefinitiveAbort(operation)).isEmpty();

    WorldDraftTerminalOutcome original = service.recordDefinitiveAbort(operation);
    byte[] changedAccount =
        accountBinding(f, plan.binding(), operationIds(operation), new byte[] {9}).canonicalBytes();
    WorldDraftTerminalOperation changedSource =
        new WorldDraftTerminalOperation(
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.canonicalTenantId(),
            operation.canonicalVersionId(),
            operation.binding(),
            operation.ownerBinding(),
            changedAccount);
    assertThatThrownBy(() -> service.readDefinitiveAbort(changedSource))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("changed binding");

    OwnerBinding substitutedOwner =
        new OwnerBinding(
            operation.ownerBinding().targetNamespace(),
            operation.ownerBinding().canonicalTenantId(),
            operation.ownerBinding().canonicalVersionId(),
            operation.ownerBinding().versionIdentityOperationId(),
            operation.ownerBinding().gameDesignVersionId(),
            operation.ownerBinding().intakeRequestId(),
            operation.ownerBinding().intakeOperationId(),
            operation.ownerBinding().intakeRequestDigest(),
            UUID.randomUUID(),
            operation.ownerBinding().sourceEvidenceDigest(),
            operation.ownerBinding().intakeReceiptDigest());
    WorldDraftTerminalOperation changedOwnerSource =
        new WorldDraftTerminalOperation(
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.canonicalTenantId(),
            operation.canonicalVersionId(),
            operation.binding(),
            substitutedOwner,
            operation.accountBindingBytes());
    assertThatThrownBy(() -> service.readDefinitiveAbort(changedOwnerSource))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("changed binding");

    DraftCommitBinding changedBinding =
        changedPayload(plan.binding(), "substituted game design bytes");
    WorldDraftTerminalOperation changedInput =
        operation(f, changedBinding, operation.operationId(), operation.authorizationFenceId());
    assertThatThrownBy(() -> service.readDefinitiveAbort(changedInput))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("changed binding");
    assertThat(service.readDefinitiveAbort(operation).orElseThrow().canonicalBytes())
        .containsExactly(original.canonicalBytes());
  }

  private Race race(ThrowingRunnable commit, ThrowingRunnable abort) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> commitFuture =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return succeeded(commit);
              });
      Future<Boolean> abortFuture =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return succeeded(abort);
              });
      await(ready);
      start.countDown();
      return new Race(
          commitFuture.get(30, TimeUnit.SECONDS), abortFuture.get(30, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private boolean succeeded(ThrowingRunnable action) {
    try {
      action.run();
      return true;
    } catch (RuntimeException expectedRaceLoser) {
      return false;
    }
  }

  private void assertRawRegionFunctionDenied(Fixture f, WorldDraftRegionCommitPlan plan) {
    Map<String, Object> change = new LinkedHashMap<>();
    var mutation = plan.regionRevisions().getFirst().mutation();
    change.put("id", f.seededRegion());
    change.put("expectedAggregateEpoch", mutation.getExpectedDraftRevisionEpoch());
    change.put("expectedScopeEpoch", mutation.getExpectedDraftScopeRevisionEpoch());
    change.put("name", "delayed");
    change.put("shardId", 1);
    change.put("weather", "rain");
    change.put("generationSeed", 17L);
    change.put("generatorType", "test");
    change.put("generatorParams", "{}");
    change.put("spacingMultiplier", 1.0d);
    String ownerJson = mapper.writeValueAsString(plan.ownerBinding());
    String changesJson = mapper.writeValueAsString(List.of(change));
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            dsl.resultQuery(
                                    "SELECT world_store_guarded_region_rows(?::jsonb, ?::jsonb, ?::jsonb)",
                                    ownerJson,
                                    plan.binding().canonicalJson(),
                                    changesJson)
                                .fetch()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("definitive no-commit");
  }

  private Fixture fixture(boolean seedRegion) {
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    long sourceRow = Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceTenant = "gd-row-" + sourceRow;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, worldSlug, "Synthetic terminal world");
    String sourceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic terminal world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic terminal world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW",
            sourceDigest);
    UUID intakeRequest = UUID.randomUUID();
    ownerTransaction()
        .execute(status -> intakeRepository.acceptFresh(NAMESPACE, intakeRequest, source));
    WorldAuthoredSourceIntakeReceipt intake =
        intakeRepository.read(NAMESPACE, intakeRequest).orElseThrow();
    Fixture created =
        ownerTransaction()
            .execute(
                status -> {
                  UUID versionId = UUID.randomUUID();
                  AuthoredWorldVersionStateEvidence.Request stateRequest =
                      new AuthoredWorldVersionStateEvidence.Request(
                          1,
                          NAMESPACE,
                          UUID.randomUUID(),
                          tenant,
                          worldSlug,
                          sourceOperation,
                          sourceDigest,
                          GAME_DESIGN_VERSION);
                  WorldAuthoredVersionIdentityReceipt version =
                      new WorldAuthoredVersionIdentityRepository(dsl)
                          .acceptFresh(
                              intake,
                              AuthoredWorldVersionStateEvidence.create(
                                  stateRequest,
                                  source,
                                  versionId,
                                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                                  1));
                  OwnerBinding owner =
                      new OwnerBinding(
                          NAMESPACE,
                          tenant,
                          versionId,
                          version.operationId(),
                          GAME_DESIGN_VERSION,
                          intakeRequest,
                          intake.operationId(),
                          intake.requestDigest(),
                          sourceOperation,
                          sourceDigest,
                          intake.receiptDigest());
                  return new Fixture(intake, version, owner, null);
                });
    if (!seedRegion) {
      return created;
    }
    Long regionId =
        ownerTransaction()
            .execute(
                status -> {
                  dsl.execute("SET LOCAL session_replication_role = 'replica'");
                  var inserted =
                      Objects.requireNonNull(
                          dsl.fetchOne(
                              "INSERT INTO region(name,tenant_id,version_id) VALUES ('seeded',?,?) RETURNING id",
                              intake.localTenantKey(),
                              created.version().localVersionKey()),
                          "seeded region insert must return a row");
                  return Objects.requireNonNull(
                      inserted.get(0, Long.class), "seeded region insert must return an id");
                });
    return new Fixture(intake, created.version(), created.owner(), regionId);
  }

  private WorldDraftTopologyCommitPlan topologyPlan(Fixture f, UUID request, UUID commit) {
    UUID aggregate = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    WorldDesignMutationRevision mutation =
        WorldDesignMutationRevision.newBuilder()
            .setCommitId(commit.toString())
            .setLogicalRevisionId(revisionId.toString())
            .setAggregateId(aggregate.toString())
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(aggregate.toString())
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("topology")
                    .setWeather("clear")
                    .setShardId(0)
                    .setGenerationSeed(17)
                    .setGeneratorType("test")
                    .setGeneratorParams("{}"))
            .build();
    List<AffectedUnit> worldUnits =
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                aggregate.toString(),
                "AGGREGATE",
                aggregate.toString(),
                "0"),
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                aggregate.toString(),
                "REGION_SUBTREE",
                aggregate.toString(),
                "0"));
    DraftCommitBinding binding = draftBinding(f, request, commit, mutation, revisionId, worldUnits);
    return WorldDraftTopologyCommitPlan.create(binding, f.owner());
  }

  private WorldDraftRegionCommitPlan regionPlan(Fixture f, UUID request, UUID commit) {
    return regionPlan(f, request, commit, 0L);
  }

  private WorldDraftRegionCommitPlan regionPlan(
      Fixture f, UUID request, UUID commit, long expectedEpoch) {
    long region = f.seededRegion();
    UUID revisionId = UUID.randomUUID();
    WorldDesignMutationRevision mutation =
        WorldDesignMutationRevision.newBuilder()
            .setCommitId(commit.toString())
            .setLogicalRevisionId(revisionId.toString())
            .setAggregateId(Long.toString(region))
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(Long.toString(region))
            .setExpectedDraftRevisionEpoch(expectedEpoch)
            .setExpectedDraftScopeRevisionEpoch(expectedEpoch)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("committed")
                    .setWeather("clear")
                    .setShardId(1)
                    .setGenerationSeed(17)
                    .setGeneratorType("test")
                    .setGeneratorParams("{}"))
            .build();
    List<AffectedUnit> worldUnits =
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                Long.toString(region),
                "AGGREGATE",
                Long.toString(region),
                Long.toString(expectedEpoch)),
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                Long.toString(region),
                "REGION_SUBTREE",
                Long.toString(region),
                Long.toString(expectedEpoch)));
    DraftCommitBinding binding = draftBinding(f, request, commit, mutation, revisionId, worldUnits);
    return WorldDraftRegionCommitPlan.create(binding, f.owner());
  }

  private DraftCommitBinding draftBinding(
      Fixture f,
      UUID request,
      UUID commit,
      WorldDesignMutationRevision worldMutation,
      UUID worldRevision,
      List<AffectedUnit> worldUnits) {
    var source = f.intake().source();
    TargetProof target =
        new TargetProof(
            f.owner().canonicalTenantId(),
            f.owner().canonicalVersionId(),
            GAME_DESIGN_VERSION,
            source.sourceGameTenantKey(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    UUID gdRevision = UUID.randomUUID();
    List<AffectedUnit> allUnits = new ArrayList<>(worldUnits);
    allUnits.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "VERSION",
            f.owner().canonicalVersionId().toString(),
            "AGGREGATE",
            f.owner().canonicalVersionId().toString(),
            "0"));
    return DraftCommitBinding.create(
        target,
        request,
        commit,
        "retained-base",
        List.of(
            new RevisionPayload("0", worldRevision, Owner.WORLD_MANAGEMENT, json(worldMutation)),
            new RevisionPayload(
                "1", gdRevision, Owner.GAME_DESIGN_CONTROL_PLANE, "opaque Game Design input")),
        allUnits);
  }

  private DraftCommitBinding changedPayload(DraftCommitBinding binding, String payload) {
    List<RevisionPayload> revisions = new ArrayList<>();
    for (RevisionPayload revision : binding.revisions()) {
      revisions.add(
          new RevisionPayload(
              revision.revisionOrder(),
              revision.revisionId(),
              revision.owner(),
              revision.owner() == Owner.GAME_DESIGN_CONTROL_PLANE ? payload : revision.payload()));
    }
    return DraftCommitBinding.create(
        binding.target(),
        binding.requestId(),
        binding.commitId(),
        binding.baseCommitId(),
        revisions,
        binding.affectedUnits());
  }

  private WorldDraftTerminalOperation operation(Fixture f, DraftCommitBinding binding) {
    return operation(f, binding, UUID.randomUUID(), UUID.randomUUID());
  }

  private WorldDraftTerminalOperation operation(
      Fixture f, DraftCommitBinding binding, UUID operationId, UUID fenceId) {
    UUID actor = UUID.randomUUID();
    DraftAuthorizationFenceBinding accountBinding =
        accountBinding(
            f,
            binding,
            new OperationIds(operationId, binding.requestId(), binding.commitId(), fenceId, actor),
            new byte[] {1, 2, 3});
    return new WorldDraftTerminalOperation(
        operationId,
        binding.requestId(),
        binding.commitId(),
        fenceId,
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding,
        f.owner(),
        accountBinding.canonicalBytes());
  }

  private DraftAuthorizationFenceBinding accountBinding(
      Fixture f, DraftCommitBinding binding, OperationIds ids, byte[] evidence) {
    return new DraftAuthorizationFenceBinding(
        ids.operation(),
        ids.request(),
        ids.commit(),
        ids.fence(),
        ids.actor(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.baseCommitId(),
        "0",
        binding.canonicalBytes(),
        binding.canonicalBytes(),
        binding.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.TENANT,
                binding.target().canonicalTenantId().toString(),
                null,
                "1",
                null,
                null,
                evidence)));
  }

  private OperationIds operationIds(WorldDraftTerminalOperation operation) {
    return new OperationIds(
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        UUID.randomUUID());
  }

  private WorldDraftTopologyCommitService topologyComponent() {
    return new WorldDraftTopologyCommitService(
        new WorldDraftTopologyCommitRepository(dsl, fence, mapper), manager, plan -> {});
  }

  private WorldDraftRegionCommitService regionComponent() {
    return new WorldDraftRegionCommitService(
        new WorldDraftRegionCommitRepository(dsl, fence, mapper), manager, plan -> {});
  }

  private WorldDraftTerminalOutcomeService terminalService() {
    return new WorldDraftTerminalOutcomeService(terminalRepository(), manager);
  }

  private WorldDraftTerminalOutcomeRepository terminalRepository() {
    return new WorldDraftTerminalOutcomeRepository(dsl, fence, mapper);
  }

  private void freeze(Fixture f) {
    OwnerBinding owner = f.owner();
    String request = "terminal-freeze-" + UUID.randomUUID();
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
            request,
            "a".repeat(64),
            1,
            "publish:" + owner.canonicalTenantId() + ":publish-request:" + request);
    ownerTransaction()
        .execute(
            status ->
                fence.claimFreeze(
                    evidence, () -> new Checkpoint("synthetic-checkpoint", "b".repeat(64), 3)));
  }

  private long count(String table, UUID requestId) {
    var result =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE request_id = ?", requestId),
            "count query must return a row");
    return Objects.requireNonNull(result.get(0, Long.class), "count query must return a value");
  }

  private List<Long> regionEpochs(Fixture f) {
    var aggregateRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT draft_revision_epoch FROM world_design_aggregate_epoch "
                    + "WHERE tenant_id = ? AND version_id = ? "
                    + "AND aggregate_type = 'REGION' AND aggregate_id = ?",
                f.intake().localTenantKey(),
                f.version().localVersionKey(),
                f.seededRegion()),
            "aggregate epoch row must exist");
    long aggregateEpoch =
        Objects.requireNonNull(aggregateRow.get(0, Long.class), "aggregate epoch value must exist");
    var scopeRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT draft_scope_revision_epoch FROM world_design_scope_epoch "
                    + "WHERE tenant_id = ? AND version_id = ? "
                    + "AND scope_type = 'REGION_SUBTREE' AND scope_id = ?",
                f.intake().localTenantKey(),
                f.version().localVersionKey(),
                Long.toString(f.seededRegion())),
            "scope epoch row must exist");
    long scopeEpoch =
        Objects.requireNonNull(scopeRow.get(0, Long.class), "scope epoch value must exist");
    return List.of(aggregateEpoch, scopeEpoch);
  }

  private long draftRegionCount(Fixture f) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) FROM region WHERE tenant_id = ? AND version_id = ?",
                f.intake().localTenantKey(),
                f.version().localVersionKey()),
            "region count query must return a row");
    return Objects.requireNonNull(row.get(0, Long.class), "region count must have a value");
  }

  private String retainedTerminalSourceFenceAndEpochSnapshot(
      WorldDraftTerminalOperation operation) {
    String snapshot =
        Objects.requireNonNull(
                dsl.fetchOne(
                    """
                    SELECT jsonb_build_object(
                        'intake', to_jsonb(intake),
                        'identity', to_jsonb(version_identity),
                        'owner', to_jsonb(owner_row),
                        'terminal', (to_jsonb(terminal) - 'account_binding_bytes' - 'outcome_bytes')
                            || jsonb_build_object(
                                'account_binding_bytes_hex',
                                encode(terminal.account_binding_bytes, 'hex'),
                                'outcome_bytes_hex', encode(terminal.outcome_bytes, 'hex')
                            ),
                        'aggregate_epochs', COALESCE((
                            SELECT jsonb_agg(
                                to_jsonb(epoch) ORDER BY epoch.aggregate_type, epoch.aggregate_id
                            )
                            FROM world_design_aggregate_epoch epoch
                            WHERE epoch.tenant_id = terminal.local_tenant_key
                              AND epoch.version_id = terminal.local_version_key
                        ), '[]'::jsonb),
                        'scope_epochs', COALESCE((
                            SELECT jsonb_agg(
                                to_jsonb(epoch) ORDER BY epoch.scope_type, epoch.scope_id
                            )
                            FROM world_design_scope_epoch epoch
                            WHERE epoch.tenant_id = terminal.local_tenant_key
                              AND epoch.version_id = terminal.local_version_key
                        ), '[]'::jsonb)
                    )::text
                    FROM world_draft_terminal_outcome terminal
                    JOIN world_authored_version_identity version_identity
                      ON version_identity.operation_id = terminal.version_identity_operation_id
                    JOIN world_authored_source_intake intake
                      ON intake.operation_id = version_identity.intake_operation_id
                    JOIN world_design_publication_fence_owner owner_row
                      ON owner_row.target_namespace = terminal.target_namespace
                     AND owner_row.canonical_tenant_id = terminal.canonical_tenant_id
                     AND owner_row.local_tenant_key = terminal.local_tenant_key
                     AND owner_row.version_id = terminal.local_version_key
                    WHERE terminal.operation_id = ?
                    """,
                    operation.operationId()),
                "retained World source/fence/outcome rows must exist")
            .get(0, String.class);
    return Objects.requireNonNull(
        snapshot, "retained World source/fence/outcome snapshot must exist");
  }

  private void replaceV36TerminalGuardWithV37() {
    String serviceSchema =
        Objects.requireNonNull(
                dsl.fetchOne("SELECT current_schema()"), "World service schema row must exist")
            .get(0, String.class);
    Objects.requireNonNull(serviceSchema, "World service schema must be selected");
    String v36Function =
        migrationFunction(
                "db/migration/V36__world_draft_terminal_outcomes.sql",
                "CREATE FUNCTION world_reject_aborted_draft_attempt()")
            .replaceFirst("^CREATE FUNCTION", "CREATE OR REPLACE FUNCTION")
            .replace("${serviceSchema}", serviceSchema);
    String v37Function =
        migrationFunction(
                "db/migration/V37__world_draft_terminal_guard_namespace.sql",
                "CREATE OR REPLACE FUNCTION world_reject_aborted_draft_attempt()")
            .replace("${serviceSchema}", serviceSchema);

    // PostgreSQL DDL is transactional: a failed V37 replacement rolls back the V36 body too.
    ownerTransaction()
        .executeWithoutResult(
            status -> {
              dsl.execute(v36Function);
              dsl.execute(v37Function);
              dsl.execute(
                  "REVOKE ALL ON FUNCTION world_reject_aborted_draft_attempt() FROM PUBLIC");
            });
  }

  private String migrationFunction(String resourceName, String declaration) {
    try (InputStream resource =
        WorldDraftTerminalOutcomePostgresIntegrationTest.class
            .getClassLoader()
            .getResourceAsStream(resourceName)) {
      if (resource == null) {
        throw new IllegalStateException("World migration resource is missing: " + resourceName);
      }
      String migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
      int start = migration.indexOf(declaration);
      int end = start < 0 ? -1 : migration.indexOf("$$;", start);
      if (start < 0 || end < 0) {
        throw new IllegalStateException("World migration function is missing: " + declaration);
      }
      return migration.substring(start, end + "$$;".length());
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read World migration: " + resourceName, exception);
    }
  }

  private long worldAbortedAttemptGuardFunctionOid() {
    return Objects.requireNonNull(
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT 'world_reject_aborted_draft_attempt()'::regprocedure::oid::bigint"),
                "World aborted-attempt guard function row must exist")
            .get(0, Long.class),
        "World aborted-attempt guard function must exist");
  }

  private long worldAbortedAttemptGuardTriggerCount() {
    return Objects.requireNonNull(
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT count(*) FROM pg_trigger "
                        + "WHERE tgfoid = 'world_reject_aborted_draft_attempt()'::regprocedure "
                        + "AND NOT tgisinternal"),
                "World aborted-attempt guard trigger-count row must exist")
            .get(0, Long.class),
        "World aborted-attempt guard trigger count must exist");
  }

  private boolean worldAbortedAttemptGuardHasPublicExecute() {
    return Objects.requireNonNull(
        Objects.requireNonNull(
                dsl.fetchOne(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_proc function_row
                        CROSS JOIN LATERAL aclexplode(
                            COALESCE(function_row.proacl, acldefault('f', function_row.proowner))
                        ) acl_entry
                        WHERE function_row.oid =
                            'world_reject_aborted_draft_attempt()'::regprocedure
                          AND acl_entry.grantee = 0
                          AND acl_entry.privilege_type = 'EXECUTE'
                    )
                    """),
                "World aborted-attempt guard ACL row must exist")
            .get(0, Boolean.class),
        "World aborted-attempt guard ACL must be readable");
  }

  private String regionName(long regionId) {
    var result =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT name FROM region WHERE id = ?", regionId),
            "region lookup must return a row");
    return Objects.requireNonNull(result.get(0, String.class), "region lookup must return a name");
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private String digest(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Fixture timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run();
  }

  private record Race(boolean commitWon, boolean abortWon) {}

  private record OperationIds(UUID operation, UUID request, UUID commit, UUID fence, UUID actor) {}

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner,
      Long seededRegionId) {
    long seededRegion() {
      if (seededRegionId == null) {
        throw new IllegalStateException("Fixture has no retained region");
      }
      return seededRegionId;
    }
  }
}
