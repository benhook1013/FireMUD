package unit.net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload.ActorKind;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionPreseededActorAssignmentOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class GameSessionPreseededActorAssignmentOwnerEvidenceAdapterTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID ASSIGNMENT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ACCOUNT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER_TENANT = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID REALM = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_NAMESPACE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID VERSION = uuid("66666666-6666-4666-8666-666666666666");

  @Test
  void accountSnapshotMustBindExactAssignmentAccountTenantAndCurrentStagingPurpose() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    var adapter = adapter(channelFactory);

    assertThatThrownBy(
            () ->
                adapter.resolveCurrentEligibleTarget(
                    request(INSTANCE.toString()), identity(), staging(OTHER_TENANT)))
        .isInstanceOf(
            PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException.class);

    verifyNoInteractions(channelFactory);
  }

  @Test
  void numericLegacyGameInstanceIdsAreNotConvertedIntoCanonicalUuidSelectors() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    var adapter = adapter(channelFactory);

    assertThatThrownBy(
            () -> adapter.resolveCurrentEligibleTarget(request("73"), identity(), staging(TENANT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gameInstanceId must be a canonical non-nil UUID");

    verifyNoInteractions(channelFactory);
  }

  private static GameSessionPreseededActorAssignmentOwnerEvidenceAdapter adapter(
      GrpcChannelFactory channelFactory) {
    var client =
        new GameSessionPreseededActorAssignmentOwnerReadClient(
            new ServiceEndpointsProperties(), tlsProperties(), channelFactory, NAMESPACE);
    return new GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(client, NAMESPACE);
  }

  private static PreseededActorAssignmentRequest request(String gameInstanceId) {
    return new PreseededActorAssignmentRequest(
        ASSIGNMENT,
        ACCOUNT,
        new PreseededActorCorePayload(ActorKind.PLAYER, "Ari"),
        new PreseededActorAssignmentExpectedTarget(
            TENANT,
            REALM,
            "earth",
            "main",
            gameInstanceId,
            71L,
            VERSION,
            "a".repeat(64),
            PLAYABLE_NAMESPACE,
            "release:exact",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
  }

  private static RuntimeAccountIdentityEvidence identity() {
    return new RuntimeAccountIdentityEvidence(
        1, NAMESPACE, ASSIGNMENT, ACCOUNT, 73L, "ACCOUNT_REPOSITORY_INSERT");
  }

  private static AccountActorStagingEligibilityEvidence staging(UUID tenant) {
    return AccountActorStagingEligibilityEvidence.seal(
        NAMESPACE,
        ASSIGNMENT,
        ACCOUNT,
        tenant,
        Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
        Instant.parse("2026-10-08T03:04:05Z"),
        "ACCOUNT_REPOSITORY_INSERT",
        "ACTIVE",
        "ACTIVE",
        true,
        "EXPLICIT_JOIN",
        17L,
        19L,
        "FRESH_GAME_DESIGN",
        uuid("77777777-7777-4777-8777-777777777777"),
        "sha256:" + "b".repeat(64),
        23L,
        uuid("88888888-8888-4888-8888-888888888888"),
        "sha256:" + "c".repeat(64),
        false);
  }

  private static CommonGrpcClientProperties tlsProperties() {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain("entity-client.crt");
    properties.setPrivateKey("entity-client.key");
    properties.setCaCert("game-session-ca.crt");
    return properties;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
