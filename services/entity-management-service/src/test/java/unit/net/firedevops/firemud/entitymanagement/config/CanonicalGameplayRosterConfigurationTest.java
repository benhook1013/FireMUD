package unit.net.firedevops.firemud.entitymanagement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.config.CanonicalGameplayRosterConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentService;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CanonicalGameplayRosterConfigurationTest {
  @Test
  void defaultConfigurationKeepsGameSessionOwnerReadDisabledAndDenied() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
              assertThat(context).hasSingleBean(CanonicalGameplayRosterOwnerEvidencePort.class);
              assertThat(context)
                  .hasSingleBean(CanonicalGameplayRosterSelectedAssignmentService.class);
              assertThat(context.getBean(CanonicalGameplayRosterOwnerEvidencePort.class))
                  .isNotInstanceOf(GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter.class);
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(CanonicalGameplayRosterOwnerEvidencePort.class)
                              .resolveCurrentTarget(null))
                  .isInstanceOf(
                      CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException
                          .class);
            });
  }

  @Test
  void explicitEnablementComposesTheEntityAdapterAroundThePinnedGameSessionClient() {
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    runner()
        .withPropertyValues("firemud.canonical-gameplay-roster-owner-read.enabled=true")
        .withBean(GameSessionCanonicalGameplayRosterOwnerReadClient.class, () -> client)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .getBean(CanonicalGameplayRosterOwnerEvidencePort.class)
                  .isInstanceOf(GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter.class);
            });
  }

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(CanonicalGameplayRosterConfiguration.class)
        .withPropertyValues("firemud.grpc.workload-namespace=test")
        .withBean(CharacterRepository.class, () -> mock(CharacterRepository.class));
  }
}
