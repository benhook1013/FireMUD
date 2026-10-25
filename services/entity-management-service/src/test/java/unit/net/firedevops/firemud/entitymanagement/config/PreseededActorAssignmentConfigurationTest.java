package unit.net.firedevops.firemud.entitymanagement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.entitymanagement.client.AccountPreseededActorStagingEligibilityClient;
import net.firedevops.firemud.entitymanagement.client.AccountRuntimeIdentityClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionPreseededActorAssignmentOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class PreseededActorAssignmentConfigurationTest {
  @Test
  void manualCompositionDeniesByDefaultBeforeReadsOrPersistence() {
    var configuration = new PreseededActorAssignmentConfiguration();
    var request = request();
    var repository = mock(CharacterRepository.class);
    var runAuthority = configuration.disabledRunOwnedPreseededAssignmentAuthority();
    var accountIdentityPort = configuration.disabledPreseededActorAccountIdentityPort();
    var stagingEligibilityPort = configuration.disabledPreseededActorStagingEligibilityPort();
    var ownerEvidencePort = configuration.unavailablePreseededActorAssignmentOwnerEvidencePort();
    PreseededActorAssignmentService service =
        configuration.preseededActorAssignmentService(
            runAuthority,
            accountIdentityPort,
            stagingEligibilityPort,
            ownerEvidencePort,
            repository);

    assertThatThrownBy(() -> service.assign(request))
        .isInstanceOf(AdminAuthorizationException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_RUN_OWNED_ACTION_GRANT_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                accountIdentityPort.resolveRuntimeAccountIdentity(
                    request.canonicalAccountUuid().toString(), request.assignmentUuid().toString()))
        .isInstanceOf(
            PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_OWNER_EVIDENCE_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                stagingEligibilityPort.resolvePreseededActorStagingEligibility(
                    request.canonicalAccountUuid().toString(),
                    request.expectedTarget().canonicalTenantUuid().toString(),
                    request.assignmentUuid().toString()))
        .isInstanceOf(
            PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_OWNER_EVIDENCE_UNAVAILABLE");
    assertThatThrownBy(() -> ownerEvidencePort.resolveCurrentEligibleTarget(request, null, null))
        .isInstanceOf(
            PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void explicitlyInvokedFactoriesCreateOnlyTheSuppliedOwnerReadComposition() {
    var configuration = new PreseededActorAssignmentConfiguration();
    var channels = mock(GrpcChannelFactory.class);
    var endpoints = new ServiceEndpointsProperties();
    var tls = tlsProperties();
    var accountIdentityClient =
        configuration.preseededActorAccountIdentityClient(endpoints, tls, channels, "gameplay");
    var stagingEligibilityClient =
        configuration.preseededActorStagingEligibilityClient(endpoints, tls, channels, "gameplay");
    var ownerReadClient =
        configuration.preseededActorAssignmentOwnerReadClient(endpoints, tls, channels, "gameplay");
    var accountIdentityPort =
        configuration.enabledPreseededActorAccountIdentityPort(accountIdentityClient);
    var stagingEligibilityPort =
        configuration.enabledPreseededActorStagingEligibilityPort(stagingEligibilityClient);
    var ownerEvidencePort =
        configuration.preseededActorAssignmentOwnerEvidencePort(ownerReadClient, "gameplay");

    assertThat(accountIdentityClient).isInstanceOf(AccountRuntimeIdentityClient.class);
    assertThat(stagingEligibilityClient)
        .isInstanceOf(AccountPreseededActorStagingEligibilityClient.class);
    assertThat(ownerReadClient)
        .isInstanceOf(GameSessionPreseededActorAssignmentOwnerReadClient.class);
    assertThat(accountIdentityPort).isNotNull();
    assertThat(stagingEligibilityPort).isNotNull();
    assertThat(ownerEvidencePort)
        .isInstanceOf(GameSessionPreseededActorAssignmentOwnerEvidenceAdapter.class);
    verifyNoInteractions(channels);
  }

  private static PreseededActorAssignmentRequest request() {
    return new PreseededActorAssignmentRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Sora"),
        new PreseededActorAssignmentExpectedTarget(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "world",
            "realm",
            UUID.randomUUID().toString(),
            3L,
            UUID.randomUUID(),
            "1".repeat(64),
            UUID.randomUUID(),
            "release/test",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
  }

  private static CommonGrpcClientProperties tlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("entity-client.crt");
    tls.setPrivateKey("entity-client.key");
    tls.setCaCert("owner-service-ca.crt");
    return tls;
  }
}
