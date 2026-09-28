package net.firedevops.firemud.accountservice;

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
public class AccountServiceApplication {
  public static void main(String[] args) {
    if ("true".equals(System.getenv("FIREMUD_ACCOUNT_TENANT_MIGRATION_ENABLED"))) {
      requireNoSharedJwtSecret();
      SpringApplication application = new SpringApplication(AccountServiceApplication.class);
      application.setWebApplicationType(WebApplicationType.NONE);
      application.setDefaultProperties(
          java.util.Map.of(
              "spring.main.web-application-type", "none",
              "spring.grpc.server.enabled", "false",
              "firemud.auth.jwt-secret", oneShotJwtSecret()));
      try (ConfigurableApplicationContext ignored = application.run(args)) {
        // The conditional migration runner completed its one-shot evidence or import operation.
      }
    } else {
      SpringApplication.run(AccountServiceApplication.class, args);
    }
  }

  private static void requireNoSharedJwtSecret() {
    if (System.getenv("FIREMUD_AUTH_JWT_SECRET") != null
        || System.getenv("FIREMUD_AUTH_JWT_SECRET_PATH") != null) {
      throw new IllegalStateException("Account migration Job must not receive the shared JWT key");
    }
  }

  private static String oneShotJwtSecret() {
    return java.util.UUID.randomUUID().toString().replace("-", "")
        + java.util.UUID.randomUUID().toString().replace("-", "");
  }
}
