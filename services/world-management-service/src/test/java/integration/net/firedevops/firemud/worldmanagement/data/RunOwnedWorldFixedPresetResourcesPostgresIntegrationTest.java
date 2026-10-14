package net.firedevops.firemud.worldmanagement.data;

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
import org.flywaydb.core.api.MigrationVersion;
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
  private static final String CONTENT_DIGEST =
      "sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc";
  private static final String VALID_DIGEST = "sha256:" + "a".repeat(64);

  private static final Map<String, String> PARENT_MIGRATIONS =
      Map.ofEntries(
          Map.entry(
              "V1000__create_saga_schema.sql",
              "13f58ab66bd9c12483e113c8758ef9907ab3d14d47f61a298dd18f929d5aa537"),
          Map.entry(
              "V1001__saga_instance_table.sql",
              "ebc4bbfd1a10314850e19ca6017d654a98c2ee4f5b95d1cce2ee078d990eb2bf"),
          Map.entry(
              "V1002__saga_step_table.sql",
              "d9d98a2e51d53d78d17d27b15fe696e8b1e161af7c6de142680ec7b8a0dc7c31"),
          Map.entry(
              "V10__room_exit_direction.sql",
              "9b174b153af563916b409abdad3da24f2d88f8a547de6d2b1f1085da3f06264e"),
          Map.entry(
              "V11__room_localized_text_variants.sql",
              "cded77589b64b740689276c96e346ffff97226294b72ef184ab9ae5eaeea7b65"),
          Map.entry(
              "V12__world_instance_activation.sql",
              "3fb5eecc00edb542dd45dd8dd11a632732f47277f96cae4bfc26ebf0f905fa62"),
          Map.entry(
              "V13__world_instance_termination.sql",
              "894f7002fe67c6c65ab04018be7802c78b505666a0d16e2d2794eac4fccd54d4"),
          Map.entry(
              "V14__room_instance_topology.sql",
              "4134d648bb29fd22e4e9d0b0df38454a698f1ab9449c1118c027a4e256658bc4"),
          Map.entry(
              "V15__zone_instance_topology.sql",
              "413445894f5f39eb726e5824566c7bacab8ab81e7c51aadfe3d5917544395677"),
          Map.entry(
              "V16__runtime_world_event_scope.sql",
              "905ac02e6a65308ab70fc5e986608131043c7eec7a2ebd1615f13652d36a4ffb"),
          Map.entry(
              "V17__world_instance_remap_set.sql",
              "286a77a1110a9f3b8d7f88a6a829edb5551dbef73f02de1cdbf184c5947181d8"),
          Map.entry(
              "V18__version_scoped_world_templates.sql",
              "02b07237b04c64d9f4215c42b4cefe31bdc05bd066c6f0823f14733a9772819b"),
          Map.entry(
              "V19__world_design_mutation_ledger.sql",
              "fbbec4b460ce5aa9d4ea9dce70b156840c4cb8d99a0a1589771bad7a220d2f59"),
          Map.entry(
              "V1__init.sql", "7c65f75f6d2e32f7c07e24ea0260768e5660987a54a9148cef3422f5352bee64"),
          Map.entry(
              "V20__world_entity_spawn_binding.sql",
              "ac5c8c2c326f47c4782c5595d7bbde478320ebb05bcde14f8e02a3bee158afd7"),
          Map.entry(
              "V21__scoped_generation_rules.sql",
              "9e358fe5dd4a41f92a5b16681ad2adc7d24b969e5f476152228b2462974e68dd"),
          Map.entry(
              "V22__room_instance_internal_record_naming.sql",
              "f56fce69e28c9dd9457dc508b016657480abd78d2a3190e782b8e2c0e3c4f340"),
          Map.entry(
              "V23.0.1__world_run_owned_fixture_launch_binding.sql",
              "f014485e67b32b6404bb5cd40dc5568c2d1d9bc1993bafe826660c5b438ceecc"),
          Map.entry(
              "V23.1__world_authored_source_intake.sql",
              "a7ac30d48da1911eb3395716e827ac0f0ec612970d4f40b5a27530d634430445"),
          Map.entry(
              "V23__initial_admission_bind_hold.sql",
              "5e6f6070a02fbf09836dcc663ee3c12390dcb0dbea33a9e15ddd021a913ca1f1"),
          Map.entry(
              "V24__world_canonical_tenant_mutation_guard.sql",
              "6ad0933008cea2d0fe28c8e247277950f789ac1f6cc061f1204b23cb044e82ea"),
          Map.entry(
              "V25__world_design_publication_fence.sql",
              "c852a2e24232df5aec8628c1046570b49edb465a416bbdf942b60fe80ae17f94"),
          Map.entry(
              "V26__world_complete_launch_binding.sql",
              "171595e883d8d459bfd05b8331cc2ace6faf41406b26a743cdc6d133cba33f7e"),
          Map.entry(
              "V27.1__world_publication_owner_binding.sql",
              "93def8666763a08b1db34fd3db88cfa9eea0d5bc7bf836cffe9ff8766d0468f1"),
          Map.entry(
              "V27.2__world_legacy_tenant_claim_concurrency.sql",
              "5a0c8de956baf2dce47e45bff6b3e022dd24d6a4c7c0ebeb4d55fdafb757f9bc"),
          Map.entry(
              "V27__world_authored_version_identity.sql",
              "e9ff823152776361f2ac79d08b6dddce72105892fce0706041e8956994d8db0d"),
          Map.entry(
              "V2__add_tenant_and_expiration.sql",
              "180a5c49ce5dc144daeb442c833921a410c51828f8196d1bbb4e6fe2d1955838"),
          Map.entry(
              "V3__world_events.sql",
              "b6aaa18a6f01c7270e062a9cf8d2b13e0a57a092e7f418b87eaa382e3c9f3f3d"),
          Map.entry(
              "V4__room_exits.sql",
              "02fbaee1e529649fa6dbe803795ac78cc40f5121735399131592b32bd23cb23a"),
          Map.entry(
              "V5__generation_rules.sql",
              "27ba4f1827c6eafa14ccd6a4b85c9eb5c3de312ee85cdcae2da846ee1d8817a4"),
          Map.entry(
              "V6__region_shard.sql",
              "a8bca5bad73f02936a8971cc9c34c19921d02bee55f860cad08d2c73932025cb"),
          Map.entry(
              "V7__optimistic_locking_and_indexes.sql",
              "40d428bf21b99351068ee6cf11778ab3171f720f00ad2d8c9230b245093f9261"),
          Map.entry(
              "V8__region_generation_metadata.sql",
              "a3bc7811eff6743f771346188f811b14d882356f67ccdbf6213bb2cc87695570"),
          Map.entry(
              "V9__region_spacing_multiplier.sql",
              "5a5e2279d78b88cfad19802bbdf37416df714ee864265b08472b61fb234b6c32"));

  private static final String HISTORY = "flyway_schema_history_world_management_service";
  // Literal disclosed donor postimages, not qualification or current authority.
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
    assertThat(versions(dsl)).hasSize(75);
    assertThat(versions(dsl).subList(34, 40))
        .containsExactly("1003", "1004", "1005", "1006", "1007", "1007.1");
    assertThat(versions(dsl).subList(40, 75)).containsExactlyElementsOf(remainingVersions());
    var configured =
        migration(dataSource("world_management_service"), "world_management_service", null);
    assertThat(configured.getConfiguration().isOutOfOrder()).isFalse();
    configured.validate();
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
    var association =
        associationRepository()
            .readRunOwnedFixtureAssociation(
                evidence, RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence));
    assertThat(prepared.tenantId()).isEqualTo(association.worldPrivateKeys().tenantId());
    assertThat(prepared.gameInstanceId())
        .isEqualTo(association.worldPrivateKeys().gameInstanceId());
    assertThat(prepared.versionId()).isEqualTo(association.worldPrivateKeys().versionId());
    assertThat(
            scalarLong(
                "SELECT row_version FROM world_instance WHERE id = ?",
                association.worldInstanceId()))
        .isZero();
    assertPresetContent(association);
    var original = allRows(dsl);
    var fresh = RunOwnedWorldFixtureEvidenceTestFixtures.freshObservation(evidence);
    assertThat(RunOwnedWorldFixtureLifecycleTestFactory.prepare(nativeService(fresh), fresh))
        .isEqualTo(prepared);
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
  void installed34ThroughParent40ToFull75RetainsOriginalRowsXminAndStableLegacyUpdates()
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
      var full = migration(source, schema, null);
      full.migrate();
      full.validate();
      assertThat(full.getConfiguration().isOutOfOrder()).isFalse();
      assertThat(versions(connection)).hasSize(75);
      assertThat(versions(connection).subList(40, 75))
          .containsExactlyElementsOf(remainingVersions());
      assertThat(history(connection).subList(0, 40)).isEqualTo(parentHistory);
      for (var entry : originalColumns.entrySet())
        assertThat(retainedRows(connection, schema, entry.getKey(), entry.getValue()))
            .as("all original values and xmin for %s", entry.getKey())
            .isEqualTo(originalRows.get(entry.getKey()));
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
                  .fetchSingle("SELECT count(*) FROM world_canonical_instance_association")
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
      // Roll both writes back, then recompare the complete original row images and xmin.
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
      for (var entry : originalColumns.entrySet())
        assertThat(retainedRows(connection, schema, entry.getKey(), entry.getValue()))
            .isEqualTo(originalRows.get(entry.getKey()));
      // No current Human protection is fabricated. Literal pre-extension legacy and owner
      // receipts are retained; a historical canonical Human association requires a separate donor.
    } finally {
      connection.execute("DROP SCHEMA IF EXISTS " + DSL.name(schema) + " CASCADE");
    }
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
                      dsl, evidence, plan, worldId, privateKey);
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

  private WorldLifecycleCommandServiceImpl nativeService(RunOwnedWorldLaunchEvidence evidence) {
    return nativeService(evidence, new AtomicInteger());
  }

  private WorldLifecycleCommandServiceImpl nativeService(
      RunOwnedWorldLaunchEvidence evidence, AtomicInteger upstreamReads) {
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
        new RunOwnedWorldLaunchEvidenceProvider(
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

  private static Flyway migration(DriverManagerDataSource source, String schema, String target) {
    Path root = repositoryRoot();
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table(HISTORY)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(
                "filesystem:"
                    + root.resolve(
                        "services/world-management-service/src/main/resources/db/migration"),
                "filesystem:"
                    + root.resolve("services/common-saga/src/main/resources/db/migration/saga"));
    if (target != null) configuration.target(MigrationVersion.fromVersion(target));
    return configuration.load();
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
    List<String> versions = new ArrayList<>(List.of("1008", "1009", "1009.1"));
    for (int version = 1010; version <= 1041; version++) versions.add(Integer.toString(version));
    return List.copyOf(versions);
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("settings.gradle.kts"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("FireMUD repository root unavailable");
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
    return RunOwnedWorldFixtureEvidenceTestFixtures.create(objectMapper);
  }
}
