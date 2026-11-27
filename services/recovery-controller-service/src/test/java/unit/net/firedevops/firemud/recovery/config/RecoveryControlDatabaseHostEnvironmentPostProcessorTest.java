package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class RecoveryControlDatabaseHostEnvironmentPostProcessorTest {
  private static final String REDIS_AUTO_CONFIGURATION =
      "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration";

  private final RecoveryControlDatabaseHostEnvironmentPostProcessor postProcessor =
      new RecoveryControlDatabaseHostEnvironmentPostProcessor();

  @Test
  void rejectsMissingOrBlankRecoveryDatabaseHost() {
    assertThatThrownBy(() ->
            postProcessor.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FIREMUD_RECOVERY_CONTROL_DB_HOST");

    assertThatThrownBy(() ->
            postProcessor.postProcessEnvironment(
                new MockEnvironment()
                    .withProperty(
                        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, " \t"),
                new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FIREMUD_RECOVERY_CONTROL_DB_HOST");
  }

  @Test
  void bootDiscoversAndInvokesTheRegisteredDatasourceGuard() {
    // Exercise normal Boot factory discovery without registering connection-capable beans.
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(FactoryDiscoveryProbe.class);
    application.setEnvironment(environment);
    application.setWebApplicationType(WebApplicationType.NONE);
    assertThatThrownBy(
            () -> {
              try (var context =
                  application.run(
                      "--spring.config.location=optional:classpath:/recovery-factory-discovery-fixture.yml",
                      "--spring.flyway.enabled=false",
                      "--spring.grpc.server.enabled=false",
                      "--FIREMUD_RECOVERY_CONTROL_DB_HOST=recovery-control.invalid",
                      "--spring.datasource.url=" + recoveryUrl("unconfigured.invalid", "5432"))) {
                assertThatCode(context::getBeanDefinitionCount).doesNotThrowAnyException();
              }
            })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.datasource.url ")
        .hasMessageNotContaining("unconfigured.invalid");
  }

  @Configuration(proxyBeanMethods = false)
  static class FactoryDiscoveryProbe {}

  @Test
  void acceptsExplicitLocalhostForAnIsolatedFixture() {
    assertThatCode(() ->
            postProcessor.postProcessEnvironment(
                fixtureEnvironment()
                    .withProperty(
                        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY,
                        "localhost")
                    .withProperty("spring.datasource.url", recoveryUrl("localhost", "5432")),
                new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsCommaSeparatedRecoveryDatabaseHosts() {
    assertThatThrownBy(() ->
            postProcessor.postProcessEnvironment(
                new MockEnvironment()
                    .withProperty(
                        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY,
                        "recovery-control.invalid,localhost"),
                new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly one host");
  }

  @Test
  void acceptsAnExplicitSingleRecoveryDatabaseHostname() {
    assertThatCode(() ->
            postProcessor.postProcessEnvironment(
                fixtureEnvironment()
                    .withProperty(
                        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY,
                        "recovery-control.invalid")
                    .withProperty(
                        "spring.datasource.url", recoveryUrl("recovery-control.invalid", "5432")),
                new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "invalid", "0", "65536", "5432/other", "5432,5433"})
  void rejectsInvalidRecoveryDatabasePorts(String port) {
    var environment =
        new MockEnvironment()
            .withProperty(
                RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, "localhost")
            .withProperty(RecoveryControlDatabaseHostEnvironmentPostProcessor.PORT_PROPERTY, port);
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FIREMUD_RECOVERY_CONTROL_DB_PORT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "5544", "65535"})
  void acceptsAnExplicitRecoveryDatabasePort(String port) {
    var environment =
        fixtureEnvironment()
            .withProperty(
                RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, "localhost")
            .withProperty(RecoveryControlDatabaseHostEnvironmentPostProcessor.PORT_PROPERTY, port)
            .withProperty("spring.datasource.url", recoveryUrl("localhost", port));
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost",
        "127.0.0.1",
        "10.2.3.4",
        "[::1]",
        "[2001:db8::1]",
        "recovery-control",
        "recovery-control.invalid."
      })
  void acceptsOnlySupportedSingleHostForms(String host) {
    var environment =
        fixtureEnvironment()
            .withProperty(RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, host)
            .withProperty("spring.datasource.url", recoveryUrl(host, "5432"));
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "host:5432",
        "host/database",
        "host?user=secret",
        "host#fragment",
        "user@host",
        "host%2fother",
        "jdbc:postgresql://host",
        "//host",
        "host\\other",
        "host,other",
        "host other",
        "host\n",
        "::1",
        "[::1]:5432",
        "[::gg]",
        "[1:2:3]",
        "[fe80::1%25eth0]",
        "256.1.2.3",
        "-host",
        "host..invalid"
      })
  void rejectsUrlComponentsAndMalformedHostLiterals(String host) {
    var environment =
        new MockEnvironment()
            .withProperty(RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, host);
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly one host")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY,
        RecoveryControlDatabaseHostEnvironmentPostProcessor.PORT_PROPERTY
      })
  void sanitizesDatabaseEndpointPropertyResolutionFailures(String property) {
    var environment = fixtureEnvironment().withProperty(property, "${fixture-sensitive-key}");
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Recovery database overrides must retain")
        .hasMessageNotContaining("fixture-sensitive-key")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"spring.datasource.url", "spring.datasource.hikari.jdbc-url"})
  void requiresExtendedQueryModeOnTheRecoveryDatasource(String property) {
    var environment =
        fixtureEnvironment()
            .withProperty(property, recoveryUrl("localhost", "5432").replace("extended", "simple"));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith(property + " must match the dedicated recovery datasource")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.datasource.url",
        "spring.datasource.hikari.jdbc-url",
        "spring.datasource.username",
        "spring.datasource.password",
        "spring.datasource.hikari.username",
        "spring.datasource.hikari.password",
        "spring.datasource.jndi-name",
        "spring.datasource.type",
        "spring.datasource.hikari.data-source-class-name",
        "spring.datasource.hikari.data-source-jndi",
        "spring.datasource.hikari.data-source-properties.serverName",
        "spring.flyway.url",
        "spring.flyway.user",
        "spring.flyway.password"
      })
  void refusesDivergentDatasourceOrSeparateFlywayOverridesWithoutExposingValues(String property) {
    var environment = fixtureEnvironment().withProperty(property, "override-secret");
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith(
            switch (property) {
              case "spring.datasource.hikari.data-source-properties.serverName" ->
                  "spring.datasource.hikari.data-source-properties ";
              case "spring.datasource.hikari.data-source-jndi" ->
                  "spring.datasource.hikari.data-source-j-n-d-i ";
              default -> property + " ";
            })
        .hasMessageNotContaining("override-secret")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_HIKARI_JDBCURL",
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_DATASOURCE_HIKARI_DATASOURCECLASSNAME",
        "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SERVERNAME",
        "SPRING_FLYWAY_URL",
        "SPRING_FLYWAY_USER",
        "SPRING_FLYWAY_PASSWORD"
      })
  void refusesRelaxedEnvironmentOverridesBeforeDatasourceOrMigrationSetup(String property) {
    var environment = fixtureEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of(property, "override-secret")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith(
            switch (property) {
              case "SPRING_DATASOURCE_URL" -> "spring.datasource.url ";
              case "SPRING_DATASOURCE_HIKARI_JDBCURL" -> "spring.datasource.hikari.jdbc-url ";
              case "SPRING_DATASOURCE_PASSWORD" -> "spring.datasource.password ";
              case "SPRING_DATASOURCE_HIKARI_DATASOURCECLASSNAME" ->
                  "spring.datasource.hikari.data-source-class-name ";
              case "SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SERVERNAME" ->
                  "spring.datasource.hikari.data-source-properties ";
              case "SPRING_FLYWAY_URL" -> "spring.flyway.url ";
              case "SPRING_FLYWAY_USER" -> "spring.flyway.user ";
              case "SPRING_FLYWAY_PASSWORD" -> "spring.flyway.password ";
              default ->
                  throw new AssertionError("Unhandled override fixture property: " + property);
            })
        .hasMessageNotContaining("override-secret")
        .hasNoCause();
  }

  @Test
  void rejectsAutomaticBaseliningFromRelaxedEnvironmentOverrides() {
    var environment = fixtureEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment",
                Map.of(
                    "SPRING_FLYWAY_BASELINEONMIGRATE",
                    "true",
                    "SPRING_FLYWAY_BASELINEVERSION",
                    "5")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.baseline-on-migrate must be disabled");
  }

  @Test
  void acceptsExplicitlyDisabledAutomaticBaselining() {
    var environment =
        fixtureEnvironment().withProperty("spring.flyway.baseline-on-migrate", "false");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsDatasourceAutoConfigurationExclusionEvenWithMigrationsDisabled(
      boolean flywayEnabled) {
    var environment =
        fixtureEnvironment()
            .withProperty("spring.flyway.enabled", Boolean.toString(flywayEnabled))
            .withProperty("spring.autoconfigure.exclude[0]", REDIS_AUTO_CONFIGURATION)
            .withProperty(
                "spring.autoconfigure.exclude[1]", DataSourceAutoConfiguration.class.getName());
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.autoconfigure.exclude must retain the recovery datasource")
        .hasNoCause();
  }

  @Test
  void rejectsAutoConfigurationExclusionForEnabledFlyway() {
    var environment =
        fixtureEnvironment()
            .withProperty(
                "spring.autoconfigure.exclude",
                REDIS_AUTO_CONFIGURATION + "," + FlywayAutoConfiguration.class.getName());
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.autoconfigure.exclude must retain the recovery datasource")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "no", "off", "0", "TRUE", "FALSE", " false ", "fixture-secret"})
  void rejectsNoncanonicalFlywayActivationValues(String activation) {
    var environment = fixtureEnvironment().withProperty("spring.flyway.enabled", activation);
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("spring.flyway.enabled must be true or false")
        .hasNoCause();
  }

  @Test
  void rejectsNoncanonicalFlywayActivationFromHighestPrecedenceEnvironment() {
    var environment = fixtureEnvironment().withProperty("spring.flyway.enabled", "true");
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of("SPRING_FLYWAY_ENABLED", "off")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("spring.flyway.enabled must be true or false")
        .hasNoCause();
  }

  @Test
  void acceptsDisabledFlywayWithoutMigrationSettings() {
    var environment = endpointFixture().withProperty("spring.flyway.enabled", "false");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void requiresCanonicalMigrationHistoryWhenFlywayActivationIsUnset() {
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(endpointFixture(), new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.table must name the dedicated recovery")
        .hasNoCause();
  }

  @ParameterizedTest
  @CsvSource({
    "spring.flyway.table, fixture_other_history",
    "spring.flyway.default-schema, fixture_other_schema",
    "spring.flyway.schemas, fixture_other_schema",
    "spring.flyway.schemas, 'recovery_control,fixture_other_schema'",
    "spring.flyway.locations, ''",
    "spring.flyway.locations, classpath:db/fixture_other",
    "spring.flyway.locations, 'classpath:db/recovery-control-migration,classpath:db/fixture_other'"
  })
  void rejectsDivergentFlywayMigrationIdentity(String property, String value) {
    var environment = fixtureEnvironment().withProperty(property, value);
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith(property + " ")
        .hasMessageNotContaining("fixture_other")
        .hasNoCause();
  }

  @Test
  void rejectsSkipExecutionFromRelaxedEnvironmentOverride() {
    var environment =
        fixtureEnvironment().withProperty("spring.flyway.skip-executing-migrations", "false");
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of("SPRING_FLYWAY_SKIPEXECUTINGMIGRATIONS", "true")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.skip-executing-migrations must be disabled")
        .hasNoCause();
  }

  @Test
  void acceptsExplicitMigrationExecution() {
    var environment =
        fixtureEnvironment().withProperty("spring.flyway.skip-executing-migrations", "false");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsDisabledSchemaCreationFromRelaxedEnvironmentOverride() {
    var environment = fixtureEnvironment().withProperty("spring.flyway.create-schemas", "true");
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of("SPRING_FLYWAY_CREATESCHEMAS", "false")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.create-schemas must be enabled")
        .hasNoCause();
  }

  @Test
  void rejectsDryRunOutputFromRelaxedEnvironmentOverride() {
    var environment = fixtureEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment",
                Map.of("SPRING_FLYWAY_DRYRUNOUTPUT", "fixture-dry-run.sql")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.dry-run-output is unsupported")
        .hasMessageNotContaining("fixture-dry-run.sql")
        .hasNoCause();
  }

  @Test
  void ignoresDryRunOutputForDisabledFlywayFixture() {
    var environment =
        fixtureEnvironment()
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("spring.flyway.dry-run-output", "fixture-dry-run.sql");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsIncompatibleFlywayEncodingFromRelaxedEnvironmentOverride() {
    var environment = fixtureEnvironment().withProperty("spring.flyway.encoding", "UTF-8");
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of("SPRING_FLYWAY_ENCODING", "UTF-16")));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.encoding must be UTF-8")
        .hasNoCause();
  }

  @Test
  void acceptsExplicitUtf8FlywayEncoding() {
    var environment = fixtureEnvironment().withProperty("spring.flyway.encoding", "UTF-8");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"spring.flyway.callback-locations", "spring.flyway.callback-locations[0]"})
  void rejectsScalarAndIndexedFlywayCallbackLocations(String callbackProperty) {
    var environment = fixtureEnvironment().withProperty(callbackProperty, "classpath:db/callbacks");
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("spring.flyway.callback-locations is unsupported")
        .hasMessageNotContaining("classpath:db/callbacks")
        .hasNoCause();
  }

  @Test
  void ignoresEncodingAndCallbackLocationsForDisabledFlywayFixture() {
    var environment =
        fixtureEnvironment()
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("spring.flyway.encoding", "UTF-16")
            .withProperty("spring.flyway.callback-locations[0]", "classpath:db/callbacks");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsRedisExclusionAndSchemaCreationForEnabledFlyway(boolean flywayEnabled) {
    var environment =
        fixtureEnvironment()
            .withProperty("spring.autoconfigure.exclude", REDIS_AUTO_CONFIGURATION)
            .withProperty("spring.flyway.enabled", Boolean.toString(flywayEnabled))
            .withProperty("spring.flyway.create-schemas", Boolean.toString(flywayEnabled));
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsMatchingDatasourceOverridesAndKeepsLocalFixtureSettings() {
    String url = recoveryUrl("localhost", "5544");
    var environment =
        fixtureEnvironment()
            .withProperty("FIREMUD_RECOVERY_CONTROL_DB_PORT", "5544")
            .withProperty("spring.datasource.url", url)
            .withProperty("spring.datasource.hikari.jdbc-url", url)
            .withProperty("spring.datasource.username", "fixture-user")
            .withProperty("spring.datasource.password", "fixture-password")
            .withProperty("spring.datasource.hikari.username", "fixture-user")
            .withProperty("spring.datasource.hikari.password", "fixture-password")
            .withProperty("spring.datasource.type", "com.zaxxer.hikari.HikariDataSource")
            .withProperty("spring.flyway.locations", "classpath:db/recovery-control-migration");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesEvenAnIdenticalExplicitFlywayEndpoint() {
    var environment =
        fixtureEnvironment().withProperty("spring.flyway.url", recoveryUrl("localhost", "5432"));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.flyway.url")
        .hasNoCause();
  }

  @Test
  void requiresExplicitPrimaryRecoveryUrlEvenWhenHikariHasTheExactEndpoint() {
    var environment =
        new MockEnvironment()
            .withProperty(
                RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, "localhost")
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("spring.datasource.hikari.jdbc-url", recoveryUrl("localhost", "5432"));
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.url")
        .hasNoCause();
  }

  private static String recoveryUrl(String host, String port) {
    return "jdbc:postgresql://"
        + host
        + ":"
        + port
        + "/firemud_recovery_control?logServerErrorDetail=false&preferQueryMode=extended";
  }

  private static MockEnvironment fixtureEnvironment() {
    return endpointFixture()
        .withProperty("spring.flyway.locations", "classpath:db/recovery-control-migration")
        .withProperty("spring.flyway.schemas", "recovery_control")
        .withProperty("spring.flyway.default-schema", "recovery_control")
        .withProperty("spring.flyway.table", "flyway_schema_history_recovery_control");
  }

  private static MockEnvironment endpointFixture() {
    return new MockEnvironment()
        .withProperty("spring.datasource.url", recoveryUrl("localhost", "5432"))
        .withProperty(
            RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, "localhost")
        .withProperty("FIREMUD_RECOVERY_CONTROL_DB_USER", "fixture-user")
        .withProperty("FIREMUD_RECOVERY_CONTROL_DB_PASSWORD", "fixture-password");
  }
}
