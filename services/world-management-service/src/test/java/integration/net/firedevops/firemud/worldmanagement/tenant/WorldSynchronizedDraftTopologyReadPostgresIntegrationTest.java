package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityClient;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Real World PostgreSQL exact-binding graph selection. The synchronized GD evidence and the
 * component-store permission callback are explicit synthetic fixtures, not Account authorization or
 * proof of a registered creator/write path.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorldSynchronizedDraftTopologyReadPostgresIntegrationTest {
  private static final String NAMESPACE = "world-draft-read-test";
  private static final long GAME_DESIGN_VERSION = 9_000_000_000_000_001L;

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Fixture fixture;

  @BeforeAll
  static void migrate() {
    String schema = "world_sync_read_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_world_management_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    // Match the production proxy's NOT_SUPPORTED source-intake reads inside owner transactions.
    TransactionInterceptor intakeTransactions = new TransactionInterceptor();
    intakeTransactions.setTransactionManager(manager);
    intakeTransactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    ProxyFactory intakeProxy = new ProxyFactory(new WorldAuthoredSourceIntakeRepository(dsl));
    intakeProxy.setProxyTargetClass(true);
    intakeProxy.addAdvice(intakeTransactions);
    WorldAuthoredSourceIntakeRepository intakeRepository =
        (WorldAuthoredSourceIntakeRepository) intakeProxy.getProxy();
    WorldDesignPublicationFenceRepository fence =
        new WorldDesignPublicationFenceRepository(dsl, intakeRepository);
    ObjectMapper mapper = new ObjectMapper();
    var repository = new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
    fixture = new Fixture(dsl, transaction, manager, intakeRepository, fence, mapper, repository);
  }

  @Test
  void deniesArbitraryAppliedFixtureBytesEvenWhenOriginalGraphIsRetained() throws Exception {
    Seed seed = fixture.seed();
    WorldDraftTopologyCommitPlan plan = fixture.plan(seed, UUID.randomUUID(), UUID.randomUUID());
    WorldDraftTopologyCommitService writerFixture =
        new WorldDraftTopologyCommitService(fixture.repository(), fixture.manager(), ignored -> {});
    var stored = writerFixture.store(plan);
    assertThat(stored.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");

    // Simulate later unverified current content; synchronized normal reads must use retained bytes.
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.dsl().execute("SET LOCAL session_replication_role='replica'");
              fixture
                  .dsl()
                  .execute(
                      "UPDATE room SET name='later-unverified-room' WHERE tenant_id=? AND version_id=?",
                      seed.intake().localTenantKey(),
                      seed.version().localVersionKey());
              fixture.dsl().execute("SET LOCAL session_replication_role='origin'");
            });
    assertThat(
            fixture
                .dsl()
                .resultQuery(
                    "SELECT name FROM room WHERE tenant_id=? AND version_id=?",
                    seed.intake().localTenantKey(),
                    seed.version().localVersionKey())
                .fetch(0, String.class))
        .contains("later-unverified-room");

    var request =
        new DraftSynchronizedVisibilityEvidence.Request(
            1, NAMESPACE, UUID.randomUUID(), plan.binding().target());
    DraftSynchronizedVisibilityEvidence evidence = evidence(request, plan.binding());
    DraftSynchronizedVisibilityClient client = mock(DraftSynchronizedVisibilityClient.class);
    when(client.read(request)).thenReturn(evidence);

    assertThatThrownBy(
            () ->
                new WorldSynchronizedDraftTopologyReadService(client, fixture.repository())
                    .read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no canonical World APPLIED-result carrier");
  }

  @Test
  void missingCanonicalOwnerOutputNeverFallsBackToMutableCurrentWorldGraph() throws Exception {
    Seed seed = fixture.seed();
    WorldDraftTopologyCommitPlan storedPlan =
        fixture.plan(seed, UUID.randomUUID(), UUID.randomUUID());
    new WorldDraftTopologyCommitService(fixture.repository(), fixture.manager(), ignored -> {})
        .store(storedPlan);

    var unretainedBinding =
        DraftCommitBinding.create(
            storedPlan.binding().target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            storedPlan.binding().baseCommitId(),
            storedPlan.binding().revisions(),
            storedPlan.binding().affectedUnits());
    var request =
        new DraftSynchronizedVisibilityEvidence.Request(
            1, NAMESPACE, UUID.randomUUID(), unretainedBinding.target());
    DraftSynchronizedVisibilityClient client = mock(DraftSynchronizedVisibilityClient.class);
    when(client.read(request)).thenReturn(evidence(request, unretainedBinding));

    assertThatThrownBy(
            () ->
                new WorldSynchronizedDraftTopologyReadService(client, fixture.repository())
                    .read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no canonical World APPLIED-result carrier");
  }

  private static DraftSynchronizedVisibilityEvidence evidence(
      DraftSynchronizedVisibilityEvidence.Request request, DraftCommitBinding binding) {
    List<Map<String, Object>> epochs = new java.util.ArrayList<>();
    for (AffectedUnit unit : binding.affectedUnits(Owner.WORLD_MANAGEMENT)) {
      Map<String, Object> epoch = new java.util.LinkedHashMap<>();
      epoch.put("aggregateType", unit.aggregateType());
      epoch.put("aggregateId", unit.aggregateId());
      epoch.put("scopeType", unit.scopeType());
      epoch.put("scopeId", unit.scopeId());
      epoch.put("expectedEpoch", unit.expectedEpoch());
      epoch.put(
          "resultingEpoch",
          new java.math.BigInteger(unit.expectedEpoch()).add(java.math.BigInteger.ONE).toString());
      epochs.add(epoch);
    }
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("owner", Owner.WORLD_MANAGEMENT.name());
    result.put("status", "APPLIED");
    result.put("commitId", binding.commitId().toString());
    result.put("bindingDigest", binding.digest());
    // This synthetic identity/bytes pair is not a canonical World APPLIED-output carrier.
    result.put("resultIdentity", "fixture-world-result");
    result.put(
        "resultBytesBase64",
        Base64.getEncoder()
            .encodeToString("fixture-world-result-bytes".getBytes(StandardCharsets.UTF_8)));
    result.put("appliedEpochs", epochs);
    String vector = canonical(new ObjectMapper().writeValueAsString(List.of(result)));
    return new DraftSynchronizedVisibilityEvidence(
        request,
        binding,
        "SYNCHRONIZED",
        new DraftSynchronizedVisibilityEvidence.Fence(
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            vector,
            java.time.OffsetDateTime.parse("2026-10-06T00:00:00Z")));
  }

  private static WorldDesignMutationRevision mutation(
      UUID commitId,
      UUID revisionId,
      UUID aggregateId,
      WorldDesignAggregateType type,
      UUID regionId) {
    var builder =
        WorldDesignMutationRevision.newBuilder()
            .setLogicalRevisionId(revisionId.toString())
            .setCommitId(commitId.toString())
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setAggregateType(type)
            .setAggregateId(aggregateId.toString())
            .setExpectedDraftRevisionEpoch(0)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(regionId.toString())
            .setExpectedDraftScopeRevisionEpoch(0)
            .setScopeMutationPolicy(
                WorldDesignScopeMutationPolicy.WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED);
    return builder.build();
  }

  private static String canonical(String json) {
    try {
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private record Seed(
      AuthoredWorldSourceEvidence source,
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transaction,
      org.springframework.transaction.PlatformTransactionManager manager,
      WorldAuthoredSourceIntakeRepository intakeRepository,
      WorldDesignPublicationFenceRepository fence,
      ObjectMapper mapper,
      WorldDraftTopologyCommitRepository repository) {
    Seed seed() {
      UUID tenant = UUID.randomUUID();
      UUID registration = UUID.randomUUID();
      UUID sourceOperation = UUID.randomUUID();
      String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
      String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
      long sourceRow = Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
      String sourceTenant = "gd-row-" + sourceRow;
      String sourceRequestDigest =
          AuthoredWorldSourceDigest.requestDigest(
              NAMESPACE, registration, tenant, tenantSlug, worldSlug, "Synthetic read world");
      String sourceEvidenceDigest =
          AuthoredWorldSourceDigest.evidenceDigest(
              NAMESPACE,
              registration,
              sourceOperation,
              sourceRequestDigest,
              tenant,
              tenantSlug,
              worldSlug,
              "Synthetic read world",
              sourceRow,
              sourceTenant,
              "NEW_GAME_ROW");
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              1,
              NAMESPACE,
              registration,
              sourceOperation,
              sourceRequestDigest,
              tenant,
              tenantSlug,
              worldSlug,
              "Synthetic read world",
              sourceRow,
              sourceTenant,
              "NEW_GAME_ROW",
              sourceEvidenceDigest);
      UUID intakeRequestId = UUID.randomUUID();
      transaction.executeWithoutResult(
          status -> intakeRepository.acceptFresh(NAMESPACE, intakeRequestId, source));
      WorldAuthoredSourceIntakeReceipt intake =
          intakeRepository.read(NAMESPACE, intakeRequestId).orElseThrow();
      UUID canonicalVersionId = UUID.randomUUID();
      AuthoredWorldVersionStateEvidence.Request stateRequest =
          new AuthoredWorldVersionStateEvidence.Request(
              1,
              NAMESPACE,
              UUID.randomUUID(),
              tenant,
              worldSlug,
              sourceOperation,
              sourceEvidenceDigest,
              GAME_DESIGN_VERSION);
      WorldAuthoredVersionIdentityReceipt version =
          transaction.execute(
              status ->
                  new WorldAuthoredVersionIdentityRepository(dsl)
                      .acceptFresh(
                          intake,
                          AuthoredWorldVersionStateEvidence.create(
                              stateRequest,
                              source,
                              canonicalVersionId,
                              VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                              1L)));
      OwnerBinding owner =
          new OwnerBinding(
              NAMESPACE,
              tenant,
              canonicalVersionId,
              version.operationId(),
              GAME_DESIGN_VERSION,
              intakeRequestId,
              intake.operationId(),
              intake.requestDigest(),
              sourceOperation,
              sourceEvidenceDigest,
              intake.receiptDigest());
      return new Seed(source, intake, version, owner);
    }

    WorldDraftTopologyCommitPlan plan(Seed seed, UUID requestId, UUID commitId) throws Exception {
      UUID region = UUID.randomUUID();
      UUID zone = UUID.randomUUID();
      UUID room = UUID.randomUUID();
      List<WorldDesignMutationRevision> mutations =
          List.of(
              mutation(
                      commitId,
                      UUID.randomUUID(),
                      region,
                      WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
                      region)
                  .toBuilder()
                  .setRegion(
                      RegionDesignMutation.newBuilder()
                          .setName("region")
                          .setWeather("rain")
                          .setGeneratorType("fixture"))
                  .build(),
              mutation(
                      commitId,
                      UUID.randomUUID(),
                      zone,
                      WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                      region)
                  .toBuilder()
                  .setZone(
                      ZoneDesignMutation.newBuilder()
                          .setName("zone")
                          .setRegionId(region.toString()))
                  .build(),
              mutation(
                      commitId,
                      UUID.randomUUID(),
                      room,
                      WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                      region)
                  .toBuilder()
                  .setRoom(
                      RoomDesignMutation.newBuilder()
                          .setName("original-room")
                          .setZoneId(zone.toString()))
                  .build());
      List<RevisionPayload> revisions = new java.util.ArrayList<>();
      List<AffectedUnit> units = new java.util.ArrayList<>();
      for (int index = 0; index < mutations.size(); index++) {
        WorldDesignMutationRevision value = mutations.get(index);
        String aggregateType =
            value.getAggregateType().name().substring("WORLD_DESIGN_AGGREGATE_TYPE_".length());
        String scopeType =
            value.getScopeType().name().substring("WORLD_DESIGN_SCOPE_TYPE_".length());
        revisions.add(
            new RevisionPayload(
                Integer.toString(index),
                UUID.fromString(value.getLogicalRevisionId()),
                Owner.WORLD_MANAGEMENT,
                JsonFormat.printer().omittingInsignificantWhitespace().print(value)));
        units.add(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                aggregateType,
                value.getAggregateId(),
                "AGGREGATE",
                value.getAggregateId(),
                "0"));
        units.add(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                aggregateType,
                value.getAggregateId(),
                scopeType,
                value.getScopeId(),
                "0"));
      }
      DraftCommitBinding binding =
          DraftCommitBinding.create(
              new DraftCommitBinding.TargetProof(
                  seed.owner().canonicalTenantId(),
                  seed.owner().canonicalVersionId(),
                  GAME_DESIGN_VERSION,
                  seed.source().sourceGameTenantKey(),
                  seed.source().sourceGameRowId(),
                  seed.source().sourceGameTenantKey(),
                  seed.source().provenanceKind()),
              requestId,
              commitId,
              "base-source-1",
              revisions,
              units);
      return WorldDraftTopologyCommitPlan.create(binding, seed.owner());
    }
  }
}
