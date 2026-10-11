package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit opt-in owner-repository prerequisite for canonical initial admission. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.canonical-game-instance-launch-association-owner-read",
    name = "enabled",
    havingValue = "true")
public class CanonicalGameInstanceLaunchAssociationReadConfiguration {
  @Bean
  CanonicalGameInstanceLaunchAssociationRepository canonicalGameInstanceLaunchAssociationRepository(
      DSLContext dsl, @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new CanonicalGameInstanceLaunchAssociationRepository(dsl, workloadNamespace);
  }
}
