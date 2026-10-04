package net.firedevops.firemud.accountservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AccountServiceApplicationTest {
  private static final AtomicInteger BEAN_INITIALIZATIONS = new AtomicInteger();
  private static final AtomicInteger MIGRATION_SIDE_EFFECTS = new AtomicInteger();

  @BeforeEach
  void resetStartupEffects() {
    BEAN_INITIALIZATIONS.set(0);
    MIGRATION_SIDE_EFFECTS.set(0);
  }

  @Test
  void oneShotStartupRejectsMissingFlywaySettingBeforeBeanOrMigrationInitialization() {
    var application =
        AccountServiceApplication.createMigrationApplication(StartupProbeConfiguration.class);

    assertThrows(
        IllegalStateException.class,
        () ->
            application.run(
                "--spring.config.location=optional:classpath:/missing-account-test.yml"));

    assertEquals(0, BEAN_INITIALIZATIONS.get());
    assertEquals(0, MIGRATION_SIDE_EFFECTS.get());
  }

  @Test
  void oneShotStartupRejectsEnabledOrInvalidFlywaySettingBeforeInitialization() {
    for (String value : new String[] {"true", "enabled"}) {
      resetStartupEffects();
      var application =
          AccountServiceApplication.createMigrationApplication(StartupProbeConfiguration.class);

      assertThrows(
          IllegalStateException.class,
          () ->
              application.run(
                  "--spring.config.location=optional:classpath:/missing-account-test.yml",
                  "--spring.flyway.enabled=" + value));

      assertEquals(0, BEAN_INITIALIZATIONS.get());
      assertEquals(0, MIGRATION_SIDE_EFFECTS.get());
    }
  }

  @Test
  void explicitFlywayFalseAllowsOneShotStartupInitialization() {
    var application =
        AccountServiceApplication.createMigrationApplication(StartupProbeConfiguration.class);

    try (var ignored =
        application.run(
            "--spring.config.location=optional:classpath:/missing-account-test.yml",
            "--spring.flyway.enabled=false")) {
      assertEquals(2, BEAN_INITIALIZATIONS.get());
      assertEquals(1, MIGRATION_SIDE_EFFECTS.get());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class StartupProbeConfiguration {
    @Bean
    Object beanInitializationProbe() {
      BEAN_INITIALIZATIONS.incrementAndGet();
      return new Object();
    }

    @Bean
    ApplicationRunner migrationProbe() {
      BEAN_INITIALIZATIONS.incrementAndGet();
      return args -> MIGRATION_SIDE_EFFECTS.incrementAndGet();
    }
  }
}
