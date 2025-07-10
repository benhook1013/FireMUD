package net.firedevops.firemud;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.config.AuthConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class, AuthConfig.class})
public class SpringCloudGatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(SpringCloudGatewayApplication.class, args);
  }
}
