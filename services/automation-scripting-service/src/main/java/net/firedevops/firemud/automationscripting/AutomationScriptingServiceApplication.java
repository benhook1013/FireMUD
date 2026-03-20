package net.firedevops.firemud.automationscripting;

import net.firedevops.firemud.automationscripting.config.AuthProperties;
import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableSagaEntityScan
@EnableConfigurationProperties(AuthProperties.class)
public class AutomationScriptingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AutomationScriptingServiceApplication.class, args);
  }
}
