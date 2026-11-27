package net.firedevops.firemud.recovery.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Rejects implicit, malformed, or divergent recovery database endpoints before datasource setup.
 */
public final class RecoveryControlDatabaseHostEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  public static final String HOST_PROPERTY = "FIREMUD_RECOVERY_CONTROL_DB_HOST";
  static final String PORT_PROPERTY = "FIREMUD_RECOVERY_CONTROL_DB_PORT";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String host = environment.getProperty(HOST_PROPERTY);
    if (!isSingleHost(host)) {
      throw new IllegalStateException(
          HOST_PROPERTY + " must name the isolated recovery database using exactly one host");
    }
    String port = environment.getProperty(PORT_PROPERTY, "5432");
    int portNumber = port.matches("[0-9]{1,5}") ? Integer.parseInt(port) : 0;
    if (portNumber < 1 || portNumber > 65535) {
      throw new IllegalStateException(
          PORT_PROPERTY + " must be a decimal port from 1 through 65535");
    }
    // Use Boot's relaxed binding, including environment-variable and command-line overrides.
    // Validate rather than silently overriding an endpoint that would escape journal isolation.
    try {
      Binder binder = Binder.get(environment);
      boolean flywayEnabled =
          !Boolean.FALSE.equals(binder.bind("spring.flyway.enabled", Boolean.class).orElse(null));
      var configuredExclusions = binder.bind("spring.autoconfigure.exclude", String[].class);
      List<String> exclusions =
          configuredExclusions.isBound() ? Arrays.asList(configuredExclusions.get()) : List.of();
      if (exclusions.contains(DataSourceAutoConfiguration.class.getName())
          || (flywayEnabled && exclusions.contains(FlywayAutoConfiguration.class.getName()))) {
        throw new SafeOverrideException(
            "spring.autoconfigure.exclude must retain the recovery datasource and enabled Flyway");
      }
      if (flywayEnabled
          && Boolean.FALSE.equals(
              binder.bind("spring.flyway.create-schemas", Boolean.class).orElse(null))) {
        throw new SafeOverrideException(
            "spring.flyway.create-schemas must be enabled to bootstrap the recovery schema");
      }
      if (flywayEnabled) {
        requireAbsent(binder, "spring.flyway.dry-run-output");
        String encoding = binder.bind("spring.flyway.encoding", String.class).orElse(null);
        if (encoding != null && !"UTF-8".equalsIgnoreCase(encoding.trim())) {
          throw new SafeOverrideException(
              "spring.flyway.encoding must be UTF-8 for the recovery migrations");
        }
        if (binder
            .bind("spring.flyway.callback-locations", Bindable.listOf(String.class))
            .isBound()) {
          throw new SafeOverrideException(
              "spring.flyway.callback-locations is unsupported for the recovery migrations");
        }
      }
      String url = "jdbc:postgresql://" + host + ":" + port + "/firemud_recovery_control";
      var primaryUrl = binder.bind("spring.datasource.url", String.class);
      if (!primaryUrl.isBound() || !Objects.equals(primaryUrl.get(), url)) {
        throw new SafeOverrideException(
            "spring.datasource.url must match the dedicated recovery datasource");
      }
      requireMatchingOverride(binder, "spring.datasource.hikari.jdbc-url", url);
      String user = environment.getProperty("FIREMUD_RECOVERY_CONTROL_DB_USER");
      String password = environment.getProperty("FIREMUD_RECOVERY_CONTROL_DB_PASSWORD");
      requireMatchingOverride(binder, "spring.datasource.username", user);
      requireMatchingOverride(binder, "spring.datasource.password", password);
      requireMatchingOverride(binder, "spring.datasource.hikari.username", user);
      requireMatchingOverride(binder, "spring.datasource.hikari.password", password);
      requireMatchingOverride(
          binder, "spring.datasource.type", "com.zaxxer.hikari.HikariDataSource");
      requireAbsent(binder, "spring.datasource.jndi-name");
      requireAbsent(binder, "spring.datasource.hikari.data-source-class-name");
      requireAbsent(binder, "spring.datasource.hikari.data-source-j-n-d-i");
      Map<String, String> dataSourceProperties =
          binder
              .bind(
                  "spring.datasource.hikari.data-source-properties",
                  Bindable.mapOf(String.class, String.class))
              .orElse(Map.of());
      if (dataSourceProperties == null || !dataSourceProperties.isEmpty()) {
        throw new SafeOverrideException(
            "spring.datasource.hikari.data-source-properties is unsupported");
      }
      // Flyway must use the very same datasource, not create an independent connection endpoint.
      requireAbsent(binder, "spring.flyway.url");
      requireAbsent(binder, "spring.flyway.user");
      requireAbsent(binder, "spring.flyway.password");
      if (Boolean.TRUE.equals(
          binder.bind("spring.flyway.baseline-on-migrate", Boolean.class).orElse(null))) {
        throw new SafeOverrideException(
            "spring.flyway.baseline-on-migrate must be disabled for the recovery datasource");
      }
    } catch (SafeOverrideException invalidOverride) {
      throw invalidOverride;
    } catch (RuntimeException invalidOverride) {
      // Binding exceptions can carry URLs and passwords in both their message and cause.
      throw new IllegalStateException(
          "Recovery database overrides must retain the dedicated datasource and shared Flyway"
              + " connection");
    }
  }

  private static void requireMatchingOverride(Binder binder, String property, String expected) {
    var value = binder.bind(property, String.class);
    if (value.isBound() && !Objects.equals(value.get(), expected)) {
      throw new SafeOverrideException(property + " must match the dedicated recovery datasource");
    }
  }

  private static void requireAbsent(Binder binder, String property) {
    if (binder.bind(property, String.class).isBound()) {
      throw new SafeOverrideException(property + " is unsupported for the recovery datasource");
    }
  }

  // Only validation branches with fixed, value-free text may use this exception type.
  private static final class SafeOverrideException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private SafeOverrideException(String message) {
      super(message);
    }
  }

  private static boolean isSingleHost(String host) {
    if (host == null || host.isEmpty()) return false;
    // JDBC interpolation requires brackets around IPv6. URI parsing validates the literal without
    // performing DNS resolution; scoped/escaped addresses and URL components are not supported.
    if (host.matches("\\[[0-9a-fA-F:.]+\\]")) {
      try {
        return URI.create("http://" + host).getHost() != null;
      } catch (IllegalArgumentException invalidLiteral) {
        return false;
      }
    }
    if (host.matches("[0-9]+(?:\\.[0-9]+){3}")) {
      for (String octet : host.split("\\.")) {
        if (octet.length() > 3 || Integer.parseInt(octet) > 255) return false;
      }
      return true;
    }
    return host.length() <= 253
        && host.matches(
            "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\\.?");
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
