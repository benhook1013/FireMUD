package net.firedevops.firemud.entitymanagement;

import net.firedevops.firemud.entitymanagement.config.LookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LookProperties.class)
public class EntityManagementServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(EntityManagementServiceApplication.class, args);
  }
}
