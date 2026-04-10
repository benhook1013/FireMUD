package net.firedevops.firemud.gamelogic.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class GameLogicGrpcClientConfigTest {

  @Test
  void stubsApplyInjectedStubCustomizer() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world-management-service:6565");
    endpoints.setEntityManagementService("entity-management-service:6565");
    endpoints.setSocialGroupsService("social-groups-service:6565");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel worldChannel = mock(ManagedChannel.class);
    ManagedChannel entityChannel = mock(ManagedChannel.class);
    ManagedChannel socialChannel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel("world-management-service:6565", 6565, grpc, false))
        .thenReturn(worldChannel);
    when(channelFactory.buildChannel("entity-management-service:6565", 6565, grpc, false))
        .thenReturn(entityChannel);
    when(channelFactory.buildChannel("social-groups-service:6565", 6565, grpc, false))
        .thenReturn(socialChannel);

    AtomicInteger customizeCalls = new AtomicInteger();
    BlockingGrpcStubCustomizer customizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
            customizeCalls.incrementAndGet();
            return stub;
          }
        };
    ObjectProvider<BlockingGrpcStubCustomizer> provider =
        new SingleStubCustomizerProvider(customizer);
    GameLogicGrpcClientConfig config =
        new GameLogicGrpcClientConfig(endpoints, grpc, channelFactory, provider);

    config.init();
    config.worldManagementStub();
    config.entityManagementStub();
    config.socialGroupsStub();

    assertThat(customizeCalls.get()).isEqualTo(3);
  }

  private record SingleStubCustomizerProvider(BlockingGrpcStubCustomizer customizer)
      implements ObjectProvider<BlockingGrpcStubCustomizer> {
    @Override
    public BlockingGrpcStubCustomizer getObject(Object... args) {
      return customizer;
    }

    @Override
    public BlockingGrpcStubCustomizer getIfAvailable() {
      return customizer;
    }

    @Override
    public BlockingGrpcStubCustomizer getIfUnique() {
      return customizer;
    }

    @Override
    public BlockingGrpcStubCustomizer getObject() {
      return customizer;
    }
  }
}
