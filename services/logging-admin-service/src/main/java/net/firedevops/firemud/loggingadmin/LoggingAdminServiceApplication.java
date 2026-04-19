package net.firedevops.firemud.loggingadmin;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableSagaEntityScan
@OpenAPIDefinition(info = @Info(title = "Logging Admin Service", version = "v1"))
public class LoggingAdminServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(LoggingAdminServiceApplication.class, args);
  }
}
