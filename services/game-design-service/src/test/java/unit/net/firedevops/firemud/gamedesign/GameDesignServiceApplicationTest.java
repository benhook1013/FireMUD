package net.firedevops.firemud.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class GameDesignServiceApplicationTest {
  private static final AtomicInteger MIGRATION_SIDE_EFFECTS = new AtomicInteger();

  @BeforeEach
  void resetMigrationSideEffect() {
    MIGRATION_SIDE_EFFECTS.set(0);
  }

  @Test
  void selectsOneShotModesIndependentlyAndRejectsBothTogether() {
    assertThat(GameDesignServiceApplication.selectMigrationMode("true", null))
        .isEqualTo(GameDesignServiceApplication.MigrationMode.ENTITY_BASELINE);
    assertThat(GameDesignServiceApplication.selectMigrationMode(null, "true"))
        .isEqualTo(GameDesignServiceApplication.MigrationMode.TENANT_ASSOCIATION);
    assertThat(GameDesignServiceApplication.selectMigrationMode(null, null))
        .isEqualTo(GameDesignServiceApplication.MigrationMode.NORMAL);

    assertThatThrownBy(() -> GameDesignServiceApplication.selectMigrationMode("true", "true"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be enabled together");
  }

  @Test
  void missingFlywaySettingStopsBeforeBeanMigrationSideEffect() {
    assertThatThrownBy(() -> startOneShotApplication())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Game Design one-shot migration Jobs require spring.flyway.enabled=false explicitly");

    assertThat(MIGRATION_SIDE_EFFECTS).hasValue(0);
  }

  @Test
  void explicitFalseAllowsApplicationInitialization() {
    try (ConfigurableApplicationContext context =
        startOneShotApplication("--spring.flyway.enabled=false")) {
      assertThat(context.isActive()).isTrue();
      assertThat(MIGRATION_SIDE_EFFECTS).hasValue(1);
    }
  }

  @Test
  void trueOrInvalidFlywaySettingStopsBeforeBeanMigrationSideEffect() {
    for (String setting : new String[] {"true", "invalid"}) {
      assertThatThrownBy(() -> startOneShotApplication("--spring.flyway.enabled=" + setting))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Game Design one-shot migration Jobs require spring.flyway.enabled=false explicitly");
      assertThat(MIGRATION_SIDE_EFFECTS).hasValue(0);
    }
  }

  private static ConfigurableApplicationContext startOneShotApplication(String... args) {
    SpringApplication application = new SpringApplication(SideEffectConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setLogStartupInfo(false);
    application.addInitializers(
        new GameDesignServiceApplication.RequireFlywayDisabledInitializer());
    String[] isolatedArgs = new String[args.length + 1];
    isolatedArgs[0] = "--spring.config.location=optional:classpath:/missing-game-design-test.yml";
    System.arraycopy(args, 0, isolatedArgs, 1, args.length);
    return application.run(isolatedArgs);
  }

  @Configuration(proxyBeanMethods = false)
  static class SideEffectConfiguration {
    @Bean
    Object migrationSideEffect() {
      MIGRATION_SIDE_EFFECTS.incrementAndGet();
      return new Object();
    }
  }
}
