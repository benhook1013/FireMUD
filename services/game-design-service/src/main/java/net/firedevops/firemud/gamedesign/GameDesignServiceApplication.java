package net.firedevops.firemud.gamedesign;

import net.firedevops.firemud.common.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GlobalExceptionHandler.class)
public class GameDesignServiceApplication {
  public static void main(String[] args) {
    MigrationMode migrationMode =
        selectMigrationMode(
            System.getenv("FIREMUD_ENTITY_BASELINE_MIGRATION_ENABLED"),
            System.getenv("FIREMUD_TENANT_ASSOCIATION_MIGRATION_ENABLED"));
    if (migrationMode != MigrationMode.NORMAL) {
      requireNoSharedJwtSecret();
      SpringApplication application = new SpringApplication(GameDesignServiceApplication.class);
      application.setWebApplicationType(WebApplicationType.NONE);
      application.addInitializers(new RequireFlywayDisabledInitializer());
      application.setDefaultProperties(
          java.util.Map.of(
              "spring.main.web-application-type", "none",
              "spring.grpc.server.enabled", "false",
              "firemud.temporal.enabled", "false",
              "firemud.auth.jwt-secret",
                  java.util.UUID.randomUUID().toString().replace("-", "")
                      + java.util.UUID.randomUUID().toString().replace("-", "")));
      try (ConfigurableApplicationContext ignored = application.run(args)) {
        // The conditional migration runner has completed, including exact database readback.
      }
    } else {
      SpringApplication.run(GameDesignServiceApplication.class, args);
    }
  }

  static MigrationMode selectMigrationMode(String entityBaseline, String tenantAssociation) {
    boolean entityBaselineEnabled = "true".equals(entityBaseline);
    boolean tenantAssociationEnabled = "true".equals(tenantAssociation);
    if (entityBaselineEnabled && tenantAssociationEnabled) {
      throw new IllegalStateException(
          "Game Design one-shot migration modes cannot be enabled together");
    }
    if (entityBaselineEnabled) {
      return MigrationMode.ENTITY_BASELINE;
    }
    if (tenantAssociationEnabled) {
      return MigrationMode.TENANT_ASSOCIATION;
    }
    return MigrationMode.NORMAL;
  }

  enum MigrationMode {
    NORMAL,
    ENTITY_BASELINE,
    TENANT_ASSOCIATION
  }

  static final class RequireFlywayDisabledInitializer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
      String flywayEnabled =
          applicationContext.getEnvironment().getProperty("spring.flyway.enabled");
      if (!"false".equalsIgnoreCase(flywayEnabled)) {
        throw new IllegalStateException(
            "Game Design one-shot migration Jobs require spring.flyway.enabled=false explicitly");
      }
    }
  }

  private static void requireNoSharedJwtSecret() {
    if (System.getenv("FIREMUD_AUTH_JWT_SECRET") != null
        || System.getenv("FIREMUD_AUTH_JWT_SECRET_PATH") != null) {
      throw new IllegalStateException(
          "Game Design migration Job must not receive the shared JWT key");
    }
  }
}
