package net.firedevops.firemud.socialgroups;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SocialGroupsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(SocialGroupsServiceApplication.class, args);
  }
}
