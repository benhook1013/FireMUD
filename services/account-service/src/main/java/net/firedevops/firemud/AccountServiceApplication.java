package net.firedevops.firemud;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.config.AuthConfig;
import net.firedevops.firemud.config.GrpcClientProperties;
import net.firedevops.firemud.config.MailConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(GrpcClientProperties.class)
@Import({
  DatabaseAutoConfiguration.class,
  CommonAutoConfiguration.class,
  AuthConfig.class,
  MailConfig.class
})
public class AccountServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AccountServiceApplication.class, args);
  }
}
