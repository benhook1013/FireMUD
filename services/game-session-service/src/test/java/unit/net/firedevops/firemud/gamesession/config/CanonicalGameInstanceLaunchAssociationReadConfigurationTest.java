package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.gamesession.config.CanonicalGameInstanceLaunchAssociationReadConfiguration;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class CanonicalGameInstanceLaunchAssociationReadConfigurationTest {
  private static final String OWNER_OPT_IN =
      "firemud.canonical-game-instance-launch-association-owner-read.enabled";

  @Test
  void associationOwnerRepositoryIsDisabledByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            CanonicalGameInstanceLaunchAssociationReadConfiguration.class, Dependencies.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(CanonicalGameInstanceLaunchAssociationRepository.class);
            });
  }

  @Test
  void explicitOptInRequiresAndUsesTheExactWorkloadNamespace() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            CanonicalGameInstanceLaunchAssociationReadConfiguration.class, Dependencies.class)
        .withPropertyValues(OWNER_OPT_IN + "=true", "firemud.grpc.workload-namespace=gameplay")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .hasSingleBean(CanonicalGameInstanceLaunchAssociationRepository.class);
            });

    new ApplicationContextRunner()
        .withUserConfiguration(
            CanonicalGameInstanceLaunchAssociationReadConfiguration.class, Dependencies.class)
        .withPropertyValues(OWNER_OPT_IN + "=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    DSLContext dsl() {
      return mock(DSLContext.class);
    }
  }
}
