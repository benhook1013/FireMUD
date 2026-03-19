package net.firedevops.firemud.socialgroups;

import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import net.firedevops.firemud.socialgroups.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan(
    basePackageClasses = {SocialGroupsServiceApplication.class, SagaInstance.class, SagaStep.class})
@EnableConfigurationProperties(ChatProperties.class)
public class SocialGroupsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(SocialGroupsServiceApplication.class, args);
  }
}
