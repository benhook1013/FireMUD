package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import javax.sql.DataSource;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.impl.GameInstanceServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

class CrossServiceAppHarnessTest {
  private static final String OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";
  private static final StartSessionRequest REQUEST =
      new StartSessionRequest(41L, 810L, "cross-service-fixture", OWNER_ACCOUNT_UUID);

  @Test
  void databaseDisabledOrMissingReplacesTheRealServiceWithTheStub() {
    for (String databaseEnabled : new String[] {"false", null}) {
      try (AnnotationConfigApplicationContext context = context(databaseEnabled)) {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(GameInstanceServiceImpl.class)).isEmpty();
        assertThat(context.getBean("gameInstanceServiceImpl"))
            .isNotInstanceOf(GameInstanceServiceImpl.class);

        GameInstanceService service = context.getBean(GameInstanceService.class);
        assertThat(service.startSession(REQUEST, false).id()).isEqualTo(-1L);
        assertThatThrownBy(() -> service.startRunOwnedInitialLaunch(REQUEST))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("run-owned launch requires a database-enabled test context");
      }
    }
  }

  @Test
  void databaseEnabledWrapperDelegatesOnlyRunOwnedLaunchToTheNamedService() {
    try (AnnotationConfigApplicationContext context =
        context("true", EnabledGameInstanceServiceConfiguration.class)) {
      GameInstanceService actualService =
          context.getBean("gameInstanceServiceImpl", GameInstanceService.class);
      GameInstanceService harnessService = context.getBean(GameInstanceService.class);

      assertThat(harnessService.startSession(REQUEST, false).id()).isEqualTo(-1L);
      assertThat(harnessService.startRunOwnedInitialLaunch(REQUEST)).isNull();

      verify(actualService).startRunOwnedInitialLaunch(REQUEST);
      verify(actualService, never()).startSession(any(), anyBoolean());
    }
  }

  private static AnnotationConfigApplicationContext context(
      String databaseEnabled, Class<?>... extraConfigurations) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(true);
    if (databaseEnabled != null) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "cross-service-harness-test",
                  Map.of("firemud.database.enabled", databaseEnabled)));
    }
    if (databaseEnabled != null && databaseEnabled.equals("true")) {
      context.register(extraConfigurations);
      context.register(CrossServiceAppHarness.GameSessionTestOverrides.class);
    } else {
      context.register(GameInstanceServiceImpl.class);
      context.register(CrossServiceAppHarness.GameSessionTestOverrides.class);
    }
    context.refresh();
    return context;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EnabledGameInstanceServiceConfiguration {
    @Bean(name = "gameInstanceServiceImpl")
    GameInstanceService gameInstanceServiceImpl() {
      return mock(GameInstanceService.class);
    }
  }
}
