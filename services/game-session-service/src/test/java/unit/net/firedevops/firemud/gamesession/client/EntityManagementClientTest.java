package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class EntityManagementClientTest {
  private static final SessionContext SESSION_CONTEXT =
      new SessionContext(
          41L, 22L, 0L, "", 123L, "", 1L, "R-1021", "", null, 1L, "world", "realm", 17L, "SHARED");

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

    assertThatThrownBy(() -> client.listRoomEntities(partialRouting, "R-1021"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Incomplete admitted routing bundle");
  }

  @Test
  void listRoomEntitiesFailsClosedWhenSessionContextDropsEntireAdmittedRoutingBundle() {
    EntityManagementClient client = newClient();
    SessionContext missingRouting =
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
            null,
            null,
            0L,
            SESSION_CONTEXT.playableStateScope());

    assertThatThrownBy(() -> client.listRoomEntities(missingRouting, "R-1021"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing admitted routing bundle");
  }

  @Test
  void listRoomEntitiesRejectsLegacyRuntimeRoomIdsBeforeDispatch() {
    EntityManagementClient client = newClient();

    assertThatThrownBy(() -> client.listRoomEntities(SESSION_CONTEXT, "room-1021"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomInstanceId must be a runtime room id like R-1021");
  }

  @Test
  void findCharacterByNameRejectsLegacyRuntimeRoomIdsInSessionContextBeforeDispatch() {
    EntityManagementClient client = newClient();
    SessionContext legacyRoomContext =
        new SessionContext(
            SESSION_CONTEXT.sessionId(),
            SESSION_CONTEXT.tenantId(),
            SESSION_CONTEXT.accountId(),
            SESSION_CONTEXT.loginName(),
            SESSION_CONTEXT.characterId(),
            SESSION_CONTEXT.characterName(),
            SESSION_CONTEXT.gameInstanceId(),
            "room-1021",
            SESSION_CONTEXT.jwt(),
            SESSION_CONTEXT.localeTag(),
            SESSION_CONTEXT.bootstrapGameInstanceId(),
            SESSION_CONTEXT.worldSlug(),
            SESSION_CONTEXT.realmSlug(),
            SESSION_CONTEXT.pointerVersion(),
            SESSION_CONTEXT.playableStateScope());

    assertThatThrownBy(
            () ->
                client.findCharacterByName(
                    legacyRoomContext, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Emberline"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomInstanceId must be a runtime room id like R-1021");
  }

  @Test
  void listCharactersFailsClosedForCurrentAndReplacementInstancesWithinTheSameNamespace()
      throws Exception {
    AtomicInteger rosterRpcCalls = new AtomicInteger();
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                new EntityManagementServiceGrpc.EntityManagementServiceImplBase() {
                  @Override
                  public void listCharactersByAccount(
                      ListCharactersByAccountRequest request,
                      StreamObserver<ListCharactersByAccountResponse> responseObserver) {
                    rosterRpcCalls.incrementAndGet();
                    responseObserver.onNext(ListCharactersByAccountResponse.getDefaultInstance());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService(serverName);
    EntityManagementClient client =
        new EntityManagementClient(
            endpoints,
            new CommonGrpcClientProperties(),
            new GrpcChannelFactory() {
              @Override
              public ManagedChannel buildChannel(
                  String target,
                  int defaultPort,
                  CommonGrpcClientProperties properties,
                  boolean keepAlive) {
                return channel;
              }
            },
            BlockingGrpcStubCustomizer.noop(),
            mock(GameplaySessionAttestationService.class));
    try {
      client.init();

      // Both calls represent one durable namespace before and after runtime replacement. The
      // current API cannot carry that namespace, so neither positive runtime ID is dispatchable.
      ListCharactersByAccountResponse currentInstance =
          client.listCharactersByAccount(
              "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
      ListCharactersByAccountResponse replacementInstance =
          client.listCharactersByAccount(
              "22", "123", "42", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

      assertThat(currentInstance.getError().getCode()).isEqualTo("CHARACTER_LIST_UNAVAILABLE");
      assertThat(replacementInstance.getError().getCode()).isEqualTo("CHARACTER_LIST_UNAVAILABLE");
      assertThat(rosterRpcCalls).hasValue(0);
    } finally {
      client.close();
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
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
