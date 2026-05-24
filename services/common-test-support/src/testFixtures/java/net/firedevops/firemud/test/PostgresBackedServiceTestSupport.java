package net.firedevops.firemud.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgresBackedServiceTestSupport {
  private static final Map<String, String> SERVICE_MODULE_DIRECTORY =
      Map.ofEntries(
          Map.entry("account_service", "account-service"),
          Map.entry("automation_scripting_service", "automation-scripting-service"),
          Map.entry("entity_management_service", "entity-management-service"),
          Map.entry("game_design_service", "game-design-service"),
          Map.entry("game_logic_service", "game-logic-service"),
          Map.entry("game_session_service", "game-session-service"),
          Map.entry("gateway", "spring-cloud-gateway"),
          Map.entry("logging_admin_service", "logging-admin-service"),
          Map.entry("social_groups_service", "social-groups-service"),
          Map.entry("tcp_proxy_service", "tcp-proxy-service"),
          Map.entry("world_management_service", "world-management-service"));

  private PostgresBackedServiceTestSupport() {}

  public static void registerPostgresService(
      DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres, String serviceSchema) {
    String flywayLocations = filesystemFlywayLocations(serviceSchema);
    String flywayTable = flywayHistoryTable(serviceSchema);
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", postgres::getDatabaseName);
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
    registry.add("firemud.postgres.schema", () -> serviceSchema);
    registry.add("SERVICE_SCHEMA", () -> serviceSchema);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.locations", () -> flywayLocations);
    registry.add("spring.flyway.schemas", () -> serviceSchema);
    registry.add("spring.flyway.default-schema", () -> serviceSchema);
    registry.add("spring.flyway.table", () -> flywayTable);
    registry.add("spring.flyway.placeholders.serviceSchema", () -> serviceSchema);
  }

  public static void registerEmbeddedFlywayService(
      DynamicPropertyRegistry registry, String jdbcUrl, String serviceSchema) {
    String flywayLocations = filesystemFlywayLocations(serviceSchema);
    String flywayTable = flywayHistoryTable(serviceSchema);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.url", () -> jdbcUrl);
    registry.add("spring.flyway.user", () -> "sa");
    registry.add("spring.flyway.password", () -> "");
    registry.add("spring.flyway.locations", () -> flywayLocations);
    registry.add("spring.flyway.schemas", () -> serviceSchema);
    registry.add("spring.flyway.default-schema", () -> serviceSchema);
    registry.add("spring.flyway.table", () -> flywayTable);
    registry.add("spring.flyway.placeholders.serviceSchema", () -> serviceSchema);
  }

  public static void registerRedisService(
      DynamicPropertyRegistry registry, GenericContainer<?> redis) {
    registry.add("firemud.redis.host", redis::getHost);
    registry.add("firemud.redis.port", () -> redis.getMappedPort(6379));
  }

  private static String filesystemFlywayLocations(String serviceSchema) {
    Path repoRoot = findRepoRoot();
    Path serviceMigrations =
        repoRoot
            .resolve("services")
            .resolve(serviceModuleDirectory(serviceSchema))
            .resolve("src/main/resources/db/migration");
    Path sagaMigrations =
        repoRoot.resolve("services/common-saga/src/main/resources/db/migration/saga");
    return "filesystem:"
        + serviceMigrations.toAbsolutePath()
        + ",filesystem:"
        + sagaMigrations.toAbsolutePath();
  }

  private static String serviceModuleDirectory(String serviceSchema) {
    String moduleDirectory = SERVICE_MODULE_DIRECTORY.get(serviceSchema);
    if (moduleDirectory == null) {
      throw new IllegalArgumentException(
          "No service module mapping defined for schema " + serviceSchema);
    }
    return moduleDirectory;
  }

  private static String flywayHistoryTable(String serviceSchema) {
    return "flyway_schema_history_" + serviceSchema;
  }

  private static Path findRepoRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Unable to locate repo root for Flyway test support");
  }
}
