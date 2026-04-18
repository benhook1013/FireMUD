package net.firedevops.firemud.accountservice;

import net.firedevops.firemud.accountservice.config.AccountAuthProperties;
import net.firedevops.firemud.accountservice.config.MailConfig;
import net.firedevops.firemud.accountservice.config.PaymentConfig;
import net.firedevops.firemud.common.saga.persistence.EnableSagaEntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableSagaEntityScan
@EnableConfigurationProperties(AccountAuthProperties.class)
@Import({MailConfig.class, PaymentConfig.class})
public class AccountServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AccountServiceApplication.class, args);
  }
}
