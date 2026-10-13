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
import net.firedevops.firemud.entitymanagement.service.PreseededActorAccountIdentityPort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorStagingEligibilityPort;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededActorAssignmentAuthorityAdapter;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededAssignmentAuthority;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionPreseededActorAssignmentOwnerEvidenceAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the bounded Entity writer while leaving Account/owner evidence and run authorization denied
 * unless their explicitly enabled producers are present.
 */
@Configuration
public class PreseededActorAssignmentConfiguration {
  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  @ConditionalOnMissingBean(PreseededActorAccountIdentityPort.class)
  public PreseededActorAccountIdentityPort disabledPreseededActorAccountIdentityPort() {
    return (accountUuid, assignmentUuid) -> {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_IDENTITY_UNAVAILABLE");
    };
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean({
    PreseededActorAccountIdentityPort.class,
    AccountRuntimeIdentityClient.class
  })
  public AccountRuntimeIdentityClient preseededActorAccountIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @org.springframework.beans.factory.annotation.Value("${firemud.grpc.workload-namespace:}")
          String workloadNamespace) {
    return new AccountRuntimeIdentityClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(PreseededActorAccountIdentityPort.class)
  public PreseededActorAccountIdentityPort enabledPreseededActorAccountIdentityPort(
      AccountRuntimeIdentityClient accountIdentityClient) {
    return accountIdentityClient::resolveRuntimeAccountIdentity;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  @ConditionalOnMissingBean(PreseededActorStagingEligibilityPort.class)
  public PreseededActorStagingEligibilityPort disabledPreseededActorStagingEligibilityPort() {
    return (accountUuid, tenantUuid, assignmentUuid) -> {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_STAGING_EVIDENCE_UNAVAILABLE");
    };
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean({
    PreseededActorStagingEligibilityPort.class,
    AccountPreseededActorStagingEligibilityClient.class
  })
  public AccountPreseededActorStagingEligibilityClient preseededActorStagingEligibilityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @org.springframework.beans.factory.annotation.Value("${firemud.grpc.workload-namespace:}")
          String workloadNamespace) {
    return new AccountPreseededActorStagingEligibilityClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean(initMethod = "init", destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean({
    PreseededActorAssignmentOwnerEvidencePort.class,
    GameSessionPreseededActorAssignmentOwnerReadClient.class
  })
  public GameSessionPreseededActorAssignmentOwnerReadClient preseededActorAssignmentOwnerReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @org.springframework.beans.factory.annotation.Value("${firemud.grpc.workload-namespace:}")
          String workloadNamespace) {
    return new GameSessionPreseededActorAssignmentOwnerReadClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(PreseededActorAssignmentOwnerEvidencePort.class)
  public PreseededActorAssignmentOwnerEvidencePort preseededActorAssignmentOwnerEvidencePort(
      GameSessionPreseededActorAssignmentOwnerReadClient gameSessionClient,
      @org.springframework.beans.factory.annotation.Value("${firemud.grpc.workload-namespace:}")
          String workloadNamespace) {
    return new GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(
        gameSessionClient, workloadNamespace);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  @ConditionalOnMissingBean(RunOwnedPreseededAssignmentAuthority.class)
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

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(RunOwnedPreseededAssignmentAuthority.class)
  public RunOwnedPreseededAssignmentAuthority runOwnedPreseededAssignmentAuthorityFromCapability(
      @org.springframework.beans.factory.annotation.Value(
              "${firemud.run-owned-preseeded-actor-assignment.capability-path:}")
          String capabilityPath,
      @org.springframework.beans.factory.annotation.Value(
              "${firemud.run-owned-preseeded-actor-assignment.run-id:}")
          String runId,
      @org.springframework.beans.factory.annotation.Value(
              "${firemud.run-owned-preseeded-actor-assignment.compose-project-name:}")
          String composeProjectName,
      @org.springframework.beans.factory.annotation.Value("${firemud.grpc.workload-namespace:}")
          String workloadNamespace,
      @org.springframework.beans.factory.annotation.Value(
              "${spring.ssl.bundle.pem.firemud-grpc.truststore.certificate:}")
          String entityServerTrustRoot,
      @org.springframework.beans.factory.annotation.Value("${spring.grpc.server.ssl.bundle:}")
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
   * The enabled source adapter combines one current Account staging snapshot with exact current
   * Game Session/Game Design selectors. Account status is a point-in-time observation, not a held
   * JOIN authorization, PLAY grant, or gameplay admission decision; persisted assignments do not
   * authorize runtime admission or activation.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.run-owned-preseeded-actor-assignment",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  @ConditionalOnMissingBean(PreseededActorAssignmentOwnerEvidencePort.class)
  public PreseededActorAssignmentOwnerEvidencePort
      unavailablePreseededActorAssignmentOwnerEvidencePort() {
    return (PreseededActorAssignmentRequest request,
        RuntimeAccountIdentityEvidence accountIdentityEvidence,
        AccountActorStagingEligibilityEvidence stagingEligibilityEvidence) -> {
      throw new PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException();
    };
  }

  @Bean
  @ConditionalOnMissingBean
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

  /** Receipt reads are read-only and remain available when assignment creation is disabled. */
  @Bean
  @ConditionalOnMissingBean
  public PreseededActorAssignmentReceiptService preseededActorAssignmentReceiptService(
      CharacterRepository characterRepository) {
    return new PreseededActorAssignmentReceiptService(characterRepository);
  }
}
