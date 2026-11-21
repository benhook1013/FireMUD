package net.firedevops.firemud.recovery.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Rejects an implicit or empty recovery-control database endpoint before datasource setup. */
public final class RecoveryControlDatabaseHostEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  public static final String HOST_PROPERTY = "FIREMUD_RECOVERY_CONTROL_DB_HOST";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String host = environment.getProperty(HOST_PROPERTY);
    if (host == null || host.isBlank()) {
      throw new IllegalStateException(HOST_PROPERTY + " must name the isolated recovery database");
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
