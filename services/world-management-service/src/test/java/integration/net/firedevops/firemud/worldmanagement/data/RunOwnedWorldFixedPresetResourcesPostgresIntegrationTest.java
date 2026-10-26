package net.firedevops.firemud.worldmanagement.data;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.PARENT_MIGRATIONS;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.repositoryRoot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.impl.RunOwnedWorldFixtureLifecycleTestFactory;
import net.firedevops.firemud.worldmanagement.service.impl.WorldLifecycleCommandServiceImpl;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Native PostgreSQL PREPARING-only World owner proof with controlled upstream wire inputs.
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
  private record LifecycleGuardProbeCase(
      int key,
      String label,
      String oldMutation,
      String activationMutation,
      String expectedDiagnostic) {}

  private record LifecycleTransitionGuardProbeCase(
      int key,
      String label,
      String startingMutation,
      String transitionMutation,
      String expectedDiagnostic) {}

  private static final String CONTENT_DIGEST =
      "sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc";
  private static final String VALID_DIGEST = "sha256:" + "a".repeat(64);

  private static final Map<String, String> PROTECTED_ARTIFACTS =
      Map.ofEntries(
          Map.entry(
              "V1003__world_authored_graph_snapshot.sql",
              "83e4353e13c648e7a4041d9db2e455a677c49cbe1232843a7bc011ae7d07dd18"),
          Map.entry(
              "V1004__world_guarded_region_draft_commits.sql",
              "4d702e93bcd3f1cc42b24829a9b2083e62b36a89dac35eed974180ddfbc98c35"),
          Map.entry(
              "V1005__world_guarded_uuid_topology_commits.sql",
              "7bf03448938d3d335367fecdd4481250d8235339aa39465b439e651aac539a89"),
          Map.entry(
              "V1006__world_canonical_frozen_topology.sql",
              "766d33d8cc81693fa57c20fdf962a299ed4b35730a8914c407d1200ab3fba5f3"),
          Map.entry(
              "V1007__world_digest_schema3_capture.sql",
              "1a4d1a986793b7947abecb88b97a6faaf4caf95209e49a5de6e5b1c768cd5178"),
          Map.entry(
              "V1008__world_canonical_instance_association.sql",
              "6bece257d5aa97ac0d2073744da650c51366c4076ac72c055f1589123b7f40b9"),
          Map.entry(
              "V1009__world_guarded_canonical_instance_preparation.sql",
              "6c68bcd4086b8ccf0ea188a10a4e95473ad66ebb18a3d6152197dbd7afd68ed1"),
          Map.entry(
              "V1010__world_draft_terminal_outcomes.sql",
              "7d70fb9c89c92ae4eb97998645ff9b818c63d23681166088553384a9a8b5f8a7"),
          Map.entry(
              "V1011__world_draft_terminal_guard_namespace.sql",
              "8e8df60368e92646b70730089ef39fdcf8a53cd248fa931a537f80fc405b80a7"),
          Map.entry(
              "V1012__world_account_bound_graph_application.sql",
              "1ebfaa3513be9438a1840bbe8ccdbdd4b9c9f2f939b25a2498a24a75da1ae125"),
          Map.entry(
              "V1013__world_preparation_exact_release_graph.sql",
              "46c280c1c42fd532df0e0a8efb9a89a0f3dd84695e887fe9102910f6d58db0ee"),
          Map.entry(
              "V1014__world_explicit_fresh_graph_start_location.sql",
              "a7369c0426c854c4e44c34dc1a58fd86952e04c7e2c142cd0b0535bf326480a1"),
          Map.entry(
              "V1015__world_release_start_location_materialization.sql",
              "493e19c4fd37b1ed97f9e8821eff1ba4cd99ac1dc0aea9217d64690371afa56e"),
          Map.entry(
              "V1016__canonical_instance_activation_operation.sql",
              "b251f8d2c0c172bbff71dbb8c33cfb599a6201c9f57cc1c978624ce78f33cf52"),
          Map.entry(
              "V1017__world_publication_terminal_handoff.sql",
              "0dbee29bf9a746fd28cceca950dc383c3c2a1f0f2ea89d0363c973e6debd2d0b"),
          Map.entry(
              "V1018__world_operational_region_assignment.sql",
              "e91713eeba68dab0731d3bc8a69fabbfb828e70d194e73425615fbead050f784"),
          Map.entry(
              "V1019__world_canonical_initial_player_location.sql",
              "b04f1abe5f9ea8a27def3ebe04305abcb8a846f7356dd547f4da0b16973d40ea"),
          Map.entry(
              "V1020__canonical_initial_admission_hold_binding.sql",
              "2baac1fccf9fe87dc2986c6119b8f68ec4c8d078db994f41db2ae880bc25e319"),
          Map.entry(
              "V1021__canonical_initial_admission_hold_terminal.sql",
              "7f5016a8a72367aee9516805d1cacf8410167823fec625add9eeee091dd5cee1"),
          Map.entry(
              "V1022__canonical_initial_player_location_typed_hold.sql",
              "8529548fee59f76acad5a3703ac778bf71785c51f1d3bc1a62c0fa6109cc57ff"),
          Map.entry(
              "V1023__canonical_initial_location_operation_origins.sql",
              "ac780a590828a37bb153dbf6e7340ff028bd8657907917fdfcc5fda4323c14fb"),
          Map.entry(
              "V1024__world_canonical_player_admission_hold.sql",
              "49c31f8ddc677e609491265c6fc35fbeb8c8d60b7ab9cda7dfee934a64b5ff62"),
          Map.entry(
              "V1025__canonical_activation_complete_region_evidence.sql",
              "b4e3b6332f31464be44311d903ce0bdb1a90e17274fa657b98cb0c6503b13988"),
          Map.entry(
              "V1026__world_selected_publication_account_binding.sql",
              "42b3010f6e7b68626f9a68f7dc79f65a8aec499e360ba5d48038ef6981aaffae"),
          Map.entry(
              "V1027__world_publication_terminal_account_binding.sql",
              "8d0dc990b18cffc3eb19eff3784e5ec42179bb5ec37cb70ff9a8a5949e3dfd6b"),
          Map.entry(
              "V1028__selected_publication_artifact_inventory.sql",
              "8b978acbb65eac2870e47f3c39d0a4af132a06ed7acfedc1d663a5451e6a01aa"),
          Map.entry(
              "V1029__publication_operation_inventory_v2.sql",
              "339ceba9b01c7215ca73a39851e4c1da2a6c0b32f86e6dc539dad83731a1c353"),
          Map.entry(
              "V1030__world_publication_inventory_projection_precedence.sql",
              "d42e3c8959793d0908386068bf52806ef9fefd07068d296b6ffe00dd9079b36a"),
          Map.entry(
              "V1031__publication_account_order_projection.sql",
              "c5fcd5dbca23e5f427d0ad04e6d1484f88784c81c9bcbf8432f269430bd2c993"),
          Map.entry(
              "V1032__world_selected_inbound_closure_declaration.sql",
              "011b926c50748670bbf3f4e113ea56c1cdd514252557c08ea6632eb96e9378df"),
          Map.entry(
              "V1033__world_selected_publication_inventory_schema_v2.sql",
              "cceacb2d57a4875efff197a4bf2c89920cc11a7c606d878478270239ad748654"),
          Map.entry(
              "V1034__world_selected_closure_schema4_guards.sql",
              "fe07f9a870094d8cb3b748095bc0a0cb4be6d7d3958a59a653af1b5a737a4b40"),
          Map.entry(
              "V1035__world_start_session_execution_terminal_fence.sql",
              "a7931633a6e1c5ab03e12cc4026b543c8e64fa4c894f5d5f5fbe343c3c5a24be"),
          Map.entry(
              "V1036__world_original_start_session_attempt_admission.sql",
              "c627a2ffbb7cac690de6abbb07d75911db3aba50abdf4d9924c81b4fa95b7d7c"),
          Map.entry(
              "V1037__world_launch_captured_catalog_identity.sql",
              "c714e5b4c6a01a3d5d713a399d4dc33e2cf4daae4f0abf52015a42a3669e39d5"),
          Map.entry(
              "V1038__original_start_session_protected_activation.sql",
              "030e9990884f08c8e83e133bc4eb315c114e662bbb81a96f9f310563bcfd3a50"),
          Map.entry(
              "V1039__canonical_activation_preparation_schema.sql",
              "b744728b0cda73934bcb69fb6cd4c07c1cb50e516bf11cbd4e218e396017b875"),
          Map.entry(
              "V1040__world_run_owned_fixture_canonical_association.sql",
              "5707339f2f028df888a181b34864acfc87a4a06bc76a5d4c74200a0fa47d2a60"),
          Map.entry(
              "V1041__world_run_owned_fixed_preset_preparation.sql",
              "c702cbb04c60f07fdf319c05a3f14f155c01ae8b0f1c9f5830ca152d5be5fbc4"),
          Map.entry(
              "V1042__world_canonical_association_required_field_checks.sql",
              "670e8c82e4a736a5e645ce3ebb3ee2fb5692d996080b473ec5055885c4d73ebb"),
          Map.entry(
              "V1042.1__world_execution_terminal_service_schema.sql",
              "d64c9ad97a4af81d6fddc4c44da7dc4e9afd87b27e905764094f1f37c7c7bd70"),
          Map.entry(
              "V1042.2__world_fixed_preset_read_transaction.sql",
              "4edc9508e967a7fd0c367e00abab4d9694c5dfbc6c5474aaeda9b1eb561c70aa"),
          Map.entry(
              "V1042.3__world_fixture_row_mutation_diagnostic.sql",
              "7e8e2d531c693d5856ee808c26f7c910d1117fbddf8a9044784b10d4372280f5"),
          Map.entry(
              "V1042.4__world_fixture_association_owner_copy_checks.sql",
              "47d443cd8cf2aa74566476575a96faf7a69466daac2324deb2be4f88095b1c60"),
          Map.entry(
              "V1042.5__world_fixed_preset_complete_room_readback.sql",
              "b32bf71e51132168f385ef6b5042c70d99587ab26ab73927234bda03a66464b8"),
          Map.entry(
              "V1042.6__world_terminal_binding_and_location_operation_checks.sql",
              "87b0c67affa696bda5f2bfafdf533405b90cea01b0c2a5a7de8f79f4ff8eca80"),
          Map.entry(
              "V1007.1__world_restore_stable_legacy_update_fastpath.sql",
              "56efb5521ba235ce413caa8ed6ce54dad2c169064f9478e1d31bf1dd869743ed"),
          Map.entry(
              "V1009.1__world_restore_stable_legacy_update_fastpath.sql",
              "f69c64e8ed47714ccf2ac5548562152f5ee5f64a8b1f0ffaf68c6c5d49f20dad"));

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

  private Context controlledPeerContext;
  private Context previousPeerContext;

  @BeforeEach
  void attachControlledGsContext() {
    // Stipulated certificate/principal data is not an actual TLS handshake or producer proof.
    GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
    when(certificate.leafSha256()).thenReturn("a".repeat(64));
    controlledPeerContext =
        Context.ROOT
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/dev/sa/game-session-service",
                    "dev",
                    "game-session-service"))
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
    previousPeerContext = controlledPeerContext.attach();
  }

  @AfterEach
  void detachControlledGsContext() {
    controlledPeerContext.detach(previousPeerContext);
  }

  @Test
  void freshDefaultHistoryRetainsLiteralProtectedArtifactsAndNumericFrontier() throws Exception {
    Path root = repositoryRoot();
    Path world = root.resolve("services/world-management-service/src/main/resources/db/migration");
    Path saga = root.resolve("services/common-saga/src/main/resources/db/migration/saga");
    for (var artifacts : List.of(PARENT_MIGRATIONS, PROTECTED_ARTIFACTS))
      for (var artifact : artifacts.entrySet()) {
        Path file = world.resolve(artifact.getKey());
        if (!Files.exists(file)) file = saga.resolve(artifact.getKey());
        assertThat(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))))
            .as("literal migration %s", artifact.getKey())
            .isEqualTo(artifact.getValue());
      }
    var configured =
        migration(dataSource("world_management_service"), "world_management_service", null);
    assertThat(configured.getConfiguration().isOutOfOrder()).isFalse();
    configured.validate();
    int resolvedVersionedCount = resolvedVersionedMigrationCount(configured);
    assertThat(versions(dsl)).hasSize(resolvedVersionedCount);
    assertThat(versions(dsl).subList(34, 40))
        .containsExactly("1003", "1004", "1005", "1006", "1007", "1007.1");
    assertThat(versions(dsl).subList(40, resolvedVersionedCount))
        .containsExactlyElementsOf(remainingVersions());
  }

  @Test
  void packageOwnerRejectsMissingWrongNamespaceServiceAndLeafBeforeUpstreamOrSqlEffects() {
    var evidence = controlledEvidence();
    var upstreamReads = new AtomicInteger();
    var owner = nativeService(evidence, upstreamReads);
    var original = allRows(dsl);
    List<Context> denied = new ArrayList<>();
    denied.add(Context.ROOT);
    for (var selector :
        List.of(
            List.of(
                "spiffe://firemud/ns/dev/sa/entity-management-service",
                "dev",
                "entity-management-service",
                "a".repeat(64)),
            List.of(
                "spiffe://firemud/ns/other/sa/game-session-service",
                "other",
                "game-session-service",
                "a".repeat(64)),
            List.of(
                "spiffe://firemud/ns/dev/sa/game-session-service",
                "dev",
                "game-session-service",
                "d".repeat(64)))) {
      var certificate = mock(GrpcPeerCertificateEvidence.class);
      when(certificate.leafSha256()).thenReturn(selector.get(3));
      denied.add(
          Context.ROOT
              .withValue(
                  GrpcPeerIdentity.CONTEXT_KEY,
                  new GrpcPeerIdentity(selector.get(0), selector.get(1), selector.get(2)))
              .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate));
    }
    for (Context context : denied) {
      var previous = context.attach();
      try {
        assertThatThrownBy(() -> RunOwnedWorldFixtureLifecycleTestFactory.prepare(owner, evidence))
            .isInstanceOf(SecurityException.class);
      } finally {
        context.detach(previous);
      }
    }
    assertThat(upstreamReads.get()).isZero();
    assertThat(allRows(dsl)).isEqualTo(original);
  }

  @Test
  void packageOwnerPreparationRestartAndFreshObservationRetainAllRowsAndXmin() {
    var evidence = controlledEvidence();
    var prepared =
        RunOwnedWorldFixtureLifecycleTestFactory.prepare(nativeService(evidence), evidence);
    assertThat(prepared.status()).isEqualTo("PREPARING");
    assertThat(prepared.lifecycleEpoch()).isEqualTo(1L);
    var gameSession = evidence.gameSessionAssociation();
    var association =
        associationRepository()
            .readRunOwnedFixtureAssociation(
                evidence, RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence));
    var worldPrivateKeys = association.worldPrivateKeys();
    assertThat(prepared.tenantId()).isEqualTo(gameSession.gameSessionTenantId());
    assertThat(prepared.gameInstanceId()).isEqualTo(gameSession.gameInstanceId());
    assertThat(prepared.versionId()).isEqualTo(evidence.launchDescriptor().versionId());
    assertThat(prepared.tenantId()).isNotEqualTo(worldPrivateKeys.tenantId());
    assertThat(prepared.gameInstanceId()).isNotEqualTo(worldPrivateKeys.gameInstanceId());
    assertThat(prepared.versionId()).isNotEqualTo(worldPrivateKeys.versionId());
    var storedWorldKeys =
        dsl.fetchSingle(
            "SELECT tenant_id, game_instance_id, version_id FROM world_instance WHERE id = ?",
            association.worldInstanceId());
    assertThat(storedWorldKeys.get("tenant_id", Long.class)).isEqualTo(worldPrivateKeys.tenantId());
    assertThat(storedWorldKeys.get("game_instance_id", Long.class))
        .isEqualTo(worldPrivateKeys.gameInstanceId());
    assertThat(storedWorldKeys.get("version_id", Long.class))
        .isEqualTo(worldPrivateKeys.versionId());
    assertThat(
            scalarLong(
                "SELECT row_version FROM world_instance WHERE id = ?",
                association.worldInstanceId()))
        .isZero();
    assertPresetContent(association);
    var original = allRows(dsl);
    var fresh = RunOwnedWorldFixtureEvidenceTestFixtures.freshObservation(evidence);
    var replay = RunOwnedWorldFixtureLifecycleTestFactory.prepare(nativeService(fresh), fresh);
    assertThat(replay).isEqualTo(prepared);
    assertThat(replay.tenantId()).isEqualTo(gameSession.gameSessionTenantId());
    assertThat(replay.gameInstanceId()).isEqualTo(gameSession.gameInstanceId());
    assertThat(replay.versionId()).isEqualTo(evidence.launchDescriptor().versionId());
    assertThat(
            dsl.fetchSingle(
                    "SELECT tenant_id, game_instance_id, version_id FROM world_instance WHERE id = ?",
                    association.worldInstanceId())
                .intoMap())
        .containsEntry("tenant_id", worldPrivateKeys.tenantId())
        .containsEntry("game_instance_id", worldPrivateKeys.gameInstanceId())
        .containsEntry("version_id", worldPrivateKeys.versionId());
    assertThat(allRows(dsl)).isEqualTo(original);
    var changed = RunOwnedWorldFixtureEvidenceTestFixtures.changedReceiptBytes(evidence);
    assertThatThrownBy(
            () -> RunOwnedWorldFixtureLifecycleTestFactory.prepare(nativeService(changed), changed))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.RegistrationConflictException.class);
    var second = RunOwnedWorldFixtureEvidenceTestFixtures.secondAssignment(evidence);
    assertThatThrownBy(
            () -> RunOwnedWorldFixtureLifecycleTestFactory.prepare(nativeService(second), second))
        .isInstanceOf(
            WorldCanonicalInstanceAssociationRepository.RegistrationConflictException.class);
    assertThat(allRows(dsl)).isEqualTo(original);
    assertNoPendingManifests(association.worldInstanceId());
  }

  @Test
  void fixtureAssociationRejectsEveryWorldCopyMismatchThroughTheOwnerTransaction() {
    var retainedEvidence = controlledEvidence();
    RunOwnedWorldFixtureLifecycleTestFactory.prepare(
        nativeService(retainedEvidence), retainedEvidence);

    var evidence = controlledEvidence();
    var sourceRepository = new WorldAuthoredSourceIntakeRepository(dsl);
    var versionRepository = new WorldAuthoredVersionIdentityRepository(dsl);
    var source =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status ->
                        sourceRepository.acceptFresh(
                            "dev", UUID.randomUUID(), evidence.sourceReceipt().sourceEvidence())));
    var state =
        AuthoredWorldVersionStateEvidence.create(
            new AuthoredWorldVersionStateEvidence.Request(
                1,
                "dev",
                UUID.randomUUID(),
                source.canonicalTenantId(),
                source.worldSlug(),
                source.sourceOperationId(),
                source.sourceEvidenceDigest(),
                evidence.launchDescriptor().versionId()),
            source.source(),
            evidence.gameSessionAssociation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            evidence.launchDescriptor().versionStateEpoch());
    var version =
        Objects.requireNonNull(
            ownerTransaction().execute(status -> versionRepository.acceptFresh(source, state)));
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
    var associationRepository = associationRepository();
    var fenceRepository = new WorldDesignPublicationFenceRepository(dsl, sourceRepository);

    String schema = dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String functionName = "world_test_fixture_copy_mutation_" + suffix;
    String triggerName = "trg_world_canonical_instance_association_01_test_copy_" + suffix;
    String function = DSL.name(schema, functionName).toString();
    String associationTable = DSL.name(schema, "world_canonical_instance_association").toString();
    String createFunction =
        """
        CREATE FUNCTION __test_function__() RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $test$
        DECLARE
            mismatch_field TEXT := current_setting('world.fixture_copy_mismatch_field', TRUE);
        BEGIN
            IF TG_OP = 'INSERT' AND NEW.schema_version = 3
                AND NEW.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
                AND mismatch_field IS NOT NULL THEN
                CASE mismatch_field
                    WHEN 'canonical_realm_id' THEN NEW.canonical_realm_id := pg_catalog.gen_random_uuid();
                    WHEN 'catalog_revision' THEN NEW.catalog_revision := NEW.catalog_revision + 1;
                    WHEN 'playtest_lifecycle_id' THEN NEW.playtest_lifecycle_id := pg_catalog.gen_random_uuid();
                    WHEN 'playtest_state_generation' THEN NEW.playtest_state_generation := COALESCE(NEW.playtest_state_generation, 0) + 1;
                    WHEN 'script_patch_version' THEN NEW.script_patch_version := COALESCE(NEW.script_patch_version, 'fixture-copy-mismatch') || '-mutated';
                    WHEN 'runtime_flags_json' THEN NEW.runtime_flags_json := NEW.runtime_flags_json || ' ';
                    WHEN 'generation_config_revision' THEN NEW.generation_config_revision := NEW.generation_config_revision || '-mutated';
                    WHEN 'release_bundle_id' THEN NEW.release_bundle_id := NEW.release_bundle_id + 1;
                    WHEN 'published_release_bundle_ref' THEN NEW.published_release_bundle_ref := NEW.published_release_bundle_ref || '-mutated';
                    WHEN 'remap_set_id' THEN NEW.remap_set_id := COALESCE(NEW.remap_set_id, 'fixture-copy-mismatch') || '-mutated';
                    ELSE RAISE EXCEPTION 'Unknown fixture copy mutation field';
                END CASE;
            END IF;
            RETURN NEW;
        END;
        $test$
        """
            .replace("__test_function__", function);
    String trigger =
        "CREATE TRIGGER "
            + DSL.name(triggerName)
            + " BEFORE INSERT ON "
            + associationTable
            + " FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()";

    try {
      dsl.execute(createFunction);
      dsl.execute(trigger);
      var beforeInsertTriggers =
          dsl.fetch(
                  "SELECT tgname FROM pg_trigger WHERE tgrelid = ?::REGCLASS "
                      + "AND NOT tgisinternal AND (tgtype & 7) = 7 ORDER BY tgname",
                  associationTable)
              .getValues(0, String.class);
      String executionGuard = "trg_world_canonical_instance_association_00_execution";
      String ownerCopyValidator = "trg_world_canonical_instance_association_exact_insert";
      assertThat(beforeInsertTriggers)
          .as("real manifest guard, injected row mutation, then owner-copy validator")
          .contains(executionGuard, triggerName, ownerCopyValidator);
      assertThat(beforeInsertTriggers.indexOf(executionGuard))
          .isLessThan(beforeInsertTriggers.indexOf(triggerName));
      assertThat(beforeInsertTriggers.indexOf(triggerName))
          .isLessThan(beforeInsertTriggers.indexOf(ownerCopyValidator));

      var originalRows = allRows(dsl);
      for (String field :
          List.of(
              "canonical_realm_id",
              "catalog_revision",
              "playtest_lifecycle_id",
              "playtest_state_generation",
              "script_patch_version",
              "runtime_flags_json",
              "generation_config_revision",
              "release_bundle_id",
              "published_release_bundle_ref",
              "remap_set_id")) {
        assertSqlState(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status -> {
                          dsl.fetchOne(
                              "SELECT set_config('world.fixture_copy_mismatch_field', ?, TRUE)",
                              field);
                          fenceRepository.lockOpen(binding(source, version));
                          associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
                              evidence, request, source, version);
                        }),
            "23514",
            "Run-owned fixture association differs from its exact World, source, Version, or GS owner readback");
        assertThat(allRows(dsl))
            .as("original rows and xmin after %s owner-copy mismatch", field)
            .isEqualTo(originalRows);
      }
    } finally {
      dsl.execute("DROP TRIGGER IF EXISTS " + DSL.name(triggerName) + " ON " + associationTable);
      dsl.execute("DROP FUNCTION IF EXISTS " + function + "()");
    }
  }

  @Test
  void racingActualPackageOwnersConvergeOneOriginalAllocation() throws Exception {
    var evidence = controlledEvidence();
    var firstOwner = nativeService(evidence);
    var secondOwner = nativeService(evidence);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldInstanceLifecycleSnapshotDto> first =
          executor.submit(
              () ->
                  withControlledPeer(
                      () -> {
                        ready.countDown();
                        await(release);
                        return RunOwnedWorldFixtureLifecycleTestFactory.prepare(
                            firstOwner, evidence);
                      }));
      Future<WorldInstanceLifecycleSnapshotDto> second =
          executor.submit(
              () ->
                  withControlledPeer(
                      () -> {
                        ready.countDown();
                        await(release);
                        return RunOwnedWorldFixtureLifecycleTestFactory.prepare(
                            secondOwner, evidence);
                      }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_canonical_instance_association WHERE control_plane_request_id = ?",
                evidence.capability().operationId()))
        .isEqualTo(1L);
    var association =
        associationRepository()
            .readRunOwnedFixtureAssociation(
                evidence, RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence));
    assertPresetContent(association);
    assertNoPendingManifests(association.worldInstanceId());
  }

  @Test
  void actualOwnerRollbackLeavesNoAllocationTopologyResultOrManifest() {
    var evidence = controlledEvidence();
    var sourceOwner = new WorldAuthoredSourceIntakeRepository(dsl);
    var versionOwner = new WorldAuthoredVersionIdentityRepository(dsl);
    var intake =
        ownerTransaction()
            .execute(
                status ->
                    sourceOwner.acceptFresh(
                        "dev", UUID.randomUUID(), evidence.sourceReceipt().sourceEvidence()));
    var state =
        AuthoredWorldVersionStateEvidence.create(
            new AuthoredWorldVersionStateEvidence.Request(
                1,
                "dev",
                UUID.randomUUID(),
                intake.canonicalTenantId(),
                intake.worldSlug(),
                intake.sourceOperationId(),
                intake.sourceEvidenceDigest(),
                evidence.launchDescriptor().versionId()),
            intake.source(),
            evidence.gameSessionAssociation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            evidence.launchDescriptor().versionStateEpoch());
    var version = ownerTransaction().execute(status -> versionOwner.acceptFresh(intake, state));
    var original = allRows(dsl);
    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .executeWithoutResult(
                        status -> {
                          new WorldDesignPublicationFenceRepository(dsl, sourceOwner)
                              .lockOpen(binding(intake, version));
                          var allocated =
                              associationRepository()
                                  .allocateRunOwnedFixtureInOwnerTransaction(
                                      evidence,
                                      RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence),
                                      intake,
                                      version);
                          RunOwnedWorldFixedPresetResources.materialize(
                              dsl,
                              evidence,
                              RunOwnedWorldFixedPresetResources.plan(allocated, intake, version),
                              allocated.worldInstanceId(),
                              allocated.worldPrivateKeys().gameInstanceId());
                          throw new IllegalStateException(
                              "controlled failure after actual topology writes");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure after actual topology writes");
    assertThat(allRows(dsl)).isEqualTo(original);
    // PostgreSQL sequences are nontransactional; this compares all rows/xmin and manifests.
  }

  @Test
  void actualFixtureMaterializationRetainsUnderOwnerSavepoint() {
    var evidence = controlledEvidence();
    var sourceOwner = new WorldAuthoredSourceIntakeRepository(dsl);
    var versionOwner = new WorldAuthoredVersionIdentityRepository(dsl);
    var intake =
        ownerTransaction()
            .execute(
                status ->
                    sourceOwner.acceptFresh(
                        "dev", UUID.randomUUID(), evidence.sourceReceipt().sourceEvidence()));
    var state =
        AuthoredWorldVersionStateEvidence.create(
            new AuthoredWorldVersionStateEvidence.Request(
                1,
                "dev",
                UUID.randomUUID(),
                intake.canonicalTenantId(),
                intake.worldSlug(),
                intake.sourceOperationId(),
                intake.sourceEvidenceDigest(),
                evidence.launchDescriptor().versionId()),
            intake.source(),
            evidence.gameSessionAssociation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            evidence.launchDescriptor().versionStateEpoch());
    var version = ownerTransaction().execute(status -> versionOwner.acceptFresh(intake, state));
    var fixtureAssociations =
        new WorldCanonicalInstanceAssociationRepository(
            dsl, new WorldCompleteLaunchBindingRepository(dsl), sourceOwner, versionOwner);
    var fixtureFence = new WorldDesignPublicationFenceRepository(dsl, sourceOwner);
    var fixtureRequest = RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence);
    var allocatedFixture =
        ownerTransaction()
            .execute(
                status -> {
                  fixtureFence.lockOpen(binding(intake, version));
                  return fixtureAssociations.allocateRunOwnedFixtureInOwnerTransaction(
                      evidence, fixtureRequest, intake, version);
                });
    var fixtureAssociation =
        fixtureAssociations.readRunOwnedFixtureAssociation(evidence, fixtureRequest);
    var fixturePlan = RunOwnedWorldFixedPresetResources.plan(fixtureAssociation, intake, version);
    var savepoint = new TransactionTemplate(transactionManager);
    savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    savepoint.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    var materialized =
        ownerTransaction()
            .execute(
                status -> {
                  var result =
                      savepoint.execute(
                          nestedStatus ->
                              RunOwnedWorldFixedPresetResources.materialize(
                                  dsl,
                                  evidence,
                                  fixturePlan,
                                  allocatedFixture.worldInstanceId(),
                                  allocatedFixture.worldPrivateKeys().gameInstanceId()));
                  var preparationStamp =
                      dsl.fetchSingle(
                          "SELECT p.preparation_inserted_full_xid::text, p.xmin::text, "
                              + "pg_current_xact_id()::text, "
                              + "(txid_current() % 4294967296)::text "
                              + "FROM world_canonical_instance_preparation p "
                              + "WHERE p.world_instance_id = ?",
                          allocatedFixture.worldInstanceId());
                  assertThat(preparationStamp.get(0, String.class))
                      .as("the BEFORE INSERT server stamp records the top-level full XID")
                      .isEqualTo(preparationStamp.get(2, String.class));
                  assertThat(preparationStamp.get(1, String.class))
                      .as("the row inserted beneath the owner savepoint keeps its child xmin")
                      .isNotEqualTo(preparationStamp.get(3, String.class));
                  return result;
                });
    assertThat(materialized).isNotNull();
    assertThat(materialized.regionCount()).isEqualTo(1);
    assertThat(materialized.zoneCount()).isEqualTo(1);
    assertThat(materialized.roomCount()).isEqualTo(2);
    assertThat(materialized.exitCount()).isEqualTo(2);
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_canonical_instance_preparation "
                    + "WHERE world_instance_id = ?",
                allocatedFixture.worldInstanceId()))
        .isEqualTo(1L);
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_canonical_instance_preparation_execution_manifest "
                    + "WHERE fixture_world_instance_id = ?",
                allocatedFixture.worldInstanceId()))
        .isZero();
    assertNoPendingManifests(allocatedFixture.worldInstanceId());
  }

  @Test
  void fixturePreparingCannotActivateAcquireHoldsPlaceActorsOrReuseConsumedManifests()
      throws Exception {
    var evidence = controlledEvidence();
    RunOwnedWorldFixtureLifecycleTestFactory.prepare(nativeService(evidence), evidence);
    var association =
        associationRepository()
            .readRunOwnedFixtureAssociation(
                evidence, RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence));
    long id = association.worldInstanceId();
    var original = allRows(dsl);
    for (String status : List.of("ACTIVE", "FAILED_PRE_ACTIVATION", "TERMINATING"))
      assertSqlState(
          () ->
              dsl.execute(
                  "UPDATE world_instance SET status = ?, lifecycle_epoch = 2, row_version = 1 WHERE id = ?",
                  status,
                  id),
          "23514");
    assertSqlState(
        () ->
            dsl.execute(
                "INSERT INTO world_instance SELECT (jsonb_populate_record(NULL::world_instance, to_jsonb(w) || jsonb_build_object('id', ?, 'status', 'ACTIVE'))).* FROM world_instance w WHERE id = ?",
                positiveLong(),
                id),
        "23514");
    assertThatThrownBy(
            () ->
                associationRepository()
                    .activateRunOwnedFixtureInOwnerTransaction(association, 1L, 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("RUN_OWNED_WORLD_ACTIVATION_UNAVAILABLE:");
    assertThat(
            dsl.fetchSingle(
                    "SELECT to_regprocedure(?)",
                    dsl.fetchSingle("SELECT current_schema()").get(0, String.class)
                        + ".world_activate_run_owned_fixture_instance(bigint,bigint,bigint)")
                .get(0))
        .isNull();
    assertThat(
            ownerTransaction()
                .<Boolean>execute(
                    status ->
                        dsl.fetchSingle(
                                "SELECT world_consume_canonical_prepare_execution_manifest('world_instance','INSERT',NULL,to_jsonb(w)) FROM world_instance w WHERE id = ?",
                                id)
                            .get(0, Boolean.class)))
        .isFalse();
    assertThat(
            ownerTransaction()
                .<Boolean>execute(
                    status ->
                        dsl.fetchSingle(
                                "SELECT world_consume_region_execution_manifest('region','INSERT',NULL,to_jsonb(r)) FROM region r WHERE tenant_id = ? AND version_id = ?",
                                association.worldPrivateKeys().tenantId(),
                                association.worldPrivateKeys().versionId())
                            .get(0, Boolean.class)))
        .isFalse();
    for (String table :
        List.of(
            "world_canonical_initial_player_location_operation",
            "character_location",
            "world_canonical_player_admission_hold"))
      for (boolean omitCanonical : List.of(false, true)) {
        Map<String, Object> selectors = new LinkedHashMap<>();
        selectors.put("world_instance_id", omitCanonical ? id : null);
        selectors.put(
            "canonical_game_instance_id",
            omitCanonical ? null : association.identity().canonicalGameInstanceId().toString());
        assertSqlState(
            () ->
                dsl.execute(
                    "INSERT INTO "
                        + DSL.name(table)
                        + " SELECT (jsonb_populate_record(NULL::"
                        + DSL.name(table)
                        + ", ?::jsonb)).*",
                    json(selectors)),
            "23514",
            "disabled");
      }
    for (boolean omitCanonical : List.of(false, true)) {
      Map<String, Object> selectors = new LinkedHashMap<>();
      selectors.put(
          "canonical_game_instance_id",
          omitCanonical ? null : association.identity().canonicalGameInstanceId().toString());
      selectors.put("tenant_id", omitCanonical ? association.worldPrivateKeys().tenantId() : null);
      selectors.put(
          "game_instance_id",
          omitCanonical ? association.worldPrivateKeys().gameInstanceId() : null);
      selectors.put(
          "version_id", omitCanonical ? association.worldPrivateKeys().versionId() : null);
      assertSqlState(
          () ->
              dsl.execute(
                  "INSERT INTO initial_admission_bind_hold SELECT (jsonb_populate_record(NULL::initial_admission_bind_hold, ?::jsonb)).*",
                  json(selectors)),
          "23514",
          "disabled");
    }
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.fetch(
                            "SELECT world_finalize_canonical_initial_admission_hold(NULL::uuid,NULL::uuid,'COMMITTED',NULL::text,NULL::text,NULL::text,NULL::bigint,NULL::timestamptz,false,NULL::bytea,NULL::text)")),
        "23514",
        "terminal proof is incomplete");
    // Invalid controlled bytes with a correct byte digest only pass the preliminary digest
    // check. They are not owner proof or permission to create a fixture hold; NULL selectors
    // must then fail before any candidate, World row or hold can be locked or changed.
    byte[] invalidControlledProbe = "{}".getBytes(StandardCharsets.UTF_8);
    String invalidControlledProbeDigest =
        "sha256:"
            + HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(invalidControlledProbe));
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.fetch(
                            "SELECT world_finalize_canonical_initial_admission_hold(NULL::uuid,NULL::uuid,'COMMITTED',NULL::text,NULL::text,NULL::text,NULL::bigint,TIMESTAMPTZ '2026-10-14T00:00:00Z',false,?::bytea,?::text)",
                            invalidControlledProbe,
                            invalidControlledProbeDigest)),
        "55000",
        "hold identity is missing or untyped");
    assertThat(allRows(dsl)).isEqualTo(original);
    assertNoPendingManifests(id);
    // First OPEN is GS-owned; no manufactured World table, hold or terminal is introduced.
  }

  @Test
  void installed34ThroughParent40ToDefaultFrontierRetainsRowsXminAndStableLegacyUpdates()
      throws Exception {
    String schema = "world_fixture_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var source = dataSource(schema);
    var connection = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      var installed = migration(source, schema, "1002");
      assertThat(installed.getConfiguration().isOutOfOrder()).isFalse();
      installed.migrate();
      installed.validate();
      assertThat(versions(connection)).hasSize(34);
      assertThat(
              connection
                  .fetch("SELECT script FROM " + DSL.name(HISTORY) + " WHERE version IS NOT NULL")
                  .getValues(0, String.class))
          .containsExactlyInAnyOrderElementsOf(PARENT_MIGRATIONS.keySet());
      long worldId =
          connection
              .fetchSingle(
                  "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, control_plane_request_id, launch_descriptor_id, version_id, runtime_flags_json, generation_config_revision, release_bundle_id, published_release_bundle_ref, version_state_epoch, status) VALUES (7001,8101,17,'retained-parent-world','retained-parent-descriptor',11,'{}','parent-generation',77,'retained-parent-release',3,'PREPARING') RETURNING id")
              .get(0, Long.class);
      long firstRegion =
          connection
              .fetchSingle(
                  "INSERT INTO region_instance (tenant_id, game_instance_id, world_instance_id, name, generation_seed) VALUES (7001,8101,?,'Retained parent region',42) RETURNING id",
                  worldId)
              .get(0, Long.class);
      long secondRegion =
          connection
              .fetchSingle(
                  "INSERT INTO region_instance (tenant_id, game_instance_id, world_instance_id, name, generation_seed) VALUES (7001,8101,?,'Independent parent region',43) RETURNING id",
                  worldId)
              .get(0, Long.class);
      long sagaId =
          connection
              .fetchSingle(
                  "INSERT INTO saga_instance (saga_name,state) VALUES ('retained-parent-saga','PENDING') RETURNING id")
              .get(0, Long.class);
      connection.execute(
          "INSERT INTO saga_step (instance_id,name,status,attempt) VALUES (?,'retained-parent-step','PENDING',2)",
          sagaId);
      var write = new TransactionTemplate(new DataSourceTransactionManager(source));
      write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var evidence = controlledEvidence();
      var sourceOwner = new WorldAuthoredSourceIntakeRepository(connection);
      var versionOwner = new WorldAuthoredVersionIdentityRepository(connection);
      UUID intakeRequest = UUID.randomUUID();
      var intake =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      sourceOwner.acceptFresh(
                          "dev", intakeRequest, evidence.sourceReceipt().sourceEvidence())));
      var state =
          AuthoredWorldVersionStateEvidence.create(
              new AuthoredWorldVersionStateEvidence.Request(
                  1,
                  "dev",
                  UUID.randomUUID(),
                  intake.canonicalTenantId(),
                  intake.worldSlug(),
                  intake.sourceOperationId(),
                  intake.sourceEvidenceDigest(),
                  evidence.launchDescriptor().versionId()),
              intake.source(),
              evidence.gameSessionAssociation().canonicalVersionId(),
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
              evidence.launchDescriptor().versionStateEpoch());
      var version =
          Objects.requireNonNull(write.execute(status -> versionOwner.acceptFresh(intake, state)));
      Map<String, List<String>> originalColumns = new LinkedHashMap<>();
      Map<String, List<String>> originalRows = new LinkedHashMap<>();
      for (String table :
          List.of(
              "world_authored_source_tenant_association",
              "world_authored_source_tenant_key_reservation",
              "world_authored_source_intake",
              "world_authored_version_identity",
              "world_instance",
              "region_instance",
              "saga_instance",
              "saga_step")) {
        var columns =
            connection
                .fetch(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                    schema,
                    table)
                .getValues(0, String.class);
        assertThat(columns).isNotEmpty();
        originalColumns.put(table, columns);
        originalRows.put(table, retainedRows(connection, schema, table, columns));
        assertThat(originalRows.get(table)).isNotEmpty();
      }
      var installedHistory = history(connection);
      var parent = migration(source, schema, "1007.1");
      parent.migrate();
      parent.validate();
      assertThat(versions(connection)).hasSize(40);
      assertThat(versions(connection).subList(34, 40))
          .containsExactly("1003", "1004", "1005", "1006", "1007", "1007.1");
      assertThat(history(connection).subList(0, 34)).isEqualTo(installedHistory);
      for (var entry : originalColumns.entrySet())
        assertThat(retainedRows(connection, schema, entry.getKey(), entry.getValue()))
            .isEqualTo(originalRows.get(entry.getKey()));
      var parentHistory = history(connection);
      var preparing = migration(source, schema, "1041");
      preparing.migrate();
      preparing.validate();
      assertThat(preparing.getConfiguration().isOutOfOrder()).isFalse();
      assertThat(versions(connection)).hasSize(75);
      assertThat(versions(connection).subList(40, 75))
          .containsExactlyElementsOf(remainingVersionsThrough(1041));
      assertThat(history(connection).subList(0, 40)).isEqualTo(parentHistory);
      for (var entry : originalColumns.entrySet())
        assertThat(retainedRows(connection, schema, entry.getKey(), entry.getValue()))
            .isEqualTo(originalRows.get(entry.getKey()));

      // Create and materialize a controlled schema-3 fixture through the existing World owners at
      // the V1041 checkpoint, then preserve that real fixture while advancing to V1042.8.
      var fixtureAssociations =
          new WorldCanonicalInstanceAssociationRepository(
              connection,
              new WorldCompleteLaunchBindingRepository(connection),
              sourceOwner,
              versionOwner);
      var fixtureFence = new WorldDesignPublicationFenceRepository(connection, sourceOwner);
      var gsAssociation = evidence.gameSessionAssociation();
      var descriptor = evidence.launchDescriptor();
      var fixtureRequest =
          new PreparedWorldInstanceRequest(
              gsAssociation.gameSessionTenantId(),
              gsAssociation.gameInstanceId(),
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
      WorldCanonicalInstanceAssociation allocatedFixture =
          write.execute(
              status -> {
                fixtureFence.lockOpen(binding(intake, version));
                return fixtureAssociations.allocateRunOwnedFixtureInOwnerTransaction(
                    evidence, fixtureRequest, intake, version);
              });
      assertThat(allocatedFixture).isNotNull();
      var fixtureAssociation =
          fixtureAssociations.readRunOwnedFixtureAssociation(evidence, fixtureRequest);
      var fixturePlan = RunOwnedWorldFixedPresetResources.plan(fixtureAssociation, intake, version);
      write.executeWithoutResult(
          status ->
              RunOwnedWorldFixedPresetResources.materialize(
                  connection,
                  evidence,
                  fixturePlan,
                  fixtureAssociation.worldInstanceId(),
                  fixtureAssociation.worldPrivateKeys().gameInstanceId()));
      var preparedFixture =
          connection.fetchSingle(
              "SELECT status, lifecycle_epoch, row_version FROM world_instance WHERE id = ?",
              fixtureAssociation.worldInstanceId());
      assertThat(preparedFixture.get(0, String.class)).isEqualTo("PREPARING");
      assertThat(preparedFixture.get(1, Long.class)).isEqualTo(1L);
      assertThat(preparedFixture.get(2, Long.class)).isZero();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM world_canonical_instance_association WHERE world_instance_id = ? AND schema_version = 3 AND canonical_authority_kind = 'RUN_OWNED_FIXTURE'",
                      fixtureAssociation.worldInstanceId())
                  .get(0, Long.class))
          .isEqualTo(1L);
      String retainedPreparationAt10428 =
          connection
              .fetchSingle(
                  "SELECT (to_jsonb(p) - 'preparation_inserted_full_xid')::text "
                      + "|| ':xmin=' || p.xmin::text "
                      + "FROM world_canonical_instance_preparation p WHERE p.world_instance_id = ?",
                  fixtureAssociation.worldInstanceId())
              .get(0, String.class);

      var through10428 = migration(source, schema, "1042.8");
      through10428.migrate();
      through10428.validate();
      assertThat(through10428.getConfiguration().isOutOfOrder()).isFalse();
      assertThat(versions(connection)).contains("1042.8").doesNotContain("1042.9");
      String preservedPublicationRequestId = "pre-v1042-9-null-stamp";
      var preservedWorkflow =
          PublicationDigestRequestBinding.full(
                  intake.canonicalTenantId().toString(),
                  Long.toString(version.gameDesignVersionId()),
                  preservedPublicationRequestId)
              .derivedWorkflowIdentity();
      UUID preservedPublicationFence = UUID.randomUUID();
      connection.execute(
          "INSERT INTO world_design_publication_fence_attempt "
              + "(publication_fence, target_namespace, canonical_tenant_id, local_tenant_key, "
              + "version_id, intake_operation_id, intake_request_id, source_operation_id, "
              + "source_evidence_digest, intake_receipt_digest, publication_request_id, "
              + "request_digest, version_state_epoch, publish_workflow_id, applied_commit_id, "
              + "content_digest, digest_schema_version, owner_binding_schema_version, "
              + "canonical_version_id, version_identity_operation_id, game_design_version_id, "
              + "intake_request_digest, attempt_created_full_xid) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, 1, ?, ?, ?, ?, NULL)",
          preservedPublicationFence,
          intake.targetNamespace(),
          intake.canonicalTenantId(),
          intake.localTenantKey(),
          version.localVersionKey(),
          intake.operationId(),
          intake.intakeRequestId(),
          intake.sourceOperationId(),
          intake.sourceEvidenceDigest(),
          intake.receiptDigest(),
          preservedPublicationRequestId,
          "a".repeat(64),
          evidence.launchDescriptor().versionStateEpoch(),
          preservedWorkflow,
          "retained-null-stamp-commit",
          "b".repeat(64),
          version.canonicalVersionId(),
          version.operationId(),
          version.gameDesignVersionId(),
          intake.requestDigest());
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT attempt_created_full_xid::text FROM world_design_publication_fence_attempt "
                          + "WHERE publication_fence = ?",
                      preservedPublicationFence)
                  .get(0, String.class))
          .isNull();
      String preservedForgedRequestId = "pre-v1042-9-forged-stamp";
      var preservedForgedWorkflow =
          PublicationDigestRequestBinding.full(
                  intake.canonicalTenantId().toString(),
                  Long.toString(version.gameDesignVersionId()),
                  preservedForgedRequestId)
              .derivedWorkflowIdentity();
      UUID preservedForgedPublicationFence = UUID.randomUUID();
      connection.execute(
          "INSERT INTO world_design_publication_fence_attempt "
              + "(publication_fence, target_namespace, canonical_tenant_id, local_tenant_key, "
              + "version_id, intake_operation_id, intake_request_id, source_operation_id, "
              + "source_evidence_digest, intake_receipt_digest, publication_request_id, "
              + "request_digest, version_state_epoch, publish_workflow_id, applied_commit_id, "
              + "content_digest, digest_schema_version, owner_binding_schema_version, "
              + "canonical_version_id, version_identity_operation_id, game_design_version_id, "
              + "intake_request_digest, attempt_created_full_xid) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, 1, ?, ?, ?, ?, ?::xid8)",
          preservedForgedPublicationFence,
          intake.targetNamespace(),
          intake.canonicalTenantId(),
          intake.localTenantKey(),
          version.localVersionKey(),
          intake.operationId(),
          intake.intakeRequestId(),
          intake.sourceOperationId(),
          intake.sourceEvidenceDigest(),
          intake.receiptDigest(),
          preservedForgedRequestId,
          "c".repeat(64),
          evidence.launchDescriptor().versionStateEpoch(),
          preservedForgedWorkflow,
          "retained-forged-stamp-commit",
          "d".repeat(64),
          version.canonicalVersionId(),
          version.operationId(),
          version.gameDesignVersionId(),
          intake.requestDigest(),
          "9223372036854775807");
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT attempt_created_full_xid::text FROM world_design_publication_fence_attempt "
                          + "WHERE publication_fence = ?",
                      preservedForgedPublicationFence)
                  .get(0, String.class))
          .isEqualTo("9223372036854775807");
      int versionedCountAt10428 = versions(connection).size();
      var retainedRowsAt10428 = new LinkedHashMap<>(allRows(connection));
      retainedRowsAt10428.remove(HISTORY);
      var historyAt10428 = history(connection);
      Map<String, String> sameTransactionFunctionMetadata = new LinkedHashMap<>();
      for (String function :
          List.of(
              "world_validate_selected_publication_account_binding()",
              "world_validate_selected_publication_artifact_inventory()",
              "world_retain_preparation_start_location()",
              "world_guard_preparation_start_location()",
              "world_require_preparation_start_location(jsonb,jsonb,uuid)"))
        sameTransactionFunctionMetadata.put(
            function,
            connection
                .fetchSingle(
                    "SELECT jsonb_build_array(oid::text, proowner::text, prosecdef::text, "
                        + "proconfig::text, proacl::text)::text FROM pg_proc "
                        + "WHERE oid = to_regprocedure(?)",
                    schema + "." + function)
                .get(0, String.class));
      Map<String, String> sameTransactionTriggerMetadata = new LinkedHashMap<>();
      for (String trigger :
          List.of(
              "trg_world_publication_account_binding_validate",
              "trg_world_publication_account_binding_immutable",
              "trg_world_publication_account_binding_no_truncate",
              "trg_world_selected_publication_artifact_inventory_validate",
              "trg_world_selected_publication_artifact_inventory_immutable",
              "trg_world_selected_publication_artifact_inventory_no_truncate",
              "trg_world_preparation_retain_start_location",
              "trg_world_preparation_start_location_guard",
              "trg_world_preparation_start_location_no_truncate"))
        sameTransactionTriggerMetadata.put(
            trigger,
            connection
                .fetchSingle(
                    "SELECT jsonb_build_array(t.oid::text, t.tgfoid::text, t.tgtype::text, "
                        + "t.tgenabled::text, encode(t.tgargs, 'hex'), "
                        + "pg_get_expr(t.tgqual, t.tgrelid))::text "
                        + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                        + "WHERE c.relnamespace = to_regnamespace(?) AND t.tgname = ?",
                    schema,
                    trigger)
                .get(0, String.class));
      String guardProcedure = schema + ".world_guard_canonical_activation_transition()";
      var originalGuardIdentity =
          connection.fetchSingle(
              "SELECT oid::text, proowner::text, prosecdef, proconfig::text, proacl::text FROM pg_proc WHERE oid = to_regprocedure(?)",
              guardProcedure);
      var originalGuardTrigger =
          connection.fetchSingle(
              "SELECT t.oid::text, t.tgfoid::text, t.tgtype, t.tgenabled, t.tgargs::text, t.tgqual::text "
                  + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                  + "WHERE c.relnamespace = to_regnamespace(?) AND t.tgname = 'trg_world_canonical_activation_transition'",
              schema);
      var full = migration(source, schema, null);
      full.migrate();
      full.validate();
      assertThat(full.getConfiguration().isOutOfOrder()).isFalse();
      int resolvedVersionedCount = resolvedVersionedMigrationCount(full);
      assertThat(versions(connection)).hasSize(resolvedVersionedCount);
      assertThat(versions(connection).subList(40, resolvedVersionedCount))
          .containsExactlyElementsOf(remainingVersions());
      assertThat(history(connection).subList(0, 40)).isEqualTo(parentHistory);
      assertThat(history(connection).subList(0, versionedCountAt10428)).isEqualTo(historyAt10428);
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT attempt_created_full_xid::text FROM world_design_publication_fence_attempt "
                          + "WHERE publication_fence = ?",
                      preservedPublicationFence)
                  .get(0, String.class))
          .isNull();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT attempt_inserted_full_xid::text FROM world_design_publication_fence_attempt "
                          + "WHERE publication_fence = ?",
                      preservedPublicationFence)
                  .get(0, String.class))
          .isNull();
      assertThat(
              connection.fetchSingle(
                  "SELECT attempt_created_full_xid::text, attempt_inserted_full_xid::text "
                      + "FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
                  preservedForgedPublicationFence))
          .as(
              "the pre-1042.9 caller-forged stamp is retained while the new server stamp stays NULL")
          .satisfies(
              row -> {
                assertThat(row.get(0, String.class)).isEqualTo("9223372036854775807");
                assertThat(row.get(1, String.class)).isNull();
              });
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM world_design_publication_fence_attempt "
                          + "WHERE attempt_inserted_full_xid IS NOT NULL")
                  .get(0, Long.class))
          .isZero();
      for (var entry : sameTransactionFunctionMetadata.entrySet())
        assertThat(
                connection
                    .fetchSingle(
                        "SELECT jsonb_build_array(oid::text, proowner::text, prosecdef::text, "
                            + "proconfig::text, proacl::text)::text FROM pg_proc "
                            + "WHERE oid = to_regprocedure(?)",
                        schema + "." + entry.getKey())
                    .get(0, String.class))
            .as("function OID, owner, security, search_path and ACL for %s", entry.getKey())
            .isEqualTo(entry.getValue());
      for (var entry : sameTransactionTriggerMetadata.entrySet())
        assertThat(
                connection
                    .fetchSingle(
                        "SELECT jsonb_build_array(t.oid::text, t.tgfoid::text, t.tgtype::text, "
                            + "t.tgenabled::text, encode(t.tgargs, 'hex'), "
                            + "pg_get_expr(t.tgqual, t.tgrelid))::text "
                            + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                            + "WHERE c.relnamespace = to_regnamespace(?) AND t.tgname = ?",
                        schema,
                        entry.getKey())
                    .get(0, String.class))
            .as("existing trigger identity and behavior for %s", entry.getKey())
            .isEqualTo(entry.getValue());
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT pg_get_functiondef(to_regprocedure(?))",
                      schema + ".world_guard_preparation_start_location()")
                  .get(0, String.class))
          .contains("p.preparation_inserted_full_xid=pg_current_xact_id()")
          .doesNotContain("p.xmin::TEXT");
      var preparationStampFunction =
          connection.fetchSingle(
              "SELECT proowner::text, prosecdef, proconfig::text FROM pg_proc "
                  + "WHERE oid = to_regprocedure(?)",
              schema + ".world_stamp_preparation_insert_full_xid()");
      assertThat(preparationStampFunction.get("prosecdef", Boolean.class)).isTrue();
      assertThat(preparationStampFunction.get("proconfig", String.class))
          .contains("pg_catalog")
          .contains(schema);
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM pg_proc p CROSS JOIN LATERAL "
                          + "aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) acl "
                          + "WHERE p.oid = to_regprocedure(?) AND acl.grantee = 0::oid "
                          + "AND acl.privilege_type = 'EXECUTE'",
                      schema + ".world_stamp_preparation_insert_full_xid()")
                  .get(0, Long.class))
          .as("the preparation stamp trigger function grants no EXECUTE to PUBLIC")
          .isZero();
      assertThat(
              connection.fetchSingle(
                  "SELECT t.tgtype::text, t.tgenabled::text, t.tgfoid::text "
                      + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                      + "WHERE c.relnamespace = to_regnamespace(?) "
                      + "AND c.relname = 'world_canonical_instance_preparation' "
                      + "AND t.tgname = 'trg_world_preparation_full_xid_stamp'",
                  schema))
          .satisfies(
              row -> {
                assertThat(row.get(0, String.class)).isEqualTo("7");
                assertThat(row.get(1, String.class)).isEqualTo("O");
                assertThat(row.get(2, String.class))
                    .isEqualTo(
                        connection
                            .fetchSingle(
                                "SELECT oid::text FROM pg_proc WHERE oid = to_regprocedure(?)",
                                schema + ".world_stamp_preparation_insert_full_xid()")
                            .get(0, String.class));
              });
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                          + "WHERE c.relnamespace = to_regnamespace(?) "
                          + "AND c.relname = 'world_canonical_instance_preparation' "
                          + "AND NOT t.tgisinternal AND (t.tgtype & 7) = 7",
                      schema)
                  .get(0, Long.class))
          .as(
              "the exact manifest validator and server stamp are the only BEFORE INSERT row triggers")
          .isEqualTo(2L);
      assertThat("trg_world_canonical_instance_preparation_exact_insert")
          .isLessThan("trg_world_preparation_full_xid_stamp");
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT preparation_inserted_full_xid::text "
                          + "FROM world_canonical_instance_preparation WHERE world_instance_id = ?",
                      fixtureAssociation.worldInstanceId())
                  .get(0, String.class))
          .as("a preparation retained before V1042.11 remains unbackfilled")
          .isNull();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT (to_jsonb(p) - 'preparation_inserted_full_xid')::text "
                          + "|| ':xmin=' || p.xmin::text "
                          + "FROM world_canonical_instance_preparation p WHERE p.world_instance_id = ?",
                      fixtureAssociation.worldInstanceId())
                  .get(0, String.class))
          .as("all original preparation columns and xmin survive the V1042.11 upgrade")
          .isEqualTo(retainedPreparationAt10428);
      for (var entry : originalColumns.entrySet())
        assertThat(retainedRows(connection, schema, entry.getKey(), entry.getValue()))
            .as("all original values and xmin for %s", entry.getKey())
            .containsAll(originalRows.get(entry.getKey()));
      var retainedRowsAtDefaultFrontierOriginalColumns =
          new LinkedHashMap<>(allRowsWithoutNewServerStamps(connection));
      retainedRowsAtDefaultFrontierOriginalColumns.remove(HISTORY);
      assertThat(retainedRowsAtDefaultFrontierOriginalColumns)
          .as("all retained original row values and xmin from V1042.8 through the V1042.11 repairs")
          .isEqualTo(retainedRowsAt10428);
      var retainedRowsAtDefaultFrontier = new LinkedHashMap<>(allRows(connection));
      retainedRowsAtDefaultFrontier.remove(HISTORY);
      assertThat(
              connection.fetchSingle(
                  "SELECT oid::text, proowner::text, prosecdef, proconfig::text, proacl::text FROM pg_proc WHERE oid = to_regprocedure(?)",
                  guardProcedure))
          .isEqualTo(originalGuardIdentity);
      var installedAccountBinding =
          connection
              .fetchSingle(
                  "SELECT pg_get_functiondef(to_regprocedure(?))",
                  schema + ".world_validate_selected_publication_account_binding()")
              .get(0, String.class);
      var installedArtifactInventory =
          connection
              .fetchSingle(
                  "SELECT pg_get_functiondef(to_regprocedure(?))",
                  schema + ".world_validate_selected_publication_artifact_inventory()")
              .get(0, String.class);
      var installedPreparationRetainer =
          connection
              .fetchSingle(
                  "SELECT pg_get_functiondef(to_regprocedure(?))",
                  schema + ".world_retain_preparation_start_location()")
              .get(0, String.class);
      assertThat(installedAccountBinding)
          .contains("attempt_created_full_xid = pg_current_xact_id()")
          .contains("attempt_inserted_full_xid = pg_current_xact_id()")
          .doesNotContain("a.xmin::TEXT");
      assertThat(installedArtifactInventory)
          .contains("attempt_created_full_xid = pg_current_xact_id()")
          .contains("attempt_inserted_full_xid = pg_current_xact_id()")
          .doesNotContain("a.xmin::TEXT");
      assertThat(installedPreparationRetainer)
          .contains("to_jsonb(retained) = to_jsonb(NEW)")
          .contains("world_canonical_instance_preparation_execution_manifest")
          .doesNotContain("retained.xmin::TEXT");
      var stampFunction =
          connection.fetchSingle(
              "SELECT proowner::text, prosecdef, proconfig::text FROM pg_proc "
                  + "WHERE oid = to_regprocedure(?)",
              schema + ".world_stamp_publication_attempt_full_xid()");
      assertThat(stampFunction.get("proowner", String.class))
          .isEqualTo(
              connection
                  .fetchSingle(
                      "SELECT proowner::text FROM pg_proc WHERE oid = to_regprocedure(?)",
                      schema + ".world_validate_selected_publication_account_binding()")
                  .get(0, String.class));
      assertThat(stampFunction.get("prosecdef", Boolean.class)).isTrue();
      assertThat(stampFunction.get("proconfig", String.class))
          .contains("pg_catalog")
          .contains(schema);
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM pg_proc p CROSS JOIN LATERAL "
                          + "aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) acl "
                          + "WHERE p.oid = to_regprocedure(?) AND acl.grantee = 0::oid "
                          + "AND acl.privilege_type = 'EXECUTE'",
                      schema + ".world_stamp_publication_attempt_full_xid()")
                  .get(0, Long.class))
          .as("the stamp trigger function grants no EXECUTE privilege to PUBLIC")
          .isZero();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                          + "WHERE c.relnamespace = to_regnamespace(?) "
                          + "AND t.tgname = 'trg_world_publication_attempt_full_xid_stamp' "
                          + "AND c.relname = 'world_design_publication_fence_attempt' "
                          + "AND t.tgtype = 7 AND t.tgenabled = 'O' "
                          + "AND t.tgfoid = to_regprocedure(?)",
                      schema,
                      schema + ".world_stamp_publication_attempt_full_xid()")
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              connection.fetchSingle(
                  "SELECT t.oid::text, t.tgfoid::text, t.tgtype, t.tgenabled, t.tgargs::text, t.tgqual::text "
                      + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                      + "WHERE c.relnamespace = to_regnamespace(?) AND t.tgname = 'trg_world_canonical_activation_transition'",
                  schema))
          .isEqualTo(originalGuardTrigger);
      assertPredecessorLifecycleGuardProbe(
          source,
          schema,
          fixtureAssociation.worldInstanceId(),
          fixtureAssociation.identity().canonicalGameInstanceId().toString());
      List<String> deniedMetadataUpdates =
          List.of(
              "failure_reason = 'fixture failure'",
              "termination_request_id = 'fixture termination'",
              "terminated_at = clock_timestamp()");
      for (String mutation : deniedMetadataUpdates) {
        assertSqlState(
            () ->
                connection.execute(
                    "UPDATE world_instance SET " + mutation + " WHERE id = ?",
                    fixtureAssociation.worldInstanceId()),
            "23514",
            "World tenant key is reserved for a canonical authored source");
      }
      // The earlier owner-manifest trigger denies naked production writes first. Exercise the
      // actual installed activation guard separately on a connection-local copy of that same row;
      // no production trigger is disabled and no owner execution manifest is fabricated.
      try (var probeConnection = source.getConnection()) {
        var probe = DSL.using(probeConnection, SQLDialect.POSTGRES);
        probe.execute(
            "CREATE TEMP TABLE fixture_row_guard_probe AS TABLE "
                + DSL.name(schema, "world_instance")
                + " WITH NO DATA");
        probe.execute(
            "INSERT INTO pg_temp.fixture_row_guard_probe SELECT * FROM "
                + DSL.name(schema, "world_instance")
                + " WHERE id = ?",
            fixtureAssociation.worldInstanceId());
        probe.execute(
            "CREATE TRIGGER fixture_row_guard BEFORE UPDATE ON pg_temp.fixture_row_guard_probe "
                + "FOR EACH ROW EXECUTE FUNCTION "
                + DSL.name(schema, "world_guard_canonical_activation_transition")
                + "()");
        var probeRowsBefore =
            probe.fetch(
                "SELECT row_to_json(t)::text, xmin::text FROM pg_temp.fixture_row_guard_probe t");
        for (String mutation : deniedMetadataUpdates) {
          assertSqlState(
              () -> probe.execute("UPDATE pg_temp.fixture_row_guard_probe SET " + mutation),
              "23514",
              "Run-owned fixture World row mutations are disabled");
        }
        assertThat(
                probe.fetch(
                    "SELECT row_to_json(t)::text, xmin::text FROM pg_temp.fixture_row_guard_probe t"))
            .isEqualTo(probeRowsBefore);
      }
      assertThat(allRows(connection).get("world_instance"))
          .as(
              "fixture row remains byte-for-byte and xmin stable after denied lifecycle metadata writes")
          .isEqualTo(retainedRowsAt10428.get("world_instance"));
      assertThat(sourceOwner.read("dev", intakeRequest)).contains(intake);
      assertThat(
              versionOwner.readByCanonicalVersion(
                  "dev",
                  intake.canonicalTenantId(),
                  intake.worldSlug(),
                  version.canonicalVersionId()))
          .contains(version);
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT canonical_game_instance_id, canonical_target_namespace, canonical_tenant_id, canonical_world_slug, playable_state_namespace_id, playable_state_scope, public_production, canonical_launch_binding_operation_id, canonical_realm_id, catalog_revision, canonical_authority_kind, fixture_provenance_digest, game_session_tenant_id, game_session_game_instance_id, game_design_version_id FROM world_instance WHERE id = ?",
                      worldId)
                  .intoArray())
          .containsOnlyNulls();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT canonical_region_instance_id FROM region_instance WHERE id = ?",
                      firstRegion)
                  .get(0))
          .isNull();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM world_canonical_instance_association WHERE world_instance_id = ?",
                      worldId)
                  .get(0, Long.class))
          .isZero();
      assertThat(
              connection
                  .fetchSingle(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND NOT t.tgisinternal AND t.tgenabled='D'",
                      schema)
                  .get(0, Long.class))
          .isZero();
      // Stable UPDATE on disjoint rows remains independent even with the same legacy tenant.
      // Roll both writes back, then recompare the complete retained row images and xmin.
      try (var first = source.getConnection();
          var second = source.getConnection()) {
        first.setAutoCommit(false);
        second.setAutoCommit(false);
        try (var statement =
            first.prepareStatement(
                "UPDATE region_instance SET name='held legacy row' WHERE id=?")) {
          statement.setLong(1, firstRegion);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        try (var timeout = second.createStatement();
            var statement =
                second.prepareStatement(
                    "UPDATE region_instance SET name='independent legacy row' WHERE id=?")) {
          timeout.execute("SET LOCAL lock_timeout='1000ms'");
          statement.setLong(1, secondRegion);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        } finally {
          second.rollback();
          first.rollback();
        }
      }
      var rowsAfterLegacyRollback = new LinkedHashMap<>(allRows(connection));
      rowsAfterLegacyRollback.remove(HISTORY);
      assertThat(rowsAfterLegacyRollback)
          .as("all retained row values and xmin after legacy rollback")
          .isEqualTo(retainedRowsAtDefaultFrontier);
      // No current Human protection is fabricated. Literal pre-extension legacy and owner
      // receipts are retained; a historical canonical Human association requires a separate donor.
    } finally {
      connection.execute("DROP SCHEMA IF EXISTS " + DSL.name(schema) + " CASCADE");
    }
  }

  @Test
  void installedAssociationCheckExpressionsRejectRequiredNullsWithoutUnknowns() {
    // This is a typed CHECK-expression/storage-shape proof, not authorized Human row creation.
    String schemaCheck =
        associationCheckExpression(dsl, "ck_world_canonical_instance_association_schema");
    String digestCheck =
        associationCheckExpression(dsl, "ck_world_canonical_instance_association_digests");

    for (int schemaVersion : List.of(1, 2)) {
      var humanShape = associationShape(schemaVersion);
      assertThat(evaluateAssociationCheck(dsl, schemaCheck, humanShape))
          .as("valid Human schema-%s storage shape", schemaVersion)
          .isTrue();
      assertThat(evaluateAssociationCheck(dsl, digestCheck, humanShape))
          .as("valid Human schema-%s digest shape", schemaVersion)
          .isTrue();

      var missingBinding = new LinkedHashMap<>(humanShape);
      missingBinding.put("canonical_launch_binding_operation_id", null);
      assertThat(evaluateAssociationCheck(dsl, schemaCheck, missingBinding))
          .as("Human schema-%s binding NULL evaluates FALSE", schemaVersion)
          .isFalse();

      var missingReleaseDigest = new LinkedHashMap<>(humanShape);
      missingReleaseDigest.put("release_attestation_digest", null);
      assertThat(evaluateAssociationCheck(dsl, digestCheck, missingReleaseDigest))
          .as("Human schema-%s release digest NULL evaluates FALSE", schemaVersion)
          .isFalse();
    }

    var schema2WithoutCatalog = associationShape(2);
    schema2WithoutCatalog.put("catalog_revision", null);
    assertThat(evaluateAssociationCheck(dsl, schemaCheck, schema2WithoutCatalog))
        .as("Human schema-2 catalog NULL evaluates FALSE")
        .isFalse();

    var fixtureShape = associationShape(3);
    assertThat(evaluateAssociationCheck(dsl, schemaCheck, fixtureShape))
        .as("valid schema-3 fixture storage shape")
        .isTrue();
    assertThat(evaluateAssociationCheck(dsl, digestCheck, fixtureShape))
        .as("schema-3 fixture retains absent Human release digest")
        .isTrue();
    var fixtureWithoutCatalog = new LinkedHashMap<>(fixtureShape);
    fixtureWithoutCatalog.put("catalog_revision", null);
    assertThat(evaluateAssociationCheck(dsl, schemaCheck, fixtureWithoutCatalog))
        .as("schema-3 fixture catalog NULL evaluates FALSE")
        .isFalse();
  }

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
    RunOwnedCanonicalWorldLaunchEvidence evidence = controlledEvidence();
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
                      dsl, evidence, plan, worldId, privateKey);
                });
    assertThat(result).isNotNull();
    assertThat(result.regionCount()).isEqualTo(1);
    assertThat(result.zoneCount()).isEqualTo(1);
    assertThat(result.roomCount()).isEqualTo(2);
    assertThat(result.exitCount()).isEqualTo(2);
    var readbackRowsBefore = allRows(dsl);
    assertFixedPresetReadbackMode(
        "writable READ COMMITTED",
        plan,
        worldId,
        privateKey,
        result,
        false,
        TransactionDefinition.ISOLATION_READ_COMMITTED,
        "read committed",
        true,
        readbackRowsBefore);
    assertFixedPresetReadbackMode(
        "read-only REPEATABLE READ",
        plan,
        worldId,
        privateKey,
        result,
        true,
        TransactionDefinition.ISOLATION_REPEATABLE_READ,
        "repeatable read",
        true,
        readbackRowsBefore);
    assertFixedPresetReadbackMode(
        "read-only SERIALIZABLE",
        plan,
        worldId,
        privateKey,
        result,
        true,
        TransactionDefinition.ISOLATION_SERIALIZABLE,
        "serializable",
        true,
        readbackRowsBefore);
    assertFixedPresetReadbackMode(
        "read-only READ COMMITTED",
        plan,
        worldId,
        privateKey,
        result,
        true,
        TransactionDefinition.ISOLATION_READ_COMMITTED,
        "read committed",
        false,
        readbackRowsBefore);
    assertFixedPresetReadbackMode(
        "writable REPEATABLE READ",
        plan,
        worldId,
        privateKey,
        result,
        false,
        TransactionDefinition.ISOLATION_REPEATABLE_READ,
        "repeatable read",
        false,
        readbackRowsBefore);
    assertFixedPresetReadbackMode(
        "writable SERIALIZABLE",
        plan,
        worldId,
        privateKey,
        result,
        false,
        TransactionDefinition.ISOLATION_SERIALIZABLE,
        "serializable",
        false,
        readbackRowsBefore);
    assertFixedPresetReadbackMode(
        "writable READ UNCOMMITTED",
        plan,
        worldId,
        privateKey,
        result,
        false,
        TransactionDefinition.ISOLATION_READ_UNCOMMITTED,
        "read uncommitted",
        false,
        readbackRowsBefore);
    assertThat(RunOwnedWorldFixedPresetResources.readCommitted(dsl, plan, worldId, privateKey))
        .as("standalone readback in PostgreSQL's implicit writable READ COMMITTED transaction")
        .isEqualTo(result);
    assertThat(allRows(dsl))
        .as("no row or byte changes after standalone readback")
        .isEqualTo(readbackRowsBefore);
    assertRoomReadbackRequiresBothRooms(plan, worldId, privateKey, readbackRowsBefore);
    assertNoPendingManifests(worldId);
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
        scalarText(
            "SELECT to_jsonb(w)::text || w.xmin::text FROM world_instance w WHERE id = ?", worldId);
    String retainedPreparation =
        scalarText(
            "SELECT to_jsonb(p)::text || p.xmin::text FROM world_canonical_instance_preparation p WHERE world_instance_id = ?",
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
                      dsl, evidence, plan, worldId, privateKey);
                });
    assertThat(replayResult).isEqualTo(result);
    assertThat(resourceCount()).isEqualTo(materializedCount);
    assertThat(
            scalarText(
                "SELECT to_jsonb(w)::text || w.xmin::text FROM world_instance w WHERE id = ?",
                worldId))
        .isEqualTo(retainedWorld);
    assertThat(
            scalarText(
                "SELECT to_jsonb(p)::text || p.xmin::text FROM world_canonical_instance_preparation p WHERE world_instance_id = ?",
                worldId))
        .isEqualTo(retainedPreparation);
    assertNoPendingManifests(worldId);
  }

  private WorldLifecycleCommandServiceImpl nativeService(
      RunOwnedCanonicalWorldLaunchEvidence evidence) {
    return nativeService(evidence, new AtomicInteger());
  }

  private WorldLifecycleCommandServiceImpl nativeService(
      RunOwnedCanonicalWorldLaunchEvidence evidence, AtomicInteger upstreamReads) {
    // Only upstream replies are controlled. The fixture bridge uses real World SQL owners.
    var source = mock(AuthoredWorldSourceClient.class);
    when(source.read(any()))
        .thenAnswer(
            invocation -> {
              upstreamReads.incrementAndGet();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return evidence.sourceReceipt().sourceEvidence();
            });
    var versions = mock(AuthoredWorldVersionStateClient.class);
    when(versions.read(any()))
        .thenAnswer(
            invocation -> {
              upstreamReads.incrementAndGet();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              AuthoredWorldVersionStateEvidence.Request request = invocation.getArgument(0);
              return AuthoredWorldVersionStateEvidence.create(
                  request,
                  evidence.sourceReceipt().sourceEvidence(),
                  evidence.gameSessionAssociation().canonicalVersionId(),
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                  evidence.launchDescriptor().versionStateEpoch());
            });
    // Package preparation consumes the closed-parser carrier directly; this enabled provider
    // supplies configuration only and does not pretend to read files or authenticate producers.
    var provider =
        new RunOwnedCanonicalWorldLaunchEvidenceProvider(
            true,
            "/controlled-unused-capability",
            "/controlled-unused-receipt",
            evidence.capability().runId(),
            evidence.capability().composeProjectName(),
            new CommonGrpcClientProperties(),
            gameDesignClient,
            null,
            null,
            "dev");
    return RunOwnedWorldFixtureLifecycleTestFactory.create(
        dsl,
        transactionManager,
        provider,
        source,
        versions,
        gameDesignClient,
        entityManagementClient);
  }

  private WorldCanonicalInstanceAssociationRepository associationRepository() {
    return new WorldCanonicalInstanceAssociationRepository(
        dsl,
        new WorldCompleteLaunchBindingRepository(dsl),
        new WorldAuthoredSourceIntakeRepository(dsl),
        new WorldAuthoredVersionIdentityRepository(dsl));
  }

  private static OwnerBinding binding(
      WorldAuthoredSourceIntakeReceipt source, WorldAuthoredVersionIdentityReceipt version) {
    return new OwnerBinding(
        source.targetNamespace(),
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
  }

  private static <T> T withControlledPeer(Supplier<T> action) {
    var certificate = mock(GrpcPeerCertificateEvidence.class);
    when(certificate.leafSha256()).thenReturn("a".repeat(64));
    var context =
        Context.ROOT
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/dev/sa/game-session-service",
                    "dev",
                    "game-session-service"))
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("controlled barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("controlled barrier interrupted", interrupted);
    }
  }

  private static Map<String, List<String>> allRows(DSLContext connection) {
    String schema = connection.fetchSingle("SELECT current_schema()").get(0, String.class);
    assertThat(schema).isNotBlank();
    var tables =
        connection
            .fetch(
                "SELECT tablename FROM pg_tables WHERE schemaname = ? ORDER BY tablename", schema)
            .getValues(0, String.class);
    assertThat(tables).isNotEmpty().contains("world_instance");
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table : tables)
      rows.put(
          table,
          connection
              .fetch(
                  "SELECT to_jsonb(retained)::text || ':xmin=' || retained.xmin::text FROM "
                      + DSL.name(schema, table)
                      + " retained ORDER BY 1")
              .getValues(0, String.class));
    return rows;
  }

  private static Map<String, List<String>> allRowsWithoutNewServerStamps(DSLContext connection) {
    String schema = connection.fetchSingle("SELECT current_schema()").get(0, String.class);
    assertThat(schema).isNotBlank();
    var tables =
        connection
            .fetch(
                "SELECT tablename FROM pg_tables WHERE schemaname = ? ORDER BY tablename", schema)
            .getValues(0, String.class);
    assertThat(tables).isNotEmpty().contains("world_design_publication_fence_attempt");
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table : tables) {
      String retainedRow =
          switch (table) {
            case "world_design_publication_fence_attempt" ->
                "(to_jsonb(retained) - 'attempt_inserted_full_xid')";
            case "world_canonical_instance_preparation" ->
                "(to_jsonb(retained) - 'preparation_inserted_full_xid')";
            default -> "to_jsonb(retained)";
          };
      rows.put(
          table,
          connection
              .fetch(
                  "SELECT "
                      + retainedRow
                      + "::text || ':xmin=' || retained.xmin::text FROM "
                      + DSL.name(schema, table)
                      + " retained ORDER BY 1")
              .getValues(0, String.class));
    }
    return rows;
  }

  /**
   * Exercises the installed trigger against controlled copies of an owner-created row. This is SQL
   * predicate and retention proof only; it does not invoke or stand in for the authenticated
   * source-first predecessor owner, receipt, World hold, or lifecycle handoff.
   */
  private static void assertPredecessorLifecycleGuardProbe(
      DriverManagerDataSource source,
      String schema,
      long canonicalWorldInstanceId,
      String canonicalGameInstanceId)
      throws Exception {
    String probeTable = "pg_temp.fixture_source_lifecycle_probe";
    String worldTable = DSL.name(schema, "world_instance").toString();
    List<LifecycleGuardProbeCase> cases =
        List.of(
            new LifecycleGuardProbeCase(0, "exact source-only activation", null, null, null),
            new LifecycleGuardProbeCase(
                1,
                "binding removed on activation",
                null,
                "run_owned_fixture_binding_sha256 = NULL, status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                2,
                "malformed original binding",
                "run_owned_fixture_binding_sha256 = repeat('A', 64)",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                3,
                "changed binding on activation",
                null,
                "run_owned_fixture_binding_sha256 = CASE WHEN run_owned_fixture_binding_sha256 = repeat('0', 64) THEN repeat('1', 64) ELSE repeat('0', 64) END, status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                4,
                "changed preparing epoch",
                "run_owned_fixture_preparing_lifecycle_epoch = 2",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                5,
                "stale lifecycle epoch",
                "lifecycle_epoch = 2",
                "status = 'ACTIVE', lifecycle_epoch = 3, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                6,
                "wrong original lifecycle state",
                "status = 'FAILED_PRE_ACTIVATION'",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                7,
                "unexpected next lifecycle epoch",
                null,
                "status = 'ACTIVE', lifecycle_epoch = 3, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                8,
                "row version skips a compare-and-set step",
                null,
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 2",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                9,
                "canonical identity partial",
                "canonical_game_instance_id = '00000000-0000-0000-0000-000000000001'::uuid",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                10,
                "realm-only partial",
                "canonical_realm_id = '00000000-0000-0000-0000-000000000001'::uuid",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                11,
                "catalog-only partial",
                "catalog_revision = 7",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                12,
                "realm/catalog-only partial pair",
                "canonical_realm_id = '00000000-0000-0000-0000-000000000001'::uuid, catalog_revision = 7",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                13,
                "fixture provenance partial",
                "fixture_provenance_digest = 'sha256:' || repeat('a', 64)",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                14,
                "Game Session tenant allocation partial",
                "game_session_tenant_id = 44",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                15,
                "Game Session instance allocation partial",
                "game_session_game_instance_id = 45",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                16,
                "Game Design version allocation partial",
                "game_design_version_id = 46",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                17,
                "allocation tuple without canonical identity",
                "game_session_tenant_id = 44, game_session_game_instance_id = 45, game_design_version_id = 46",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                18,
                "new row adds realm metadata",
                null,
                "canonical_realm_id = '00000000-0000-0000-0000-000000000001'::uuid, status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                19,
                "new row adds catalog metadata",
                null,
                "catalog_revision = 7, status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                20,
                "new row adopts canonical identity",
                null,
                "canonical_game_instance_id = '00000000-0000-0000-0000-000000000001'::uuid, status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                21,
                "new row adds fixture provenance",
                null,
                "fixture_provenance_digest = 'sha256:' || repeat('a', 64), status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                22,
                "new row adds allocation data",
                null,
                "game_session_tenant_id = 44, status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                23,
                "new row changes source request identity",
                null,
                "control_plane_request_id = 'different-source-operation', status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                24,
                "existing termination request",
                "termination_request_id = 'staged-termination-request'",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                25,
                "existing terminal timestamp",
                "terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                26,
                "Human activation still needs its committed operation",
                "run_owned_fixture_binding_sha256 = NULL, run_owned_fixture_preparing_lifecycle_epoch = NULL, "
                    + "canonical_game_instance_id = '"
                    + canonicalGameInstanceId
                    + "'::uuid, canonical_authority_kind = 'HUMAN', canonical_launch_binding_operation_id = '00000000-0000-0000-0000-000000000002'::uuid",
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "requires its exact activation operation"),
            new LifecycleGuardProbeCase(
                27,
                "null original preparing epoch",
                "run_owned_fixture_preparing_lifecycle_epoch = NULL",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                28,
                "null original lifecycle epoch",
                "lifecycle_epoch = NULL",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                29,
                "null original lifecycle status",
                "status = NULL",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                30,
                "null original row version",
                "row_version = NULL",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                31,
                "null resulting lifecycle epoch",
                null,
                "status = 'ACTIVE', lifecycle_epoch = NULL, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                32,
                "null resulting row version",
                null,
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = NULL",
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                33,
                "negative original row version",
                "row_version = -1",
                null,
                "lifecycle transitions are disabled"),
            new LifecycleGuardProbeCase(
                49,
                "lifecycle transition cannot rewrite update timestamp",
                null,
                "updated_at = updated_at + INTERVAL '1 second', status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1",
                "lifecycle transitions are disabled"));
    List<LifecycleTransitionGuardProbeCase> transitionCases =
        List.of(
            new LifecycleTransitionGuardProbeCase(
                34,
                "PREPARING cannot skip to TERMINATING",
                null,
                "status = 'TERMINATING', lifecycle_epoch = 2, row_version = row_version + 1, termination_request_id = 'preparing-skip'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                35,
                "PREPARING cannot skip to TERMINATED",
                null,
                "status = 'TERMINATED', lifecycle_epoch = 2, row_version = row_version + 1, termination_request_id = 'preparing-skip', terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                36,
                "FAILED_PRE_ACTIVATION cannot enter termination",
                "status = 'FAILED_PRE_ACTIVATION', lifecycle_epoch = 2, row_version = 1, failure_reason = 'prepare failed'",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = row_version + 1, termination_request_id = 'failed-skip'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                37,
                "ACTIVE cannot skip to TERMINATED",
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = 1",
                "status = 'TERMINATED', lifecycle_epoch = 3, row_version = row_version + 1, termination_request_id = 'active-skip', terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                38,
                "ACTIVE termination requires a nonblank request ID",
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = 1",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = row_version + 1, termination_request_id = '   '",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                39,
                "ACTIVE termination requires a request ID",
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = 1",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                40,
                "ACTIVE termination requires the next epoch",
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = 1",
                "status = 'TERMINATING', lifecycle_epoch = 4, row_version = row_version + 1, termination_request_id = 'stale-epoch'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                41,
                "ACTIVE termination requires one row-version step",
                "status = 'ACTIVE', lifecycle_epoch = 2, row_version = 1",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = row_version + 2, termination_request_id = 'stale-row-version'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                42,
                "termination finalization preserves its request ID",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = 2, failure_reason = 'cleanup pending', termination_request_id = 'termination-42'",
                "status = 'TERMINATED', lifecycle_epoch = 4, row_version = row_version + 1, termination_request_id = 'different-request', terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                43,
                "termination finalization requires a terminal timestamp",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = 2, termination_request_id = 'termination-43'",
                "status = 'TERMINATED', lifecycle_epoch = 4, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                44,
                "termination finalization retains failure metadata",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = 2, failure_reason = 'cleanup pending', termination_request_id = 'termination-44'",
                "status = 'TERMINATED', lifecycle_epoch = 4, row_version = row_version + 1, failure_reason = 'rewritten failure', terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                45,
                "TERMINATED cannot reopen as ACTIVE",
                "status = 'TERMINATED', lifecycle_epoch = 4, row_version = 3, failure_reason = 'cleanup pending', termination_request_id = 'termination-45', terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "status = 'ACTIVE', lifecycle_epoch = 5, row_version = row_version + 1",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                46,
                "TERMINATED cannot reopen as TERMINATING",
                "status = 'TERMINATED', lifecycle_epoch = 4, row_version = 3, failure_reason = 'cleanup pending', termination_request_id = 'termination-46', terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "status = 'TERMINATING', lifecycle_epoch = 5, row_version = row_version + 1, terminated_at = NULL",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                47,
                "termination finalization requires one row-version step",
                "status = 'TERMINATING', lifecycle_epoch = 3, row_version = 2, termination_request_id = 'termination-47'",
                "status = 'TERMINATED', lifecycle_epoch = 4, row_version = row_version + 2, terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z'",
                "lifecycle transitions are disabled"),
            new LifecycleTransitionGuardProbeCase(
                48,
                "source-only fixture row rejects metadata-only mutation",
                null,
                "failure_reason = 'unowned metadata change', row_version = row_version + 1",
                "lifecycle transitions are disabled"));

    try (var connection = source.getConnection()) {
      var probe = DSL.using(connection, SQLDialect.POSTGRES);
      String originalBinding =
          probe
              .fetchSingle(
                  "SELECT run_owned_fixture_binding_sha256 FROM " + worldTable + " WHERE id = ?",
                  canonicalWorldInstanceId)
              .get(0, String.class);
      assertThat(originalBinding).matches("[0-9a-f]{64}");
      probe.execute(
          "CREATE TEMP TABLE fixture_source_lifecycle_probe AS SELECT 0::INTEGER AS probe_key, source.* FROM "
              + worldTable
              + " source WITH NO DATA");
      for (LifecycleGuardProbeCase probeCase : cases) {
        insertSourceOnlyLifecycleProbeRow(
            probe, probeTable, worldTable, canonicalWorldInstanceId, probeCase.key());
        if (probeCase.oldMutation() != null) {
          probe.execute(
              "UPDATE " + probeTable + " SET " + probeCase.oldMutation() + " WHERE probe_key = ?",
              probeCase.key());
        }
      }
      for (LifecycleTransitionGuardProbeCase probeCase : transitionCases) {
        insertSourceOnlyLifecycleProbeRow(
            probe, probeTable, worldTable, canonicalWorldInstanceId, probeCase.key());
        if (probeCase.startingMutation() != null) {
          probe.execute(
              "UPDATE "
                  + probeTable
                  + " SET "
                  + probeCase.startingMutation()
                  + " WHERE probe_key = ?",
              probeCase.key());
        }
      }
      int failedPreparationKey = 100;
      insertSourceOnlyLifecycleProbeRow(
          probe, probeTable, worldTable, canonicalWorldInstanceId, failedPreparationKey);
      // This row shape reaches the original Human activation-operation guard, but it is only a
      // trigger probe; its Human label and copied fixture UUID do not represent Human authority.
      probe.execute(
          "CREATE TRIGGER fixture_source_lifecycle_guard BEFORE UPDATE ON "
              + probeTable
              + " FOR EACH ROW EXECUTE FUNCTION "
              + DSL.name(schema, "world_guard_canonical_activation_transition")
              + "()");

      LifecycleGuardProbeCase valid = cases.getFirst();
      probe.execute(
          "UPDATE "
              + probeTable
              + " SET status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1 "
              + "WHERE probe_key = ?",
          valid.key());
      var activated =
          probe.fetchSingle(
              "SELECT status, lifecycle_epoch, row_version, run_owned_fixture_binding_sha256, "
                  + "run_owned_fixture_preparing_lifecycle_epoch, canonical_game_instance_id, "
                  + "canonical_realm_id, catalog_revision, fixture_provenance_digest, "
                  + "game_session_tenant_id, game_session_game_instance_id, game_design_version_id "
                  + "FROM "
                  + probeTable
                  + " WHERE probe_key = ?",
              valid.key());
      assertThat(activated.get(0, String.class)).isEqualTo("ACTIVE");
      assertThat(activated.get(1, Long.class)).isEqualTo(2L);
      assertThat(activated.get(2, Long.class)).isEqualTo(1L);
      assertThat(activated.get(3, String.class)).isEqualTo(originalBinding);
      assertThat(activated.get(4, Long.class)).isEqualTo(1L);
      assertSourceOnlyCanonicalColumnsNull(probe, probeTable, valid.key());
      assertThat(
              probe
                  .fetchSingle(
                      "SELECT canonical_game_instance_id, canonical_realm_id, catalog_revision, "
                          + "fixture_provenance_digest, game_session_tenant_id, "
                          + "game_session_game_instance_id, game_design_version_id FROM "
                          + probeTable
                          + " WHERE probe_key = ?",
                      valid.key())
                  .intoArray())
          .containsOnlyNulls();

      // Termination commits ACTIVE→TERMINATING before the Entity cleanup call. A cleanup error
      // returns with these exact request/reason fields retained; it is not another row transition.
      probe.execute(
          "UPDATE "
              + probeTable
              + " SET status = 'TERMINATING', lifecycle_epoch = 3, row_version = row_version + 1, "
              + "termination_request_id = 'entity-cleanup-request-0', "
              + "failure_reason = 'Entity cleanup failure retained for retry' WHERE probe_key = ?",
          valid.key());
      var terminating =
          probe.fetchSingle(
              "SELECT status, lifecycle_epoch, row_version, run_owned_fixture_binding_sha256, "
                  + "run_owned_fixture_preparing_lifecycle_epoch, termination_request_id, "
                  + "failure_reason, terminated_at FROM "
                  + probeTable
                  + " WHERE probe_key = ?",
              valid.key());
      assertThat(terminating.get(0, String.class)).isEqualTo("TERMINATING");
      assertThat(terminating.get(1, Long.class)).isEqualTo(3L);
      assertThat(terminating.get(2, Long.class)).isEqualTo(2L);
      assertThat(terminating.get(3, String.class)).isEqualTo(originalBinding);
      assertThat(terminating.get(4, Long.class)).isEqualTo(1L);
      assertThat(terminating.get(5, String.class)).isEqualTo("entity-cleanup-request-0");
      assertThat(terminating.get(6, String.class))
          .isEqualTo("Entity cleanup failure retained for retry");
      assertThat(terminating.get(7)).isNull();
      assertSourceOnlyCanonicalColumnsNull(probe, probeTable, valid.key());
      var retainedAfterCleanupFailure =
          probe.fetch(
              "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                  + probeTable
                  + " row WHERE probe_key = ?",
              valid.key());
      assertSqlState(
          () ->
              probe.execute(
                  "UPDATE "
                      + probeTable
                      + " SET failure_reason = 'unowned cleanup retry metadata', "
                      + "row_version = row_version + 1 WHERE probe_key = ?",
                  valid.key()),
          "23514",
          "lifecycle transitions are disabled");
      assertThat(
              probe.fetch(
                  "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                      + probeTable
                      + " row WHERE probe_key = ?",
                  valid.key()))
          .as("a rejected metadata-only update leaves the controlled TERMINATING row unchanged")
          .isEqualTo(retainedAfterCleanupFailure);

      probe.execute(
          "UPDATE "
              + probeTable
              + " SET status = 'TERMINATED', lifecycle_epoch = 4, row_version = row_version + 1, "
              + "terminated_at = TIMESTAMPTZ '2026-10-14T00:00:00Z' WHERE probe_key = ?",
          valid.key());
      var terminated =
          probe.fetchSingle(
              "SELECT status, lifecycle_epoch, row_version, termination_request_id, failure_reason, "
                  + "terminated_at FROM "
                  + probeTable
                  + " WHERE probe_key = ?",
              valid.key());
      assertThat(terminated.get(0, String.class)).isEqualTo("TERMINATED");
      assertThat(terminated.get(1, Long.class)).isEqualTo(4L);
      assertThat(terminated.get(2, Long.class)).isEqualTo(3L);
      assertThat(terminated.get(3, String.class)).isEqualTo("entity-cleanup-request-0");
      assertThat(terminated.get(4, String.class))
          .isEqualTo("Entity cleanup failure retained for retry");
      assertThat(terminated.get(5)).isNotNull();
      assertSourceOnlyCanonicalColumnsNull(probe, probeTable, valid.key());
      var beforeTerminalReopen =
          probe.fetch(
              "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                  + probeTable
                  + " row WHERE probe_key = ?",
              valid.key());
      assertSqlState(
          () ->
              probe.execute(
                  "UPDATE "
                      + probeTable
                      + " SET status = 'ACTIVE', lifecycle_epoch = 5, row_version = row_version + 1 "
                      + "WHERE probe_key = ?",
                  valid.key()),
          "23514",
          "lifecycle transitions are disabled");
      assertThat(
              probe.fetch(
                  "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                      + probeTable
                      + " row WHERE probe_key = ?",
                  valid.key()))
          .as("terminal lifecycle reopening fails without changing the controlled row")
          .isEqualTo(beforeTerminalReopen);

      probe.execute(
          "UPDATE "
              + probeTable
              + " SET status = 'FAILED_PRE_ACTIVATION', lifecycle_epoch = 2, "
              + "row_version = row_version + 1, failure_reason = 'preparation failure retained' "
              + "WHERE probe_key = ?",
          failedPreparationKey);
      var failedPreparation =
          probe.fetchSingle(
              "SELECT status, lifecycle_epoch, row_version, failure_reason, "
                  + "run_owned_fixture_binding_sha256, run_owned_fixture_preparing_lifecycle_epoch "
                  + "FROM "
                  + probeTable
                  + " WHERE probe_key = ?",
              failedPreparationKey);
      assertThat(failedPreparation.get(0, String.class)).isEqualTo("FAILED_PRE_ACTIVATION");
      assertThat(failedPreparation.get(1, Long.class)).isEqualTo(2L);
      assertThat(failedPreparation.get(2, Long.class)).isEqualTo(1L);
      assertThat(failedPreparation.get(3, String.class)).isEqualTo("preparation failure retained");
      assertThat(failedPreparation.get(4, String.class)).isEqualTo(originalBinding);
      assertThat(failedPreparation.get(5, Long.class)).isEqualTo(1L);
      assertSourceOnlyCanonicalColumnsNull(probe, probeTable, failedPreparationKey);
      var beforeDowngrade =
          probe.fetch(
              "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                  + probeTable
                  + " row WHERE probe_key = ?",
              valid.key());
      assertSqlState(
          () ->
              probe.execute(
                  "UPDATE "
                      + probeTable
                      + " SET status = 'PREPARING', lifecycle_epoch = 1, row_version = row_version + 1 "
                      + "WHERE probe_key = ?",
                  valid.key()),
          "23514",
          "lifecycle transitions are disabled");
      assertThat(
              probe.fetch(
                  "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                      + probeTable
                      + " row WHERE probe_key = ?",
                  valid.key()))
          .as("a lifecycle downgrade fails without changing the controlled row")
          .isEqualTo(beforeDowngrade);

      for (LifecycleGuardProbeCase probeCase : cases.subList(1, cases.size())) {
        String activation =
            probeCase.activationMutation() == null
                ? "status = 'ACTIVE', lifecycle_epoch = 2, row_version = row_version + 1"
                : probeCase.activationMutation();
        var before =
            probe.fetch(
                "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                    + probeTable
                    + " row WHERE probe_key = ?",
                probeCase.key());
        assertSqlState(
            () ->
                probe.execute(
                    "UPDATE " + probeTable + " SET " + activation + " WHERE probe_key = ?",
                    probeCase.key()),
            "23514",
            probeCase.expectedDiagnostic());
        assertThat(
                probe.fetch(
                    "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                        + probeTable
                        + " row WHERE probe_key = ?",
                    probeCase.key()))
            .as("%s fails without changing the controlled row", probeCase.label())
            .isEqualTo(before);
      }
      for (LifecycleTransitionGuardProbeCase probeCase : transitionCases) {
        var before =
            probe.fetch(
                "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                    + probeTable
                    + " row WHERE probe_key = ?",
                probeCase.key());
        assertSqlState(
            () ->
                probe.execute(
                    "UPDATE "
                        + probeTable
                        + " SET "
                        + probeCase.transitionMutation()
                        + " WHERE probe_key = ?",
                    probeCase.key()),
            "23514",
            probeCase.expectedDiagnostic());
        assertThat(
                probe.fetch(
                    "SELECT to_jsonb(row)::text || ':xmin=' || row.xmin::text FROM "
                        + probeTable
                        + " row WHERE probe_key = ?",
                    probeCase.key()))
            .as("%s fails without changing the controlled row", probeCase.label())
            .isEqualTo(before);
      }
    }
  }

  private static void insertSourceOnlyLifecycleProbeRow(
      DSLContext probe, String probeTable, String worldTable, long worldInstanceId, int key) {
    probe.execute(
        "INSERT INTO "
            + probeTable
            + " SELECT ?, source.* FROM "
            + worldTable
            + " source WHERE source.id = ?",
        key,
        worldInstanceId);
    probe.execute(
        "UPDATE "
            + probeTable
            + " SET canonical_game_instance_id = NULL, canonical_target_namespace = NULL, "
            + "canonical_tenant_id = NULL, canonical_world_slug = NULL, "
            + "playable_state_namespace_id = NULL, playable_state_scope = NULL, "
            + "public_production = NULL, playtest_lifecycle_id = NULL, "
            + "playtest_state_generation = NULL, canonical_launch_binding_operation_id = NULL, "
            + "canonical_authority_kind = NULL, fixture_provenance_digest = NULL, "
            + "canonical_realm_id = NULL, catalog_revision = NULL, game_session_tenant_id = NULL, "
            + "game_session_game_instance_id = NULL, game_design_version_id = NULL, "
            + "run_owned_fixture_preparing_lifecycle_epoch = 1, "
            + "status = 'PREPARING', lifecycle_epoch = 1, row_version = 0, failure_reason = NULL, "
            + "termination_request_id = NULL, terminated_at = NULL WHERE probe_key = ?",
        key);
  }

  private static void assertSourceOnlyCanonicalColumnsNull(
      DSLContext probe, String probeTable, int key) {
    assertThat(
            probe
                .fetchSingle(
                    "SELECT canonical_game_instance_id, canonical_target_namespace, canonical_tenant_id, "
                        + "canonical_world_slug, playable_state_namespace_id, playable_state_scope, "
                        + "public_production, playtest_lifecycle_id, playtest_state_generation, "
                        + "canonical_launch_binding_operation_id, canonical_authority_kind, "
                        + "fixture_provenance_digest, canonical_realm_id, catalog_revision, "
                        + "game_session_tenant_id, game_session_game_instance_id, game_design_version_id "
                        + "FROM "
                        + probeTable
                        + " WHERE probe_key = ?",
                    key)
                .intoArray())
        .containsOnlyNulls();
  }

  private void assertPresetContent(WorldCanonicalInstanceAssociation association) {
    var definition = RunOwnedWorldFixedPresetResources.definition();
    long worldId = association.worldInstanceId();
    var templates =
        dsl.fetch(
            "SELECT family, template_id FROM world_authored_topology_identity WHERE fixture_world_instance_id = ? ORDER BY family, template_id",
            worldId);
    Map<UUID, String> expected = new LinkedHashMap<>();
    expected.put(definition.region().templateId(), "REGION");
    expected.put(definition.zone().templateId(), "ZONE");
    definition.rooms().forEach(room -> expected.put(room.templateId(), "ROOM"));
    definition.exits().forEach(exit -> expected.put(exit.templateId(), "ROOM_EXIT"));
    assertThat(
            templates.intoMap(
                record -> record.get("template_id", UUID.class),
                record -> record.get("family", String.class)))
        .isEqualTo(expected);
    var runtime =
        dsl.fetch(
            "SELECT family, template_id FROM world_canonical_instance_topology_identity WHERE world_instance_id = ? ORDER BY family, template_id",
            worldId);
    assertThat(
            runtime.intoMap(
                record -> record.get("template_id", UUID.class),
                record -> record.get("family", String.class)))
        .isEqualTo(expected);
    assertThat(
            scalarLong(
                "SELECT count(*) FROM world_authored_topology_identity t JOIN world_canonical_instance_topology_identity r ON r.world_instance_id = t.fixture_world_instance_id AND r.family = t.family AND r.template_id = t.template_id AND r.template_private_row_key = t.private_row_key WHERE t.fixture_world_instance_id = ?",
                worldId))
        .isEqualTo(6L);
    var region =
        dsl.fetchSingle(
            "SELECT t.name, t.spacing_multiplier, t.generation_seed, r.name AS runtime_name, r.spacing_multiplier AS runtime_spacing, r.generation_seed AS runtime_seed FROM world_authored_topology_identity m JOIN region t ON t.id = m.private_row_key JOIN world_canonical_instance_topology_identity mi ON mi.world_instance_id = m.fixture_world_instance_id AND mi.template_id = m.template_id JOIN region_instance r ON r.id = mi.runtime_row_id WHERE m.fixture_world_instance_id = ? AND m.family = 'REGION'",
            worldId);
    assertThat(region.get("name", String.class)).isEqualTo(definition.region().name());
    assertThat(region.get("runtime_name", String.class)).isEqualTo(definition.region().name());
    assertThat(region.get("spacing_multiplier", Double.class))
        .isEqualTo(definition.region().spacingMultiplier());
    assertThat(region.get("runtime_spacing", Double.class))
        .isEqualTo(definition.region().spacingMultiplier());
    assertThat(region.get("generation_seed", Long.class))
        .isEqualTo(definition.region().generationSeed());
    assertThat(region.get("runtime_seed", Long.class))
        .isEqualTo(definition.region().generationSeed());
    var zone =
        dsl.fetchSingle(
            "SELECT z.name, zi.name AS runtime_name FROM world_authored_topology_identity m JOIN zone z ON z.id = m.private_row_key JOIN world_canonical_instance_topology_identity mi ON mi.world_instance_id = m.fixture_world_instance_id AND mi.template_id = m.template_id JOIN zone_instance zi ON zi.id = mi.runtime_row_id JOIN world_authored_topology_identity rm ON rm.fixture_world_instance_id = m.fixture_world_instance_id AND rm.family = 'REGION' AND rm.private_row_key = z.region_id JOIN world_canonical_instance_topology_identity ri ON ri.world_instance_id = mi.world_instance_id AND ri.template_id = rm.template_id AND ri.runtime_row_id = zi.region_instance_id WHERE m.fixture_world_instance_id = ? AND m.family = 'ZONE'",
            worldId);
    assertThat(zone.get("name", String.class)).isEqualTo(definition.zone().name());
    assertThat(zone.get("runtime_name", String.class)).isEqualTo(definition.zone().name());
    for (var room : definition.rooms()) {
      var actual =
          dsl.fetchSingle(
              "SELECT r.name, r.description, ri.name AS runtime_name, ri.description AS runtime_description FROM world_authored_topology_identity m JOIN room r ON r.id = m.private_row_key JOIN world_canonical_instance_topology_identity mi ON mi.world_instance_id = m.fixture_world_instance_id AND mi.template_id = m.template_id JOIN room_instance ri ON ri.id = mi.runtime_row_id AND ri.room_instance_row_id = mi.runtime_room_instance_id JOIN world_authored_topology_identity zm ON zm.fixture_world_instance_id = m.fixture_world_instance_id AND zm.family = 'ZONE' AND zm.private_row_key = r.zone_id JOIN world_canonical_instance_topology_identity zi ON zi.world_instance_id = mi.world_instance_id AND zi.template_id = zm.template_id AND zi.runtime_row_id = ri.zone_instance_id WHERE m.fixture_world_instance_id = ? AND m.template_id = ?",
              worldId,
              room.templateId());
      assertThat(actual.get("name", String.class)).isEqualTo(room.name());
      assertThat(actual.get("runtime_name", String.class)).isEqualTo(room.name());
      assertThat(actual.get("description", String.class)).isEqualTo(room.description());
      assertThat(actual.get("runtime_description", String.class)).isEqualTo(room.description());
    }
    for (var exit : definition.exits()) {
      var actual =
          dsl.fetchSingle(
              "SELECT e.direction, e.cost, re.direction AS runtime_direction, re.cost AS runtime_cost FROM world_authored_topology_identity m JOIN room_exit e ON e.id = m.private_row_key JOIN world_canonical_instance_topology_identity mi ON mi.world_instance_id = m.fixture_world_instance_id AND mi.template_id = m.template_id JOIN room_instance_exit re ON re.id = mi.runtime_row_id JOIN world_authored_topology_identity fm ON fm.fixture_world_instance_id = m.fixture_world_instance_id AND fm.template_id = ? AND fm.private_row_key = e.from_room_id JOIN world_authored_topology_identity tm ON tm.fixture_world_instance_id = m.fixture_world_instance_id AND tm.template_id = ? AND tm.private_row_key = e.to_room_id JOIN world_canonical_instance_topology_identity fi ON fi.world_instance_id = mi.world_instance_id AND fi.template_id = fm.template_id AND fi.runtime_room_instance_id = re.from_room_instance_record_id JOIN world_canonical_instance_topology_identity ti ON ti.world_instance_id = mi.world_instance_id AND ti.template_id = tm.template_id AND ti.runtime_room_instance_id = re.to_room_instance_record_id WHERE m.fixture_world_instance_id = ? AND m.template_id = ?",
              exit.fromRoomTemplateId(),
              exit.toRoomTemplateId(),
              worldId,
              exit.templateId());
      assertThat(actual.get("direction", String.class)).isEqualTo(exit.direction());
      assertThat(actual.get("runtime_direction", String.class)).isEqualTo(exit.direction());
      assertThat(actual.get("cost", Integer.class)).isEqualTo(exit.cost());
      assertThat(actual.get("runtime_cost", Integer.class)).isEqualTo(exit.cost());
    }
    var source = association.runOwnedFixture().sourceIntakeReceipt();
    var plan =
        RunOwnedWorldFixedPresetResources.plan(association, source, association.versionIdentity());
    var read = new TransactionTemplate(transactionManager);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
    assertThat(
            read.<String>execute(
                status ->
                    RunOwnedWorldFixedPresetResources.readCommitted(
                            dsl, plan, worldId, association.worldPrivateKeys().gameInstanceId())
                        .graphSha256()))
        .isEqualTo(plan.expectedGraphSha256());
  }

  private DriverManagerDataSource dataSource(String schema) {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    return source;
  }

  private static List<String> versions(DSLContext connection) {
    return connection
        .fetch(
            "SELECT version FROM "
                + DSL.name(HISTORY)
                + " WHERE version IS NOT NULL ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static List<String> history(DSLContext connection) {
    return connection
        .fetch(
            "SELECT to_jsonb(h)::text FROM "
                + DSL.name(HISTORY)
                + " h WHERE version IS NOT NULL ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static List<String> remainingVersions() {
    List<String> versions = new ArrayList<>(remainingVersionsThrough(1042));
    versions.add("1042.1");
    versions.add("1042.2");
    versions.add("1042.3");
    versions.add("1042.4");
    versions.add("1042.5");
    versions.add("1042.6");
    versions.add("1042.7");
    versions.add("1042.8");
    versions.add("1042.9");
    versions.add("1042.10");
    versions.add("1042.11");
    versions.add("1042.12");
    versions.add("1042.13");
    versions.add("1044");
    return List.copyOf(versions);
  }

  private static int resolvedVersionedMigrationCount(Flyway flyway) {
    int count = 0;
    for (var migration : flyway.info().all()) if (migration.getVersion() != null) count++;
    return count;
  }

  private static List<String> remainingVersionsThrough(int latestWorldVersion) {
    List<String> versions = new ArrayList<>(List.of("1008", "1009", "1009.1"));
    for (int version = 1010; version <= latestWorldVersion; version++)
      versions.add(Integer.toString(version));
    return List.copyOf(versions);
  }

  private static List<String> retainedRows(
      DSLContext connection, String schema, String table, List<String> columns) {
    String projection =
        columns.stream()
            .map(column -> DSL.name(column).toString())
            .collect(java.util.stream.Collectors.joining(","));
    return connection
        .fetch(
            "SELECT to_jsonb(retained)::text FROM (SELECT xmin::text AS retained_xmin, "
                + projection
                + " FROM "
                + DSL.name(schema, table)
                + ") retained ORDER BY 1")
        .getValues(0, String.class);
  }

  private long scalarLong(String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchSingle(sql, bindings).get(0, Long.class));
  }

  private String scalarText(String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchSingle(sql, bindings).get(0, String.class));
  }

  private String associationCheckExpression(DSLContext connection, String constraintName) {
    return Objects.requireNonNull(
        connection
            .fetchSingle(
                "SELECT pg_get_expr(c.conbin, c.conrelid) FROM pg_constraint c "
                    + "WHERE c.conrelid = 'world_canonical_instance_association'::regclass "
                    + "AND c.contype = 'c' AND c.conname = ?",
                constraintName)
            .get(0, String.class));
  }

  private Boolean evaluateAssociationCheck(
      DSLContext connection, String expression, Map<String, Object> typedShape) {
    return connection
        .fetchSingle(
            "SELECT ("
                + expression
                + ") FROM jsonb_populate_record(NULL::world_canonical_instance_association, ?::jsonb) AS candidate",
            json(typedShape))
        .get(0, Boolean.class);
  }

  private static Map<String, Object> associationShape(int schemaVersion) {
    Map<String, Object> shape = new LinkedHashMap<>();
    shape.put("schema_version", schemaVersion);
    shape.put("canonical_authority_kind", schemaVersion == 3 ? "RUN_OWNED_FIXTURE" : null);
    shape.put("canonical_realm_id", schemaVersion == 1 ? null : UUID.randomUUID().toString());
    shape.put("catalog_revision", schemaVersion == 1 ? null : 1L);
    shape.put(
        "canonical_launch_binding_operation_id",
        schemaVersion == 3 ? null : UUID.randomUUID().toString());
    shape.put("release_attestation_digest", schemaVersion == 3 ? null : VALID_DIGEST);
    shape.put("source_evidence_digest", VALID_DIGEST);
    shape.put("intake_request_digest", VALID_DIGEST);
    shape.put("intake_receipt_digest", VALID_DIGEST);
    shape.put("descriptor_request_digest", VALID_DIGEST);
    shape.put("descriptor_result_digest", VALID_DIGEST);
    if (schemaVersion == 3) {
      shape.put("fixture_provenance_digest", VALID_DIGEST);
      shape.put("game_session_tenant_id", 1L);
      shape.put("game_session_game_instance_id", 1L);
      shape.put("game_design_version_id", 1L);
      shape.put("preparation_input_digest", VALID_DIGEST);
      shape.put("preparing_lifecycle_epoch", 1L);
      shape.put("game_session_association_response_bytes", "\\x01");
    }
    return shape;
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }

  private void assertFixedPresetReadbackMode(
      String mode,
      RunOwnedWorldFixedPresetResources.FixedPresetPlan plan,
      long worldId,
      long privateGameInstanceKey,
      RunOwnedWorldFixedPresetResources.MaterializedFixedPreset expected,
      boolean readOnly,
      int isolationLevel,
      String expectedIsolation,
      boolean supported,
      Map<String, List<String>> rowsBefore) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(isolationLevel);
    transaction.setReadOnly(readOnly);
    if (supported) {
      RunOwnedWorldFixedPresetResources.MaterializedFixedPreset actual =
          transaction.execute(
              status ->
                  readPresetInCurrentTransaction(
                      plan, worldId, privateGameInstanceKey, readOnly, expectedIsolation));
      assertThat(actual).as(mode).isEqualTo(expected);
    } else {
      assertSqlState(
          () ->
              transaction.execute(
                  status ->
                      readPresetInCurrentTransaction(
                          plan, worldId, privateGameInstanceKey, readOnly, expectedIsolation)),
          "23514",
          "Run-owned fixed-preset readback requires an owner read transaction");
    }
    assertThat(allRows(dsl)).as("no row or byte changes after " + mode).isEqualTo(rowsBefore);
    assertNoPendingManifests(worldId);
  }

  private void assertRoomReadbackRequiresBothRooms(
      RunOwnedWorldFixedPresetResources.FixedPresetPlan plan,
      long worldId,
      long privateGameInstanceKey,
      Map<String, List<String>> rowsBefore) {
    String schema = dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    String originalRoutine =
        DSL.name(schema, "world_read_run_owned_fixed_preset") + "(text,text,bigint,bigint)";
    String installedDefinition =
        dsl.fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", originalRoutine)
            .get(0, String.class);
    var originalReadback =
        RunOwnedWorldFixedPresetResources.readCommitted(dsl, plan, worldId, privateGameInstanceKey);
    String roomCountStart =
        "OR (SELECT count(*)\n"
            + "        FROM \""
            + schema
            + "\".world_authored_topology_identity m\n"
            + "        JOIN \""
            + schema
            + "\".room r ON r.id = m.private_row_key";
    String anyRoomStart =
        "OR NOT EXISTS (\n"
            + "        SELECT 1\n"
            + "        FROM \""
            + schema
            + "\".world_authored_topology_identity m\n"
            + "        JOIN \""
            + schema
            + "\".room r ON r.id = m.private_row_key";
    String roomCountEnd =
        "AND ri.zone_instance_id = zi.runtime_row_id)) <> 2\n"
            + "        OR (SELECT count(*) FROM \""
            + schema
            + "\".world_authored_topology_identity m\n"
            + "            JOIN \""
            + schema
            + "\".room_exit e";
    String anyRoomEnd =
        "AND ri.zone_instance_id = zi.runtime_row_id))\n"
            + "        OR (SELECT count(*) FROM \""
            + schema
            + "\".world_authored_topology_identity m\n"
            + "            JOIN \""
            + schema
            + "\".room_exit e";
    String callArguments = "(?, ?, ?, ?)";
    for (var roomMismatch :
        List.of(
            Map.entry("Candle-lit Antechamber", "Candle-lit Antechamber (test-only mismatch)"),
            Map.entry("Smith''s Annex", "Smith''s Annex (test-only mismatch)"))) {
      String expectedRoomName = "r.name = '" + roomMismatch.getKey() + "'";
      String substitutedRoomName = "r.name = '" + roomMismatch.getValue() + "'";
      String changedRoomDefinition =
          replaceExactlyOnce(
              installedDefinition,
              expectedRoomName,
              substitutedRoomName,
              "fixed room expectation " + roomMismatch.getKey());

      String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
      String oldName = "world_test_old_room_readback_" + suffix;
      String currentName = "world_test_current_room_readback_" + suffix;
      String oldQualifiedName = DSL.name(schema, oldName).toString();
      String currentQualifiedName = DSL.name(schema, currentName).toString();
      String nameAnchor = "world_read_run_owned_fixed_preset(";
      String oldDefinition =
          replaceExactlyOnce(
              changedRoomDefinition,
              nameAnchor,
              oldName + "(",
              "old test function name for " + roomMismatch.getKey());
      String currentDefinition =
          replaceExactlyOnce(
              changedRoomDefinition,
              nameAnchor,
              currentName + "(",
              "current test function name for " + roomMismatch.getKey());
      oldDefinition =
          replaceExactlyOnce(
              oldDefinition,
              roomCountStart,
              anyRoomStart,
              "old room predicate start for " + roomMismatch.getKey());
      oldDefinition =
          replaceExactlyOnce(
              oldDefinition,
              roomCountEnd,
              anyRoomEnd,
              "old room predicate end for " + roomMismatch.getKey());

      try {
        dsl.execute(oldDefinition);
        dsl.execute(currentDefinition);
        assertThat(
                dsl.fetchOne(
                    "SELECT * FROM " + oldQualifiedName + callArguments,
                    plan.planJson(),
                    plan.expectedContentDigest(),
                    worldId,
                    privateGameInstanceKey))
            .as(
                "the former any-matching-room check accepts a mismatch for "
                    + roomMismatch.getKey())
            .isNotNull();
        assertSqlState(
            () ->
                dsl.fetchOne(
                    "SELECT * FROM " + currentQualifiedName + callArguments,
                    plan.planJson(),
                    plan.expectedContentDigest(),
                    worldId,
                    privateGameInstanceKey),
            "23514",
            "RUN_OWNED_WORLD_FIXED_PRESET_TOPOLOGY_MISMATCH");
        assertThat(allRows(dsl))
            .as("test-local read copies preserve rows and xmin for " + roomMismatch.getKey())
            .isEqualTo(rowsBefore);
      } finally {
        dsl.execute(
            "DROP FUNCTION IF EXISTS "
                + oldQualifiedName
                + "(text,text,bigint,bigint), "
                + currentQualifiedName
                + "(text,text,bigint,bigint)");
      }
    }
    assertThat(
            RunOwnedWorldFixedPresetResources.readCommitted(
                dsl, plan, worldId, privateGameInstanceKey))
        .as("the installed committed read remains unchanged after the controlled copies")
        .isEqualTo(originalReadback);
    assertThat(allRows(dsl))
        .as("the installed read and test-local copies preserve every stored row and xmin")
        .isEqualTo(rowsBefore);
  }

  private static String replaceExactlyOnce(
      String source, String anchor, String replacement, String description) {
    int offset = source.indexOf(anchor);
    assertThat(offset).as(description + " anchor exists").isGreaterThanOrEqualTo(0);
    assertThat(source.indexOf(anchor, offset + anchor.length()))
        .as(description + " anchor is unique")
        .isEqualTo(-1);
    return source.substring(0, offset) + replacement + source.substring(offset + anchor.length());
  }

  private RunOwnedWorldFixedPresetResources.MaterializedFixedPreset readPresetInCurrentTransaction(
      RunOwnedWorldFixedPresetResources.FixedPresetPlan plan,
      long worldId,
      long privateGameInstanceKey,
      boolean readOnly,
      String expectedIsolation) {
    var settings =
        dsl.fetchSingle(
            "SELECT current_setting('transaction_isolation'), "
                + "current_setting('transaction_read_only')");
    assertThat(settings.get(0, String.class)).isEqualTo(expectedIsolation);
    assertThat(settings.get(1, String.class)).isEqualTo(readOnly ? "on" : "off");
    return RunOwnedWorldFixedPresetResources.readCommitted(
        dsl, plan, worldId, privateGameInstanceKey);
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

  private RunOwnedCanonicalWorldLaunchEvidence controlledEvidence() {
    return RunOwnedWorldFixtureEvidenceTestFixtures.create(objectMapper);
  }
}
