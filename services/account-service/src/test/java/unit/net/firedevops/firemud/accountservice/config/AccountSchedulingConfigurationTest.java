package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class AccountSchedulingConfigurationTest {
  @Test
  void migrationModeDoesNotRegisterSchedulingPostProcessor() {
    try (AnnotationConfigApplicationContext context = contextWithMigrationEnabled(true)) {
      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
  }

  @Test
  void ordinaryModeRegistersSchedulingPostProcessor() {
    try (AnnotationConfigApplicationContext context = contextWithMigrationEnabled(false)) {
      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
    }
  }

  private static AnnotationConfigApplicationContext contextWithMigrationEnabled(boolean enabled) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test", Map.of("firemud.account-tenant-migration.enabled", enabled)));
    context.register(AccountSchedulingConfiguration.class);
    context.refresh();
    return context;
  }
}
