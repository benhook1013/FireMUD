package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.service.devisolated.DevIsolatedCommandService;
import net.firedevops.firemud.gamesession.service.impl.CommandServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CommandServiceConditionDebugTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(CommandServiceTestConfig.class)
          .withBean(CommandServiceImpl.class)
          .withBean(DevIsolatedCommandService.class);

  @Test
  void commandServiceImplRegistersWhenDevIsolatedIsFalse() {
    contextRunner
        .withPropertyValues("game-session.dev-isolated=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(CommandService.class);
              assertThat(context).hasSingleBean(CommandServiceImpl.class);
              assertThat(context).doesNotHaveBean(DevIsolatedCommandService.class);
            });
  }

  @Configuration
  static class CommandServiceTestConfig {
    @Bean
    TickService tickService() {
      return Mockito.mock(TickService.class);
    }

    @Bean
    SessionRateLimiter sessionRateLimiter() {
      return Mockito.mock(SessionRateLimiter.class);
    }

    @Bean
    GameInstanceRepository gameInstanceRepository() {
      return Mockito.mock(GameInstanceRepository.class);
    }

    @Bean
    DevIsolatedProperties devIsolatedProperties() {
      return new DevIsolatedProperties(false);
    }
  }
}
