package net.firedevops.firemud.gamedesign.config;

import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationService;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit opt-in composition for the protected published realm-policy owner read. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.game-design.published-realm-entry-policy-read",
    name = "enabled",
    havingValue = "true")
public class RealmPolicyPublicationReadConfiguration {

  @Bean
  RealmPolicyPublicationRepository realmPolicyPublicationRepository(DSLContext dsl) {
    return new RealmPolicyPublicationRepository(dsl);
  }

  @Bean
  RealmPolicyPublicationService realmPolicyPublicationService(
      RealmPolicyPublicationRepository repository, PlatformTransactionManager transactions) {
    return new RealmPolicyPublicationService(repository, transactions);
  }
}
