package net.firedevops.firemud.worldmanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameDesignClientTest {
  private static final String GAME_DESIGN_DEV_URI =
      "spiffe://firemud/ns/dev/sa/game-design-service";

  @Test
  void exactDescriptorReadUsesDigestsAndAuthenticatedDevGameDesignPeer() throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    doReturn(stub).when(stub).withCallCredentials(any(CallCredentials.class));
    doReturn(stub).when(stub).withInterceptors(any(ClientInterceptor[].class));
    doReturn(stub).when(stub).withDeadlineAfter(anyLong(), any(TimeUnit.class));

    var expectedRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "dev",
            "7f1e5306-e9a7-46d9-b5cf-75348e20ffbd",
            UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d"),
            "demo",
            UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed"),
            "sha256:" + "a".repeat(64),
            17L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var expectedEvidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            expectedRequest,
            "launch-descriptor-fixture-11",
            11L,
            false,
            null,
            "{}",
            "genrev-11",
            77L,
            77L,
            "owner-release-ref-11",
            false,
            null);
    UUID readRequestId = UUID.fromString("a897fd58-8034-4e5d-a943-c522b6c61699");
    var getRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            readRequestId, expectedRequest, expectedEvidence.resultDigest());
    var response =
        GetLaunchDescriptorResponse.newBuilder()
            .setRequestId(readRequestId.toString())
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(expectedEvidence))
            .build();
    when(stub.getLaunchDescriptor(any())).thenReturn(response);

    GameDesignClient client = newClient(stub);

    GameDesignClient.LaunchDescriptorReadback actual = client.getLaunchDescriptor(getRequest);

    assertThat(actual.message()).isEqualTo(response.getLaunchDescriptor());
    assertThat(actual.evidence()).isEqualTo(expectedEvidence);
    ArgumentCaptor<net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest> requestCaptor =
        ArgumentCaptor.forClass(
            net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest.class);
    verify(stub).getLaunchDescriptor(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getRequestId()).isEqualTo(readRequestId.toString());
    assertThat(requestCaptor.getValue().getExpectedRequestDigest())
        .isEqualTo(expectedEvidence.requestDigest());
    assertThat(requestCaptor.getValue().getExpectedResultDigest())
        .isEqualTo(expectedEvidence.resultDigest());

    ArgumentCaptor<CallCredentials> credentialsCaptor =
        ArgumentCaptor.forClass(CallCredentials.class);
    verify(stub).withCallCredentials(credentialsCaptor.capture());
    assertThat(credentialsCaptor.getValue())
        .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
    assertThat(readPeerUri(credentialsCaptor.getValue())).isEqualTo(GAME_DESIGN_DEV_URI);
    ArgumentCaptor<ClientInterceptor[]> interceptorCaptor =
        ArgumentCaptor.forClass(ClientInterceptor[].class);
    verify(stub).withInterceptors(interceptorCaptor.capture());
    assertThat(interceptorCaptor.getValue()).hasSize(1);
    assertThat(interceptorCaptor.getValue()[0])
        .isInstanceOf(GrpcServerPeerIdentityClientInterceptor.class);
    assertThat(readPeerUri(interceptorCaptor.getValue()[0])).isEqualTo(GAME_DESIGN_DEV_URI);
  }

  private static GameDesignClient newClient(
      GameDesignServiceGrpc.GameDesignServiceBlockingStub stub) throws Exception {
    GameDesignClient client =
        new GameDesignClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static String readPeerUri(Object evidence) throws Exception {
    Field field = evidence.getClass().getDeclaredField("expectedPeerUri");
    field.setAccessible(true);
    return (String) field.get(evidence);
  }
}
