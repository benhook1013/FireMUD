package unit.net.firedevops.firemud.entitymanagement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAccountIdentityPort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorStagingEligibilityPort;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededAssignmentAuthority;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionPreseededActorAssignmentOwnerEvidenceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PreseededActorAssignmentConfigurationTest {
  @Test
  void defaultConfigurationLeavesOwnerEvidenceUnavailableAndDoesNotCreateGsClient() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(GameSessionPreseededActorAssignmentOwnerReadClient.class);
              assertThat(context).hasSingleBean(PreseededActorAssignmentOwnerEvidencePort.class);
              assertThat(context.getBean(PreseededActorAssignmentOwnerEvidencePort.class))
                  .isNotInstanceOf(GameSessionPreseededActorAssignmentOwnerEvidenceAdapter.class);
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(PreseededActorAssignmentOwnerEvidencePort.class)
                              .resolveCurrentEligibleTarget(null, null, null))
                  .isInstanceOf(
                      PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException
                          .class);
            });
  }

  @Test
  void explicitRunOwnedEnablementComposesTheGsAssignmentOwnerAdapter() {
    var gsClient = client();
    runner()
        .withPropertyValues("firemud.run-owned-preseeded-actor-assignment.enabled=true")
        .withBean(PreseededActorAccountIdentityPort.class, () -> (account, assignment) -> null)
        .withBean(
            PreseededActorStagingEligibilityPort.class, () -> (account, tenant, assignment) -> null)
        .withBean(
            RunOwnedPreseededAssignmentAuthority.class,
            () -> mock(RunOwnedPreseededAssignmentAuthority.class))
        .withBean(GameSessionPreseededActorAssignmentOwnerReadClient.class, () -> gsClient)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .getBean(PreseededActorAssignmentOwnerEvidencePort.class)
                  .isInstanceOf(GameSessionPreseededActorAssignmentOwnerEvidenceAdapter.class);
            });
  }

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(PreseededActorAssignmentConfiguration.class)
        .withPropertyValues("firemud.grpc.workload-namespace=gameplay")
        .withBean(CharacterRepository.class, () -> mock(CharacterRepository.class));
  }

  private static GameSessionPreseededActorAssignmentOwnerReadClient client() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("entity-client.crt");
    tls.setPrivateKey("entity-client.key");
    tls.setCaCert("game-session-ca.crt");
    return new GameSessionPreseededActorAssignmentOwnerReadClient(
        new ServiceEndpointsProperties(), tls, mock(GrpcChannelFactory.class), "gameplay");
  }
}
