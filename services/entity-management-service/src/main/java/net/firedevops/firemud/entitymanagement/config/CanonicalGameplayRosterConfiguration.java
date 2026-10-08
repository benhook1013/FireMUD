package net.firedevops.firemud.entitymanagement.config;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterService;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/** Wires guarded roster discovery with default-off mTLS Game Session owner evidence. */
@Configuration
public class CanonicalGameplayRosterConfiguration {
  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.canonical-gameplay-roster-owner-read",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  @ConditionalOnMissingBean(CanonicalGameplayRosterOwnerEvidencePort.class)
  public CanonicalGameplayRosterOwnerEvidencePort
      unavailableCanonicalGameplayRosterOwnerEvidence() {
    return request -> {
      throw new CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException();
    };
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "firemud.canonical-gameplay-roster-owner-read",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean({
    CanonicalGameplayRosterOwnerEvidencePort.class,
    GameSessionCanonicalGameplayRosterOwnerReadClient.class
  })
  public GameSessionCanonicalGameplayRosterOwnerReadClient
      gameSessionCanonicalGameplayRosterOwnerReadClient(
          ServiceEndpointsProperties endpoints,
          CommonGrpcClientProperties tlsProperties,
          GrpcChannelFactory channelFactory,
          @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionCanonicalGameplayRosterOwnerReadClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.canonical-gameplay-roster-owner-read",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(CanonicalGameplayRosterOwnerEvidencePort.class)
  public CanonicalGameplayRosterOwnerEvidencePort
      gameSessionCanonicalGameplayRosterOwnerEvidencePort(
          GameSessionCanonicalGameplayRosterOwnerReadClient ownerReadClient,
          @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(
        ownerReadClient, workloadNamespace);
  }

  @Bean
  @ConditionalOnMissingBean
  public CanonicalGameplayRosterService canonicalGameplayRosterService(
      CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort,
      CharacterRepository characterRepository) {
    return new CanonicalGameplayRosterService(ownerEvidencePort, characterRepository);
  }

  @Bean
  @ConditionalOnMissingBean
  public CanonicalGameplayRosterSelectedAssignmentService
      canonicalGameplayRosterSelectedAssignmentService(
          CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort,
          CharacterRepository characterRepository) {
    return new CanonicalGameplayRosterSelectedAssignmentService(
        ownerEvidencePort, characterRepository);
  }

  @Bean
  @GlobalServerInterceptor
  public CanonicalGameplayRosterPeerInterceptor canonicalGameplayRosterPeerInterceptor(
      @Value("${firemud.grpc.workload-namespace:}") String trustedNamespace) {
    return new CanonicalGameplayRosterPeerInterceptor(trustedNamespace);
  }
}
