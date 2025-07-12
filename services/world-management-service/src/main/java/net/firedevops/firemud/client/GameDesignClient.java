package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import java.io.File;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.config.GrpcClientProperties;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Design Service. */
@Component
public class GameDesignClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private ManagedChannel channel;
  private GameDesignServiceGrpc.GameDesignServiceBlockingStub stub;

  public GameDesignClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
  }

  @PostConstruct
  void init() throws SSLException {
    String target = endpoints.getGameDesignService();
    if (target == null || target.isEmpty()) {
      target = "game-design-service:6565";
    }
    String[] parts = target.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6565;
    var sslContext =
        GrpcSslContexts.forClient()
            .trustManager(new File(tlsProps.getCaCert()))
            .keyManager(new File(tlsProps.getCertChain()), new File(tlsProps.getPrivateKey()))
            .build();
    channel = NettyChannelBuilder.forAddress(host, port).sslContext(sslContext).build();
    stub = GameDesignServiceGrpc.newBlockingStub(channel);
  }

  /** Returns published versions for the given game. */
  public ListVersionsResponse listVersions(long gameId) {
    ListVersionsRequest request = ListVersionsRequest.newBuilder().setGameId(gameId).build();
    return stub.listVersions(request);
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
