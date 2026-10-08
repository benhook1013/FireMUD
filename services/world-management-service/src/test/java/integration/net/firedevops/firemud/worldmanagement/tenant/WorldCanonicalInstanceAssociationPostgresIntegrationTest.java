package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
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
 * Focused PostgreSQL proof for guarded, immutable, non-authorizing canonical materialization and
 * World canonical-instance association.
 *
 * <p>Upstream Game Design and Game Session results, Account ordering, and held preparation
 * authority remain explicitly isolated fixture evidence. World source intake, Version identity,
 * APPLIED graph, frozen selector, PUBLISHED terminal storage, guarded runtime materialization,
 * association, migration, constraints, and independent committed readback use actual migrated
 * PostgreSQL repositories and tables. This fixture does not claim authenticated producer/delivery
 * evidence, Account commit authority, lifecycle transition success, actor authority, or gameplay
 * admission.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldCanonicalInstanceAssociationPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long VERSION_EPOCH = 7L;

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
  @Autowired private WorldDesignPublicationFenceRepository publicationFence;
  @Autowired private WorldAuthoredGraphSnapshotRepository graphSnapshots;

  @Autowired
  private net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService
      digestService;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  private WorldAuthoredSourceIntakeRepository sourceRepository;
  private WorldCompleteLaunchBindingRepository launchBindingRepository;
  private WorldAuthoredVersionIdentityRepository versionIdentityRepository;
  private WorldCanonicalInstanceAssociationRepository associationRepository;
  private final Map<UUID, WorldCanonicalInstanceTopologyPlan> frozenPlans =
      new java.util.HashMap<>();

  @BeforeEach
  void createRepositories() {
    sourceRepository = new WorldAuthoredSourceIntakeRepository(dsl);
    launchBindingRepository = new WorldCompleteLaunchBindingRepository(dsl);
    versionIdentityRepository = new WorldAuthoredVersionIdentityRepository(dsl);
    associationRepository =
        new WorldCanonicalInstanceAssociationRepository(
            dsl, launchBindingRepository, sourceRepository, versionIdentityRepository);
  }

  @Test
  void changedReleaseCommitFailsJavaAndDatabaseBeforeAllocation() {
    assertReleaseCheckpointSubstitutionDenied(true);
  }

  @Test
  void changedWorldContentDigestFailsJavaAndDatabaseBeforeAllocation() {
    assertReleaseCheckpointSubstitutionDenied(false);
  }

  private void assertReleaseCheckpointSubstitutionDenied(boolean changeCommit) {
    Fixture original = fixture();
    var topology = frozenPlan(original);
    var checkpoint = topology.sourceBinding().freeze();
    var changedCheckpoint =
        new CaptureRequest(
            checkpoint.targetNamespace(),
            checkpoint.canonicalTenantId(),
            checkpoint.canonicalVersionId(),
            checkpoint.intakeRequestId(),
            checkpoint.publicationFence(),
            checkpoint.publicationRequestId(),
            checkpoint.requestDigest(),
            checkpoint.versionStateEpoch(),
            checkpoint.publishWorkflowId(),
            changeCommit ? UUID.randomUUID().toString() : checkpoint.appliedCommitId(),
            changeCommit
                ? checkpoint.contentDigest()
                : (checkpoint.contentDigest().startsWith("0") ? "1" : "0")
                    + checkpoint.contentDigest().substring(1),
            checkpoint.digestSchemaVersion(),
            checkpoint.suppliedOwnedAffectedTuples());
    var descriptorEvidence =
        completeEvidence(
            original.source().source(),
            controlRequest(),
            original.versionIdentity().gameDesignVersionId(),
            original.versionIdentity().canonicalVersionId(),
            VERSION_EPOCH,
            "changed-checkpoint-descriptor",
            changedCheckpoint.appliedCommitId());
    var evidence =
        new CompleteLaunchBindingEvidence(
            descriptorEvidence.descriptor(),
            releaseWithWorldCheckpoint(descriptorEvidence.releaseAttestation(), changedCheckpoint));
    // This is a complete, digest-valid five-owner pair for the same Version. Pair retention alone
    // must not make its different commit or World content eligible for local materialization.
    var changedBinding =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status ->
                        launchBindingRepository.acceptFresh(
                            NAMESPACE, original.source().receipt(), evidence)));
    UUID instance = UUID.randomUUID();
    UUID playableNamespace = UUID.randomUUID();
    var request = requestFor(evidence, instance, UUID.randomUUID());
    var response =
        responseFor(
            evidence, instance, playableNamespace, request.readRequestId(), "GS_FIXTURE", 1L);
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstancePreparation.Input(
                    request, response, changedBinding, original.versionIdentity(), topology))
        .isInstanceOf(IllegalArgumentException.class);

    // Deliberately bypass the Java constructor to exercise the database owner's independent gate.
    var rawInput = mock(WorldCanonicalInstancePreparation.Input.class);
    when(rawInput.gameSessionReadRequest()).thenReturn(request);
    when(rawInput.gameSessionReadEvidence()).thenReturn(response);
    when(rawInput.completeLaunchBinding()).thenReturn(changedBinding);
    when(rawInput.versionIdentity()).thenReturn(original.versionIdentity());
    when(rawInput.topologyPlan()).thenReturn(topology);
    String inputJson = WorldCanonicalInstancePreparationRepository.inputJson(rawInput);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status ->
                            dsl.fetch(
                                "SELECT * FROM world_prepare_canonical_instance(?, 'sha256:' || encode(sha256(convert_to(?, 'UTF8')), 'hex'))",
                                inputJson,
                                inputJson)))
        .rootCause()
        .hasMessageContaining("release differs from the exact selected frozen World graph");
    assertThat(preparationCount(instance)).isZero();
    assertThat(associationCount(instance)).isZero();
    assertThat(
            dsl.fetchOne(
                "SELECT id FROM world_instance WHERE canonical_game_instance_id = ?", instance))
        .isNull();
    assertThat(
            dsl.fetchOne(
                "SELECT id FROM world_instance WHERE canonical_launch_binding_operation_id = ?",
                changedBinding.operationId()))
        .isNull();
    assertThat(
            launchBindingRepository
                .read(
                    NAMESPACE,
                    changedBinding.canonicalTenantId(),
                    changedBinding.controlPlaneRequestId())
                .orElseThrow()
                .evidence())
        .isEqualTo(evidence);
  }

  @Test
  void retainsFullCanonicalAssociationAndReadsItAfterTheOwnerCommit() {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    WorldCanonicalInstancePreparation.Result preparation =
        prepareCanonicalWorldRow(
            fixture, canonicalGameInstanceId, playableStateNamespaceId, "GS_FIXTURE", 1L);
    long worldInstanceId = preparation.association().worldInstanceId();
    assertThat(preparation.storageStatus()).isEqualTo("MATERIALIZED_UNVERIFIED");
    assertThat(preparation.regionCount()).isEqualTo(1);
    assertThat(preparation.zoneCount()).isEqualTo(1);
    assertThat(preparation.roomCount()).isEqualTo(2);
    assertThat(preparation.exitCount()).isEqualTo(1);
    WorldCanonicalInstanceAssociation.Claim claim =
        claim(
            fixture,
            canonicalGameInstanceId,
            playableStateNamespaceId,
            worldInstanceId,
            1L,
            "GS_FIXTURE");

    ownerTransaction()
        .executeWithoutResult(status -> associationRepository.retainClaimInOwnerTransaction(claim));

    assertThat(associationCount(canonicalGameInstanceId)).isEqualTo(1L);
    WorldCanonicalInstanceAssociation stored =
        associationRepository.readOwnerAssociation(canonicalGameInstanceId).orElseThrow();
    assertThat(stored.identity()).isEqualTo(claim.identity());
    assertThat(stored.worldInstanceId()).isEqualTo(worldInstanceId);
    assertThat(stored.canonicalVersionId())
        .isEqualTo(fixture.binding().evidence().releaseAttestation().canonicalVersionId());
    assertThat(stored.launchBindingOperationId()).isEqualTo(fixture.binding().operationId());
    assertThat(stored.versionIdentityOperationId())
        .isEqualTo(fixture.versionIdentity().operationId());
    assertThat(stored.completeLaunchBinding()).isEqualTo(fixture.binding());
    assertThat(stored.versionIdentity()).isEqualTo(fixture.versionIdentity());
    assertThat(stored.worldPrepareFields().privateTenantKey())
        .isEqualTo(fixture.source().receipt().localTenantKey());
    assertThat(stored.worldPrepareFields().privateGameInstanceKey()).isPositive();
    assertThat(stored.worldPrepareFields().localVersionKey())
        .isEqualTo(fixture.versionIdentity().localVersionKey());
    assertThat(stored.worldPrepareFields().launchDescriptorId())
        .isEqualTo(fixture.binding().descriptor().launchDescriptorId());

    Record row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT canonical_game_instance_id, canonical_target_namespace, canonical_tenant_id, "
                    + "canonical_world_slug, playable_state_namespace_id, playable_state_scope, "
                    + "public_production, "
                    + "playtest_lifecycle_id, playtest_state_generation, control_plane_request_id, "
                    + "world_instance_id, local_tenant_key, local_version_key, "
                    + "canonical_launch_binding_operation_id, version_identity_operation_id, "
                    + "source_operation_id, source_evidence_digest, descriptor_request_digest, "
                    + "descriptor_result_digest, release_attestation_digest "
                    + "FROM world_canonical_instance_association WHERE canonical_game_instance_id = ?",
                canonicalGameInstanceId),
            "canonical instance association row missing after committed readback");
    assertThat(row.get("canonical_game_instance_id", UUID.class))
        .isEqualTo(canonicalGameInstanceId);
    assertThat(row.get("canonical_target_namespace", String.class)).isEqualTo(NAMESPACE);
    assertThat(row.get("canonical_tenant_id", UUID.class))
        .isEqualTo(fixture.source().source().canonicalTenantId());
    assertThat(row.get("canonical_world_slug", String.class))
        .isEqualTo(fixture.source().source().worldSlug());
    assertThat(row.get("playable_state_namespace_id", UUID.class))
        .isEqualTo(playableStateNamespaceId);
    assertThat(row.get("playable_state_scope", String.class)).isEqualTo("SHARED");
    assertThat(row.get("public_production", Boolean.class)).isTrue();
    assertThat(row.get("playtest_lifecycle_id", UUID.class)).isNull();
    assertThat(row.get("playtest_state_generation", Long.class)).isNull();
    assertThat(row.get("world_instance_id", Long.class)).isEqualTo(worldInstanceId);
    assertThat(row.get("local_tenant_key", Long.class))
        .isEqualTo(fixture.source().receipt().localTenantKey());
    assertThat(row.get("local_version_key", Long.class))
        .isEqualTo(fixture.versionIdentity().localVersionKey());
    assertThat(row.get("canonical_launch_binding_operation_id", UUID.class))
        .isEqualTo(fixture.binding().operationId());
    assertThat(row.get("version_identity_operation_id", UUID.class))
        .isEqualTo(fixture.versionIdentity().operationId());
    assertThat(row.get("source_operation_id", UUID.class))
        .isEqualTo(fixture.source().receipt().sourceOperationId());
    assertThat(row.get("source_evidence_digest", String.class))
        .isEqualTo(fixture.source().receipt().sourceEvidenceDigest());
    assertThat(row.get("descriptor_request_digest", String.class))
        .isEqualTo(fixture.binding().descriptor().requestDigest());
    assertThat(row.get("descriptor_result_digest", String.class))
        .isEqualTo(fixture.binding().descriptor().resultDigest());
    assertThat(row.get("release_attestation_digest", String.class))
        .isEqualTo(fixture.binding().evidence().releaseAttestation().evidenceDigest());
    Record worldRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT public_production FROM world_instance WHERE id = ?", worldInstanceId),
            "canonical World instance row missing after committed readback");
    assertThat(worldRow.get("public_production", Boolean.class)).isTrue();
    long privateGameInstanceKey =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT game_instance_id FROM world_instance WHERE id = ?", worldInstanceId),
                "canonical World row missing private instance key")
            .get("game_instance_id", Long.class);
    Record region =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, canonical_region_instance_id, name, weather, generation_seed "
                    + "FROM region_instance WHERE tenant_id = ? AND game_instance_id = ?",
                fixture.source().receipt().localTenantKey(),
                privateGameInstanceKey),
            "canonical runtime region missing");
    Record zone =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, canonical_zone_instance_id, template_zone_id, region_instance_id, name "
                    + "FROM zone_instance WHERE tenant_id = ? AND game_instance_id = ?",
                fixture.source().receipt().localTenantKey(),
                privateGameInstanceKey),
            "canonical runtime zone missing");
    Record room =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT ri.id, ri.room_instance_row_id, ri.template_room_id, ri.region_instance_id, "
                    + "ri.zone_instance_id, ri.name, ri.description "
                    + "FROM room_instance ri WHERE ri.tenant_id = ? AND ri.game_instance_id = ? "
                    + "AND ri.name = 'synthetic room'",
                fixture.source().receipt().localTenantKey(),
                privateGameInstanceKey),
            "canonical runtime room missing");
    assertThat(region.get("canonical_region_instance_id", UUID.class)).isNotNull();
    assertThat(zone.get("canonical_zone_instance_id", UUID.class)).isNotNull();
    assertThat(region.get("name", String.class)).isEqualTo("synthetic region");
    assertThat(region.get("weather", String.class)).isEqualTo("rain");
    assertThat(region.get("generation_seed", Long.class)).isEqualTo(9001L);
    assertThat(zone.get("name", String.class)).isEqualTo("synthetic zone");
    assertThat(zone.get("region_instance_id", Long.class)).isEqualTo(region.get("id", Long.class));
    assertThat(room.get("name", String.class)).isEqualTo("synthetic room");
    assertThat(room.get("description", String.class)).isEqualTo("synthetic materialization proof");
    assertThat(room.get("region_instance_id", Long.class)).isEqualTo(region.get("id", Long.class));
    assertThat(room.get("zone_instance_id", Long.class)).isEqualTo(zone.get("id", Long.class));
    List<Record> roomRows =
        dsl.fetch(
            "SELECT room_instance_row_id, template_room_id, region_instance_id, zone_instance_id, name "
                + "FROM room_instance WHERE tenant_id = ? AND game_instance_id = ? ORDER BY name",
            fixture.source().receipt().localTenantKey(),
            privateGameInstanceKey);
    assertThat(roomRows).hasSize(2);
    assertThat(roomRows)
        .allSatisfy(
            roomRow -> {
              assertThat(roomRow.get("region_instance_id", Long.class))
                  .isEqualTo(region.get("id", Long.class));
              assertThat(roomRow.get("zone_instance_id", Long.class))
                  .isEqualTo(zone.get("id", Long.class));
            });
    assertThat(roomRows)
        .extracting(roomRow -> roomRow.get("name", String.class))
        .containsExactly("synthetic destination", "synthetic room");
    Record exit =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, from_room_instance_record_id, to_room_instance_record_id, direction, cost "
                    + "FROM room_instance_exit WHERE tenant_id = ? AND game_instance_id = ?",
                fixture.source().receipt().localTenantKey(),
                privateGameInstanceKey),
            "canonical runtime room exit missing");
    assertThat(exit.get("from_room_instance_record_id", Long.class))
        .isEqualTo(room.get("room_instance_row_id", Long.class));
    assertThat(exit.get("to_room_instance_record_id", Long.class))
        .isNotEqualTo(exit.get("from_room_instance_record_id", Long.class));
    assertThat(exit.get("direction", String.class)).isEqualTo("EAST");
    assertThat(exit.get("cost", Integer.class)).isEqualTo(3);
    var frozenPlan = frozenPlans.get(fixture.versionIdentity().operationId());
    assertThat(region.get("canonical_region_instance_id", UUID.class))
        .isNotEqualTo(frozenPlan.regions().getFirst().identity().templateId());
    assertThat(zone.get("canonical_zone_instance_id", UUID.class))
        .isNotEqualTo(frozenPlan.zones().getFirst().identity().templateId());
    Record regionIdentity =
        topologyIdentity(
            worldInstanceId, "REGION", frozenPlan.regions().getFirst().identity().templateId());
    Record zoneIdentity =
        topologyIdentity(
            worldInstanceId, "ZONE", frozenPlan.zones().getFirst().identity().templateId());
    Record roomIdentity =
        topologyIdentity(
            worldInstanceId,
            "ROOM",
            frozenPlan.rooms().stream()
                .filter(item -> item.content().getName().equals("synthetic room"))
                .findFirst()
                .orElseThrow()
                .identity()
                .templateId());
    Record exitIdentity =
        topologyIdentity(
            worldInstanceId,
            "ROOM_EXIT",
            frozenPlan.roomExits().getFirst().identity().templateId());
    assertThat(regionIdentity.get("runtime_row_id", Long.class))
        .isEqualTo(region.get("id", Long.class));
    assertThat(regionIdentity.get("runtime_identity", UUID.class))
        .isEqualTo(region.get("canonical_region_instance_id", UUID.class));
    assertThat(zoneIdentity.get("runtime_row_id", Long.class))
        .isEqualTo(zone.get("id", Long.class));
    assertThat(zoneIdentity.get("runtime_identity", UUID.class))
        .isEqualTo(zone.get("canonical_zone_instance_id", UUID.class));
    assertThat(roomIdentity.get("runtime_room_instance_id", Long.class))
        .isEqualTo(room.get("room_instance_row_id", Long.class));
    assertThat(roomIdentity.get("template_private_row_key", Long.class))
        .isEqualTo(room.get("template_room_id", Long.class));
    assertThat(zoneIdentity.get("template_private_row_key", Long.class))
        .isEqualTo(zone.get("template_zone_id", Long.class));
    assertThat(exitIdentity.get("runtime_row_id", Long.class))
        .isEqualTo(exit.get("id", Long.class));
    Record mappedFamilies =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) AS row_count, COUNT(DISTINCT family) AS family_count "
                    + "FROM world_canonical_instance_topology_identity WHERE world_instance_id = ?",
                worldInstanceId),
            "canonical source/runtime association rows missing");
    assertThat(mappedFamilies.get("row_count", Long.class)).isEqualTo(5L);
    assertThat(mappedFamilies.get("family_count", Long.class)).isEqualTo(4L);
  }

  @Test
  void exactRetryReusesOriginalAssociationAfterReadMetadataChangeWithoutLifecycleMutation() {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    WorldCanonicalInstancePreparation.Result originalPreparation =
        prepareCanonicalWorldRow(
            fixture, canonicalGameInstanceId, playableStateNamespaceId, "GS_FIXTURE", 1L);
    long worldInstanceId = originalPreparation.association().worldInstanceId();
    WorldCanonicalInstanceAssociation.Claim originalClaim =
        claim(
            fixture,
            canonicalGameInstanceId,
            playableStateNamespaceId,
            worldInstanceId,
            1L,
            "GS_FIXTURE");
    ownerTransaction()
        .executeWithoutResult(
            status -> associationRepository.retainClaimInOwnerTransaction(originalClaim));
    WorldCanonicalInstanceAssociation original =
        associationRepository.readOwnerAssociation(canonicalGameInstanceId).orElseThrow();

    // This is only a fresh, synthetic Game Session observation/read correlation. No World
    // lifecycle owner operation is forged; the current guarded lifecycle transition remains a
    // separate unimplemented execution path.
    WorldCanonicalInstancePreparation.Result changedObservation =
        prepareCanonicalWorldRow(
            fixture, canonicalGameInstanceId, playableStateNamespaceId, "GS_RETRIED_FIXTURE", 2L);
    assertThat(changedObservation.association()).isEqualTo(original);
    assertThat(changedObservation.inputDigest()).isEqualTo(originalPreparation.inputDigest());
    WorldCanonicalInstanceAssociation.Claim retry =
        claim(
            fixture,
            canonicalGameInstanceId,
            playableStateNamespaceId,
            worldInstanceId,
            2L,
            "GS_RETRIED_FIXTURE");
    ownerTransaction()
        .executeWithoutResult(status -> associationRepository.retainClaimInOwnerTransaction(retry));

    assertThat(associationRepository.readOwnerAssociation(canonicalGameInstanceId))
        .contains(original);
    assertThat(associationCount(canonicalGameInstanceId)).isEqualTo(1L);
    Record runtime =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT status, lifecycle_epoch, row_version FROM world_instance WHERE id = ?",
                worldInstanceId),
            "World instance row missing after exact retry");
    assertThat(runtime.get("status", String.class)).isEqualTo("PREPARING");
    assertThat(runtime.get("lifecycle_epoch", Long.class)).isEqualTo(1L);
    assertThat(runtime.get("row_version", Long.class)).isZero();
    assertThat(retry.gameSessionRead().readRequestId())
        .isNotEqualTo(originalClaim.gameSessionRead().readRequestId());
    assertThat(retry.gameSessionRead().currentGameSessionStatus()).isEqualTo("GS_RETRIED_FIXTURE");
  }

  @Test
  void rejectsChangedRequestEchoDescriptorAttestationAndPlayableScopeWithoutMutation() {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    long worldInstanceId =
        insertCanonicalWorldRow(fixture, canonicalGameInstanceId, playableStateNamespaceId);
    WorldCanonicalInstanceAssociation.Claim valid =
        claim(
            fixture,
            canonicalGameInstanceId,
            playableStateNamespaceId,
            worldInstanceId,
            1L,
            "GS_FIXTURE");
    ownerTransaction()
        .executeWithoutResult(status -> associationRepository.retainClaimInOwnerTransaction(valid));
    WorldCanonicalInstanceAssociation originalStored =
        associationRepository.readOwnerAssociation(canonicalGameInstanceId).orElseThrow();

    var originalRequest = valid.gameSessionRequest();
    var response = valid.gameSessionRead();
    var changedRequest =
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            originalRequest.readRequestId(),
            originalRequest.targetNamespace(),
            originalRequest.canonicalTenantId(),
            originalRequest.worldSlug(),
            originalRequest.canonicalGameInstanceId(),
            originalRequest.controlPlaneRequestId(),
            "substituted-descriptor",
            originalRequest.expectedDescriptorRequestDigest(),
            originalRequest.expectedDescriptorResultDigest(),
            originalRequest.expectedReleaseAttestationEvidenceDigest());
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.Claim(
                    changedRequest,
                    response,
                    worldInstanceId,
                    fixture.binding(),
                    fixture.versionIdentity()))
        .isInstanceOf(IllegalArgumentException.class);

    CompleteLaunchBindingEvidence changedDescriptor =
        completeEvidence(
            fixture.source().source(),
            fixture.binding().controlPlaneRequestId(),
            fixture.versionIdentity().gameDesignVersionId(),
            fixture.versionIdentity().canonicalVersionId(),
            VERSION_EPOCH,
            "substituted-descriptor",
            fixture.binding().evidence().releaseAttestation().commitId(),
            Objects.requireNonNull(
                fixture.binding().evidence().releaseAttestation().worldStartLocationEvidence()));
    UUID changedDescriptorReadId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.Claim(
                    requestFor(changedDescriptor, canonicalGameInstanceId, changedDescriptorReadId),
                    responseFor(
                        changedDescriptor,
                        canonicalGameInstanceId,
                        playableStateNamespaceId,
                        changedDescriptorReadId,
                        "GS_FIXTURE",
                        1L),
                    worldInstanceId,
                    fixture.binding(),
                    fixture.versionIdentity()))
        .isInstanceOf(IllegalArgumentException.class);

    AuthoredWorldReleaseAttestationEvidence changedAttestation =
        attestation(
            fixture.binding().descriptor(),
            fixture.source().source(),
            fixture.versionIdentity().canonicalVersionId(),
            fixture.binding().evidence().releaseAttestation().commitId(),
            Objects.requireNonNull(
                fixture.binding().evidence().releaseAttestation().worldStartLocationEvidence()),
            'e');
    CompleteLaunchBindingEvidence changedPair =
        new CompleteLaunchBindingEvidence(fixture.binding().descriptor(), changedAttestation);
    UUID changedAttestationReadId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.Claim(
                    requestFor(changedPair, canonicalGameInstanceId, changedAttestationReadId),
                    responseFor(
                        changedPair,
                        canonicalGameInstanceId,
                        playableStateNamespaceId,
                        changedAttestationReadId,
                        "GS_FIXTURE",
                        1L),
                    worldInstanceId,
                    fixture.binding(),
                    fixture.versionIdentity()))
        .isInstanceOf(IllegalArgumentException.class);

    var changedScopeResponse =
        new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
            response.readRequestId(),
            response.targetNamespace(),
            response.canonicalTenantId(),
            response.worldSlug(),
            response.canonicalGameInstanceId(),
            response.controlPlaneRequestId(),
            response.launchDescriptorId(),
            response.descriptorRequestDigest(),
            response.descriptorResultDigest(),
            response.releaseAttestationEvidenceDigest(),
            UUID.randomUUID(),
            "SHARED",
            true,
            response.currentGameSessionStatus(),
            response.currentGameSessionRowVersion(),
            response.descriptor(),
            response.releaseAttestation());
    var changedScopeClaim =
        new WorldCanonicalInstanceAssociation.Claim(
            valid.gameSessionRequest(),
            changedScopeResponse,
            worldInstanceId,
            fixture.binding(),
            fixture.versionIdentity());
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
                    response.readRequestId(),
                    response.targetNamespace(),
                    response.canonicalTenantId(),
                    response.worldSlug(),
                    response.canonicalGameInstanceId(),
                    response.controlPlaneRequestId(),
                    response.launchDescriptorId(),
                    response.descriptorRequestDigest(),
                    response.descriptorResultDigest(),
                    response.releaseAttestationEvidenceDigest(),
                    response.playableStateNamespaceId(),
                    "ISOLATED",
                    true,
                    response.currentGameSessionStatus(),
                    response.currentGameSessionRowVersion(),
                    response.descriptor(),
                    response.releaseAttestation()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status ->
                            associationRepository.retainClaimInOwnerTransaction(changedScopeClaim)))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class);

    assertThat(associationCount(canonicalGameInstanceId)).isEqualTo(1L);
    assertThat(associationRepository.readOwnerAssociation(canonicalGameInstanceId))
        .contains(originalStored);
  }

  @Test
  void rejectsMissingPublicProductionAndPrivatePlaytestClassificationBeforeMutation() {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    // Invalid public-production evidence is rejected before invoking the owner producer. A
    // canonical row cannot be fabricated here just to create an association-free fixture.
    UUID readRequestId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                responseFor(
                    fixture.binding().evidence(),
                    canonicalGameInstanceId,
                    playableStateNamespaceId,
                    readRequestId,
                    "GS_FIXTURE",
                    1L,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit public-production evidence");

    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
                    readRequestId,
                    NAMESPACE,
                    fixture.source().source().canonicalTenantId(),
                    fixture.source().source().worldSlug(),
                    canonicalGameInstanceId,
                    fixture.binding().controlPlaneRequestId(),
                    fixture.binding().descriptor().launchDescriptorId(),
                    fixture.binding().descriptor().requestDigest(),
                    fixture.binding().descriptor().resultDigest(),
                    fixture.binding().evidence().releaseAttestation().evidenceDigest(),
                    playableStateNamespaceId,
                    "ISOLATED",
                    true,
                    "GS_FIXTURE",
                    1L,
                    fixture.binding().descriptor(),
                    fixture.binding().evidence().releaseAttestation()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("accepts SHARED only");

    assertThat(associationCount(canonicalGameInstanceId)).isZero();
    assertThat(associationRepository.readOwnerAssociation(canonicalGameInstanceId)).isEmpty();
  }

  @Test
  void rejectsStructurallyValidPlanFromAnotherFrozenCaptureBeforeAllocation() {
    Fixture selected = fixture();
    Fixture substitutedSource = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    var selectedPair = new SeededPair(selected.binding(), selected.versionIdentity());
    var request = request(selectedPair, canonicalGameInstanceId, UUID.randomUUID());
    var response =
        responseFor(
            selected.binding().evidence(),
            canonicalGameInstanceId,
            playableStateNamespaceId,
            request.readRequestId(),
            "GS_FIXTURE",
            1L);
    WorldCanonicalInstanceTopologyPlan validOtherCapture = frozenPlan(substitutedSource);

    assertThatThrownBy(
            () ->
                new WorldCanonicalInstancePreparation.Input(
                    request,
                    response,
                    selected.binding(),
                    selected.versionIdentity(),
                    validOtherCapture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact V27 identity and frozen commit");

    assertThat(preparationCount(canonicalGameInstanceId)).isZero();
    assertThat(associationCount(canonicalGameInstanceId)).isZero();
    assertThat(
            dsl.fetchOne(
                "SELECT id FROM world_instance WHERE canonical_game_instance_id = ?",
                canonicalGameInstanceId))
        .isNull();
  }

  @Test
  void rejectsDifferentBindingTenantVersionAndPrivateWorldRowWithoutMutation() {
    Fixture original = fixture();
    UUID originalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    long originalWorldInstanceId =
        insertCanonicalWorldRow(original, originalGameInstanceId, playableStateNamespaceId);
    WorldCanonicalInstanceAssociation.Claim originalClaim =
        claim(
            original,
            originalGameInstanceId,
            playableStateNamespaceId,
            originalWorldInstanceId,
            1L,
            "GS_FIXTURE");
    ownerTransaction()
        .executeWithoutResult(
            status -> associationRepository.retainClaimInOwnerTransaction(originalClaim));

    SeededPair secondBinding =
        seedPair(
            original.source(),
            original.versionIdentity(),
            controlRequest(),
            "synthetic-second-binding");
    Fixture changedBindingFixture =
        new Fixture(original.source(), secondBinding.binding(), secondBinding.versionIdentity());
    assertThatThrownBy(
            () ->
                prepareCanonicalWorldRow(
                    changedBindingFixture,
                    originalGameInstanceId,
                    playableStateNamespaceId,
                    "GS_FIXTURE",
                    1L))
        .isInstanceOf(
            WorldCanonicalInstancePreparationRepository.ConflictingPreparationException.class);
    WorldCanonicalInstanceAssociation.Claim changedBindingClaim =
        claim(
            secondBinding,
            originalGameInstanceId,
            playableStateNamespaceId,
            originalWorldInstanceId,
            1L,
            "GS_FIXTURE");
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status ->
                            associationRepository.retainClaimInOwnerTransaction(
                                changedBindingClaim)))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class);

    SeededPair otherVersion =
        seedPair(
            original.source(),
            null,
            controlRequest(),
            "synthetic-version-descriptor",
            UUID.randomUUID(),
            positiveLong());
    UUID otherGameInstanceId = UUID.randomUUID();
    UUID otherPlayableNamespaceId = UUID.randomUUID();
    long otherWorldInstanceId =
        insertCanonicalWorldRow(
            otherVersion, original.source(), otherGameInstanceId, otherPlayableNamespaceId);
    assertThat(otherWorldInstanceId).isNotEqualTo(originalWorldInstanceId);
    assertThat(dsl.fetchOne("SELECT id FROM world_instance WHERE id = ?", otherWorldInstanceId))
        .isNotNull();
    UUID wrongVersionReadId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceAssociation.Claim(
                    request(otherVersion, otherGameInstanceId, wrongVersionReadId),
                    responseFor(
                        otherVersion.binding().evidence(),
                        otherGameInstanceId,
                        otherPlayableNamespaceId,
                        wrongVersionReadId,
                        "GS_FIXTURE",
                        1L),
                    otherWorldInstanceId,
                    otherVersion.binding(),
                    original.versionIdentity()))
        .isInstanceOf(IllegalArgumentException.class);

    WorldCanonicalInstanceAssociation.Claim wrongPrivateWorldRow =
        claim(
            original,
            originalGameInstanceId,
            playableStateNamespaceId,
            otherWorldInstanceId,
            1L,
            "GS_FIXTURE");
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status ->
                            associationRepository.retainClaimInOwnerTransaction(
                                wrongPrivateWorldRow)))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class);

    Fixture otherTenant = fixture();
    UUID otherTenantGameInstanceId = UUID.randomUUID();
    WorldCanonicalInstanceAssociation.Claim wrongTenant =
        claim(
            otherTenant,
            otherTenantGameInstanceId,
            UUID.randomUUID(),
            originalWorldInstanceId,
            1L,
            "GS_FIXTURE");
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status -> associationRepository.retainClaimInOwnerTransaction(wrongTenant)))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class);

    assertThat(associationCount(originalGameInstanceId)).isEqualTo(1L);
    assertThat(associationRepository.readOwnerAssociation(originalGameInstanceId)).isPresent();
    assertThat(associationRepository.readOwnerAssociation(otherGameInstanceId))
        .hasValueSatisfying(
            association ->
                assertThat(association.worldInstanceId()).isEqualTo(otherWorldInstanceId));
    assertThat(associationRepository.readOwnerAssociation(otherTenantGameInstanceId)).isEmpty();
  }

  @Test
  void deniesLegacyNumericRowAndRejectsUpdatingItToCanonicalIdentity() throws SQLException {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    long legacyGameInstanceKey = positiveLong();
    long legacyWorldInstanceId = insertLegacyWorldRow(fixture, legacyGameInstanceKey);
    WorldCanonicalInstanceAssociation.Claim legacyClaim =
        claim(
            fixture,
            canonicalGameInstanceId,
            playableStateNamespaceId,
            legacyWorldInstanceId,
            1L,
            "GS_FIXTURE");

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status -> associationRepository.retainClaimInOwnerTransaction(legacyClaim)))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException.class)
        .hasMessageContaining("retained numeric rows cannot be associated");
    assertThat(associationCount(canonicalGameInstanceId)).isZero();

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_instance SET public_production = TRUE, canonical_game_instance_id = ?, "
                        + "canonical_target_namespace = ?, canonical_tenant_id = ?, "
                        + "canonical_world_slug = ?, playable_state_namespace_id = ?, "
                        + "playable_state_scope = 'SHARED', canonical_launch_binding_operation_id = ? "
                        + "WHERE id = ?",
                    canonicalGameInstanceId,
                    NAMESPACE,
                    fixture.source().source().canonicalTenantId(),
                    fixture.source().source().worldSlug(),
                    playableStateNamespaceId,
                    fixture.binding().operationId(),
                    legacyWorldInstanceId))
        .isInstanceOf(DataAccessException.class);
    Record legacy =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT tenant_id, game_instance_id, version_id, public_production, canonical_game_instance_id, "
                    + "canonical_tenant_id FROM world_instance WHERE id = ?",
                legacyWorldInstanceId),
            "legacy World row missing after rejected association attempt");
    assertThat(legacy.get("tenant_id", Long.class))
        .isNotEqualTo(fixture.source().receipt().localTenantKey());
    assertThat(legacy.get("game_instance_id", Long.class)).isEqualTo(legacyGameInstanceKey);
    assertThat(legacy.get("version_id", Long.class))
        .isEqualTo(fixture.versionIdentity().localVersionKey());
    assertThat(legacy.get("public_production", Boolean.class)).isNull();
    assertThat(legacy.get("canonical_game_instance_id", UUID.class)).isNull();
    assertThat(legacy.get("canonical_tenant_id", UUID.class)).isNull();
    assertThat(associationRepository.readOwnerAssociation(canonicalGameInstanceId)).isEmpty();
  }

  @Test
  void rollsBackCompletePreparationWhenHeldCommitAuthorityIsLostBeforeCommit() {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    WorldCanonicalInstancePreparation.Input input =
        preparationInput(
            fixture, canonicalGameInstanceId, playableStateNamespaceId, "GS_FIXTURE", 1L);
    AtomicInteger heldChecks = new AtomicInteger();
    WorldCanonicalInstancePreparationService service =
        preparationService(
            () -> {
              if (heldChecks.incrementAndGet() == 3) {
                throw new IllegalStateException("synthetic held authority lost before commit");
              }
            });

    assertThatThrownBy(() -> service.prepare(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic held authority lost before commit");

    assertThat(associationCount(canonicalGameInstanceId)).isZero();
    assertThat(associationRepository.readOwnerAssociation(canonicalGameInstanceId)).isEmpty();
    assertThat(preparationCount(canonicalGameInstanceId)).isZero();
    assertThat(
            dsl.fetchOne(
                "SELECT id FROM world_instance WHERE canonical_game_instance_id = ?",
                canonicalGameInstanceId))
        .isNull();
  }

  @Test
  void concurrentExactPreparationsForTheSameCanonicalGameInstanceRetainOneOwnerResult()
      throws Exception {
    Fixture fixture = fixture();
    UUID canonicalGameInstanceId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    WorldCanonicalInstancePreparation.Input exactInput =
        preparationInput(
            fixture, canonicalGameInstanceId, playableStateNamespaceId, "GS_FIXTURE", 1L);
    WorldCanonicalInstancePreparationService firstService = preparationService(() -> {});
    WorldCanonicalInstancePreparationService secondService = preparationService(() -> {});
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldCanonicalInstancePreparation.Result> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return firstService.prepare(exactInput);
              });
      Future<WorldCanonicalInstancePreparation.Result> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return secondService.prepare(exactInput);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      WorldCanonicalInstancePreparation.Result firstResult = first.get(25, TimeUnit.SECONDS);
      WorldCanonicalInstancePreparation.Result secondResult = second.get(25, TimeUnit.SECONDS);
      assertThat(firstResult.association().worldInstanceId())
          .isEqualTo(secondResult.association().worldInstanceId());
      assertThat(firstResult.inputDigest()).isEqualTo(secondResult.inputDigest());
    }

    assertThat(associationCount(canonicalGameInstanceId)).isEqualTo(1L);
    assertThat(preparationCount(canonicalGameInstanceId)).isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM world_instance WHERE canonical_game_instance_id = ?",
                        canonicalGameInstanceId),
                    "Canonical World row count returned no result")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void forwardV32MigrationPreservesLegacyRowsWithoutInventingCanonicalMapping()
      throws SQLException {
    String schema = "world_canonical_instance_v32_" + UUID.randomUUID().toString().replace("-", "");
    long tenantKey =
        8_100_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L);
    long gameInstanceKey = positiveLong();
    long worldInstanceId;
    try {
      migrateFixture(schema, MigrationVersion.fromVersion("32"));
      try (Connection connection = fixtureConnection(schema);
          PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                      + "control_plane_request_id, launch_descriptor_id, version_id, "
                      + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
                      + "version_state_epoch, status) VALUES (?, ?, 71, 'retained-v32-request', "
                      + "'retained-v32-descriptor', 83, 'retained-v32-config', 97, "
                      + "'retained-v32-release', 5, 'LEGACY_FIXTURE') RETURNING id")) {
        insert.setLong(1, tenantKey);
        insert.setLong(2, gameInstanceKey);
        try (ResultSet result = insert.executeQuery()) {
          assertThat(result.next()).isTrue();
          worldInstanceId = result.getLong(1);
        }
      }

      migrateFixture(schema, null);

      try (Connection connection = fixtureConnection(schema)) {
        try (PreparedStatement query =
            connection.prepareStatement(
                "SELECT tenant_id, game_instance_id, version_id, status, public_production, "
                    + "canonical_game_instance_id, canonical_target_namespace, canonical_tenant_id, "
                    + "canonical_world_slug, playable_state_namespace_id, playable_state_scope, "
                    + "playtest_lifecycle_id, playtest_state_generation, "
                    + "canonical_launch_binding_operation_id FROM world_instance WHERE id = ?")) {
          query.setLong(1, worldInstanceId);
          try (ResultSet retained = query.executeQuery()) {
            assertThat(retained.next()).isTrue();
            assertThat(retained.getLong("tenant_id")).isEqualTo(tenantKey);
            assertThat(retained.getLong("game_instance_id")).isEqualTo(gameInstanceKey);
            assertThat(retained.getLong("version_id")).isEqualTo(83L);
            assertThat(retained.getString("status")).isEqualTo("LEGACY_FIXTURE");
            assertThat(retained.getObject("public_production")).isNull();
            assertThat(retained.getObject("canonical_game_instance_id")).isNull();
            assertThat(retained.getString("canonical_target_namespace")).isNull();
            assertThat(retained.getObject("canonical_tenant_id")).isNull();
            assertThat(retained.getString("canonical_world_slug")).isNull();
            assertThat(retained.getObject("playable_state_namespace_id")).isNull();
            assertThat(retained.getString("playable_state_scope")).isNull();
            assertThat(retained.getObject("playtest_lifecycle_id")).isNull();
            assertThat(retained.getObject("playtest_state_generation")).isNull();
            assertThat(retained.getObject("canonical_launch_binding_operation_id")).isNull();
            assertThat(retained.next()).isFalse();
          }
        }
        try (Statement statement = connection.createStatement();
            ResultSet associations =
                statement.executeQuery(
                    "SELECT COUNT(*) FROM world_canonical_instance_association")) {
          assertThat(associations.next()).isTrue();
          assertThat(associations.getLong(1)).isZero();
        }

        UUID attemptedGameInstanceId = UUID.randomUUID();
        UUID attemptedTenantId = UUID.randomUUID();
        UUID attemptedPlayableNamespaceId = UUID.randomUUID();
        UUID attemptedBindingId = UUID.randomUUID();
        assertThatThrownBy(
                () -> {
                  try (PreparedStatement update =
                      connection.prepareStatement(
                          "UPDATE world_instance SET public_production = TRUE, canonical_game_instance_id = ?, "
                              + "canonical_target_namespace = 'firemud', canonical_tenant_id = ?, "
                              + "canonical_world_slug = 'retained-v32-world', "
                              + "playable_state_namespace_id = ?, playable_state_scope = 'SHARED', "
                              + "canonical_launch_binding_operation_id = ? WHERE id = ?")) {
                    update.setObject(1, attemptedGameInstanceId);
                    update.setObject(2, attemptedTenantId);
                    update.setObject(3, attemptedPlayableNamespaceId);
                    update.setObject(4, attemptedBindingId);
                    update.setLong(5, worldInstanceId);
                    update.executeUpdate();
                  }
                })
            .isInstanceOf(SQLException.class);
      }

      try (Connection connection = fixtureConnection(schema);
          Statement statement = connection.createStatement();
          ResultSet count =
              statement.executeQuery("SELECT COUNT(*) FROM world_canonical_instance_association")) {
        assertThat(count.next()).isTrue();
        assertThat(count.getLong(1)).isZero();
      }
    } finally {
      dropFixtureSchema(schema);
    }
  }

  private Fixture fixture() {
    SourceFixture source = sourceFixture();
    UUID canonicalVersionId = UUID.randomUUID();
    long gameDesignVersionId = positiveLong();
    WorldAuthoredVersionIdentityReceipt identity =
        seedVersionIdentity(source, canonicalVersionId, gameDesignVersionId, VERSION_EPOCH);
    SeededPair pair = seedPair(source, identity, controlRequest(), "synthetic-launch-descriptor");
    Fixture fixture = new Fixture(source, pair.binding(), pair.versionIdentity());
    // Build and retain the original v2 publication terminal through World owner storage. Upstream
    // Account and Game Design authority in this component fixture remains explicitly stipulated;
    // STARTING is only the canonical storage-only Game Session observation carried by this input.
    preparationInput(fixture, UUID.randomUUID(), UUID.randomUUID(), "STARTING", 1L);
    return fixture;
  }

  private SourceFixture sourceFixture() {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt receipt =
        ownerTransaction()
            .execute(status -> sourceRepository.acceptFresh(NAMESPACE, UUID.randomUUID(), source));
    return new SourceFixture(
        source, Objects.requireNonNull(receipt, "source intake returned no receipt"));
  }

  private SeededPair seedPair(
      SourceFixture source,
      WorldAuthoredVersionIdentityReceipt identity,
      String controlPlaneRequestId,
      String launchDescriptorId) {
    return seedPair(
        source,
        identity,
        controlPlaneRequestId,
        launchDescriptorId,
        identity.canonicalVersionId(),
        identity.gameDesignVersionId());
  }

  private SeededPair seedPair(
      SourceFixture source,
      WorldAuthoredVersionIdentityReceipt identity,
      String controlPlaneRequestId,
      String launchDescriptorId,
      UUID canonicalVersionId,
      long gameDesignVersionId) {
    WorldAuthoredVersionIdentityReceipt resolvedIdentity =
        identity == null
            ? seedVersionIdentity(source, canonicalVersionId, gameDesignVersionId, VERSION_EPOCH)
            : identity;
    // The source and Account authority remain synthetic, but the graph, frozen selector and
    // immutable PUBLISHED terminal are written/read through the actual World owner stores.
    Fixture sourceFixture = new Fixture(source, null, resolvedIdentity);
    var frozen = frozenPlan(sourceFixture);
    var checkpoint = frozen.sourceBinding().freeze();
    var selector =
        publishedEvidence(
            publishedSelectors()
                .readCommitted(checkpoint)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Synthetic APPLIED-v2 selector was not retained")));
    CompleteLaunchBindingEvidence evidence =
        completeEvidence(
            source.source(),
            controlPlaneRequestId,
            gameDesignVersionId,
            canonicalVersionId,
            VERSION_EPOCH,
            launchDescriptorId,
            checkpoint.appliedCommitId(),
            selector);
    evidence =
        new CompleteLaunchBindingEvidence(
            evidence.descriptor(),
            releaseWithWorldCheckpoint(evidence.releaseAttestation(), checkpoint));
    CompleteLaunchBindingEvidence exactEvidence = evidence;
    WorldCompleteLaunchBindingReceipt binding =
        ownerTransaction()
            .execute(
                status ->
                    launchBindingRepository.acceptFresh(
                        NAMESPACE, source.receipt(), exactEvidence));
    return new SeededPair(
        Objects.requireNonNull(binding, "launch binding returned no receipt"), resolvedIdentity);
  }

  private WorldAuthoredVersionIdentityReceipt seedVersionIdentity(
      SourceFixture source, UUID canonicalVersionId, long gameDesignVersionId, long epoch) {
    AuthoredWorldVersionStateEvidence.Request request =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            UUID.randomUUID(),
            source.source().canonicalTenantId(),
            source.source().worldSlug(),
            source.source().operationId(),
            source.source().evidenceDigest(),
            gameDesignVersionId);
    AuthoredWorldVersionStateEvidence evidence =
        AuthoredWorldVersionStateEvidence.create(
            request,
            source.source(),
            canonicalVersionId,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            epoch);
    return Objects.requireNonNull(
        ownerTransaction()
            .execute(status -> versionIdentityRepository.acceptFresh(source.receipt(), evidence)),
        "Version identity returned no receipt");
  }

  private long insertCanonicalWorldRow(
      Fixture fixture, UUID canonicalGameInstanceId, UUID playableStateNamespaceId) {
    return prepareCanonicalWorldRow(
            fixture, canonicalGameInstanceId, playableStateNamespaceId, "GS_FIXTURE", 1L)
        .association()
        .worldInstanceId();
  }

  private long insertCanonicalWorldRow(
      SeededPair pair,
      SourceFixture source,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId) {
    Fixture fixture = new Fixture(source, pair.binding(), pair.versionIdentity());
    return insertCanonicalWorldRow(fixture, canonicalGameInstanceId, playableStateNamespaceId);
  }

  private WorldCanonicalInstancePreparation.Result prepareCanonicalWorldRow(
      Fixture fixture,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      String observedGameSessionStatus,
      long observedGameSessionRowVersion) {
    return preparationService(() -> {})
        .prepare(
            preparationInput(
                fixture,
                canonicalGameInstanceId,
                playableStateNamespaceId,
                observedGameSessionStatus,
                observedGameSessionRowVersion));
  }

  private WorldCanonicalInstancePreparation.Input preparationInput(
      Fixture fixture,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      String observedGameSessionStatus,
      long observedGameSessionRowVersion) {
    var request =
        request(
            new SeededPair(fixture.binding(), fixture.versionIdentity()),
            canonicalGameInstanceId,
            UUID.randomUUID());
    var response =
        responseFor(
            fixture.binding().evidence(),
            canonicalGameInstanceId,
            playableStateNamespaceId,
            request.readRequestId(),
            observedGameSessionStatus,
            observedGameSessionRowVersion);
    WorldCanonicalInstancePreparation.Input input =
        new WorldCanonicalInstancePreparation.Input(
            request, response, fixture.binding(), fixture.versionIdentity(), frozenPlan(fixture));
    completeIsolatedPublicationTerminal(input);
    return input;
  }

  private WorldCanonicalInstancePreparationService preparationService(Runnable heldCheck) {
    WorldCanonicalFrozenTopologyRepository frozenRepository =
        new WorldCanonicalFrozenTopologyRepository(
            dsl,
            graphSnapshots,
            new WorldDraftTopologyCommitRepository(dsl, publicationFence, objectMapper),
            digestService);
    WorldCanonicalInstancePreparationRepository preparationRepository =
        new WorldCanonicalInstancePreparationRepository(
            dsl, transactionManager, associationRepository, frozenRepository);
    // Synthetic fixture handle only. This does not authenticate GS/GD source or delivery, prove
    // APPLIED/IN_SYNC, or supply Account authority.
    return new WorldCanonicalInstancePreparationService(
        preparationRepository,
        input ->
            new WorldCanonicalInstancePreparationService.HeldCommitAuthority() {
              @Override
              public void requireHeld() {
                heldCheck.run();
              }

              @Override
              public void close() {}
            });
  }

  private WorldCanonicalInstanceTopologyPlan frozenPlan(Fixture fixture) {
    return frozenPlans.computeIfAbsent(
        fixture.versionIdentity().operationId(),
        ignored -> {
          WorldDraftTopologyCommitPlan draft = topologyPlan(fixture);
          var draftApplication = draftApplication(draft);
          appliedComponent().apply(draftApplication);
          var owner = draft.ownerBinding();
          String publicationRequest = "preparation-capture-" + UUID.randomUUID();
          // V27/release evidence retains the post-publication epoch; the original Draft freeze
          // selection is exactly the preceding epoch.
          long freezeEpoch =
              Math.subtractExact(
                  fixture.versionIdentity().versionStateEvidence().versionStateEpoch(), 1L);
          var selection = publicationSelection(draft, publicationRequest, freezeEpoch);
          String requestDigest = selection.digest().substring("sha256:".length());
          var accountBinding =
              isolatedPublicationAccountBinding(
                  draftApplication.operation().accountBindingBytes(),
                  selection,
                  publicationRequest);
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
                  publicationRequest,
                  requestDigest,
                  freezeEpoch,
                  "publish:"
                      + owner.canonicalTenantId()
                      + ":publish-request:"
                      + publicationRequest);
          var attemptCreatedInTransaction = new AtomicBoolean();
          var attempt =
              Objects.requireNonNull(
                  ownerTransaction()
                      .execute(
                          status -> {
                            var frozenAttempt =
                                publicationFence.claimFreeze(
                                    evidence,
                                    () -> {
                                      var digest =
                                          digestService.getDraftDesignDigest(
                                              Long.toString(
                                                  fixture.source().receipt().localTenantKey()),
                                              Long.toString(
                                                  fixture.versionIdentity().localVersionKey()));
                                      attemptCreatedInTransaction.set(true);
                                      return new WorldDesignPublicationFenceEvidence.Checkpoint(
                                          draft.binding().commitId().toString(),
                                          digest.contentDigest(),
                                          3);
                                    });
                            new WorldSelectedDraftPublicationAuthorizationRepository(dsl)
                                .retainOrRequireExact(
                                    frozenAttempt,
                                    accountBinding,
                                    attemptCreatedInTransaction.get());
                            return frozenAttempt;
                          }),
                  "Synthetic frozen preparation fixture returned no publication checkpoint");
          List<OwnedAffectedTuple> tuples =
              draft.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
                  .map(
                      unit ->
                          new OwnedAffectedTuple(
                              unit.owner().name(),
                              unit.aggregateType(),
                              unit.aggregateId(),
                              unit.scopeType(),
                              unit.scopeId(),
                              unit.expectedEpoch()))
                  .toList();
          CaptureRequest captureRequest =
              new CaptureRequest(
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
          WorldCanonicalFrozenTopology frozen =
              new WorldCanonicalFrozenTopologyService(frozenRepository(), transactionManager)
                  .capture(new WorldCanonicalFrozenTopology.Request(draft, captureRequest));
          return WorldCanonicalInstanceTopologyPlan.create(frozen);
        });
  }

  private WorldDraftGraphApplicationRepository appliedRepository() {
    return new WorldDraftGraphApplicationRepository(dsl, publicationFence, objectMapper);
  }

  private WorldDraftGraphApplicationService appliedComponent() {
    return new WorldDraftGraphApplicationService(
        appliedRepository(),
        transactionManager,
        operation -> {
          // Explicitly isolated Account COMMIT_ORDER authority; this is not producer proof.
          return new WorldDraftGraphApplicationService.CommitOrderProof(operation);
        });
  }

  private WorldDraftGraphApplication draftApplication(WorldDraftTopologyCommitPlan plan) {
    var binding = plan.binding();
    var account =
        new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            binding.requestId(),
            binding.commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.baseCommitId(),
            "0",
            binding.canonicalBytes(),
            binding.canonicalBytes(),
            binding.digest(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.TENANT,
                    binding.target().canonicalTenantId().toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})));
    var operation =
        new WorldDraftTerminalOperation(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId(),
            binding,
            plan.ownerBinding(),
            account.canonicalBytes());
    return new WorldDraftGraphApplication(operation, plan);
  }

  private WorldCanonicalFrozenTopologyRepository frozenRepository() {
    return new WorldCanonicalFrozenTopologyRepository(
        dsl,
        graphSnapshots,
        new WorldDraftTopologyCommitRepository(dsl, publicationFence, objectMapper),
        digestService);
  }

  private WorldPublishedStartLocationRepository publishedSelectors() {
    return new WorldPublishedStartLocationRepository(dsl, frozenRepository(), appliedRepository());
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

  private AuthoredDraftPublishSelectionBinding publicationSelection(
      WorldDraftTopologyCommitPlan plan, String publicationRequest, long freezeEpoch) {
    var binding = plan.binding();
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            publicationRequest,
            Long.toString(freezeEpoch),
            "isolated-association-publication-fixture",
            binding.requestId(),
            binding.commitId(),
            binding.digest());
    return AuthoredDraftPublishSelectionBinding.capture(
        intent,
        binding.target(),
        binding,
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            "[]",
            OffsetDateTime.parse("2026-01-01T00:00:00Z")));
  }

  private GameDesignPublicationTerminalEvidence isolatedTerminalEvidence(
      WorldCanonicalInstancePreparation.Input input) {
    var release = input.completeLaunchBinding().evidence().releaseAttestation();
    var world = Objects.requireNonNull(release.worldStartLocationEvidence());
    if (release.versionStateEpoch() != Math.addExact(world.request().versionStateEpoch(), 1L)) {
      throw new IllegalStateException(
          "Isolated PUBLISHED terminal must retain the next publication epoch after its World freeze");
    }
    var selection =
        publicationSelection(
            input.topologyPlan().sourceBinding().plan(),
            world.request().publicationRequestId(),
            world.request().versionStateEpoch());
    if (!selection.digest().equals("sha256:" + world.request().requestDigest())) {
      throw new IllegalStateException(
          "Isolated publication selection differs from the retained World request digest");
    }
    var account =
        isolatedPublicationAccountBinding(
            world.originalAccountBindingBytes(), selection, world.request().publicationRequestId());
    var operation = new GameDesignPublicationOperationBinding(account, world);
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(),
        Outcome.PUBLISHED,
        isolatedReleaseContent(input),
        release.versionStateEpoch());
  }

  private AccountPublicationAuthorizationBinding isolatedPublicationAccountBinding(
      byte[] originalDraftAccountBinding,
      AuthoredDraftPublishSelectionBinding selection,
      String publicationRequestId) {
    var originalAccount = DraftAuthorizationFenceBinding.fromStored(originalDraftAccountBinding);
    String stableIdentity =
        selection.target().canonicalTenantId()
            + ":"
            + selection.target().canonicalVersionId()
            + ":"
            + publicationRequestId;
    // Fixture-only distinct Account order retained atomically with the first freeze; not producer
    // proof.
    return new AccountPublicationAuthorizationBinding(
        UUID.nameUUIDFromBytes(
            (stableIdentity + "/account-operation").getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes(
            (stableIdentity + "/account-fence").getBytes(StandardCharsets.UTF_8)),
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            originalAccount.actorAccountId(), selection),
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                originalAccount.actorAccountId().toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private ReleaseContent isolatedReleaseContent(WorldCanonicalInstancePreparation.Input input) {
    var release = input.completeLaunchBinding().evidence().releaseAttestation();
    var world = Objects.requireNonNull(release.worldStartLocationEvidence());
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent() ? participant.baseVersionId() : null,
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        participant.digestSchemaVersion(),
                        participant.abilitySchemaDigestPresent()
                            ? participant.abilitySchemaDigest()
                            : null,
                        null,
                        null))
            .toList();
    return new ReleaseContent(
        release.canonicalTenantId(),
        release.canonicalVersionId(),
        release.publishedReleaseBundleRef(),
        1,
        "v2",
        release.publishWorkflowId(),
        release.manifestHash(),
        release.manifestSchemaVersion(),
        release.artifactDigests(),
        release.requiredManifestAssetKeys(),
        participants,
        release.commandDefinitions(),
        release.generationConfigRevision(),
        world);
  }

  private void completeIsolatedPublicationTerminal(WorldCanonicalInstancePreparation.Input input) {
    var evidence = isolatedTerminalEvidence(input);
    GameDesignPublicationTerminalEvidence retained =
        publicationTerminalComponent(evidence)
            .complete(evidence.operationBytes(), evidence.canonicalBytes());
    if (!Arrays.equals(evidence.canonicalBytes(), retained.canonicalBytes())) {
      throw new IllegalStateException(
          "Isolated World terminal readback differs from original PUBLISHED v2 evidence");
    }
  }

  private WorldPublicationTerminalService publicationTerminalComponent(
      GameDesignPublicationTerminalEvidence evidence) {
    byte[] operationBytes = evidence.operationBytes();
    return new WorldPublicationTerminalService(
        new WorldPublicationTerminalRepository(dsl, transactionManager),
        (suppliedOperation, terminalBytes) -> {
          GameDesignPublicationTerminalEvidence upstream =
              GameDesignPublicationTerminalEvidence.fromStored(terminalBytes);
          if (!Arrays.equals(operationBytes, suppliedOperation)
              || !Arrays.equals(upstream.operationBytes(), suppliedOperation)) {
            throw new IllegalArgumentException("isolated terminal operation mismatch");
          }
          return new WorldPublicationTerminalService.VerifiedTerminal(
              WorldPublicationTerminal.Request.fromStored(terminalBytes),
              new WorldPublicationTerminalService.HeldTerminalAuthority() {
                public void requireHeld() {}

                public void close() {}
              });
        });
  }

  private WorldDraftTopologyCommitPlan topologyPlan(Fixture fixture) {
    UUID commitId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID zoneId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    UUID destinationRoomId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    List<WorldDesignMutationRevision> mutations =
        new ArrayList<>(
            List.of(
                mutation(
                        commitId,
                        regionId,
                        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
                        regionId)
                    .setRegion(
                        RegionDesignMutation.newBuilder()
                            .setName("synthetic region")
                            .setWeather("rain")
                            .setShardId(7)
                            .setGenerationSeed(9001)
                            .setGeneratorType("synthetic")
                            .setGeneratorParams("{}"))
                    .build(),
                mutation(
                        commitId,
                        zoneId,
                        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                        regionId)
                    .setZone(
                        ZoneDesignMutation.newBuilder()
                            .setName("synthetic zone")
                            .setRegionId(regionId.toString()))
                    .build(),
                mutation(
                        commitId,
                        destinationRoomId,
                        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                        regionId)
                    .setRoom(
                        RoomDesignMutation.newBuilder()
                            .setName("synthetic destination")
                            .setZoneId(zoneId.toString())
                            .setDescription("synthetic destination room"))
                    .build(),
                mutation(
                        commitId,
                        roomId,
                        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                        regionId)
                    .setRoom(
                        RoomDesignMutation.newBuilder()
                            .setName("synthetic room")
                            .setZoneId(zoneId.toString())
                            .setDescription("synthetic materialization proof"))
                    .build(),
                mutation(
                        commitId,
                        UUID.randomUUID(),
                        WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
                        regionId)
                    .setRoomExit(
                        RoomExitDesignMutation.newBuilder()
                            .setFromRoomId(roomId.toString())
                            .setToRoomId(destinationRoomId.toString())
                            .setDirection("EAST")
                            .setCost(3))
                    .build()));
    WorldFreshGraphDeclaration declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(fixture.versionIdentity().canonicalTenantId().toString())
            .setVersionId(fixture.versionIdentity().canonicalVersionId().toString())
            .setStartLocation(
                RoomTemplateRef.newBuilder()
                    .setTenantId(fixture.versionIdentity().canonicalTenantId().toString())
                    .setVersionId(fixture.versionIdentity().canonicalVersionId().toString())
                    .setRoomTemplateId(roomId.toString()))
            .addFamilyCounts(
                familyCount(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION))
            .addFamilyCounts(
                familyCount(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE))
            .addFamilyCounts(
                familyCount(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
            .addFamilyCounts(
                familyCount(
                    mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT))
            .addFamilyCounts(
                familyCount(
                    mutations,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE))
            .addFamilyCounts(
                familyCount(
                    mutations,
                    WorldDesignAggregateType
                        .WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING))
            .build();
    mutations.set(
        0, mutations.getFirst().toBuilder().setFreshGraphDeclaration(declaration).build());
    List<DraftCommitBinding.RevisionPayload> revisions = new java.util.ArrayList<>();
    List<AffectedUnit> units = new java.util.ArrayList<>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0", UUID.randomUUID(), Owner.ENTITY_MANAGEMENT, "isolated Entity fixture evidence"));
    units.add(
        new AffectedUnit(
            Owner.ENTITY_MANAGEMENT,
            "NPC",
            entityId.toString(),
            "AGGREGATE",
            entityId.toString(),
            "0"));
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "isolated Game Design control-plane fixture evidence"));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "VERSION",
            fixture.versionIdentity().canonicalVersionId().toString(),
            "AGGREGATE",
            fixture.versionIdentity().canonicalVersionId().toString(),
            "0"));
    for (WorldDesignMutationRevision mutation : mutations) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              json(mutation)));
      String family =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              mutation.getScopeId(),
              "0"));
    }
    var source = fixture.source().source();
    var owner =
        new OwnerBinding(
            NAMESPACE,
            source.canonicalTenantId(),
            fixture.versionIdentity().canonicalVersionId(),
            fixture.versionIdentity().operationId(),
            fixture.versionIdentity().gameDesignVersionId(),
            fixture.source().receipt().intakeRequestId(),
            fixture.source().receipt().operationId(),
            fixture.source().receipt().requestDigest(),
            source.operationId(),
            source.evidenceDigest(),
            fixture.source().receipt().receiptDigest());
    var target =
        new DraftCommitBinding.TargetProof(
            source.canonicalTenantId(),
            fixture.versionIdentity().canonicalVersionId(),
            fixture.versionIdentity().gameDesignVersionId(),
            source.sourceGameTenantKey(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    return WorldDraftTopologyCommitPlan.create(
        DraftCommitBinding.create(
            target, UUID.randomUUID(), commitId, "synthetic-base-commit", revisions, units),
        owner);
  }

  private static WorldFreshGraphFamilyCount familyCount(
      List<WorldDesignMutationRevision> mutations, WorldDesignAggregateType family) {
    int count =
        (int) mutations.stream().filter(mutation -> mutation.getAggregateType() == family).count();
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID aggregateId, WorldDesignAggregateType type, UUID containingRegionId) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(aggregateId.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(containingRegionId.toString());
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private long preparationCount(UUID canonicalGameInstanceId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM world_canonical_instance_preparation "
                    + "WHERE canonical_game_instance_id = ?",
                canonicalGameInstanceId),
            "World preparation count query returned no row")
        .get(0, Long.class);
  }

  private Record topologyIdentity(long worldInstanceId, String family, UUID templateId) {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT template_id, template_private_row_key, runtime_row_id, runtime_identity, "
                + "runtime_room_instance_id FROM world_canonical_instance_topology_identity "
                + "WHERE world_instance_id = ? AND family = ? AND template_id = ?",
            worldInstanceId,
            family,
            templateId),
        "canonical source/runtime identity mapping missing for "
            + family
            + " template "
            + templateId);
  }

  private long insertLegacyWorldRow(Fixture fixture, long gameInstanceKey) {
    var descriptor = fixture.binding().descriptor();
    Record inserted =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                    + "control_plane_request_id, launch_descriptor_id, version_id, script_patch_version, "
                    + "runtime_flags_json, generation_config_revision, release_bundle_id, "
                    + "published_release_bundle_ref, version_state_epoch, remap_set_id, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LEGACY_FIXTURE') RETURNING id",
                8_100_000_000L
                    + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L),
                gameInstanceKey,
                descriptor.gameTemplateId(),
                descriptor.controlPlaneRequestId(),
                descriptor.launchDescriptorId(),
                fixture.versionIdentity().localVersionKey(),
                descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null,
                descriptor.runtimeFlagsJson(),
                descriptor.generationConfigRevision(),
                descriptor.releaseBundleId(),
                descriptor.publishedReleaseBundleRef(),
                descriptor.versionStateEpoch(),
                descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null),
            "legacy World row insert returned no row");
    return inserted.get("id", Long.class);
  }

  private WorldCanonicalInstanceAssociation.Claim claim(
      Fixture fixture,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      long worldInstanceId,
      long rowVersion,
      String status) {
    return claim(
        new SeededPair(fixture.binding(), fixture.versionIdentity()),
        canonicalGameInstanceId,
        playableStateNamespaceId,
        worldInstanceId,
        rowVersion,
        status);
  }

  private WorldCanonicalInstanceAssociation.Claim claim(
      SeededPair pair,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      long worldInstanceId,
      long rowVersion,
      String status) {
    WorldCanonicalInstanceAssociation.GameSessionReadRequest request =
        request(pair, canonicalGameInstanceId, UUID.randomUUID());
    WorldCanonicalInstanceAssociation.GameSessionReadEvidence response =
        responseFor(
            pair.binding().evidence(),
            canonicalGameInstanceId,
            playableStateNamespaceId,
            request.readRequestId(),
            status,
            rowVersion);
    return new WorldCanonicalInstanceAssociation.Claim(
        request, response, worldInstanceId, pair.binding(), pair.versionIdentity());
  }

  private WorldCanonicalInstanceAssociation.GameSessionReadRequest request(
      SeededPair pair, UUID canonicalGameInstanceId, UUID readRequestId) {
    return requestFor(pair.binding().evidence(), canonicalGameInstanceId, readRequestId);
  }

  private WorldCanonicalInstanceAssociation.GameSessionReadRequest requestFor(
      CompleteLaunchBindingEvidence evidence, UUID canonicalGameInstanceId, UUID readRequestId) {
    var descriptor = evidence.descriptor();
    var release = evidence.releaseAttestation();
    return new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
        readRequestId,
        NAMESPACE,
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        canonicalGameInstanceId,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private WorldCanonicalInstanceAssociation.GameSessionReadEvidence responseFor(
      CompleteLaunchBindingEvidence evidence,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      UUID readRequestId,
      String status,
      long rowVersion) {
    return responseFor(
        evidence,
        canonicalGameInstanceId,
        playableStateNamespaceId,
        readRequestId,
        status,
        rowVersion,
        true);
  }

  private WorldCanonicalInstanceAssociation.GameSessionReadEvidence responseFor(
      CompleteLaunchBindingEvidence evidence,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      UUID readRequestId,
      String status,
      long rowVersion,
      boolean publicProduction) {
    var descriptor = evidence.descriptor();
    var release = evidence.releaseAttestation();
    return new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
        readRequestId,
        descriptor.targetNamespace(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        canonicalGameInstanceId,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest(),
        playableStateNamespaceId,
        "SHARED",
        publicProduction,
        status,
        rowVersion,
        descriptor,
        release);
  }

  private long associationCount(UUID canonicalGameInstanceId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM world_canonical_instance_association "
                    + "WHERE canonical_game_instance_id = ?",
                canonicalGameInstanceId),
            "World canonical association count query returned no row")
        .get(0, Long.class);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private static CompleteLaunchBindingEvidence completeEvidence(
      AuthoredWorldSourceEvidence source,
      String controlPlaneRequestId,
      long gameDesignVersionId,
      UUID canonicalVersionId,
      long epoch,
      String launchDescriptorId,
      String attestationCommit) {
    return completeEvidence(
        source,
        controlPlaneRequestId,
        gameDesignVersionId,
        canonicalVersionId,
        epoch,
        launchDescriptorId,
        attestationCommit,
        null);
  }

  private static CompleteLaunchBindingEvidence completeEvidence(
      AuthoredWorldSourceEvidence source,
      String controlPlaneRequestId,
      long gameDesignVersionId,
      UUID canonicalVersionId,
      long epoch,
      String launchDescriptorId,
      String attestationCommit,
      WorldPublishedStartLocationEvidence selector) {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            controlPlaneRequestId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            73L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            launchDescriptorId,
            gameDesignVersionId,
            false,
            null,
            "{}",
            "generation-config-73",
            epoch,
            79L,
            "release-bundle-79",
            false,
            null);
    return new CompleteLaunchBindingEvidence(
        descriptor,
        attestation(descriptor, source, canonicalVersionId, attestationCommit, selector));
  }

  private static AuthoredWorldReleaseAttestationEvidence attestation(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      String commit,
      WorldPublishedStartLocationEvidence selector) {
    return attestation(descriptor, source, canonicalVersionId, commit, selector, 'f');
  }

  private static AuthoredWorldReleaseAttestationEvidence attestation(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      String commit,
      WorldPublishedStartLocationEvidence selector,
      char manifestHashDigit) {
    AuthoredWorldReleaseAttestationEvidence.Participant worldParticipant =
        selector == null
            ? participant("WORLD_MANAGEMENT", 3, "a", false, descriptor.versionId(), commit)
            : new AuthoredWorldReleaseAttestationEvidence.Participant(
                "WORLD_MANAGEMENT",
                Long.toString(descriptor.versionId()),
                false,
                null,
                selector.request().appliedCommitId(),
                selector.request().contentDigest(),
                selector.request().digestSchemaVersion(),
                false,
                null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        List.of(
            worldParticipant,
            participant("ENTITY_MANAGEMENT", 2, "b", false, descriptor.versionId(), commit),
            participant("GAME_LOGIC", 1, "c", true, descriptor.versionId(), commit),
            participant("AUTOMATION_SCRIPTING", 5, "d", false, descriptor.versionId(), commit),
            participant(
                "GAME_DESIGN_CONTROL_PLANE", 1, "e", false, descriptor.versionId(), commit));
    if (selector == null) {
      return AuthoredWorldReleaseAttestationEvidence.create(
          NAMESPACE,
          descriptor.resultDigest(),
          source.canonicalTenantId(),
          canonicalVersionId,
          source.worldSlug(),
          source.operationId(),
          source.evidenceDigest(),
          descriptor.launchDescriptorId(),
          descriptor.publishedReleaseBundleRef(),
          descriptor.versionStateEpoch(),
          "publish:synthetic:association-fixture",
          commit,
          participants,
          digest(manifestHashDigit),
          1,
          List.of(),
          List.of(),
          List.of("look"),
          descriptor.generationConfigRevision());
    }
    return AuthoredWorldReleaseAttestationEvidence.create(
        NAMESPACE,
        descriptor.resultDigest(),
        source.canonicalTenantId(),
        canonicalVersionId,
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        descriptor.launchDescriptorId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        selector.request().publishWorkflowId(),
        selector.request().appliedCommitId(),
        participants,
        digest(manifestHashDigit),
        1,
        List.of(),
        List.of(),
        List.of("look"),
        descriptor.generationConfigRevision(),
        selector);
  }

  private static AuthoredWorldReleaseAttestationEvidence releaseWithWorldCheckpoint(
      AuthoredWorldReleaseAttestationEvidence release, CaptureRequest checkpoint) {
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent(),
                        participant.baseVersionId(),
                        checkpoint.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(participant.participantKey())
                            ? checkpoint.contentDigest()
                            : participant.contentDigest(),
                        "WORLD_MANAGEMENT".equals(participant.participantKey())
                            ? checkpoint.digestSchemaVersion()
                            : participant.digestSchemaVersion(),
                        participant.abilitySchemaDigestPresent(),
                        participant.abilitySchemaDigest()))
            .toList();
    if (release.worldStartLocationEvidence() == null) {
      return AuthoredWorldReleaseAttestationEvidence.create(
          release.targetNamespace(),
          release.descriptorResultDigest(),
          release.canonicalTenantId(),
          release.canonicalVersionId(),
          release.worldSlug(),
          release.authoredWorldSourceOperationId(),
          release.authoredWorldSourceEvidenceDigest(),
          release.launchDescriptorId(),
          release.publishedReleaseBundleRef(),
          release.versionStateEpoch(),
          checkpoint.publishWorkflowId(),
          checkpoint.appliedCommitId(),
          participants,
          release.manifestHash(),
          release.manifestSchemaVersion(),
          release.requiredManifestAssetKeys(),
          release.artifactDigests(),
          release.commandDefinitions(),
          release.generationConfigRevision());
    }
    return AuthoredWorldReleaseAttestationEvidence.create(
        release.targetNamespace(),
        release.descriptorResultDigest(),
        release.canonicalTenantId(),
        release.canonicalVersionId(),
        release.worldSlug(),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.launchDescriptorId(),
        release.publishedReleaseBundleRef(),
        release.versionStateEpoch(),
        checkpoint.publishWorkflowId(),
        checkpoint.appliedCommitId(),
        participants,
        release.manifestHash(),
        release.manifestSchemaVersion(),
        release.requiredManifestAssetKeys(),
        release.artifactDigests(),
        release.commandDefinitions(),
        release.generationConfigRevision(),
        release.worldStartLocationEvidence());
  }

  private static AuthoredWorldReleaseAttestationEvidence.Participant participant(
      String owner,
      int schema,
      String contentHex,
      boolean hasAbilityDigest,
      long descriptorVersion,
      String commit) {
    return new AuthoredWorldReleaseAttestationEvidence.Participant(
        owner,
        Long.toString(descriptorVersion),
        false,
        null,
        commit,
        contentHex.repeat(64),
        schema,
        hasAbilityDigest,
        hasAbilityDigest ? digest('c') : null);
  }

  private static AuthoredWorldSourceEvidence source(UUID tenant, String worldSlug) {
    UUID registrationRequest = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String displayName = "Synthetic source " + tenant.toString().substring(0, 8);
    long sourceGameRowId = Math.max(1L, tenant.getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceGameTenantKey = "source-" + tenant.toString().replace("-", "").substring(0, 24);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequest, tenant, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequest,
            sourceOperationId,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequest,
        sourceOperationId,
        requestDigest,
        tenant,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static String randomWorld() {
    return "world-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
  }

  private static String controlRequest() {
    return "world-control-" + UUID.randomUUID();
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static long positiveLong() {
    return Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent canonical association barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Concurrent canonical association was interrupted", exception);
    }
  }

  private static void migrateFixture(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static Connection fixtureConnection(String schema) throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setSchema(schema);
    return connection;
  }

  private static void dropFixtureSchema(String schema) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private record SourceFixture(
      AuthoredWorldSourceEvidence source, WorldAuthoredSourceIntakeReceipt receipt) {}

  private record SeededPair(
      WorldCompleteLaunchBindingReceipt binding,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {}

  private record Fixture(
      SourceFixture source,
      WorldCompleteLaunchBindingReceipt binding,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {}
}
