package net.firedevops.firemud.socialgroups;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.socialgroups.config.ChatProperties;
import net.firedevops.firemud.socialgroups.config.GrpcClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties({GrpcClientProperties.class, ChatProperties.class})
@Import({DatabaseAutoConfiguration.class, CommonAutoConfiguration.class})
public class SocialGroupsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(SocialGroupsServiceApplication.class, args);
  }
}
