package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class RecoveryControlDatabaseHostEnvironmentPostProcessorTest {
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
  void acceptsExplicitLocalhostForAnIsolatedFixture() {
    assertThatCode(() ->
            postProcessor.postProcessEnvironment(
                new MockEnvironment()
                    .withProperty(
                        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY,
                        "localhost"),
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
                new MockEnvironment()
                    .withProperty(
                        RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY,
                        "recovery-control.invalid"),
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
        new MockEnvironment()
            .withProperty(
                RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, "localhost")
            .withProperty(RecoveryControlDatabaseHostEnvironmentPostProcessor.PORT_PROPERTY, port);
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
        new MockEnvironment()
            .withProperty(RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, host);
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
        .hasMessage(
            "Recovery database overrides must retain the dedicated datasource and shared Flyway connection")
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
        .hasMessage(
            "Recovery database overrides must retain the dedicated datasource and shared Flyway connection")
        .hasNoCause();
  }

  @Test
  void acceptsMatchingDatasourceOverridesAndKeepsLocalFixtureSettings() {
    String url = "jdbc:postgresql://localhost:5544/firemud_recovery_control";
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
            .withProperty("spring.flyway.schemas", "recovery_control")
            .withProperty("spring.flyway.default-schema", "recovery_control")
            .withProperty("spring.flyway.table", "flyway_schema_history_recovery_control")
            .withProperty("spring.flyway.locations", "classpath:db/recovery-control-migration");
    assertThatCode(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesEvenAnIdenticalExplicitFlywayEndpoint() {
    var environment =
        fixtureEnvironment()
            .withProperty(
                "spring.flyway.url", "jdbc:postgresql://localhost:5432/firemud_recovery_control");
    assertThatThrownBy(
            () -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shared Flyway connection")
        .hasNoCause();
  }

  private static MockEnvironment fixtureEnvironment() {
    return new MockEnvironment()
        .withProperty(
            RecoveryControlDatabaseHostEnvironmentPostProcessor.HOST_PROPERTY, "localhost")
        .withProperty("FIREMUD_RECOVERY_CONTROL_DB_USER", "fixture-user")
        .withProperty("FIREMUD_RECOVERY_CONTROL_DB_PASSWORD", "fixture-password");
  }
}
