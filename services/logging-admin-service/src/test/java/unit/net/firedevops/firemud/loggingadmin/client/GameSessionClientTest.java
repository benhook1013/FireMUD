package net.firedevops.firemud.loggingadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import org.junit.jupiter.api.Test;

class GameSessionClientTest {

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub =
        mock(GameSessionServiceGrpc.GameSessionServiceBlockingStub.class);
    AtomicReference<GameSessionServiceGrpc.GameSessionServiceBlockingStub> customized =
        new AtomicReference<>();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customized.set((GameSessionServiceGrpc.GameSessionServiceBlockingStub) candidate);
            return candidate;
          }
        };
    TestGameSessionClient client =
        new TestGameSessionClient(
            endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer, stub);

    client.buildStub(mock(ManagedChannel.class));

    assertThat(customized.get()).isSameAs(stub);
  }

  private static final class TestGameSessionClient extends GameSessionClient {
    private final GameSessionServiceGrpc.GameSessionServiceBlockingStub stub;

    private TestGameSessionClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        GameSessionServiceGrpc.GameSessionServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    @Override
    protected GameSessionServiceGrpc.GameSessionServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
