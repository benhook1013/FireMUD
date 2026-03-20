package net.firedevops.firemud.socialgroups;

import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import net.firedevops.firemud.socialgroups.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableSagaEntityScan(basePackageClasses = SocialGroupsServiceApplication.class)
@EnableConfigurationProperties(ChatProperties.class)
public class SocialGroupsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(SocialGroupsServiceApplication.class, args);
  }
}
