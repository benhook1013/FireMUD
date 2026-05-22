package net.firedevops.firemud.worldmanagement;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = @Info(title = "World Management Service", version = "v1"))
@EnableScheduling
public class WorldManagementServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorldManagementServiceApplication.class, args);
  }
}
