package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.api.Location;
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
}
