package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class PreV1CanonicalGameplayStoreBootstrapIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = "pre-v1-game-session-coordination";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  // Same ACL shape as the pre-v1 Compose Coordination override.
  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withExposedPorts(6379)
          .withCommand(
              "redis-server", "--user", PRINCIPAL, "on", ">" + PASSWORD, "~*", "&*", "+@all");

  @Test
  void bootstrapReachesVerifiedAndOpenAndReplaysIdenticallyAfterRestart() {
    String schema = CanonicalGameplayIssuerTestFixtures.schemaName("gs_prev1_bootstrap_");
    DriverManagerDataSource dataSource = dataSource(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(dataSource, schema, MIGRATION_LOCATION, null);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    RedisClient first = coordinationClient();
    RedisClient second = coordinationClient();
    try {
      var original = standIns(dsl, first).storeBootstrap().run();
      // A fresh process composes new stand-ins over the same retained SQL and Redis state.
      var replay = standIns(dsl, second).storeBootstrap().run();

      assertThat(original.legacyMigrationState()).isEqualTo("VERIFIED");
      assertThat(original.issuerAdmissionControlState()).isEqualTo("OPEN");
      assertThat(replay).isEqualTo(original);
      assertThat(
              dsl.fetchValue(
                  "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition"
                      + " WHERE singleton_id = 1"))
          .isEqualTo("VERIFIED");
      assertThat(dsl.fetchCount(DSL.table("game_session_canonical_legacy_migration_operation")))
          .isEqualTo(1);
      assertThat(dsl.fetchCount(DSL.table("game_session_canonical_issuer_layout_bootstrap_intent")))
          .isEqualTo(1);
      assertThat(
              dsl.fetchValue(
                  "SELECT state FROM game_session_canonical_issuer_admission_control"
                      + " WHERE issuer_id = ?",
                  PreV1CurrentIssuerStateSource.ISSUER))
          .isEqualTo("OPEN");
      assertThat(original.redisRunId()).matches("[0-9a-f]{40}");
    } finally {
      first.shutdown();
      second.shutdown();
      dropSchema(schema);
    }
  }

  @Test
  void budgetsAllocateTheTenantTagAndInsertOnce() {
    String schema = CanonicalGameplayIssuerTestFixtures.schemaName("gs_prev1_budgets_");
    DriverManagerDataSource dataSource = dataSource(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(dataSource, schema, MIGRATION_LOCATION, null);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    RedisClient client = coordinationClient();
    try {
      UUID tenantId = UUID.randomUUID();
      CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId, "test");
      var budgets = standIns(dsl, client).budgets();

      assertThat(budgets.fixtureTenant()).as("a Human catalog row is not the fixture").isEmpty();
      budgets.provision(tenantId);
      budgets.provision(tenantId);

      assertThat(
              dsl.fetchValue(
                  "SELECT tenant_gameplay_tag FROM game_session_canonical_tenant_gameplay_tag"
                      + " WHERE tenant_id = ?",
                  tenantId))
          .isEqualTo("g1-" + tenantId.toString().replace("-", ""));
      var capacity =
          dsl.fetchSingle(
              "SELECT maximum_records, maximum_binding_bytes, maximum_retained_records"
                  + " FROM game_session_capacity_evidence_budget WHERE tenant_id = ?",
              tenantId);
      assertThat(capacity.get(0, Integer.class)).isEqualTo(128);
      assertThat(capacity.get(1, Long.class)).isEqualTo(128L * 1_048_576L);
      assertThat(capacity.get(2, Integer.class)).isEqualTo(128);
      var history =
          dsl.fetchSingle(
              "SELECT maximum_transfers, maximum_items"
                  + " FROM game_session_region_transfer_history_budget WHERE tenant_id = ?",
              tenantId);
      assertThat(history.get(0, Integer.class)).isEqualTo(256);
      assertThat(history.get(1, Integer.class)).isEqualTo(1024);
    } finally {
      client.shutdown();
      dropSchema(schema);
    }
  }

  private static PreV1CanonicalPlayStandIns standIns(DSLContext dsl, RedisClient client) {
    return PreV1CanonicalPlayManualFactory.create(
        PreV1GateWaivers.enabledForTest(), dsl, client, "test");
  }

  private static RedisClient coordinationClient() {
    return CanonicalGameplayIssuerTestFixtures.createRedisClient(
        redis.getHost(), redis.getMappedPort(6379), PRINCIPAL, PASSWORD);
  }

  private static DriverManagerDataSource dataSource(String schema) {
    return CanonicalGameplayIssuerTestFixtures.dataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), schema);
  }

  private static void dropSchema(String schema) {
    CanonicalGameplayIssuerTestFixtures.dropSchema(
        dataSource("public"), schema, "Could not drop the pre-v1 bootstrap test schema");
  }
}
