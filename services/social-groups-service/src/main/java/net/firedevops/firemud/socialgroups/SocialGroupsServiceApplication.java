package net.firedevops.firemud.socialgroups;

import net.firedevops.firemud.common.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GlobalExceptionHandler.class)
public class SocialGroupsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(SocialGroupsServiceApplication.class, args);
  }
}
