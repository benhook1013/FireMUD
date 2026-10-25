package net.firedevops.firemud.entitymanagement.config;

import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.entitymanagement.client.AccountPreseededActorStagingEligibilityClient;
import net.firedevops.firemud.entitymanagement.client.AccountRuntimeIdentityClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.security.CanonicalPreseededActorAssignmentPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAccountIdentityPort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorStagingEligibilityPort;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededActorAssignmentAuthorityAdapter;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededAssignmentAuthority;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalPreseededActorAssignmentGrpcService;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionPreseededActorAssignmentOwnerEvidenceAdapter;
import org.springframework.boot.ssl.SslBundles;

/**
 * Manual factories for passive preseeded-actor assignment source services.
 *
 * <p>This class is deliberately not a Spring configuration. Production component scanning does not
 * register these factories, their services, or their owner-read clients.
 */
public class PreseededActorAssignmentConfiguration {
  public PreseededActorAccountIdentityPort disabledPreseededActorAccountIdentityPort() {
    return (accountUuid, assignmentUuid) -> {
      throw new PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException();
    };
  }

  /** Creates an Account identity client for an explicitly controlled composition. */
  public AccountRuntimeIdentityClient preseededActorAccountIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    return new AccountRuntimeIdentityClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  public PreseededActorAccountIdentityPort enabledPreseededActorAccountIdentityPort(
      AccountRuntimeIdentityClient accountIdentityClient) {
    return accountIdentityClient::resolveRuntimeAccountIdentity;
  }

  public PreseededActorStagingEligibilityPort disabledPreseededActorStagingEligibilityPort() {
    return (accountUuid, tenantUuid, assignmentUuid) -> {
      throw new PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException();
    };
  }

  /** Creates an Account staging client for an explicitly controlled composition. */
  public AccountPreseededActorStagingEligibilityClient preseededActorStagingEligibilityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    return new AccountPreseededActorStagingEligibilityClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  public PreseededActorStagingEligibilityPort enabledPreseededActorStagingEligibilityPort(
      AccountPreseededActorStagingEligibilityClient stagingEligibilityClient) {
    return stagingEligibilityClient::resolvePreseededActorStagingEligibility;
  }

  /** Creates a Game Session owner-read client for an explicitly controlled composition. */
  public GameSessionPreseededActorAssignmentOwnerReadClient preseededActorAssignmentOwnerReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    return new GameSessionPreseededActorAssignmentOwnerReadClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  public PreseededActorAssignmentOwnerEvidencePort preseededActorAssignmentOwnerEvidencePort(
      GameSessionPreseededActorAssignmentOwnerReadClient gameSessionClient,
      String workloadNamespace) {
    return new GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(
        gameSessionClient, workloadNamespace);
  }

  public RunOwnedPreseededAssignmentAuthority disabledRunOwnedPreseededAssignmentAuthority() {
    return new RunOwnedPreseededAssignmentAuthority() {
      @Override
      public void requireAuthorized(PreseededActorAssignmentRequest request, Action action) {
        throw new AdminAuthorizationException(
            "PRESEEDED_ASSIGNMENT_RUN_OWNED_ACTION_GRANT_UNAVAILABLE");
      }

      @Override
      public void requireTargetBoundAuthorized(
          PreseededActorAssignmentRequest request,
          PreseededActorAssignmentOwnerEvidence target,
          Action action) {
        throw new AdminAuthorizationException(
            "PRESEEDED_ASSIGNMENT_RUN_OWNED_ACTION_GRANT_UNAVAILABLE");
      }
    };
  }

  /** Creates run-owned authority from caller-supplied active server trust material. */
  public RunOwnedPreseededAssignmentAuthority runOwnedPreseededAssignmentAuthorityFromCapability(
      String capabilityPath,
      String runId,
      String composeProjectName,
      String workloadNamespace,
      String entityServerTrustRoot,
      String activeServerTlsBundleName,
      SslBundles sslBundles) {
    return RunOwnedPreseededActorAssignmentAuthorityAdapter.forActiveServerTrustBundle(
        capabilityPath,
        runId,
        composeProjectName,
        workloadNamespace,
        sslBundles,
        activeServerTlsBundleName,
        entityServerTrustRoot);
  }

  /**
   * The manually selected unavailable source adapter fails closed; assignment remains
   * non-admitting.
   */
  public PreseededActorAssignmentOwnerEvidencePort
      unavailablePreseededActorAssignmentOwnerEvidencePort() {
    return (PreseededActorAssignmentRequest request,
        RuntimeAccountIdentityEvidence accountIdentityEvidence,
        AccountActorStagingEligibilityEvidence stagingEligibilityEvidence) -> {
      throw new PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException();
    };
  }

  public PreseededActorAssignmentService preseededActorAssignmentService(
      RunOwnedPreseededAssignmentAuthority runAuthority,
      PreseededActorAccountIdentityPort accountIdentityPort,
      PreseededActorStagingEligibilityPort stagingEligibilityPort,
      PreseededActorAssignmentOwnerEvidencePort ownerEvidencePort,
      CharacterRepository characterRepository) {
    return new PreseededActorAssignmentService(
        runAuthority,
        accountIdentityPort,
        stagingEligibilityPort,
        ownerEvidencePort,
        characterRepository);
  }

  /** Creates the assignment RPC handler only for an explicitly controlled composition. */
  public CanonicalPreseededActorAssignmentGrpcService canonicalPreseededActorAssignmentGrpcService(
      String trustedNamespace,
      PreseededActorAssignmentService assignmentService,
      PreseededActorAssignmentReceiptService receiptService) {
    return new CanonicalPreseededActorAssignmentGrpcService(
        trustedNamespace, assignmentService, receiptService);
  }

  /** Creates the method-specific certificate guard without registering either object. */
  public CanonicalPreseededActorAssignmentPeerInterceptor
      canonicalPreseededActorAssignmentPeerInterceptor(String trustedNamespace) {
    return new CanonicalPreseededActorAssignmentPeerInterceptor(trustedNamespace);
  }

  /** Receipt reads are read-only and remain available when assignment creation is disabled. */
  public PreseededActorAssignmentReceiptService preseededActorAssignmentReceiptService(
      CharacterRepository characterRepository) {
    return new PreseededActorAssignmentReceiptService(characterRepository);
  }
}
