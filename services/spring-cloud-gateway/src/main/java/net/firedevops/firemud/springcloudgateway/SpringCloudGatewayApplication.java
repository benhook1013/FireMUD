package net.firedevops.firemud.springcloudgateway;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class SpringCloudGatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(SpringCloudGatewayApplication.class, args);
  }
}
