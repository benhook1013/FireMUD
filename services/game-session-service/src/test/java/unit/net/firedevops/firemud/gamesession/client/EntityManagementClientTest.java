package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class EntityManagementClientTest {
  private static final SessionContext SESSION_CONTEXT =
      new SessionContext(
          41L, 22L, 0L, "", 123L, "", 1L, "1021", "", null, 1L, "world", "realm", 17L, "SHARED");

  @Test
  void listRoomEntitiesFailsClosedWhenSessionContextDropsPartOfAdmittedRoutingBundle() {
    EntityManagementClient client = newClient();
    SessionContext partialRouting =
        new SessionContext(
            SESSION_CONTEXT.sessionId(),
            SESSION_CONTEXT.tenantId(),
            SESSION_CONTEXT.accountId(),
            SESSION_CONTEXT.loginName(),
            SESSION_CONTEXT.characterId(),
            SESSION_CONTEXT.characterName(),
            SESSION_CONTEXT.gameInstanceId(),
            SESSION_CONTEXT.roomInstanceId(),
            SESSION_CONTEXT.jwt(),
            SESSION_CONTEXT.localeTag(),
            SESSION_CONTEXT.bootstrapGameInstanceId(),
            SESSION_CONTEXT.worldSlug(),
            SESSION_CONTEXT.realmSlug(),
            0L,
            SESSION_CONTEXT.playableStateScope());

    assertThatThrownBy(() -> client.listRoomEntities(partialRouting, "1021"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Incomplete admitted routing bundle");
  }

  private static EntityManagementClient newClient() {
    GameplaySessionAttestationService attestationService =
        mock(GameplaySessionAttestationService.class);
    return new EntityManagementClient(
        new ServiceEndpointsProperties(),
        new CommonGrpcClientProperties(),
        mock(GrpcChannelFactory.class),
        BlockingGrpcStubCustomizer.noop(),
        attestationService);
  }
}
