package net.firedevops.firemud.loggingadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.util.List;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameSessionControlPlaneClientTest {

  @Test
  void listAdmissionPointersSendsTenantIdsAsDecimalStrings() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    ManagedChannel channel = mock(ManagedChannel.class);
    GrpcChannelFactory channelFactory =
        new GrpcChannelFactory() {
          @Override
          public ManagedChannel buildChannel(
              String target,
              int defaultPort,
              CommonGrpcClientProperties properties,
              boolean keepAlive) {
            return channel;
          }
        };
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub =
        mock(GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub.class);
    when(stub.listAdmissionPointers(any(ListAdmissionPointersRequest.class)))
        .thenReturn(ListAdmissionPointersResponse.getDefaultInstance());
    TestGameSessionControlPlaneClient client =
        new TestGameSessionControlPlaneClient(endpoints, grpc, channelFactory, stub);

    try {
      client.initialize();
      client.listAdmissionPointers(List.of(2L, Long.MAX_VALUE));

      ArgumentCaptor<ListAdmissionPointersRequest> requestCaptor =
          ArgumentCaptor.forClass(ListAdmissionPointersRequest.class);
      verify(stub).listAdmissionPointers(requestCaptor.capture());
      assertThat(requestCaptor.getValue().getTenantIdsList())
          .containsExactly("2", Long.toString(Long.MAX_VALUE));
    } finally {
      client.close();
    }
  }

  private static final class TestGameSessionControlPlaneClient
      extends GameSessionControlPlaneClient {
    private final GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
        stub;

    private TestGameSessionControlPlaneClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, BlockingGrpcStubCustomizer.noop());
      this.stub = stub;
    }

    private void initialize() throws Exception {
      initReloadingClient();
    }

    @Override
    protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
        buildStub(ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
