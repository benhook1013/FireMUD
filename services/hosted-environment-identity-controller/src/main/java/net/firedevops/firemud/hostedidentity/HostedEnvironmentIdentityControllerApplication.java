package net.firedevops.firemud.hostedidentity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HostedEnvironmentIdentityControllerApplication {
  public static void main(String[] args) {
    SpringApplication.run(HostedEnvironmentIdentityControllerApplication.class, args);
  }
}
