package net.firedevops.firemud.test;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgresBackedServiceTestSupport {
  private static final int POSTGRES_PORT = 5432;
  private static final int MAX_DIAGNOSTIC_LOG_BYTES = 16 * 1024;
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

  public static DockerImageName postgresImage(String imageReference) {
    DockerImageName image = DockerImageName.parse(imageReference);
    if (!imageReference.matches("postgres:[A-Za-z0-9_][A-Za-z0-9_.-]*@sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected a digest-pinned official postgres image");
    }
    return image.asCompatibleSubstituteFor(DockerImageName.parse("postgres"));
  }

  public static void awaitPostgresJdbcReady(PostgreSQLContainer<?> postgres) {
    String jdbcUrl = postgres.getJdbcUrl();
    Properties connectionProperties = new Properties();
    connectionProperties.setProperty("user", postgres.getUsername());
    connectionProperties.setProperty("password", postgres.getPassword());
    connectionProperties.setProperty("connectTimeout", "2");
    connectionProperties.setProperty("socketTimeout", "2");
    AtomicReference<SQLException> lastFailure = new AtomicReference<>();

    try {
      Awaitility.await()
          .alias("PostgreSQL mapped JDBC readiness at " + jdbcUrl)
          .pollInterval(Duration.ofMillis(100))
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, connectionProperties);
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT 1")) {
                  if (result.next() && result.getInt(1) == 1) {
                    return true;
                  }
                  lastFailure.set(new SQLException("PostgreSQL readiness query did not return 1"));
                  return false;
                } catch (SQLException failure) {
                  lastFailure.set(failure);
                  return false;
                }
              });
    } catch (ConditionTimeoutException timeout) {
      SQLException failure = lastFailure.get();
      if (failure == null) {
        attachPostgresReadinessDiagnostics(postgres, timeout);
        throw timeout;
      }
      IllegalStateException readinessFailure =
          new IllegalStateException(
              "Timed out waiting for PostgreSQL mapped JDBC readiness", failure);
      readinessFailure.addSuppressed(timeout);
      attachPostgresReadinessDiagnostics(postgres, readinessFailure);
      throw readinessFailure;
    }
  }

  private static void attachPostgresReadinessDiagnostics(
      PostgreSQLContainer<?> postgres, Throwable readinessFailure) {
    try {
      String diagnostics = capturePostgresReadinessDiagnostics(postgres);
      readinessFailure.addSuppressed(new IllegalStateException(diagnostics));
      System.err.println(diagnostics);
    } catch (RuntimeException diagnosticsFailure) {
      readinessFailure.addSuppressed(
          new IllegalStateException(
              "PostgreSQL readiness diagnostic collection failed", diagnosticsFailure));
    }
  }

  private static String capturePostgresReadinessDiagnostics(PostgreSQLContainer<?> postgres) {
    String containerId = postgres.getContainerId();
    String host = postgres.getHost();
    Integer mappedPort = postgres.getMappedPort(POSTGRES_PORT);
    InspectContainerResponse containerInfo = postgres.getCurrentContainerInfo();
    InspectContainerResponse.ContainerState state = containerInfo.getState();
    String logs = tailUtf8(postgres.getLogs(), MAX_DIAGNOSTIC_LOG_BYTES);

    return "PostgreSQL JDBC readiness timeout diagnostics:"
        + "\ncontainerId="
        + containerId
        + "\nhost="
        + host
        + "\nmappedPort="
        + mappedPort
        + "\nstate.running="
        + (state == null ? null : state.getRunning())
        + "\nstate.oomKilled="
        + (state == null ? null : state.getOOMKilled())
        + "\nstate.exitCode="
        + (state == null ? null : state.getExitCodeLong())
        + "\nstate.error="
        + (state == null ? null : state.getError())
        + "\nmappedPortBindings[5432/tcp]="
        + postgresPortBindings(containerInfo)
        + "\npostgresStartupLogTail(maxBytes="
        + MAX_DIAGNOSTIC_LOG_BYTES
        + "):\n"
        + logs;
  }

  private static String postgresPortBindings(InspectContainerResponse containerInfo) {
    NetworkSettings networkSettings = containerInfo.getNetworkSettings();
    Ports ports = networkSettings == null ? null : networkSettings.getPorts();
    Ports.Binding[] bindings =
        ports == null ? null : ports.getBindings().get(ExposedPort.tcp(POSTGRES_PORT));
    if (bindings == null || bindings.length == 0) {
      return "<none>";
    }

    StringBuilder result = new StringBuilder();
    for (Ports.Binding binding : bindings) {
      if (result.length() > 0) {
        result.append(',');
      }
      result.append(binding.getHostIp()).append(':').append(binding.getHostPortSpec());
    }
    return result.toString();
  }

  private static String tailUtf8(String value, int maxBytes) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return value;
    }

    int start = bytes.length - maxBytes;
    while (start < bytes.length && (bytes[start] & 0xc0) == 0x80) {
      start++;
    }
    return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
  }

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
