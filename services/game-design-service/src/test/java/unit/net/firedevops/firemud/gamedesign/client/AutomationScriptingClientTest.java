package net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;

class AutomationScriptingClientTest {

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    AtomicReference<AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub>
        customized = new AtomicReference<>();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customized.set(
                (AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub) candidate);
            return candidate;
          }
        };
    TestAutomationScriptingClient client =
        new TestAutomationScriptingClient(
            endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer, stub);

    client.buildStub(mock(ManagedChannel.class));

    assertThat(customized.get()).isSameAs(stub);
  }

  private static final class TestAutomationScriptingClient extends AutomationScriptingClient {
    private final AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub;

    private TestAutomationScriptingClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    @Override
    protected AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
