package net.firedevops.firemud.worldmanagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared migration fixtures for World Management native PostgreSQL tests. */
public final class WorldPostgresMigrationTestSupport {
  public static final String HISTORY = "flyway_schema_history_world_management_service";

  // Exact SHA-256 bytes at d7c8f84fa947644a651304205803c3919c81684e: 31 World and 3 Saga.
  public static final Map<String, String> PARENT_MIGRATIONS =
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

  private WorldPostgresMigrationTestSupport() {}

  public static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("settings.gradle.kts"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Unable to locate repository filesystem migrations");
  }

  public static String worldMigrations(Path root) {
    return "filesystem:"
        + root.resolve("services/world-management-service/src/main/resources/db/migration");
  }

  public static String sagaMigrations(Path root) {
    return "filesystem:"
        + root.resolve("services/common-saga/src/main/resources/db/migration/saga");
  }

  public static Flyway migration(DriverManagerDataSource source, String schema, String target) {
    Path root = repositoryRoot();
    return flyway(
        source,
        schema,
        target == null ? null : MigrationVersion.fromVersion(target),
        worldMigrations(root),
        sagaMigrations(root));
  }

  public static Flyway flyway(
      DriverManagerDataSource source, String schema, MigrationVersion target, String... locations) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table(HISTORY)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(locations);
    if (target != null) configuration.target(target);
    return configuration.load();
  }
}
