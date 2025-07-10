package net.firedevops.firemud;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.config.AuthConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "Logging Admin Service", version = "v1"))
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class, AuthConfig.class})
public class LoggingAdminServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(LoggingAdminServiceApplication.class, args);
  }
}
