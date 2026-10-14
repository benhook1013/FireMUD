package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
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
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded native upgrade from the literal d7c8f84 World/Saga parent. External source and Version
 * values are controlled; real World repositories retain them with all storage guards enabled. The
 * parent World row is legacy storage, not a Human frozen graph or canonical activation claim.
 * Positive component storage remains explicitly historical at 1007.1; the retained schema-2
 * snapshot upgrades through the current default frontier. The guarded-store case also repeats its
 * relevant denials at the current frontier. These component cases do not qualify current Account
 * permission or genuine Human authority. Physical Docker execution with zero skips is required to
 * qualify this definition.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldGuardedAuthoredGraphCapturePostgresIntegrationTest {
  private static final String HISTORY = "flyway_schema_history_world_management_service";
  private static final String NAMESPACE = "firemud";

  // Exact SHA-256 bytes at d7c8f84fa947644a651304205803c3919c81684e: 31 World and 3 Saga.
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

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void exactParentWithRetainedOwnerAndLegacyWorldRowsUpgradesToHistoricalGuardedFrontier(
      @TempDir Path parentMigrations) throws Exception {
    Path root = repositoryRoot();
    Path worldMigrations =
        root.resolve("services/world-management-service/src/main/resources/db/migration");
    Path sagaMigrations = root.resolve("services/common-saga/src/main/resources/db/migration/saga");
    for (var entry : PARENT_MIGRATIONS.entrySet()) {
      Path original = worldMigrations.resolve(entry.getKey());
      if (!Files.exists(original)) original = sagaMigrations.resolve(entry.getKey());
      byte[] originalBytes = Files.readAllBytes(original);
      assertThat(
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBytes)))
          .as("literal parent artifact %s", entry.getKey())
          .isEqualTo(entry.getValue());
      Files.copy(original, parentMigrations.resolve(entry.getKey()));
    }

    String schema = "world_parent_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    try {
      Flyway parent = flyway(dataSource, schema, "filesystem:" + parentMigrations);
      assertThat(parent.getConfiguration().isOutOfOrder()).isFalse();
      parent.migrate();
      parent.validate();
      assertThat(history(dsl)).hasSize(34);
      assertThat(
              dsl.fetchSingle(
                      "SELECT max(version::int) FROM " + HISTORY + " WHERE version ~ '^[0-9]+$'")
                  .get(0, Integer.class))
          .isEqualTo(1002);
      assertThat(
              dsl.fetch("SELECT script FROM " + HISTORY + " WHERE version IS NOT NULL")
                  .getValues(0, String.class))
          .containsExactlyInAnyOrderElementsOf(PARENT_MIGRATIONS.keySet());
      assertThat(
              dsl.fetchSingle("SELECT to_regclass(?)", schema + ".world_authored_graph_snapshot")
                  .get(0))
          .isNull();

      var write = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var sourceOwner = new WorldAuthoredSourceIntakeRepository(dsl);
      var versionOwner = new WorldAuthoredVersionIdentityRepository(dsl);
      // This ordinary legacy row predates any graph/association migration and is independently
      // scoped.
      long worldId =
          dsl.fetchSingle(
                  "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                      + "control_plane_request_id, launch_descriptor_id, version_id, runtime_flags_json, "
                      + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
                      + "version_state_epoch, status) VALUES (7001, 8101, 17, 'retained-parent-world', "
                      + "'retained-parent-descriptor', 11, '{}', 'parent-generation', 77, "
                      + "'retained-parent-release', 3, 'PREPARING') RETURNING id")
              .get(0, Long.class);
      dsl.execute(
          "INSERT INTO region_instance (tenant_id, game_instance_id, world_instance_id, "
              + "name, generation_seed) VALUES (7001, 8101, ?, 'Retained parent region', 42)",
          worldId);
      long sagaId =
          dsl.fetchSingle(
                  "INSERT INTO saga_instance (saga_name, state) "
                      + "VALUES ('retained-parent-saga', 'PENDING') RETURNING id")
              .get(0, Long.class);
      dsl.execute(
          "INSERT INTO saga_step (instance_id, name, status, attempt) "
              + "VALUES (?, 'retained-parent-step', 'PENDING', 2)",
          sagaId);

      AuthoredWorldSourceEvidence upstream = controlledSource();
      UUID intakeRequest = UUID.randomUUID();
      WorldAuthoredSourceIntakeReceipt source =
          Objects.requireNonNull(
              write.execute(status -> sourceOwner.acceptFresh(NAMESPACE, intakeRequest, upstream)));
      UUID canonicalVersion = UUID.randomUUID();
      var versionEvidence =
          AuthoredWorldVersionStateEvidence.create(
              new AuthoredWorldVersionStateEvidence.Request(
                  1,
                  NAMESPACE,
                  UUID.randomUUID(),
                  upstream.canonicalTenantId(),
                  upstream.worldSlug(),
                  upstream.operationId(),
                  upstream.evidenceDigest(),
                  9000000042L),
              upstream,
              canonicalVersion,
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
              3L);
      WorldAuthoredVersionIdentityReceipt version =
          Objects.requireNonNull(
              write.execute(status -> versionOwner.acceptFresh(source, versionEvidence)));
      assertThat(source.localTenantKey()).isNotEqualTo(7001L);
      assertThat(version.localVersionKey()).isNotEqualTo(version.gameDesignVersionId());
      assertThat(sourceOwner.read(NAMESPACE, intakeRequest)).contains(source);
      assertThat(
              versionOwner.readByCanonicalVersion(
                  NAMESPACE, upstream.canonicalTenantId(), upstream.worldSlug(), canonicalVersion))
          .contains(version);

      List<String> originalHistory = history(dsl);
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
        List<String> columns =
            dsl.fetch(
                    "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                    schema,
                    table)
                .getValues(0, String.class);
        assertThat(columns).isNotEmpty();
        originalColumns.put(table, columns);
        originalRows.put(table, retainedRows(dsl, table, columns));
        assertThat(originalRows.get(table)).isNotEmpty();
      }

      // Historical 40-migration frontier from actual World/Saga locations, with every guard on.
      // Current default-frontier retention is a separate native proof, not inferred here.
      Flyway current =
          historicalGuardedStage(
              dataSource, schema, "filesystem:" + worldMigrations, "filesystem:" + sagaMigrations);
      assertThat(current.getConfiguration().isOutOfOrder()).isFalse();
      current.migrate();
      current.validate();
      assertThat(history(dsl).subList(0, 34)).isEqualTo(originalHistory);
      assertThat(history(dsl)).hasSize(40);
      assertThat(
              dsl.fetch(
                      "SELECT version FROM "
                          + HISTORY
                          + " WHERE installed_rank > 34 ORDER BY installed_rank")
                  .getValues(0, String.class))
          .containsExactlyElementsOf(List.of("1003", "1004", "1005", "1006", "1007", "1007.1"));
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) FROM " + HISTORY + " WHERE version IS NOT NULL AND success")
                  .get(0, Long.class))
          .isEqualTo(40L);
      for (var entry : originalColumns.entrySet()) {
        assertThat(retainedRows(dsl, entry.getKey(), entry.getValue()))
            .as("exact original column bytes for %s", entry.getKey())
            .isEqualTo(originalRows.get(entry.getKey()));
      }
      assertThat(sourceOwner.read(NAMESPACE, intakeRequest)).contains(source);
      assertThat(
              versionOwner.readByCanonicalVersion(
                  NAMESPACE, upstream.canonicalTenantId(), upstream.worldSlug(), canonicalVersion))
          .contains(version);
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_authored_graph_snapshot")
                  .get(0, Long.class))
          .isZero();
      assertThat(
              dsl.fetchSingle(
                      "SELECT to_regclass(?)", schema + ".world_canonical_instance_association")
                  .get(0))
          .isNull();
      assertThat(
              dsl.fetchSingle(
                      "SELECT to_regclass(?)", schema + ".world_canonical_instance_preparation")
                  .get(0))
          .isNull();
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                          + "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND NOT t.tgisinternal "
                          + "AND t.tgenabled = 'D'",
                      schema)
                  .get(0, Long.class))
          .isZero();
      // Two distinct legacy rows must update without serializing on their shared reservation.
      long otherRegion =
          dsl.fetchSingle(
                  "INSERT INTO region_instance (tenant_id, game_instance_id, world_instance_id, name, generation_seed) "
                      + "VALUES (7001, 8101, ?, 'Independent retained region', 43) RETURNING id",
                  worldId)
              .get(0, Long.class);
      long firstRegion =
          dsl.fetchSingle(
                  "SELECT min(id) FROM region_instance WHERE world_instance_id = ?", worldId)
              .get(0, Long.class);
      try (var first = dataSource.getConnection();
          var second = dataSource.getConnection()) {
        first.setAutoCommit(false);
        second.setAutoCommit(false);
        try (var statement = first.createStatement()) {
          statement.executeUpdate(
              "UPDATE region_instance SET name = 'held legacy row' WHERE id = " + firstRegion);
        }
        try (var statement = second.createStatement()) {
          statement.execute("SET LOCAL lock_timeout = '1000ms'");
          assertThat(
                  statement.executeUpdate(
                      "UPDATE region_instance SET name = 'independent legacy row' WHERE id = "
                          + otherRegion))
              .isEqualTo(1);
        } finally {
          second.rollback();
          first.rollback();
        }
      }
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "DELETE FROM world_authored_source_intake WHERE operation_id = ?",
                      source.operationId()))
          .hasMessageContaining("immutable");
      assertThat(
              retainedRows(
                  dsl,
                  "world_authored_source_intake",
                  originalColumns.get("world_authored_source_intake")))
          .isEqualTo(originalRows.get("world_authored_source_intake"));
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void schemaTwoRepositorySnapshotRetainsEveryOriginalByteAndXminThroughSchemaThreeGuard()
      throws Exception {
    String schema = "world_retained_schema2_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(manager);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    Path root = repositoryRoot();
    String worldLocation =
        "filesystem:"
            + root.resolve("services/world-management-service/src/main/resources/db/migration");
    String sagaLocation =
        "filesystem:" + root.resolve("services/common-saga/src/main/resources/db/migration/saga");
    try {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .table(HISTORY)
          .placeholders(Map.of("serviceSchema", schema))
          .locations(worldLocation, sagaLocation)
          .target(MigrationVersion.fromVersion("1006"))
          .load()
          .migrate();

      AuthoredWorldSourceEvidence upstream = controlledSource();
      UUID intakeRequest = UUID.randomUUID();
      WorldAuthoredSourceIntakeReceipt intake =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredSourceIntakeRepository(dsl)
                          .acceptFresh(NAMESPACE, intakeRequest, upstream)));
      UUID canonicalVersion = UUID.randomUUID();
      long gameDesignVersion = 43L;
      var versionState =
          AuthoredWorldVersionStateEvidence.create(
              new AuthoredWorldVersionStateEvidence.Request(
                  1,
                  NAMESPACE,
                  UUID.randomUUID(),
                  upstream.canonicalTenantId(),
                  upstream.worldSlug(),
                  upstream.operationId(),
                  upstream.evidenceDigest(),
                  gameDesignVersion),
              upstream,
              canonicalVersion,
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
              1L);
      WorldAuthoredVersionIdentityReceipt version =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredVersionIdentityRepository(dsl)
                          .acceptFresh(intake, versionState)));

      String publicationRequest = "retained-schema2-repository";
      String requestDigest = "b".repeat(64);
      String contentDigest = "c".repeat(64);
      String captureRequestDigest = "d".repeat(64);
      String legacyCheckpoint = "version:" + version.localVersionKey();
      String workflow =
          PublicationDigestRequestBinding.full(
                  upstream.canonicalTenantId().toString(),
                  Long.toString(gameDesignVersion),
                  publicationRequest)
              .derivedWorkflowIdentity();
      var publication =
          new WorldDesignPublicationFenceEvidence(
              NAMESPACE,
              upstream.canonicalTenantId(),
              canonicalVersion,
              version.operationId(),
              gameDesignVersion,
              intake.intakeRequestId(),
              intake.operationId(),
              intake.requestDigest(),
              intake.sourceOperationId(),
              intake.sourceEvidenceDigest(),
              intake.receiptDigest(),
              publicationRequest,
              requestDigest,
              1L,
              workflow);
      var fenceRepository =
          new WorldDesignPublicationFenceRepository(
              dsl, new WorldAuthoredSourceIntakeRepository(dsl));
      // These are controlled predecessor-checkpoint fields for repository retention only. The
      // truthful legacy version token is not a complete commit ID, and no producer or APPLIED
      // authority is inferred from it.
      var frozen =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      fenceRepository.claimFreeze(
                          publication,
                          () ->
                              new WorldDesignPublicationFenceEvidence.Checkpoint(
                                  legacyCheckpoint, contentDigest, 2))));
      var request =
          new WorldAuthoredGraphSnapshot.CaptureRequest(
              NAMESPACE,
              upstream.canonicalTenantId(),
              canonicalVersion,
              intake.intakeRequestId(),
              frozen.publicationFence(),
              publicationRequest,
              requestDigest,
              1L,
              workflow,
              legacyCheckpoint,
              contentDigest,
              2,
              List.of());
      var graphMapper = new tools.jackson.databind.ObjectMapper();
      byte[] freshGraphBytes =
          new WorldAuthoredGraphReader(dsl)
              .readAndValidateGraph(intake.localTenantKey(), version.localVersionKey())
              .encode(graphMapper);
      assertThat(new String(freshGraphBytes, StandardCharsets.UTF_8))
          .isEqualTo(
              "{\"snapshotSchemaVersion\":2,\"regions\":[],\"zones\":[],\"rooms\":[],"
                  + "\"roomExits\":[],\"generationRules\":[],"
                  + "\"worldEntitySpawnBindings\":[]}");
      // Retain actual predecessor bytes independently of the current schema-2 writer.
      byte[] graphBytes =
          ("{\"snapshotSchemaVersion\":1,\"regions\":[],\"zones\":[],\"rooms\":[],"
                  + "\"roomExits\":[],\"generationRules\":[],"
                  + "\"worldEntitySpawnBindings\":[]}")
              .getBytes(StandardCharsets.UTF_8);
      assertThat(WorldAuthoredGraph.decode(graphBytes, graphMapper).encode(graphMapper))
          .isEqualTo(graphBytes);
      var snapshotRepository = new WorldAuthoredGraphSnapshotRepository(dsl);
      WorldAuthoredGraphSnapshot stored =
          Objects.requireNonNull(
              write.execute(
                  status -> {
                    var provenance = snapshotRepository.lockAndResolve(request);
                    return snapshotRepository.insertAndReadback(
                        new WorldAuthoredGraphSnapshot(
                            UUID.randomUUID(),
                            provenance.targetNamespace(),
                            provenance.canonicalTenantId(),
                            provenance.canonicalVersionId(),
                            provenance.versionIdentityOperationId(),
                            provenance.worldSlug(),
                            provenance.gameDesignVersionId(),
                            provenance.localVersionKey(),
                            provenance.localTenantKey(),
                            (short) 1,
                            provenance.intakeOperationId(),
                            provenance.intakeRequestId(),
                            provenance.intakeRequestDigest(),
                            provenance.sourceOperationId(),
                            provenance.sourceEvidenceDigest(),
                            provenance.intakeReceiptDigest(),
                            frozen.publicationFence(),
                            publicationRequest,
                            requestDigest,
                            1L,
                            workflow,
                            legacyCheckpoint,
                            contentDigest,
                            2,
                            captureRequestDigest,
                            "[]",
                            "[]",
                            graphBytes,
                            WorldAuthoredGraphSnapshotCapture.sha256(graphBytes),
                            WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED));
                  }));
      List<String> columns =
          dsl.fetch(
                  "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema=? AND table_name='world_authored_graph_snapshot' "
                      + "ORDER BY ordinal_position",
                  schema)
              .getValues(0, String.class);
      List<String> before = retainedRows(dsl, "world_authored_graph_snapshot", columns);
      assertThat(before).hasSize(1);

      // Keep the default current frontier: historical bytes/xmin remain retained storage
      // through later guards, not newly minted schema-3 or Account-protected capture authority.
      flyway(source, schema, worldLocation, sagaLocation).migrate();

      assertThat(
              dsl.fetch(
                      "SELECT version FROM "
                          + HISTORY
                          + " WHERE version IN ('1007','1007.1') ORDER BY installed_rank")
                  .getValues(0, String.class))
          .containsExactly("1007", "1007.1");
      assertThat(retainedRows(dsl, "world_authored_graph_snapshot", columns)).isEqualTo(before);
      WorldAuthoredGraphSnapshot retained =
          snapshotRepository.findByFence(frozen.publicationFence());
      assertThat(retained).isNotNull();
      assertThat(retained.snapshotId()).isEqualTo(stored.snapshotId());
      assertThat(retained.digestSchemaVersion()).isEqualTo(2);
      assertThat(retained.appliedCommitId()).isEqualTo(legacyCheckpoint);
      assertThat(retained.graphBytes()).containsExactly(graphBytes);
      assertThat(retained.graphSha256()).isEqualTo(stored.graphSha256());
      assertThat(retained.ownerCommitProofStatus())
          .isEqualTo(WorldAuthoredGraphSnapshot.OwnerCommitProofStatus.CAPTURED_UNVERIFIED);
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void currentTopologyWriterCommitsBeforeFreezeCheckpointAndFrozenRetryRetainsRows()
      throws Exception {
    String schema = "world_current_draft_fence_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var write = new TransactionTemplate(new DataSourceTransactionManager(source));
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    Path root = repositoryRoot();
    try {
      flyway(
              source,
              schema,
              "filesystem:"
                  + root.resolve(
                      "services/world-management-service/src/main/resources/db/migration"),
              "filesystem:"
                  + root.resolve("services/common-saga/src/main/resources/db/migration/saga"))
          .migrate();

      var upstream = controlledSource();
      var intake =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredSourceIntakeRepository(dsl)
                          .acceptFresh(NAMESPACE, UUID.randomUUID(), upstream)));
      var state =
          AuthoredWorldVersionStateEvidence.create(
              new AuthoredWorldVersionStateEvidence.Request(
                  1,
                  NAMESPACE,
                  UUID.randomUUID(),
                  upstream.canonicalTenantId(),
                  upstream.worldSlug(),
                  upstream.operationId(),
                  upstream.evidenceDigest(),
                  42L),
              upstream,
              UUID.randomUUID(),
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
              1L);
      var version =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredVersionIdentityRepository(dsl).acceptFresh(intake, state)));
      var fence =
          new WorldDesignPublicationFenceRepository(
              dsl, new WorldAuthoredSourceIntakeRepository(dsl));
      var topology =
          new WorldDraftTopologyCommitRepository(
              dsl, fence, JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build());
      // This current guarded storage result remains permission-unverified and supplies no APPLIED
      // commit or upstream publication authority.
      var plan = topologyPlan(intake, version);
      Map<String, Long> expectedFamilyCounts =
          Map.of(
              "region", 1L,
              "zone", 1L,
              "room", 2L,
              "room_exit", 1L,
              "generation_rule", 1L,
              "world_entity_spawn_binding", 1L);
      var digest =
          new WorldDraftDesignDigestServiceImpl(
              new RegionRepository(dsl),
              new ZoneRepository(dsl),
              new RoomRepository(dsl),
              new RoomExitRepository(dsl),
              new GenerationRuleRepository(dsl),
              new WorldEntitySpawnBindingRepository(dsl));
      String publicationRequest = "writer-first-current";
      var publicationBinding =
          PublicationDigestRequestBinding.full(
              upstream.canonicalTenantId().toString(), "42", publicationRequest);
      var owner = plan.ownerBinding();
      var publication =
          new WorldDesignPublicationFenceEvidence(
              NAMESPACE,
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
              publicationBinding.requestDigest(),
              1L,
              publicationBinding.derivedWorkflowIdentity());

      CountDownLatch writerStored = new CountDownLatch(1);
      CountDownLatch releaseWriter = new CountDownLatch(1);
      CountDownLatch freezeStarted = new CountDownLatch(1);
      AtomicBoolean checkpointCaptured = new AtomicBoolean();
      AtomicReference<Integer> writerBackendPid = new AtomicReference<>();
      AtomicReference<Integer> freezeBackendPid = new AtomicReference<>();
      AtomicReference<WorldDraftTopologyCommitEvidence> storedReference = new AtomicReference<>();
      AtomicReference<WorldDesignPublicationFenceEvidence.Checkpoint> checkpointReference =
          new AtomicReference<>();
      ExecutorService executor = Executors.newFixedThreadPool(2);
      WorldDesignPublicationFenceEvidence.FrozenAttempt frozen;
      try {
        Future<WorldDraftTopologyCommitEvidence> writer =
            executor.submit(
                () ->
                    write.execute(
                        status -> {
                          writerBackendPid.set(
                              dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                          var stored = topology.store(plan);
                          storedReference.set(stored);
                          writerStored.countDown();
                          await(releaseWriter);
                          return stored;
                        }));
        await(writerStored);

        Future<WorldDesignPublicationFenceEvidence.FrozenAttempt> freeze =
            executor.submit(
                () ->
                    write.execute(
                        status -> {
                          freezeBackendPid.set(
                              dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                          freezeStarted.countDown();
                          return fence.claimFreeze(
                              publication,
                              () -> {
                                checkpointCaptured.set(true);
                                var committed =
                                    Objects.requireNonNull(storedReference.get(), "writer result");
                                var committedRow =
                                    dsl.fetchOne(
                                        "SELECT graph_bytes, graph_sha256, result_bytes "
                                            + "FROM world_topology_draft_commit WHERE request_id=?",
                                        plan.binding().requestId());
                                assertThat(committedRow)
                                    .as("writer topology must be committed before checkpoint read")
                                    .isNotNull();
                                assertThat(committedRow.get("graph_bytes", byte[].class))
                                    .containsExactly(committed.graphBytes());
                                assertThat(committedRow.get("result_bytes", byte[].class))
                                    .containsExactly(committed.resultBytes());
                                assertThat(committedRow.get("graph_sha256", String.class))
                                    .isEqualTo(
                                        WorldAuthoredGraphSnapshotCapture.sha256(
                                            committed.graphBytes()));
                                for (var familyAndCount : expectedFamilyCounts.entrySet()) {
                                  String family = familyAndCount.getKey();
                                  assertThat(
                                          dsl.fetchSingle(
                                                  "SELECT count(*) FROM "
                                                      + family
                                                      + " WHERE tenant_id=? AND version_id=?",
                                                  intake.localTenantKey(),
                                                  version.localVersionKey())
                                              .get(0, Long.class))
                                      .as("committed authored family %s", family)
                                      .isEqualTo(familyAndCount.getValue());
                                }
                                var observed =
                                    digest.getDraftDesignDigest(
                                        Long.toString(intake.localTenantKey()),
                                        Long.toString(version.localVersionKey()));
                                var checkpoint =
                                    new WorldDesignPublicationFenceEvidence.Checkpoint(
                                        observed.appliedCommitId(),
                                        observed.contentDigest(),
                                        observed.digestSchemaVersion());
                                checkpointReference.set(checkpoint);
                                return checkpoint;
                              });
                        }));
        await(freezeStarted);
        awaitOwnerRowLockWait(
            dsl,
            Objects.requireNonNull(freezeBackendPid.get(), "freeze backend pid"),
            Objects.requireNonNull(writerBackendPid.get(), "writer backend pid"));
        assertThat(checkpointCaptured).isFalse();
        releaseWriter.countDown();

        assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(storedReference.get());
        frozen = freeze.get(10, TimeUnit.SECONDS);
      } finally {
        releaseWriter.countDown();
        executor.shutdownNow();
      }

      assertThat(checkpointCaptured).isTrue();
      assertThat(frozen.checkpoint()).isEqualTo(checkpointReference.get());
      assertThat(frozen.checkpoint().appliedCommitId())
          .isEqualTo("version:" + version.localVersionKey());
      assertThat(fence.readAttempt(publication)).contains(frozen);
      assertThat(topology.readCommitted(plan)).isPresent();

      List<String> retainedTables =
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
              "world_authored_source_intake",
              "world_authored_version_identity",
              "world_design_publication_fence_owner",
              "world_design_publication_fence_attempt",
              "world_authored_graph_snapshot");
      Map<String, List<String>> retainedBeforeDeniedWrite =
          retainedTableSnapshot(dsl, schema, retainedTables);
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_authored_graph_snapshot")
                  .get(0, Long.class))
          .as("owner-local storage does not mint a frozen graph capture")
          .isZero();
      var retryWithChangedRows = topologyPlan(intake, version);
      assertThatThrownBy(() -> write.execute(status -> topology.store(retryWithChangedRows)))
          .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
          .hasMessageContaining("not open");

      assertThat(retainedTableSnapshot(dsl, schema, retainedTables))
          .isEqualTo(retainedBeforeDeniedWrite);
      assertThat(fence.readAttempt(publication)).contains(frozen);
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) FROM world_design_publication_fence_owner "
                          + "WHERE target_namespace=? AND canonical_tenant_id=? "
                          + "AND local_tenant_key=? AND version_id=? "
                          + "AND owner_freeze_phase='FROZEN' "
                          + "AND current_publication_fence=?",
                      NAMESPACE,
                      owner.canonicalTenantId(),
                      intake.localTenantKey(),
                      version.localVersionKey(),
                      frozen.publicationFence())
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_design_publication_fence_attempt")
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_authored_graph_snapshot")
                  .get(0, Long.class))
          .isZero();
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void historicalGuardedStoreReplaysAndRejectsNakedWritesAndMissingCommitCheckpoint()
      throws Exception {
    String schema =
        "world_historical_guarded_graph_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(manager);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    Path root = repositoryRoot();
    String worldLocation =
        "filesystem:"
            + root.resolve("services/world-management-service/src/main/resources/db/migration");
    String sagaLocation =
        "filesystem:" + root.resolve("services/common-saga/src/main/resources/db/migration/saga");
    try {
      // Controlled permission-unverified component storage at the historical guarded frontier.
      // Later Account/APPLIED owners retain their default-current denial requirements.
      historicalGuardedStage(source, schema, worldLocation, sagaLocation).migrate();
      assertCanonicalFrozenMutationTriggerBindings(dsl, schema);
      var upstream = controlledSource();
      var intake =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredSourceIntakeRepository(dsl)
                          .acceptFresh(NAMESPACE, UUID.randomUUID(), upstream)));
      var state =
          AuthoredWorldVersionStateEvidence.create(
              new AuthoredWorldVersionStateEvidence.Request(
                  1,
                  NAMESPACE,
                  UUID.randomUUID(),
                  upstream.canonicalTenantId(),
                  upstream.worldSlug(),
                  upstream.operationId(),
                  upstream.evidenceDigest(),
                  42L),
              upstream,
              UUID.randomUUID(),
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
              1L);
      var version =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredVersionIdentityRepository(dsl).acceptFresh(intake, state)));
      var fence =
          new WorldDesignPublicationFenceRepository(
              dsl, new WorldAuthoredSourceIntakeRepository(dsl));
      var mapper = new tools.jackson.databind.ObjectMapper();
      var applicationMapper =
          JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
      var topology = new WorldDraftTopologyCommitRepository(dsl, fence, applicationMapper);
      var plan = topologyPlan(intake, version);
      var stored = Objects.requireNonNull(write.execute(status -> topology.store(plan)));
      assertThat(stored.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
      String storedOwnerBindingJson =
          dsl.fetchSingle(
                  "SELECT owner_binding_json FROM world_topology_draft_commit WHERE request_id = ?",
                  plan.binding().requestId())
              .get("owner_binding_json", String.class);
      assertThat(storedOwnerBindingJson)
          .isEqualTo(WorldDraftTopologyCommitRepository.ownerBindingJson(plan.ownerBinding()))
          .isNotEqualTo(applicationMapper.writeValueAsString(plan.ownerBinding()));
      assertThat(topology.readCommitted(plan)).isPresent();
      var defaultMapperTopology = new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
      var defaultMapperReadback = defaultMapperTopology.readCommitted(plan).orElseThrow();
      assertThat(defaultMapperReadback.graphBytes()).isEqualTo(stored.graphBytes());
      assertThat(defaultMapperReadback.resultBytes()).isEqualTo(stored.resultBytes());
      var defaultMapperReplay =
          Objects.requireNonNull(write.execute(status -> defaultMapperTopology.store(plan)));
      assertThat(defaultMapperReplay.graphBytes()).isEqualTo(stored.graphBytes());
      assertThat(defaultMapperReplay.resultBytes()).isEqualTo(stored.resultBytes());
      // Exercise the snapshot reader against actual V1005 canonical spawn storage. This is
      // graph/codec proof only: it does not supply the missing V19 complete-commit history.
      var authored =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      new WorldAuthoredGraphReader(dsl)
                          .readAndValidateGraph(
                              intake.localTenantKey(), version.localVersionKey())));
      var spawnRow =
          dsl.fetchSingle(
              "SELECT entity_template_id, entity_canonical_tenant_id, entity_canonical_version_id, "
                  + "entity_canonical_template_id FROM world_entity_spawn_binding "
                  + "WHERE tenant_id = ? AND version_id = ?",
              intake.localTenantKey(),
              version.localVersionKey());
      assertThat(spawnRow.get("entity_template_id")).isNull();
      assertThat(authored.spawnBindings()).hasSize(1);
      assertThat(authored.spawnBindings().getFirst().get("entityReference"))
          .isEqualTo(
              Map.of(
                  "kind", "CANONICAL_UUID",
                  "tenantId", spawnRow.get("entity_canonical_tenant_id", UUID.class).toString(),
                  "versionId", spawnRow.get("entity_canonical_version_id", UUID.class).toString(),
                  "templateId",
                      spawnRow.get("entity_canonical_template_id", UUID.class).toString()));
      byte[] authoredBytes = authored.encode(mapper);
      assertThat(mapper.readTree(authoredBytes).get("snapshotSchemaVersion").intValue())
          .isEqualTo(2);
      assertThat(WorldAuthoredGraph.decode(authoredBytes, mapper).encode(mapper))
          .isEqualTo(authoredBytes);
      // An independent legacy tenant exercises both storage representations and proves that
      // canonical duplicate identity includes the tenant and Version, not only the template UUID.
      // These ordinary unclaimed rows do not represent a canonical authored-source capture.
      long legacyRegion =
          dsl.fetchSingle(
                  "INSERT INTO region (tenant_id, version_id, name, generation_seed) "
                      + "VALUES (7002, 12, 'Reader region', 42) RETURNING id")
              .get(0, Long.class);
      long legacyZone =
          dsl.fetchSingle(
                  "INSERT INTO zone (tenant_id, version_id, region_id, name) "
                      + "VALUES (7002, 12, ?, 'Reader zone') RETURNING id",
                  legacyRegion)
              .get(0, Long.class);
      long legacyRoom =
          dsl.fetchSingle(
                  "INSERT INTO room (tenant_id, version_id, zone_id, name) "
                      + "VALUES (7002, 12, ?, 'Reader room') RETURNING id",
                  legacyZone)
              .get(0, Long.class);
      UUID entityTenant = UUID.randomUUID();
      UUID entityVersion = UUID.randomUUID();
      UUID entityTemplate = UUID.randomUUID();
      for (var reference :
          List.of(
              List.of(entityTenant, entityVersion, entityTemplate),
              List.of(UUID.randomUUID(), entityVersion, entityTemplate),
              List.of(entityTenant, UUID.randomUUID(), entityTemplate))) {
        dsl.execute(
            "INSERT INTO world_entity_spawn_binding (tenant_id, version_id, room_id, "
                + "entity_template_type, entity_template_id, entity_canonical_tenant_id, "
                + "entity_canonical_version_id, entity_canonical_template_id) "
                + "VALUES (7002, 12, ?, 'NPC', NULL, ?, ?, ?)",
            legacyRoom,
            reference.get(0),
            reference.get(1),
            reference.get(2));
      }
      dsl.execute(
          "INSERT INTO world_entity_spawn_binding (tenant_id, version_id, room_id, "
              + "entity_template_type, entity_template_id) VALUES (7002, 12, ?, 'NPC', ?)",
          legacyRoom,
          Long.MAX_VALUE);
      var legacyGraph =
          Objects.requireNonNull(
              write.execute(
                  status -> new WorldAuthoredGraphReader(dsl).readAndValidateGraph(7002L, 12L)));
      assertThat(legacyGraph.spawnBindings()).hasSize(4);
      assertThat(legacyGraph.spawnBindings().getLast().get("entityReference"))
          .isEqualTo(
              Map.of("kind", "RETAINED_PRIVATE_KEY", "templateId", Long.toString(Long.MAX_VALUE)));
      byte[] legacyGraphBytes = legacyGraph.encode(mapper);
      assertThat(WorldAuthoredGraph.decode(legacyGraphBytes, mapper).encode(mapper))
          .isEqualTo(legacyGraphBytes);
      var replay = Objects.requireNonNull(write.execute(status -> topology.store(plan)));
      assertThat(replay.graphBytes()).isEqualTo(stored.graphBytes());
      assertThat(replay.resultBytes()).isEqualTo(stored.resultBytes());
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_authored_topology_identity")
                  .get(0, Long.class))
          .isEqualTo(7L);
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_region_draft_execution_manifest")
                  .get(0, Long.class))
          .isZero();
      List<String> rowsBefore =
          dsl.fetch("SELECT to_jsonb(t)::text FROM world_topology_draft_commit t")
              .getValues(0, String.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE region SET name = 'naked' WHERE tenant_id = ?",
                      intake.localTenantKey()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(topology.readCommitted(plan).orElseThrow().resultBytes())
          .isEqualTo(stored.resultBytes());

      var digest =
          new WorldDraftDesignDigestServiceImpl(
              new RegionRepository(dsl),
              new ZoneRepository(dsl),
              new RoomRepository(dsl),
              new RoomExitRepository(dsl),
              new GenerationRuleRepository(dsl),
              new WorldEntitySpawnBindingRepository(dsl));
      var actual =
          digest.getDraftDesignDigest(
              Long.toString(intake.localTenantKey()), Long.toString(version.localVersionKey()));
      assertThat(actual.digestSchemaVersion()).isEqualTo(3);
      assertThat(actual.appliedCommitId()).isEqualTo("version:" + version.localVersionKey());
      String publicationRequest = "guarded-capture";
      String workflow =
          PublicationDigestRequestBinding.full(
                  upstream.canonicalTenantId().toString(), "42", publicationRequest)
              .derivedWorkflowIdentity();
      var owner = plan.ownerBinding();
      var publication =
          new WorldDesignPublicationFenceEvidence(
              NAMESPACE,
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
              "f".repeat(64),
              1L,
              workflow);
      // The checkpoint comes from the actual historical component digest writer. It does not prove
      // APPLIED.
      var frozen =
          Objects.requireNonNull(
              write.execute(
                  status ->
                      fence.claimFreeze(
                          publication,
                          () ->
                              new WorldDesignPublicationFenceEvidence.Checkpoint(
                                  actual.appliedCommitId(),
                                  actual.contentDigest(),
                                  actual.digestSchemaVersion()))));
      var tuples =
          plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
              .map(
                  u ->
                      new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                          u.owner().name(),
                          u.aggregateType(),
                          u.aggregateId(),
                          u.scopeType(),
                          u.scopeId(),
                          u.expectedEpoch()))
              .toList();
      var capture =
          new WorldAuthoredGraphSnapshot.CaptureRequest(
              NAMESPACE,
              owner.canonicalTenantId(),
              owner.canonicalVersionId(),
              owner.intakeRequestId(),
              frozen.publicationFence(),
              publicationRequest,
              publication.requestDigest(),
              publication.versionStateEpoch(),
              workflow,
              actual.appliedCommitId(),
              actual.contentDigest(),
              actual.digestSchemaVersion(),
              tuples);
      // Historical schema-3 capture must deny the synthetic checkpoint without invented APPLIED
      // rows.
      var snapshotRepository = new WorldAuthoredGraphSnapshotRepository(dsl);
      var graphCapture = new WorldAuthoredGraphSnapshotCapture(dsl, digest, snapshotRepository);
      assertThatThrownBy(() -> write.execute(status -> graphCapture.capture(capture)))
          .isInstanceOf(WorldAuthoredGraphSnapshotRepository.MissingOwnerHistoryException.class)
          .hasMessageContaining("retained V19 revision history");

      var originalRows =
          dsl.fetchSingle(
              "SELECT to_jsonb(v)::text AS identity_json, to_jsonb(i)::text AS intake_json "
                  + "FROM world_authored_version_identity v "
                  + "JOIN world_authored_source_intake i ON i.operation_id=v.intake_operation_id "
                  + "WHERE v.operation_id=?",
              owner.versionIdentityOperationId());
      String identityJson = originalRows.get("identity_json", String.class);
      String intakeJson = originalRows.get("intake_json", String.class);

      // These candidates reuse real stored topology/source bytes and must be rejected before
      // persistence; none supplies or claims V19 complete-commit/APPLIED evidence.
      var schemaTwoCapture =
          new WorldAuthoredGraphSnapshot.CaptureRequest(
              capture.targetNamespace(),
              capture.canonicalTenantId(),
              capture.canonicalVersionId(),
              capture.intakeRequestId(),
              capture.publicationFence(),
              capture.publicationRequestId(),
              capture.requestDigest(),
              capture.versionStateEpoch(),
              capture.publishWorkflowId(),
              capture.appliedCommitId(),
              capture.contentDigest(),
              2,
              capture.suppliedOwnedAffectedTuples());
      assertThatThrownBy(
              () ->
                  insertCanonicalFrozenCandidate(
                      dsl,
                      plan,
                      capture,
                      stored,
                      mapper.writeValueAsString(schemaTwoCapture),
                      identityJson,
                      intakeJson))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("New canonical graph capture requires digest schema 3")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("23514"));

      assertThatThrownBy(
              () ->
                  insertCanonicalFrozenCandidate(
                      dsl,
                      plan,
                      capture,
                      stored,
                      "{\"digestSchemaVersion\":3}",
                      identityJson,
                      intakeJson))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Canonical frozen capture differs")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("23514"));

      var changedCheckpointCapture =
          new WorldAuthoredGraphSnapshot.CaptureRequest(
              capture.targetNamespace(),
              capture.canonicalTenantId(),
              capture.canonicalVersionId(),
              capture.intakeRequestId(),
              capture.publicationFence(),
              capture.publicationRequestId(),
              capture.requestDigest(),
              capture.versionStateEpoch(),
              capture.publishWorkflowId(),
              capture.appliedCommitId() + "-mismatch",
              capture.contentDigest(),
              capture.digestSchemaVersion(),
              capture.suppliedOwnedAffectedTuples());
      assertThatThrownBy(
              () ->
                  insertCanonicalFrozenCandidate(
                      dsl,
                      plan,
                      capture,
                      stored,
                      mapper.writeValueAsString(changedCheckpointCapture),
                      identityJson,
                      intakeJson))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Canonical frozen capture differs")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("23514"));

      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_canonical_frozen_topology")
                  .get(0, Long.class))
          .isZero();

      // Row-level V1006 UPDATE/DELETE guards cannot fire on an empty frozen table. Verify their
      // installed shared-function bindings, and exercise UPDATE/DELETE on retained source rows.
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE world_authored_source_intake SET tenant_slug=tenant_slug "
                          + "WHERE operation_id=?",
                      intake.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("immutable")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "DELETE FROM world_authored_source_intake WHERE operation_id=?",
                      intake.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("immutable")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) FROM world_authored_source_intake WHERE operation_id=?",
                      intake.operationId())
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThatThrownBy(() -> dsl.execute("TRUNCATE world_canonical_frozen_topology"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("immutable")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("55000"));
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_canonical_frozen_topology")
                  .get(0, Long.class))
          .isZero();

      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_authored_graph_snapshot")
                  .get(0, Long.class))
          .isZero();
      // Positive canonical frozen capture is intentionally unavailable until the actual commit
      // token exists.
      assertThatThrownBy(() -> new WorldCanonicalFrozenTopology.Request(plan, capture))
          .hasMessageContaining("exact selected complete commit");
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_canonical_frozen_topology")
                  .get(0, Long.class))
          .isZero();
      assertThat(
              dsl.fetch("SELECT to_jsonb(t)::text FROM world_topology_draft_commit t")
                  .getValues(0, String.class))
          .isEqualTo(rowsBefore);

      // Historical storage proof above is retained as such. Repeat the actual naked-write,
      // schema-2 capture, wrong-checkpoint capture, and missing-owner-history denials after the
      // complete current World/Saga migration frontier.
      Flyway currentFrontier = flyway(source, schema, worldLocation, sagaLocation);
      currentFrontier.migrate();
      currentFrontier.validate();
      assertThat(currentFrontier.info().pending()).isEmpty();
      // V1012 added nullable application_transaction_id to retained draft-commit rows. Capture a
      // complete row-image baseline after that historical-to-current schema change so the
      // following denials prove current-frontier atomicity without conflating migration metadata.
      List<String> currentTopologyCommitRowsBefore =
          dsl.fetch("SELECT to_jsonb(t)::text FROM world_topology_draft_commit t")
              .getValues(0, String.class);
      List<String> currentRegionRowsBefore =
          dsl.fetch(
                  "SELECT xmin::text || ':' || to_jsonb(r)::text FROM region r "
                      + "WHERE tenant_id=? ORDER BY id",
                  intake.localTenantKey())
              .getValues(0, String.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE region SET name='naked at current frontier' WHERE tenant_id=?",
                      intake.localTenantKey()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("23514"));
      assertThat(
              dsl.fetch(
                      "SELECT xmin::text || ':' || to_jsonb(r)::text FROM region r "
                          + "WHERE tenant_id=? ORDER BY id",
                      intake.localTenantKey())
                  .getValues(0, String.class))
          .isEqualTo(currentRegionRowsBefore);

      assertThatThrownBy(() -> write.execute(status -> graphCapture.capture(capture)))
          .isInstanceOf(WorldAuthoredGraphSnapshotRepository.MissingOwnerHistoryException.class)
          .hasMessageContaining("retained V19 revision history");
      assertThatThrownBy(
              () ->
                  insertCanonicalFrozenCandidate(
                      dsl,
                      plan,
                      schemaTwoCapture,
                      stored,
                      mapper.writeValueAsString(schemaTwoCapture),
                      identityJson,
                      intakeJson))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("New canonical graph capture requires selected closure schema 4")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("23514"));

      // The retained current DTO accepts only schema 3; its altered checkpoint is therefore
      // rejected at the current schema-4 boundary before a schema-4 checkpoint comparison.
      var currentWrongCheckpointCapture =
          new WorldAuthoredGraphSnapshot.CaptureRequest(
              capture.targetNamespace(),
              capture.canonicalTenantId(),
              capture.canonicalVersionId(),
              capture.intakeRequestId(),
              capture.publicationFence(),
              capture.publicationRequestId(),
              capture.requestDigest(),
              capture.versionStateEpoch(),
              capture.publishWorkflowId(),
              capture.appliedCommitId() + "-current-mismatch",
              capture.contentDigest(),
              3,
              capture.suppliedOwnedAffectedTuples());
      assertThatThrownBy(
              () ->
                  insertCanonicalFrozenCandidate(
                      dsl,
                      plan,
                      currentWrongCheckpointCapture,
                      stored,
                      mapper.writeValueAsString(currentWrongCheckpointCapture),
                      identityJson,
                      intakeJson))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("New canonical graph capture requires selected closure schema 4")
          .satisfies(exception -> assertThat(sqlState(exception)).isEqualTo("23514"));
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM world_canonical_frozen_topology")
                  .get(0, Long.class))
          .isZero();
      assertThat(
              dsl.fetch("SELECT to_jsonb(t)::text FROM world_topology_draft_commit t")
                  .getValues(0, String.class))
          .isEqualTo(currentTopologyCommitRowsBefore);
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void assertCanonicalFrozenMutationTriggerBindings(DSLContext dsl, String schema) {
    var triggers =
        dsl.fetch(
            "SELECT t.tgname AS trigger_name, p.proname AS function_name, "
                + "pg_get_triggerdef(t.oid) AS trigger_definition, p.prosrc AS function_source "
                + "FROM pg_trigger t "
                + "JOIN pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "JOIN pg_proc p ON p.oid=t.tgfoid "
                + "WHERE n.nspname=? AND c.relname='world_canonical_frozen_topology' "
                + "AND NOT t.tgisinternal ORDER BY t.tgname",
            schema);
    assertThat(triggers.getValues("trigger_name", String.class))
        .containsExactly(
            "trg_world_canonical_frozen_00_schema3",
            "trg_world_canonical_frozen_immutable",
            "trg_world_canonical_frozen_insert",
            "trg_world_canonical_frozen_no_truncate");
    assertThat(triggers.getValues("function_name", String.class))
        .containsExactly(
            "world_require_new_digest_schema3_capture",
            "world_reject_authored_source_history_mutation",
            "world_check_canonical_frozen_capture",
            "world_reject_authored_source_history_mutation");
    assertThat(triggers.get(1).get("trigger_definition", String.class))
        .contains(
            "DELETE", "UPDATE", "FOR EACH ROW", "world_reject_authored_source_history_mutation");
    assertThat(triggers.get(3).get("trigger_definition", String.class))
        .contains(
            "TRUNCATE", "FOR EACH STATEMENT", "world_reject_authored_source_history_mutation");
    assertThat(triggers.get(1).get("function_source", String.class))
        .contains("RAISE EXCEPTION", "ERRCODE = '55000'")
        .doesNotContain("IF ");
    var sourceFunctions =
        dsl.fetch(
                "SELECT p.proname AS function_name FROM pg_trigger t "
                    + "JOIN pg_class c ON c.oid=t.tgrelid "
                    + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                    + "JOIN pg_proc p ON p.oid=t.tgfoid "
                    + "WHERE n.nspname=? AND c.relname='world_authored_source_intake' "
                    + "AND NOT t.tgisinternal ORDER BY t.tgname",
                schema)
            .getValues("function_name", String.class);
    assertThat(sourceFunctions)
        .containsExactly(
            "world_reject_authored_source_history_mutation",
            "world_reject_authored_source_history_mutation");
  }

  private static void insertCanonicalFrozenCandidate(
      DSLContext dsl,
      WorldDraftTopologyCommitPlan plan,
      WorldAuthoredGraphSnapshot.CaptureRequest request,
      WorldDraftTopologyCommitEvidence stored,
      String freezeRequestJson,
      String identityJson,
      String intakeJson) {
    dsl.execute(
        "INSERT INTO world_canonical_frozen_topology (capture_id,publication_fence,"
            + "request_id,commit_id,version_identity_operation_id,freeze_request_json,"
            + "owner_binding_json,binding_json,binding_digest,identity_json,intake_json,"
            + "graph_bytes,graph_sha256,storage_result_bytes,result_bytes,capture_status) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        request.publicationFence(),
        plan.binding().requestId(),
        plan.binding().commitId(),
        plan.ownerBinding().versionIdentityOperationId(),
        freezeRequestJson,
        WorldDraftTopologyCommitRepository.ownerBindingJson(plan.ownerBinding()),
        plan.binding().canonicalJson(),
        plan.binding().digest(),
        identityJson,
        intakeJson,
        stored.graphBytes(),
        WorldAuthoredGraphSnapshotCapture.sha256(stored.graphBytes()),
        stored.resultBytes(),
        stored.resultBytes(),
        WorldCanonicalFrozenTopology.STATUS);
  }

  private static String sqlState(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static WorldDraftTopologyCommitPlan topologyPlan(
      WorldAuthoredSourceIntakeReceipt intake, WorldAuthoredVersionIdentityReceipt version)
      throws Exception {
    UUID commit = UUID.randomUUID();
    UUID region = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    UUID otherRoom = UUID.randomUUID();
    List<WorldDesignMutationRevision> mutations =
        List.of(
            mutation(
                    commit,
                    region,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
                    region)
                .setRegion(RegionDesignMutation.newBuilder().setName("Guarded region"))
                .build(),
            mutation(
                    commit, zone, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE, region)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Guarded zone")
                        .setRegionId(region.toString()))
                .build(),
            mutation(
                    commit, room, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM, region)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Guarded room")
                        .setZoneId(zone.toString()))
                .build(),
            mutation(
                    commit,
                    otherRoom,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                    region)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Other room")
                        .setZoneId(zone.toString()))
                .build(),
            mutation(
                    commit,
                    UUID.randomUUID(),
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
                    region)
                .setRoomExit(
                    RoomExitDesignMutation.newBuilder()
                        .setFromRoomId(room.toString())
                        .setToRoomId(otherRoom.toString())
                        .setDirection("EAST"))
                .build(),
            mutation(
                    commit,
                    UUID.randomUUID(),
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
                    region)
                .setGenerationRule(
                    GenerationRuleDesignMutation.newBuilder().setName("rule").setValue("fixed"))
                .build(),
            mutation(
                    commit,
                    UUID.randomUUID(),
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
                    region)
                .setWorldEntitySpawnBinding(
                    WorldEntitySpawnBindingDesignMutation.newBuilder()
                        .setRoomId(room.toString())
                        .setEntityTemplateType(
                            EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                        .setEntityTemplateId(UUID.randomUUID().toString()))
                .build());
    var revisions = new ArrayList<RevisionPayload>();
    var units = new ArrayList<AffectedUnit>();
    for (var mutation : mutations) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(mutation)));
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
              region.toString(),
              "0"));
    }
    var upstream = intake.source();
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                upstream.canonicalTenantId(),
                version.canonicalVersionId(),
                version.gameDesignVersionId(),
                upstream.sourceGameTenantKey(),
                upstream.sourceGameRowId(),
                upstream.sourceGameTenantKey(),
                upstream.provenanceKind()),
            UUID.randomUUID(),
            commit,
            "controlled-component-base",
            revisions,
            units);
    var owner =
        new OwnerBinding(
            NAMESPACE,
            upstream.canonicalTenantId(),
            version.canonicalVersionId(),
            version.operationId(),
            version.gameDesignVersionId(),
            intake.intakeRequestId(),
            intake.operationId(),
            intake.requestDigest(),
            upstream.operationId(),
            upstream.evidenceDigest(),
            intake.receiptDigest());
    return WorldDraftTopologyCommitPlan.create(binding, owner);
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID id, WorldDesignAggregateType family, UUID region) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(id.toString())
        .setAggregateType(family)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(region.toString());
  }

  private static AuthoredWorldSourceEvidence controlledSource() {
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String request =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registration,
            tenant,
            "upgrade-tenant",
            "upgrade-world",
            "Retained owner world");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registration,
        operation,
        request,
        tenant,
        "upgrade-tenant",
        "upgrade-world",
        "Retained owner world",
        77L,
        "parent-source-owner",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            operation,
            request,
            tenant,
            "upgrade-tenant",
            "upgrade-world",
            "Retained owner world",
            77L,
            "parent-source-owner",
            "NEW_GAME_ROW"));
  }

  private static Flyway historicalGuardedStage(
      DriverManagerDataSource source, String schema, String... locations) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .table(HISTORY)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(locations)
        .target(MigrationVersion.fromVersion("1007.1"))
        .load();
  }

  private static Flyway flyway(DriverManagerDataSource source, String schema, String... locations) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .table(HISTORY)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(locations)
        .load();
  }

  private static List<String> history(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(t)::text FROM "
                + HISTORY
                + " t WHERE version IS NOT NULL ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static List<String> retainedRows(
      DSLContext dsl, String table, List<String> originalColumns) {
    // New columns are checked separately; compare every original column without synthesizing
    // values.
    String projection =
        originalColumns.stream()
            .map(column -> "\"" + column + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
    return dsl.fetch(
            "SELECT to_jsonb(retained)::text FROM (SELECT xmin::text AS retained_xmin, "
                + projection
                + " FROM "
                + table
                + ") retained ORDER BY to_jsonb(retained)::text")
        .getValues(0, String.class);
  }

  private static Map<String, List<String>> retainedTableSnapshot(
      DSLContext dsl, String schema, List<String> tables) {
    Map<String, List<String>> snapshot = new LinkedHashMap<>();
    for (String table : tables) {
      List<String> columns =
          dsl.fetch(
                  "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema=? AND table_name=? ORDER BY ordinal_position",
                  schema,
                  table)
              .getValues(0, String.class);
      assertThat(columns).as("retained table %s must exist", table).isNotEmpty();
      snapshot.put(table, retainedRows(dsl, table, columns));
    }
    return snapshot;
  }

  private static void awaitOwnerRowLockWait(
      DSLContext dsl, int freezeBackendPid, int writerBackendPid) {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNanos) {
      var waiterRow =
          dsl.fetchOne(
              "SELECT waiter.pid FROM pg_stat_activity waiter WHERE waiter.pid=? "
                  + "AND waiter.wait_event_type='Lock' "
                  + "AND ? = ANY(pg_blocking_pids(waiter.pid)) "
                  + "AND lower(waiter.query) LIKE '%world_design_publication_fence_owner%'",
              freezeBackendPid, writerBackendPid);
      if (waiterRow != null) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("No PostgreSQL waiter was observed on the World owner row");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for the World draft mutation gate");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("World draft mutation test was interrupted", exception);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("settings.gradle.kts"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Unable to locate repository filesystem migrations");
  }
}
