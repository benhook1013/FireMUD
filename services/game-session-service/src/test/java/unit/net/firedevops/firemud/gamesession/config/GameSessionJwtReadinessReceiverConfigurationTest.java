package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverEngine;
import net.firedevops.firemud.gamesession.service.impl.GameSessionJwtReadinessReceiverGrpcService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GameSessionJwtReadinessReceiverConfigurationTest {
  private static final String ENABLED = "firemud.game-session.jwt-readiness.receiver.enabled=true";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              GameSessionJwtReadinessReceiverConfiguration.class,
              GameSessionJwtReadinessReceiverGrpcService.class);

  @Test
  void receiverAndCryptoCompositionRemainAbsentByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context)
              .doesNotHaveBean(GameSessionJwtReadinessReceiverEngine.class)
              .doesNotHaveBean(GameSessionJwtReadinessLocalIdentityProvider.class)
              .doesNotHaveBean(GameSessionJwtReadinessReceiverGrpcService.class);
        });
  }

  @Test
  void explicitEnablementWithoutProtectedLocalIdentityStillLeavesReceiverUnregistered() {
    contextRunner
        .withPropertyValues(ENABLED)
        .run(
            context -> {
              assertThat(context)
                  .doesNotHaveBean(GameSessionJwtReadinessReceiverEngine.class)
                  .doesNotHaveBean(GameSessionJwtReadinessLocalIdentityProvider.class)
                  .doesNotHaveBean(GameSessionJwtReadinessReceiverGrpcService.class);
            });
  }
}
