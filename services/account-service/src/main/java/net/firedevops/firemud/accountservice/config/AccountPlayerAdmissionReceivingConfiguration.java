package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Explicit isolated receiving only. Requires an independently composed genuine Account source
 * owner; creates no source, credential, entitlement, admission or deployment authority. The server
 * must supply TLS client certificates and the receiver's bounded wire policy.
 */
@Configuration(proxyBeanMethods = false)
@Profile("isolated-player-admission-receiving")
@ConditionalOnProperty(
    prefix = "firemud.account.player-admission-receiving",
    name = "enabled",
    havingValue = "true")
public class AccountPlayerAdmissionReceivingConfiguration {
  @Bean
  AccountPlayerAdmissionEntitlementGrpcService accountPlayerAdmissionEntitlementReceiver(
      AccountPlayerAdmissionEntitlementOwner owner,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new AccountPlayerAdmissionEntitlementGrpcService(owner, namespace);
  }
}
