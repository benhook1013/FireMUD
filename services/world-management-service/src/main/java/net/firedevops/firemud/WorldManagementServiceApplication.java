package net.firedevops.firemud;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class WorldManagementServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorldManagementServiceApplication.class, args);
  }
}
