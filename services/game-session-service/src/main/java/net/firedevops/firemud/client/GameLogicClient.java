package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import javax.annotation.PostConstruct;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.config.GrpcClientProperties;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import org.springframework.stereotype.Component;

/** gRPC client for the Game Logic Service using mTLS. */
@Component
public class GameLogicClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private ManagedChannel channel;
  private GameLogicServiceGrpc.GameLogicServiceBlockingStub stub;

  public GameLogicClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
  }

  @PostConstruct
  void init() throws SSLException {
    String target = endpoints.getGameLogicService();
    String host = target.split(":")[0];
    int port = Integer.parseInt(target.split(":")[1]);
    var sslContext =
        GrpcSslContexts.forClient()
            .trustManager(new File(tlsProps.getCaCert()))
            .keyManager(new File(tlsProps.getCertChain()), new File(tlsProps.getPrivateKey()))
            .build();
    channel = NettyChannelBuilder.forAddress(host, port).sslContext(sslContext).build();
    stub = GameLogicServiceGrpc.newBlockingStub(channel);
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub.ping(PingRequest.newBuilder().build());
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
