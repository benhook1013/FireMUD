package net.firedevops.firemud.automationscripting;

import net.firedevops.firemud.automationscripting.config.AuthProperties;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan(
    basePackageClasses = {
      AutomationScriptingServiceApplication.class,
      SagaInstance.class,
      SagaStep.class
    })
@EnableConfigurationProperties(AuthProperties.class)
public class AutomationScriptingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AutomationScriptingServiceApplication.class, args);
  }
}
