package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
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
}
