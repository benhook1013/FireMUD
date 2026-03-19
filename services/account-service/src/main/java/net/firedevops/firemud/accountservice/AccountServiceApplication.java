package net.firedevops.firemud.accountservice;

import net.firedevops.firemud.accountservice.config.AuthConfig;
import net.firedevops.firemud.accountservice.config.MailConfig;
import net.firedevops.firemud.accountservice.config.PaymentConfig;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EntityScan(
    basePackageClasses = {AccountServiceApplication.class, SagaInstance.class, SagaStep.class})
@Import({AuthConfig.class, MailConfig.class, PaymentConfig.class})
public class AccountServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AccountServiceApplication.class, args);
  }
}
