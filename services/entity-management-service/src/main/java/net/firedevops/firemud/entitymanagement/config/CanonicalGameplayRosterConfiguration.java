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

/**
 * Manual factories for the passive canonical gameplay roster source.
 *
 * <p>This class is deliberately not a Spring configuration. Production component scanning does not
 * register these factories, their services, or their owner-read client.
 */
public class CanonicalGameplayRosterConfiguration {
  public CanonicalGameplayRosterOwnerEvidencePort
      unavailableCanonicalGameplayRosterOwnerEvidence() {
    return request -> {
      throw new CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException();
    };
  }

  /** Creates an owner-read client for an explicitly controlled composition. */
  public GameSessionCanonicalGameplayRosterOwnerReadClient
      gameSessionCanonicalGameplayRosterOwnerReadClient(
          ServiceEndpointsProperties endpoints,
          CommonGrpcClientProperties tlsProperties,
          GrpcChannelFactory channelFactory,
          String workloadNamespace) {
    return new GameSessionCanonicalGameplayRosterOwnerReadClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  public CanonicalGameplayRosterOwnerEvidencePort
      gameSessionCanonicalGameplayRosterOwnerEvidencePort(
          GameSessionCanonicalGameplayRosterOwnerReadClient ownerReadClient,
          String workloadNamespace) {
    return new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(
        ownerReadClient, workloadNamespace);
  }

  public CanonicalGameplayRosterService canonicalGameplayRosterService(
      CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort,
      CharacterRepository characterRepository) {
    return new CanonicalGameplayRosterService(ownerEvidencePort, characterRepository);
  }

  public CanonicalGameplayRosterSelectedAssignmentService
      canonicalGameplayRosterSelectedAssignmentService(
          CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort,
          CharacterRepository characterRepository) {
    return new CanonicalGameplayRosterSelectedAssignmentService(
        ownerEvidencePort, characterRepository);
  }

  public CanonicalGameplayRosterPeerInterceptor canonicalGameplayRosterPeerInterceptor(
      String trustedNamespace) {
    return new CanonicalGameplayRosterPeerInterceptor(trustedNamespace);
  }
}
