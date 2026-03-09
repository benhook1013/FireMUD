package net.firedevops.firemud.accountservice;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.accountservice.config.AuthConfig;
import net.firedevops.firemud.accountservice.config.GrpcClientProperties;
import net.firedevops.firemud.accountservice.config.MailConfig;
import net.firedevops.firemud.accountservice.config.PaymentConfig;
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
  MailConfig.class,
  PaymentConfig.class
})
public class AccountServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AccountServiceApplication.class, args);
  }
}
