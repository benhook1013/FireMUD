package net.firedevops.firemud.automationscripting;

import net.firedevops.firemud.automationscripting.config.AuthProperties;
import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class AutomationScriptingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AutomationScriptingServiceApplication.class, args);
  }
}
