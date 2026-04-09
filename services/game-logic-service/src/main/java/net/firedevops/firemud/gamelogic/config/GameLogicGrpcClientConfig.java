package net.firedevops.firemud.gamelogic.config;

import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@RequiredArgsConstructor
public class GameLogicGrpcClientConfig {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties grpcClientProperties;
  private final GrpcChannelFactory channelFactory;
  private final ObjectProvider<BlockingGrpcStubCustomizer> stubCustomizerProvider;

  private ManagedChannel worldChannel;
  private ManagedChannel entityChannel;
  private ManagedChannel socialChannel;

  @PostConstruct
  void init() throws Exception {
    worldChannel =
        channelFactory.buildChannel(
            endpoints.getWorldManagementService(), 6565, grpcClientProperties, false);
    entityChannel =
        channelFactory.buildChannel(
            endpoints.getEntityManagementService(), 6565, grpcClientProperties, false);
    socialChannel =
        channelFactory.buildChannel(
            endpoints.getSocialGroupsService(), 6565, grpcClientProperties, false);
  }

  @PreDestroy
  void shutdown() {
    if (worldChannel != null) {
      worldChannel.shutdownNow();
    }
    if (entityChannel != null) {
      entityChannel.shutdownNow();
    }
    if (socialChannel != null) {
      socialChannel.shutdownNow();
    }
  }

  @Bean
  @Lazy(false)
  public WorldManagementServiceBlockingStub worldManagementStub() {
    return customize(
        WorldManagementServiceGrpc.newBlockingStub(worldChannel).withCompression("gzip"));
  }

  @Bean
  @Lazy(false)
  public EntityManagementServiceBlockingStub entityManagementStub() {
    return customize(
        EntityManagementServiceGrpc.newBlockingStub(entityChannel).withCompression("gzip"));
  }

  @Bean
  @Lazy(false)
  public SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub socialGroupsStub() {
    return customize(
        SocialGroupsServiceGrpc.newBlockingStub(socialChannel).withCompression("gzip"));
  }

  private <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
    BlockingGrpcStubCustomizer customizer =
        stubCustomizerProvider.getIfAvailable(BlockingGrpcStubCustomizer::noop);
    return customizer.customize(stub);
  }
}
