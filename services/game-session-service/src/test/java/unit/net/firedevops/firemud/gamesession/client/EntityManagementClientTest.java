package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class EntityManagementClientTest {
  private static final SessionContext SESSION_CONTEXT =
      new SessionContext(
          41L,
          22L,
          "00000000-0000-4000-8000-000000000020",
          "",
          123L,
          "",
          1L,
          "R-1021",
          "",
          null,
          1L,
          "world",
          "realm",
          17L,
          "SHARED");

  @Test
  void readinessPingUsesCustomizedWorkloadChannelAndTwoSecondDeadlineWithoutPlayerIdentity()
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("entity-workload:6565");
    var channels = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(channels.buildChannel(eq("entity-workload:6565"), eq(6565), any(), eq(true)))
        .thenReturn(channel);
    var credentials = mock(io.grpc.CallCredentials.class);
    var customizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
            return stub.withCallCredentials(credentials);
          }
        };
    var attestationService = mock(GameplaySessionAttestationService.class);
    @SuppressWarnings("unchecked")
    ClientCall<PingRequest, PingResponse> call = mock(ClientCall.class);
    var expected = PingResponse.newBuilder().setMessage("pong").build();
    doAnswer(
            invocation -> {
              ClientCall.Listener<PingResponse> listener = invocation.getArgument(0);
              listener.onMessage(expected);
              listener.onClose(Status.OK, new Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel.<PingRequest, PingResponse>newCall(any(), any())).thenReturn(call);

    try (var client =
        new EntityManagementClient(
            endpoints,
            new CommonGrpcClientProperties(),
            channels,
            customizer,
            attestationService)) {
      client.init();
      Deadline earliestReadiness = Deadline.after(2L, TimeUnit.SECONDS);
      assertThat(client.pingForReadiness()).isEqualTo(expected);
      Deadline latestReadiness = Deadline.after(2L, TimeUnit.SECONDS);
      Deadline earliestOrdinary = Deadline.after(5L, TimeUnit.SECONDS);
      assertThat(client.ping()).isEqualTo(expected);
      Deadline latestOrdinary = Deadline.after(5L, TimeUnit.SECONDS);

      var optionsCaptor = ArgumentCaptor.forClass(CallOptions.class);
      verify(channel, times(2))
          .newCall(eq(EntityManagementServiceGrpc.getPingMethod()), optionsCaptor.capture());
      var readinessOptions = optionsCaptor.getAllValues().get(0);
      var ordinaryOptions = optionsCaptor.getAllValues().get(1);
      assertThat(readinessOptions.getDeadline()).isNotNull();
      assertThat(readinessOptions.getDeadline().compareTo(earliestReadiness))
          .isGreaterThanOrEqualTo(0);
      assertThat(readinessOptions.getDeadline().compareTo(latestReadiness)).isLessThanOrEqualTo(0);
      assertThat(ordinaryOptions.getDeadline()).isNotNull();
      assertThat(ordinaryOptions.getDeadline().compareTo(earliestOrdinary))
          .isGreaterThanOrEqualTo(0);
      assertThat(ordinaryOptions.getDeadline().compareTo(latestOrdinary)).isLessThanOrEqualTo(0);
      assertThat(readinessOptions.getCredentials()).isSameAs(credentials);
      assertThat(ordinaryOptions.getCredentials()).isSameAs(credentials);
      assertThat(readinessOptions.getCompressor()).isEqualTo("gzip");
      verify(call, times(2)).sendMessage(PingRequest.getDefaultInstance());
      verifyNoInteractions(attestationService);
      verify(channels).buildChannel(eq("entity-workload:6565"), eq(6565), any(), eq(true));
      verifyNoMoreInteractions(channels);
    }
    verify(channel).shutdown();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "20",
        "not-an-account",
        "00000000-0000-0000-0000-000000000000",
        " 00000000-0000-4000-8000-000000000020"
      })
  void rosterReadRejectsNonCanonicalAccountBeforeTransport(String accountId) {
    EntityManagementClient client = newClient();
    assertThatThrownBy(
            () ->
                client.listCharactersByAccount(
                    "22", accountId, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isInstanceOf(IllegalArgumentException.class);
  }

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
