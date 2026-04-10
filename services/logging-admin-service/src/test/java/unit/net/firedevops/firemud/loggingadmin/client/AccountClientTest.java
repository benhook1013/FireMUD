package net.firedevops.firemud.loggingadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;

class AccountClientTest {

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AtomicReference<AccountServiceGrpc.AccountServiceBlockingStub> customized =
        new AtomicReference<>();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customized.set((AccountServiceGrpc.AccountServiceBlockingStub) candidate);
            return candidate;
          }
        };
    TestAccountClient client =
        new TestAccountClient(
            endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer, stub);

    client.buildStub(mock(ManagedChannel.class));

    assertThat(customized.get()).isSameAs(stub);
  }

  private static final class TestAccountClient extends AccountClient {
    private final AccountServiceGrpc.AccountServiceBlockingStub stub;

    private TestAccountClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        AccountServiceGrpc.AccountServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    @Override
    protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
