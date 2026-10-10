package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.gamesession.config.CanonicalGameInstanceLaunchAssociationReadConfiguration;
import net.firedevops.firemud.gamesession.config.CanonicalInitialAdmissionOwnerConfiguration;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

class CanonicalInitialAdmissionOwnerConfigurationTest {
  private static final String OWNER_OPT_IN =
      "firemud.canonical-game-instance-launch-association-owner-read.enabled";

  @Test
  void disabledOwnerCompositionDoesNotRequireCanonicalLaunchRepositories() {
    new ApplicationContextRunner()
        .withUserConfiguration(CanonicalInitialAdmissionOwnerConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(CanonicalInitialAdmissionRepository.class);
              assertThat(context).doesNotHaveBean(CanonicalInitialAdmissionService.class);
            });
  }

  @Test
  void enabledOwnerCompositionFailsClosedWithoutItsExplicitLaunchPrerequisite() {
    new ApplicationContextRunner()
        .withUserConfiguration(CanonicalInitialAdmissionOwnerConfiguration.class)
        .withPropertyValues(OWNER_OPT_IN + "=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void enabledCompositionCreatesOwnerServiceOnlyWithItsExplicitDependencies() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            CanonicalGameInstanceLaunchAssociationReadConfiguration.class,
            CanonicalInitialAdmissionOwnerConfiguration.class,
            OwnerDependencies.class)
        .withPropertyValues(OWNER_OPT_IN + "=true", "firemud.grpc.workload-namespace=gameplay")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CanonicalInitialAdmissionRepository.class);
              assertThat(context).hasSingleBean(CanonicalInitialAdmissionService.class);
            });
  }

  @Test
  void enabledCompositionFailsClosedWithoutOwnerTransactionManager() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            CanonicalGameInstanceLaunchAssociationReadConfiguration.class,
            CanonicalInitialAdmissionOwnerConfiguration.class,
            OwnerDependencies.class)
        .withPropertyValues(
            OWNER_OPT_IN + "=true",
            "firemud.grpc.workload-namespace=gameplay",
            "test.initial-admission.transaction-manager-enabled=false")
        .run(context -> assertThat(context).hasFailed());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class OwnerDependencies {
    @Bean
    DSLContext initialAdmissionDsl() {
      return mock(DSLContext.class);
    }

    @Bean(name = "transactionManager")
    @ConditionalOnProperty(
        name = "test.initial-admission.transaction-manager-enabled",
        havingValue = "true",
        matchIfMissing = true)
    PlatformTransactionManager initialAdmissionTransactionManager() {
      return mock(PlatformTransactionManager.class);
    }

    @Bean
    GameSessionCanonicalRealmCatalogRepository initialAdmissionCatalogRepository() {
      return mock(GameSessionCanonicalRealmCatalogRepository.class);
    }

    @Bean
    GameSessionCanonicalAdmissionPointerRepository initialAdmissionPointerRepository() {
      return mock(GameSessionCanonicalAdmissionPointerRepository.class);
    }

    @Bean
    GameplayAdmissionPointerEventRepository initialAdmissionEventRepository() {
      return mock(GameplayAdmissionPointerEventRepository.class);
    }

    @Bean
    CanonicalInitialAdmissionWorldVerifier initialAdmissionWorldVerifier() {
      return mock(CanonicalInitialAdmissionWorldVerifier.class);
    }
  }
}
