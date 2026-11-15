package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
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
 * Native PostgreSQL World owner proof for V67/V68 with controlled upstream wire inputs.
 *
 * <p>GDS/GS values and certificate commitments are stipulated constructor data. This proves actual
 * World owner allocation, materialization and readback, not authenticated producers or TLS.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class RunOwnedWorldFixedPresetResourcesPostgresIntegrationTest {
  private static final String CONTENT_DIGEST =
      "sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc";
  private static final String VALID_DIGEST = "sha256:" + "a".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void malformedAndUnknownPlansAreDeniedBeforeAnyResourceWrite() {
    long before = resourceCount();
    assertSqlState(() -> materialize("{", 9_100_001L, 9_100_002L), "22P02");

    Map<String, Object> unknownPlan = validClosedPlan(UUID.randomUUID(), 9_100_003L, 9_100_004L);
    unknownPlan.put("unrecognized", "must be rejected");
    assertSqlState(
        () -> materialize(json(unknownPlan), 9_100_005L, 9_100_006L),
        "23514",
        "unknown or missing plan field");

    assertThat(resourceCount()).isEqualTo(before);
  }

  @Test
  void missingOwnerAssociationRejectsPlanAndCannotAdoptAnExistingLegacyWorldRow() {
    long before = resourceCount();
    long tenantKey = positiveLong();
    long privateKey = positiveLong();
    long versionKey = positiveLong();
    long worldInstanceId = insertLegacyWorldRow(tenantKey, privateKey, versionKey);
    UUID canonicalGameInstanceId = UUID.randomUUID();
    Map<String, Object> plan = validClosedPlan(canonicalGameInstanceId, tenantKey, versionKey);

    assertSqlState(() -> materialize(json(plan), worldInstanceId, privateKey), "P0002");

    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_canonical_instance_association WHERE world_instance_id = ?",
                    worldInstanceId)
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_canonical_instance_preparation WHERE canonical_game_instance_id = ?",
                    canonicalGameInstanceId)
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_canonical_instance_topology_identity WHERE world_instance_id = ?",
                    worldInstanceId)
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchValue(
                "SELECT count(*) FROM world_instance WHERE id = ? AND tenant_id = ? "
                    + "AND game_instance_id = ? AND version_id = ? AND status = 'LEGACY_FIXTURE'",
                worldInstanceId,
                tenantKey,
                privateKey,
                versionKey))
        .isEqualTo(1L);
    assertThat(resourceCount()).isEqualTo(before + 1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void realWorldOwnersMaterializeSixFixtureMappingsAndReplayExactCommittedBytes() {
    RunOwnedWorldLaunchEvidence evidence = controlledEvidence();
    var sourceRepository = new WorldAuthoredSourceIntakeRepository(dsl);
    var versionRepository = new WorldAuthoredVersionIdentityRepository(dsl);
    var associationRepository =
        new WorldCanonicalInstanceAssociationRepository(
            dsl,
            new WorldCompleteLaunchBindingRepository(dsl),
            sourceRepository,
            versionRepository);
    var sourceEvidence = evidence.sourceReceipt().sourceEvidence();
    UUID intakeRequestId = UUID.randomUUID();
    var source =
        ownerTransaction()
            .execute(
                status -> sourceRepository.acceptFresh("dev", intakeRequestId, sourceEvidence));
    assertThat(source).isNotNull();
    assertThat(sourceRepository.read("dev", intakeRequestId)).contains(source);
    var stateRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            "dev",
            UUID.randomUUID(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.sourceOperationId(),
            source.sourceEvidenceDigest(),
            evidence.launchDescriptor().versionId());
    var state =
        AuthoredWorldVersionStateEvidence.create(
            stateRequest,
            sourceEvidence,
            evidence.gameSessionAssociation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            evidence.launchDescriptor().versionStateEpoch());
    var version =
        ownerTransaction().execute(status -> versionRepository.acceptFresh(source, state));
    assertThat(version).isNotNull();
    assertThat(
            versionRepository.readByCanonicalVersion(
                "dev",
                source.canonicalTenantId(),
                source.worldSlug(),
                version.canonicalVersionId()))
        .contains(version);

    // Establish V68's OPEN publication-owner prerequisite through its actual repository. This is
    // an owner lock, not a Draft commit, revision, capture, freeze, or publication authorization.
    var fenceRepository = new WorldDesignPublicationFenceRepository(dsl, sourceRepository);
    var binding =
        new OwnerBinding(
            "dev",
            source.canonicalTenantId(),
            version.canonicalVersionId(),
            version.operationId(),
            version.gameDesignVersionId(),
            source.intakeRequestId(),
            source.operationId(),
            source.requestDigest(),
            source.sourceOperationId(),
            source.sourceEvidenceDigest(),
            source.receiptDigest());

    var gs = evidence.gameSessionAssociation();
    var descriptor = evidence.launchDescriptor();
    var request =
        new PreparedWorldInstanceRequest(
            gs.gameSessionTenantId(),
            gs.gameInstanceId(),
            descriptor.gameTemplateId(),
            evidence.capability().operationId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            null,
            descriptor.runtimeFlagsJson(),
            descriptor.generationConfigRevision(),
            descriptor.releaseBundleId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            null);
    WorldCanonicalInstanceAssociation allocated =
        ownerTransaction()
            .execute(
                status -> {
                  fenceRepository.lockOpen(binding);
                  var association =
                      associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
                          evidence, request, source, version);
                  return association;
                });
    assertThat(allocated).isNotNull();
    assertNoPendingManifests(allocated.worldInstanceId());
    var association = associationRepository.readRunOwnedFixtureAssociation(evidence, request);
    assertThat(association.isRunOwnedFixture()).isTrue();
    assertThat(association.worldInstanceId()).isEqualTo(allocated.worldInstanceId());
    assertThat(association.versionIdentity()).isEqualTo(version);
    assertThat(association.runOwnedFixture().sourceIntakeReceipt()).isEqualTo(source);
    var plan = RunOwnedWorldFixedPresetResources.plan(evidence, source, version);
    assertThat(RunOwnedWorldFixedPresetResources.plan(association, source, version).planJson())
        .isEqualTo(plan.planJson());
    long worldId = association.worldInstanceId();
    long privateKey = association.worldPrivateKeys().gameInstanceId();
    long before = resourceCount();

    // Negative SQL inputs use the actual retained World images. They establish statement
    // rollback and exact-row denial only; they do not supply authenticated GS transport proof.
    Map<String, Object> allocationWorld =
        objectMapper.readValue(
            dsl.fetchSingle("SELECT to_jsonb(w)::text FROM world_instance w WHERE id = ?", worldId)
                .get(0, String.class),
            Map.class);
    for (String automaticField :
        List.of(
            "created_at",
            "updated_at",
            "row_version",
            "failure_reason",
            "termination_request_id",
            "terminated_at")) {
      allocationWorld.remove(automaticField);
    }
    Map<String, Object> allocationAssociation =
        objectMapper.readValue(
            dsl.fetchSingle(
                    "SELECT to_jsonb(a)::text FROM world_canonical_instance_association a WHERE world_instance_id = ?",
                    worldId)
                .get(0, String.class),
            Map.class);
    // Retained fixture values cannot replay an association INSERT outside the atomic owner;
    // changing a row field cannot turn those values into a new execution authorization either.
    String unmanifestedAssociation = json(allocationAssociation);
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.execute(
                            "INSERT INTO world_canonical_instance_association "
                                + "SELECT (jsonb_populate_record(NULL::world_canonical_instance_association, ?::jsonb)).*",
                            unmanifestedAssociation)),
        "23514",
        "exact one-use transaction execution manifest");
    Map<String, Object> changedUnmanifestedAssociation = new LinkedHashMap<>(allocationAssociation);
    changedUnmanifestedAssociation.put("game_session_tenant_id", gs.gameSessionTenantId() + 1);
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.execute(
                            "INSERT INTO world_canonical_instance_association "
                                + "SELECT (jsonb_populate_record(NULL::world_canonical_instance_association, ?::jsonb)).*",
                            json(changedUnmanifestedAssociation))),
        "23514",
        "exact one-use transaction execution manifest");
    assertThat(resourceCount()).isEqualTo(before);
    assertNoPendingManifests(worldId);

    long rejectedWorldId =
        dsl.fetchSingle("SELECT nextval('world_instance_id_seq')").get(0, Long.class);
    long rejectedPrivateKey =
        dsl.fetchSingle("SELECT nextval('world_canonical_private_game_instance_key_seq')")
            .get(0, Long.class);
    String rejectedCanonicalId = UUID.randomUUID().toString();
    String rejectedOperationId = UUID.randomUUID().toString();
    allocationWorld.put("id", rejectedWorldId);
    allocationWorld.put("game_instance_id", rejectedPrivateKey);
    allocationWorld.put("canonical_game_instance_id", rejectedCanonicalId);
    allocationWorld.put("control_plane_request_id", rejectedOperationId);
    allocationAssociation.put("world_instance_id", rejectedWorldId);
    allocationAssociation.put("private_game_instance_key", rejectedPrivateKey);
    allocationAssociation.put("canonical_game_instance_id", rejectedCanonicalId);
    allocationAssociation.put("control_plane_request_id", rejectedOperationId);

    Map<String, Object> changedAllocationWorld = new LinkedHashMap<>(allocationWorld);
    changedAllocationWorld.put("game_session_tenant_id", gs.gameSessionTenantId() + 1);
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.fetchSingle(
                            "SELECT world_allocate_run_owned_fixture_instance(?::jsonb, ?::jsonb)",
                            json(changedAllocationWorld),
                            json(allocationAssociation))),
        "23514",
        "no exact transaction execution manifest");
    assertThat(resourceCount()).isEqualTo(before);
    assertNoPendingManifests(rejectedWorldId);

    // This value is not in the World row. Its association CHECK fails after the exact World
    // insert consumes its manifest, proving that the SQL owner cannot leave a half allocation.
    Map<String, Object> changedAllocationAssociation = new LinkedHashMap<>(allocationAssociation);
    changedAllocationAssociation.put("descriptor_request_digest", "invalid");
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.fetchSingle(
                            "SELECT world_allocate_run_owned_fixture_instance(?::jsonb, ?::jsonb)",
                            json(allocationWorld),
                            json(changedAllocationAssociation))),
        "23514");
    assertThat(resourceCount()).isEqualTo(before);
    assertThat(dsl.fetchValue("SELECT count(*) FROM world_instance WHERE id = ?", rejectedWorldId))
        .isEqualTo(0L);
    assertThat(
            dsl.fetchValue(
                "SELECT count(*) FROM world_canonical_instance_association WHERE world_instance_id = ?",
                rejectedWorldId))
        .isEqualTo(0L);
    assertNoPendingManifests(rejectedWorldId);

    Map<String, Object> changedSeal = objectMapper.readValue(plan.planJson(), Map.class);
    changedSeal.put("presetSealDigest", VALID_DIGEST);
    assertSqlState(
        () -> materialize(json(changedSeal), worldId, privateKey), "23514", "preset seal differs");
    Map<String, Object> changedProvenance = objectMapper.readValue(plan.planJson(), Map.class);
    changedProvenance.put("fixturePolicyEvidenceDigest", VALID_DIGEST);
    changedProvenance.put("presetSealDigest", framedSeal(changedProvenance));
    assertSqlState(() -> materialize(json(changedProvenance), worldId, privateKey), "23514");
    assertThat(resourceCount()).isEqualTo(before);
    assertNoPendingManifests(worldId);

    // The existing canonical-tenant guards forbid introducing an unowned row through SQL. Keep
    // this deny boundary enabled instead of manufacturing preexisting corruption by bypassing it.
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.execute(
                            "INSERT INTO region (tenant_id, version_id, name, shard_id, generation_seed, spacing_multiplier, version) "
                                + "VALUES (?, ?, 'unowned resource', 0, 0, 1.0, 0)",
                            source.localTenantKey(),
                            version.localVersionKey())),
        "23514",
        "no exact transaction execution manifest");
    assertThat(resourceCount()).isEqualTo(before);

    var result =
        ownerTransaction()
            .execute(
                status -> {
                  fenceRepository.lockOpen(binding);
                  var replayAllocation =
                      associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
                          evidence, request, source, version);
                  assertThat(replayAllocation.worldInstanceId()).isEqualTo(worldId);
                  return RunOwnedWorldFixedPresetResources.materialize(
                      dsl, plan, worldId, privateKey);
                });
    assertThat(result).isNotNull();
    assertThat(result.regionCount()).isEqualTo(1);
    assertThat(result.zoneCount()).isEqualTo(1);
    assertThat(result.roomCount()).isEqualTo(2);
    assertThat(result.exitCount()).isEqualTo(2);
    TransactionTemplate committedRead = new TransactionTemplate(transactionManager);
    committedRead.setReadOnly(true);
    committedRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    RunOwnedWorldFixedPresetResources.MaterializedFixedPreset committedResult =
        committedRead.execute(
            status ->
                RunOwnedWorldFixedPresetResources.readCommitted(dsl, association, source, version));
    assertThat(committedResult).isEqualTo(result);
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_authored_topology_identity WHERE fixture_world_instance_id = ? "
                    + "AND preparation_authority_kind = 'RUN_OWNED_FIXTURE' AND fixture_operation_id = ? "
                    + "AND fixture_provenance_digest = ? AND request_id IS NULL AND commit_id IS NULL "
                    + "AND revision_id IS NULL AND revision_order IS NULL",
                worldId,
                evidence.capability().operationId(),
                evidence.fixtureProvenanceDigest()))
        .isEqualTo(6L);
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_authored_topology_identity WHERE tenant_id = ? AND version_id = ?",
                source.localTenantKey(),
                version.localVersionKey()))
        .isEqualTo(6L);
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_topology_draft_commit WHERE local_tenant_key = ? AND local_version_key = ?",
                source.localTenantKey(),
                version.localVersionKey()))
        .isZero();
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_design_revision_ledger WHERE tenant_id = ? AND version_id = ?",
                source.localTenantKey(),
                version.localVersionKey()))
        .isZero();
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_canonical_instance_topology_identity WHERE world_instance_id = ?",
                worldId))
        .isEqualTo(6L);
    assertNoPendingManifests(worldId);

    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_canonical_preparation_start_location WHERE world_instance_id = ?",
                worldId))
        .isZero();

    var retained =
        dsl.fetchSingle(
            "SELECT input_json, graph_bytes, graph_sha256, input_digest, capture_id FROM world_canonical_instance_preparation "
                + "WHERE world_instance_id = ?",
            worldId);
    assertThat(retained.get("input_json", String.class)).isEqualTo(plan.planJson());
    assertThat(retained.get("graph_bytes", byte[].class))
        .isEqualTo(plan.planJson().getBytes(StandardCharsets.UTF_8));
    assertThat(retained.get("graph_sha256", String.class)).isEqualTo(plan.expectedGraphSha256());
    assertThat(retained.get("input_digest", String.class)).isEqualTo(plan.presetSealDigest());
    assertThat(retained.get("capture_id")).isNull();
    long materializedCount = resourceCount();
    String retainedWorld =
        scalarText("SELECT row_to_json(w)::text FROM world_instance w WHERE id = ?", worldId);
    String retainedPreparation =
        scalarText(
            "SELECT row_to_json(p)::text FROM world_canonical_instance_preparation p WHERE world_instance_id = ?",
            worldId);
    var replayResult =
        ownerTransaction()
            .execute(
                status -> {
                  fenceRepository.lockOpen(binding);
                  var replayAllocation =
                      associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
                          evidence, request, source, version);
                  assertThat(replayAllocation.worldInstanceId()).isEqualTo(worldId);
                  return RunOwnedWorldFixedPresetResources.materialize(
                      dsl, plan, worldId, privateKey);
                });
    assertThat(replayResult).isEqualTo(result);
    assertThat(resourceCount()).isEqualTo(materializedCount);
    assertThat(
            scalarText("SELECT row_to_json(w)::text FROM world_instance w WHERE id = ?", worldId))
        .isEqualTo(retainedWorld);
    assertThat(
            scalarText(
                "SELECT row_to_json(p)::text FROM world_canonical_instance_preparation p WHERE world_instance_id = ?",
                worldId))
        .isEqualTo(retainedPreparation);
    assertNoPendingManifests(worldId);
  }

  private long scalarLong(String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchSingle(sql, bindings).get(0, Long.class));
  }

  private String scalarText(String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchSingle(sql, bindings).get(0, String.class));
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }

  private void assertNoPendingManifests(long worldInstanceId) {
    assertThat(
            scalarLong(
                "SELECT (SELECT count(*) FROM world_region_draft_execution_manifest WHERE fixture_world_instance_id = ?) "
                    + "+ (SELECT count(*) FROM world_canonical_instance_preparation_execution_manifest WHERE fixture_world_instance_id = ?)",
                worldInstanceId,
                worldInstanceId))
        .isZero();
  }

  /**
   * Mirrors Java's length-framed seal for closed negative SQL inputs, never grants owner authority.
   */
  private static String framedSeal(Map<String, Object> plan) {
    List<String> fields =
        List.of(
            "presetId",
            "expectedContentDigest",
            "targetNamespace",
            "fixtureOperationId",
            "claimDigest",
            "fixturePolicyRequestDigest",
            "fixturePolicyEvidenceDigest",
            "fixturePolicyBytesDigest",
            "lifecycleBindingDigest",
            "gameSessionResponseBytesDigest",
            "canonicalTenantId",
            "worldSlug",
            "worldSourceIntakeOperationId",
            "sourceOperationId",
            "sourceEvidenceDigest",
            "sourceReceiptDigest",
            "worldLocalTenantKey",
            "worldVersionIdentityOperationId",
            "canonicalVersionId",
            "gameDesignVersionId",
            "versionEvidenceDigest",
            "worldLocalVersionKey",
            "launchDescriptorId",
            "descriptorRequestDigest",
            "descriptorResultDigest",
            "descriptorRuntimeFlagsJson",
            "launchGameDesignVersionId",
            "releaseBundleId",
            "publishedReleaseBundleRef",
            "manifestHash",
            "generationConfigRevision",
            "versionStateEpoch",
            "gameSessionTenantId",
            "gameSessionGameInstanceId",
            "gameSessionGameInstanceUuid",
            "gameSessionCanonicalTenantId",
            "gameSessionCanonicalVersionId",
            "playableStateNamespaceId",
            "canonicalRealmId",
            "playableStateScope",
            "publicProduction",
            "catalogRevision",
            "gameTemplateId");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      frame(digest, "firemud-world-run-owned-fixed-preset-binding/v1");
      for (String field : fields) frame(digest, Objects.requireNonNull(plan.get(field)).toString());
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void frame(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private void materialize(String planJson, long worldInstanceId, long privateGameInstanceKey) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        status ->
            dsl.fetch(
                "SELECT * FROM world_materialize_run_owned_fixed_preset(?, ?, ?, ?)",
                planJson,
                CONTENT_DIGEST,
                worldInstanceId,
                privateGameInstanceKey));
  }

  private long insertLegacyWorldRow(long tenantKey, long privateKey, long versionKey) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                    + "control_plane_request_id, launch_descriptor_id, version_id, "
                    + "runtime_flags_json, generation_config_revision, release_bundle_id, "
                    + "published_release_bundle_ref, version_state_epoch, status) "
                    + "VALUES (?, ?, 71, 'legacy-fixed-preset-test', 'legacy-descriptor', ?, '{}', "
                    + "'legacy-config', 97, 'legacy-release', 5, 'LEGACY_FIXTURE') RETURNING id",
                tenantKey,
                privateKey,
                versionKey),
            "legacy World fixture row was not inserted")
        .get("id", Long.class);
  }

  private long resourceCount() {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT (SELECT count(*) FROM world_instance) "
                    + "+ (SELECT count(*) FROM region) + (SELECT count(*) FROM zone) "
                    + "+ (SELECT count(*) FROM room) + (SELECT count(*) FROM room_exit) "
                    + "+ (SELECT count(*) FROM world_authored_topology_identity) "
                    + "+ (SELECT count(*) FROM region_instance) + (SELECT count(*) FROM zone_instance) "
                    + "+ (SELECT count(*) FROM room_instance) + (SELECT count(*) FROM room_instance_exit) "
                    + "+ (SELECT count(*) FROM world_canonical_instance_topology_identity) "
                    + "+ (SELECT count(*) FROM world_canonical_instance_preparation)"),
            "resource count query returned no row")
        .get(0, Long.class);
  }

  private static Map<String, Object> validClosedPlan(
      UUID canonicalGameInstanceId, long tenantKey, long versionKey) {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("canonicalRealmId", UUID.randomUUID());
    plan.put("canonicalTenantId", UUID.randomUUID());
    plan.put("canonicalVersionId", UUID.randomUUID());
    plan.put("catalogRevision", 1L);
    plan.put("claimDigest", "a".repeat(64));
    plan.put("descriptorRequestDigest", VALID_DIGEST);
    plan.put("descriptorResultDigest", VALID_DIGEST);
    plan.put("descriptorRuntimeFlagsJson", "{}");
    plan.put("expectedContentDigest", CONTENT_DIGEST);
    plan.put("fixtureOperationId", UUID.randomUUID());
    plan.put("fixturePolicyBytesDigest", VALID_DIGEST);
    plan.put("fixturePolicyEvidenceDigest", VALID_DIGEST);
    plan.put("fixturePolicyRequestDigest", VALID_DIGEST);
    plan.put("gameDesignVersionId", 1L);
    plan.put("gameSessionCanonicalTenantId", UUID.randomUUID());
    plan.put("gameSessionCanonicalVersionId", UUID.randomUUID());
    plan.put("gameSessionGameInstanceId", 1L);
    plan.put("gameSessionGameInstanceUuid", canonicalGameInstanceId);
    plan.put("gameSessionResponseBytesDigest", VALID_DIGEST);
    plan.put("gameSessionTenantId", 1L);
    plan.put("gameTemplateId", 1L);
    plan.put("generationConfigRevision", "fixture-test");
    plan.put("launchDescriptorId", "fixture-test");
    plan.put("launchGameDesignVersionId", 1L);
    plan.put("lifecycleBindingDigest", VALID_DIGEST);
    plan.put("manifestHash", "fixture-test");
    plan.put("playableStateNamespaceId", UUID.randomUUID());
    plan.put("playableStateScope", "SHARED");
    plan.put("presetId", "firemud-demo-topology-v1");
    plan.put("presetSealDigest", VALID_DIGEST);
    plan.put("publicProduction", false);
    plan.put("publishedReleaseBundleRef", "fixture-test");
    plan.put("releaseBundleId", 1L);
    plan.put("schemaVersion", 1);
    plan.put("sourceEvidenceDigest", VALID_DIGEST);
    plan.put("sourceOperationId", UUID.randomUUID());
    plan.put("sourceReceiptDigest", VALID_DIGEST);
    plan.put("targetNamespace", "firemud");
    plan.put("topology", topology());
    plan.put("versionEvidenceDigest", VALID_DIGEST);
    plan.put("versionStateEpoch", 1L);
    plan.put("worldLocalTenantKey", tenantKey);
    plan.put("worldLocalVersionKey", versionKey);
    plan.put("worldSlug", "fixture-test");
    plan.put("worldSourceIntakeOperationId", UUID.randomUUID());
    plan.put("worldVersionIdentityOperationId", UUID.randomUUID());
    plan.put("presetSealDigest", framedSeal(plan));
    return plan;
  }

  private static List<Map<String, Object>> topology() {
    return List.of(
        Map.of(
            "family", "REGION",
            "templateId", "9affa11b-02f5-3cc9-a50e-6760083c71e9",
            "name", "Demo Region",
            "spacingMultiplier", 1.0,
            "generationSeed", 0),
        Map.of(
            "family", "ZONE",
            "templateId", "936c4d65-c460-3b73-9f4e-6ee6295bca24",
            "parentRegionTemplateId", "9affa11b-02f5-3cc9-a50e-6760083c71e9",
            "name", "Demo Zone"),
        Map.of(
            "family", "ROOM",
            "templateId", "c6058518-f9e0-3f26-9b05-2bc786095089",
            "parentZoneTemplateId", "936c4d65-c460-3b73-9f4e-6ee6295bca24",
            "name", "Candle-lit Antechamber",
            "description",
                "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north."),
        Map.of(
            "family", "ROOM",
            "templateId", "e74cca03-3b53-3edc-84f5-890651f5fea8",
            "parentZoneTemplateId", "936c4d65-c460-3b73-9f4e-6ee6295bca24",
            "name", "Smith's Annex",
            "description",
                "An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber."),
        Map.of(
            "family", "ROOM_EXIT",
            "templateId", "18c4b7a2-6498-3865-a464-814b56e4d4c2",
            "fromRoomTemplateId", "c6058518-f9e0-3f26-9b05-2bc786095089",
            "toRoomTemplateId", "e74cca03-3b53-3edc-84f5-890651f5fea8",
            "direction", "NORTH",
            "cost", 1),
        Map.of(
            "family", "ROOM_EXIT",
            "templateId", "ab46ef8f-e74f-3197-be62-9785ef63242e",
            "fromRoomTemplateId", "e74cca03-3b53-3edc-84f5-890651f5fea8",
            "toRoomTemplateId", "c6058518-f9e0-3f26-9b05-2bc786095089",
            "direction", "SOUTH",
            "cost", 1));
  }

  private String json(Map<String, Object> value) {
    return objectMapper.writeValueAsString(value);
  }

  private static long positiveLong() {
    return Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  }

  private static void assertSqlState(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) {
    assertSqlState(action, state, null);
  }

  private static void assertSqlState(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state, String message) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              Throwable cause = failure;
              while (cause.getCause() != null) {
                cause = cause.getCause();
              }
              assertThat(cause).isInstanceOf(PSQLException.class);
              PSQLException postgresFailure = (PSQLException) cause;
              assertThat(postgresFailure.getSQLState()).isEqualTo(state);
              if (message != null) {
                assertThat(postgresFailure.getMessage()).contains(message);
              }
            });
  }

  private RunOwnedWorldLaunchEvidence controlledEvidence() {
    String claimDigest =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    String operationId =
        RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
                claimDigest, "fixture-run", "firemud-fixture-run", "a".repeat(64), "b".repeat(64))
            .toString();
    String releaseRef = UUID.randomUUID().toString();
    UUID registrationId = UUID.fromString(operationId);
    UUID sourceOperationId = UUID.randomUUID();
    UUID canonicalTenantId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "dev", registrationId, canonicalTenantId, "fixture-tenant", "demo", "Demo World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "dev",
            operationId,
            canonicalTenantId,
            "demo",
            sourceOperationId,
            sourceEvidenceDigest,
            17L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "launch-descriptor-fixture-11",
            11L,
            false,
            null,
            "{}",
            "genrev-11",
            77L,
            77L,
            releaseRef,
            false,
            null);
    String manifestHash = "sha256:" + "c".repeat(64);
    var bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(77L)
            .setVersionId(11L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v1")
            .setCanonicalTenantId(canonicalTenantId.toString())
            .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
            .setPublishedReleaseBundleRef(releaseRef)
            .setGenerationConfigRevision("genrev-11")
            .setManifestHash(manifestHash)
            .build();
    var artifact =
        VersionAssetArtifactState.newBuilder()
            .setTenantId("42")
            .setVersionId(11L)
            .setStateEpoch(2L)
            .setExportedVersionNumber(1)
            .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
            .setManifestHash(manifestHash)
            .build();
    var version =
        VersionStateSnapshot.newBuilder()
            .setTenantId("42")
            .setVersionId(11L)
            .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
            .setVersionStateEpoch(77L)
            .setUpdatedAt("2026-10-11T10:00:00Z")
            .build();
    RunOwnedInitialAdmissionFixtureWireCodec.Capability capability =
        new RunOwnedInitialAdmissionFixtureWireCodec.Capability(
            RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            42L,
            17L,
            9L,
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "a".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "b".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            "c".repeat(64),
            claimDigest);
    // Controlled constructor data remains untrusted; these commitments do not prove owner writes.
    UUID creationRequestId = UUID.fromString("1b3fdbda-a9f6-4cf5-b21e-b43f0e855f65");
    UUID creationOperationId = UUID.fromString("31e36104-3336-4cf5-899a-cb419f2f361e");
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "dev", creationRequestId, "42", "Fixture Game", null);
    var creation =
        new FreshTenantCreationEvidence(
            1,
            "dev",
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            canonicalTenantId,
            29L,
            "42",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "dev",
                creationRequestId,
                creationOperationId,
                creationRequestDigest,
                canonicalTenantId,
                29L,
                "42",
                "NEW_GAME_ROW"));
    var launchRead =
        new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
            RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
            17L,
            bundle,
            artifact,
            version,
            net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec
                .toLaunchDescriptorMessage(descriptor));
    String policyRequestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability, creation, source, launchRead);
    var policySeal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            UUID.fromString(operationId),
            policyRequestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                UUID.fromString(operationId), policyRequestDigest));
    var receipt =
        new RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "a".repeat(64),
            "b".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            "c".repeat(64),
            source,
            launchRead,
            creation,
            policySeal);
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            UUID.randomUUID(), "dev", UUID.fromString(operationId), policySeal.evidenceDigest());
    var gs =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
            gsRequest,
            42L,
            positiveLong(),
            canonicalTenantId,
            UUID.randomUUID(),
            UUID.fromString(bundle.getCanonicalVersionId()),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
            0L,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
                capability, creation, source, launchRead),
            policySeal);
    byte[] capabilityBytes = objectMapper.writeValueAsBytes(capability);
    Map<String, Object> receiptWire = new LinkedHashMap<>();
    receiptWire.put("schema", receipt.schema());
    receiptWire.put("runId", receipt.runId());
    receiptWire.put("composeProjectName", receipt.composeProjectName());
    receiptWire.put("fixtureOperationId", receipt.fixtureOperationId());
    receiptWire.put("ownerService", receipt.ownerService());
    receiptWire.put("gameSessionUriSan", receipt.gameSessionUriSan());
    receiptWire.put("gameSessionLeafSha256", receipt.gameSessionLeafSha256());
    receiptWire.put("caCertificateSha256", receipt.caCertificateSha256());
    receiptWire.put("worldManagementUriSan", receipt.worldManagementUriSan());
    receiptWire.put("worldManagementLeafSha256", receipt.worldManagementLeafSha256());
    receiptWire.put("sourceEvidence", source);
    receiptWire.put("creationEvidence", creation);
    receiptWire.put("fixturePolicySeal", policySeal);
    receiptWire.put(
        "launchReadEvidence",
        Map.of(
            "schema",
            launchRead.schema(),
            "gameTemplateId",
            launchRead.gameTemplateId(),
            "publishedReleaseBundleBase64",
            Base64.getEncoder().encodeToString(bundle.toByteArray()),
            "versionAssetArtifactStateBase64",
            Base64.getEncoder().encodeToString(artifact.toByteArray()),
            "versionStateBase64",
            Base64.getEncoder().encodeToString(version.toByteArray()),
            "launchDescriptorBase64",
            Base64.getEncoder().encodeToString(launchRead.launchDescriptor().toByteArray())));
    byte[] receiptBytes = objectMapper.writeValueAsBytes(receiptWire);
    assertThat(RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(capabilityBytes))
        .isEqualTo(capability);
    assertThat(
            RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(receiptBytes, capability))
        .isEqualTo(receipt);
    return new RunOwnedWorldLaunchEvidence(
        capability,
        receipt,
        descriptor,
        capabilityBytes,
        receiptBytes,
        "a".repeat(64),
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
        "c".repeat(64),
        "b".repeat(64),
        gs);
  }
}
