package net.firedevops.firemud.worldmanagement;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import net.firedevops.firemud.worldmanagement.config.WorldConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableSagaEntityScan
@OpenAPIDefinition(info = @Info(title = "World Management Service", version = "v1"))
@EnableScheduling
@Import(WorldConfig.class)
public class WorldManagementServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorldManagementServiceApplication.class, args);
  }
}
