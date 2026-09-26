package net.firedevops.firemud.gamedesign;

import net.firedevops.firemud.common.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GlobalExceptionHandler.class)
public class GameDesignServiceApplication {
  public static void main(String[] args) {
    if ("true".equals(System.getenv("FIREMUD_ENTITY_BASELINE_MIGRATION_ENABLED"))) {
      if (System.getenv("FIREMUD_AUTH_JWT_SECRET") != null
          || System.getenv("FIREMUD_AUTH_JWT_SECRET_PATH") != null) {
        throw new IllegalStateException(
            "migration Job must not receive the shared JWT signing key");
      }
      SpringApplication application = new SpringApplication(GameDesignServiceApplication.class);
      application.setWebApplicationType(WebApplicationType.NONE);
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
}
