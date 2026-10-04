package net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

class EntityDigestBaselineMigrationJobRunnerTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(MigrationRunnerConfiguration.class);

  private static final GrpcPeerIdentity MIGRATOR =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/dev/sa/game-design-baseline-migrator",
          "dev",
          "game-design-baseline-migrator");

  @Test
  void acceptsOnlyExactDedicatedIdentityAndTargetEnvironment() {
    assertThatCode(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev", "dev", "game-design-baseline-migrator", MIGRATOR))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongTargetServiceAccountAndCertificateIdentity() {
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev", "pr-12", "game-design-baseline-migrator", MIGRATOR))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev", "dev", "firemud-app", MIGRATOR))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev",
                    "dev",
                    "game-design-baseline-migrator",
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/dev/sa/game-design-service",
                        "dev",
                        "game-design-service")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev",
                    "dev",
                    "game-design-baseline-migrator",
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/pr-12/sa/game-design-baseline-migrator",
                        "pr-12",
                        "game-design-baseline-migrator")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void requiresFlywayDisabledAndTheModeSpecificDatabaseRole() {
    assertThatCode(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireDatabaseAuthority(
                    "enumerate", "firemud_game_design_baseline_reader", false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireDatabaseAuthority(
                    "preflight", "firemud_game_design_baseline_reader", false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireDatabaseAuthority(
                    "migrate", "firemud_game_design_baseline_writer", false))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireDatabaseAuthority(
                    "enumerate", "firemud_game_design_baseline_writer", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("migration DB credential does not match the required mode");
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireDatabaseAuthority(
                    "migrate", "firemud", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("migration DB credential does not match the required mode");
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireDatabaseAuthority(
                    "migrate", "firemud_game_design_baseline_writer", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("migration Job requires Flyway to be disabled");
  }

  @Test
  void constructsWithTheRuntimeMapperWhenMigrationIsEnabled() {
    contextRunner
        .withPropertyValues("firemud.entity-baseline-migration.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(EntityDigestBaselineMigrationJobRunner.class);
              assertThat(context).hasSingleBean(ObjectMapper.class);
              assertThat(context).hasSingleBean(EntityDigestBaselineMigrationService.class);
            });
  }

  @Test
  void doesNotCreateTheRunnerWhenMigrationIsDisabled() {
    contextRunner
        .withPropertyValues("firemud.entity-baseline-migration.enabled=false")
        .run(
            context ->
                assertThat(context).doesNotHaveBean(EntityDigestBaselineMigrationJobRunner.class));
  }

  @Configuration(proxyBeanMethods = false)
  @Import(EntityDigestBaselineMigrationJobRunner.class)
  static class MigrationRunnerConfiguration {
    @Bean
    EntityDigestBaselineMigrationService migrationService() {
      return mock(EntityDigestBaselineMigrationService.class);
    }

    @Bean
    DataSource dataSource() {
      return mock(DataSource.class);
    }
  }
}
