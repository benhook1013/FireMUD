package net.firedevops.firemud.gamelogic.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@RequiredArgsConstructor
public class GameLogicGrpcClientConfig {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties grpcClientProperties;

  private ManagedChannel worldChannel;
  private ManagedChannel entityChannel;
  private ManagedChannel socialChannel;

  @PostConstruct
  void init() throws Exception {
    worldChannel = buildChannel(endpoints.getWorldManagementService(), 6565);
    entityChannel = buildChannel(endpoints.getEntityManagementService(), 6565);
    socialChannel = buildChannel(endpoints.getSocialGroupsService(), 6565);
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
    return WorldManagementServiceGrpc.newBlockingStub(worldChannel).withCompression("gzip");
  }

  @Bean
  @Lazy(false)
  public EntityManagementServiceBlockingStub entityManagementStub() {
    return EntityManagementServiceGrpc.newBlockingStub(entityChannel).withCompression("gzip");
  }

  @Bean
  @Lazy(false)
  public SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub socialGroupsStub() {
    return SocialGroupsServiceGrpc.newBlockingStub(socialChannel).withCompression("gzip");
  }

  private ManagedChannel buildChannel(String target, int defaultPort) throws Exception {
    String resolved = target;
    if (resolved == null || resolved.isBlank()) {
      resolved = "localhost:" + defaultPort;
    }
    String[] parts = resolved.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : defaultPort;
    if (grpcClientProperties.isPlaintext()) {
      return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }
    var sslBuilder =
        GrpcSslContexts.forClient().trustManager(new File(grpcClientProperties.getCaCert()));
    if (grpcClientProperties.getCertChain() != null) {
      sslBuilder.keyManager(
          new File(grpcClientProperties.getCertChain()),
          new File(grpcClientProperties.getPrivateKey()));
    }
    return NettyChannelBuilder.forAddress(host, port).sslContext(sslBuilder.build()).build();
  }
}
