package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountPublicJwksReadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Selects the existing LOGIN/public-read consumers of the explicitly imported protected source. */
@Configuration(proxyBeanMethods = false)
@Import(AccountProtectedJwksSourceConfiguration.class)
@ConditionalOnExpression(
    "${firemud.account.gameplay-login.enabled:false} or ${firemud.account.public-jwks-read.enabled:false}")
public class AccountPublicJwksSourceConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "firemud.account.public-jwks-read",
      name = "enabled",
      havingValue = "true")
  static class CurrentPublicRead {
    @Bean
    AccountPublicJwksReadWorkloadGuard accountPublicJwksReadWorkloadGuard(
        AccountJwtSignerMaterializerTrustBinding trust,
        AccountJwtJwksApiBinding api,
        @Value("${firemud.grpc.workload-namespace:}") String namespace) {
      return new AccountPublicJwksReadWorkloadGuard(trust, api, namespace);
    }

    @Bean
    AccountPublicJwksReadService accountPublicJwksReadService(
        AccountJwtJwksTrustedSource source, AccountPublicJwksReadWorkloadGuard guard) {
      return new AccountPublicJwksReadService(source, guard);
    }
  }
}
