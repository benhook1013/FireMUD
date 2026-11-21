package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
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
}
