package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class CanonicalGameplayMigrationDriverMainTest {
  @Test
  void postgresConnectionIsBoundToTheFixedUnixSocketWithoutTcpOrSslFallback() {
    CanonicalGameplayMigrationDriverMain.PostgresSocketConfiguration socket =
        CanonicalGameplayMigrationDriverMain.postgresSocketConfiguration();

    assertThat(socket.host()).isEqualTo("localhost");
    assertThat(socket.port()).isEqualTo(5432);
    assertThat(socket.factoryClass())
        .isEqualTo("org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg");
    assertThat(socket.socketPath()).isEqualTo("/var/run/postgresql/.s.PGSQL.5432");
    assertThat(socket.sslMode()).isEqualTo("disable");
  }

  @Test
  void flywayUsesTheCanonicalServiceSpecificHistoryTable() {
    assertThat(CanonicalGameplayMigrationDriverMain.flywayHistoryTable("game_session_service"))
        .isEqualTo("flyway_schema_history_game_session_service");

    assertThatThrownBy(
            () -> CanonicalGameplayMigrationDriverMain.flywayHistoryTable("other;schema"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> CanonicalGameplayMigrationDriverMain.flywayHistoryTable("a".repeat(42)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flywayOwnsTheCanonicalFreshSchemaAndHistoryConfiguration() {
    var configuration =
        CanonicalGameplayMigrationDriverMain.configuredFlyway(
            "game_session_service", new PGSimpleDataSource());

    assertThat(configuration.getSchemas()).containsExactly("game_session_service");
    assertThat(configuration.getDefaultSchema()).isEqualTo("game_session_service");
    assertThat(configuration.getTable()).isEqualTo("flyway_schema_history_game_session_service");
    // Flyway removes the redundant nested location; the parent scan includes saga resources.
    assertThat(configuration.getLocations())
        .extracting(Location::getDescriptor)
        .containsExactly("classpath:db/migration");
    assertThat(configuration.isCreateSchemas()).isTrue();
  }

  @Test
  void migrationSourceInventoryExcludesOneExplicitFlywaySchemaMarkerAndSortsSqlSources() {
    MigrationInfo v2 = migration(CoreMigrationType.SQL, "2", "V2__second.sql", 22);
    MigrationInfo schemaMarker = migration(CoreMigrationType.SCHEMA, null, null, null);
    MigrationInfo v1 = migration(CoreMigrationType.SQL, "1", "V1__first.sql", 11);

    var migrations =
        CanonicalGameplayMigrationDriverMain.migrationSourceInventory(
            new MigrationInfo[] {v2, schemaMarker, v1});

    assertThat(migrations)
        .extracting(CanonicalGameplayMigrationDriverMain.MigrationSourceIdentity::version)
        .containsExactly("1", "2");
    assertThat(migrations)
        .extracting(CanonicalGameplayMigrationDriverMain.MigrationSourceIdentity::script)
        .containsExactly("V1__first.sql", "V2__second.sql");
    assertThat(migrations)
        .extracting(CanonicalGameplayMigrationDriverMain.MigrationSourceIdentity::checksum)
        .containsExactly(11, 22);
  }

  @Test
  void migrationSourceInventoryRejectsMultipleFlywaySchemaMarkers() {
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.migrationSourceInventory(
                    new MigrationInfo[] {
                      migration(CoreMigrationType.SCHEMA, null, null, null),
                      migration(CoreMigrationType.SCHEMA, null, null, null)
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schema marker was ambiguous");
  }

  @Test
  void migrationSourceInventoryRetainsDuplicateAndMalformedNormalMigrationRejection() {
    MigrationInfo firstVersionOne = migration(CoreMigrationType.SQL, "1", "V1__first.sql", 11);
    MigrationInfo duplicateVersionOne = migration(CoreMigrationType.SQL, "1", "V1__second.sql", 12);
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.migrationSourceInventory(
                    new MigrationInfo[] {firstVersionOne, duplicateVersionOne}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("versions were ambiguous");

    MigrationInfo missingVersion =
        migration(CoreMigrationType.SQL, null, "V1__missing_version.sql", 11);
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.migrationSourceInventory(
                    new MigrationInfo[] {missingVersion}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity was incomplete");

    MigrationInfo missingScript = migration(CoreMigrationType.SQL, "1", null, 11);
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.migrationSourceInventory(
                    new MigrationInfo[] {missingScript}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity was incomplete");
  }

  @Test
  void redisTlsObservationRequiresOnlyTheExactTlsEndpointAndDisabledPlainPort() {
    String runId = "a".repeat(40);
    String caDigest = "sha256:" + "4".repeat(64);

    Map<String, Object> observation =
        CanonicalGameplayMigrationDriverMain.validatedRedisObservation(
            runId, "0", "6380", caDigest);

    assertThat(observation)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("runId", runId, "tlsEnabled", true, "tlsPort", 6380, "trustCaSha256", caDigest));
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.validatedRedisObservation(
                    runId, null, "6380", caDigest))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.validatedRedisObservation(
                    runId, "6379", "6380", caDigest))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.validatedRedisObservation(
                    runId, "0", "6379", caDigest))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.validatedRedisObservation(
                    runId, "0", "6380", null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void redisTopologyRequiresAnUnambiguousStandaloneServerObservation() {
    Properties standalone = new Properties();
    standalone.setProperty("cluster_enabled", "0");

    CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(standalone);
    assertThatThrownBy(
            () -> CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("topology");
    assertThatThrownBy(
            () ->
                CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(
                    new Properties()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("topology");

    Properties malformed = new Properties();
    malformed.setProperty("cluster_enabled", "unknown");
    assertThatThrownBy(
            () -> CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(malformed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("topology");

    Properties clustered = new Properties();
    clustered.setProperty("cluster_enabled", "1");
    assertThatThrownBy(
            () -> CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(clustered))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("topology");
  }

  private static MigrationInfo migration(
      CoreMigrationType type, String version, String script, Integer checksum) {
    MigrationInfo migration = mock(MigrationInfo.class);
    when(migration.getType()).thenReturn(type);
    when(migration.getVersion())
        .thenReturn(version == null ? null : MigrationVersion.fromVersion(version));
    when(migration.getScript()).thenReturn(script);
    when(migration.getChecksum()).thenReturn(checksum);
    return migration;
  }
}
