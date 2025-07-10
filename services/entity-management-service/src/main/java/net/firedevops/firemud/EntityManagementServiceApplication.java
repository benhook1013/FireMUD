package net.firedevops.firemud;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class EntityManagementServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(EntityManagementServiceApplication.class, args);
  }
}
