package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
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
  private TlsCertificateWatcher watcher;

  public GameLogicClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    watcher =
        new TlsCertificateWatcher(
            List.of(
                Path.of(tlsProps.getCertChain()),
                Path.of(tlsProps.getPrivateKey()),
                Path.of(tlsProps.getCaCert())),
            this::safeReload);
  }

  private synchronized void safeReload() {
    try {
      reloadChannel();
    } catch (SSLException e) {
      // Log but continue using the existing channel if reload fails
      net.firedevops.firemud.common.LoggingUtil.getLogger(GameLogicClient.class)
          .error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException {
    String target = endpoints.getGameLogicService();
    String host = target.split(":")[0];
    int port = Integer.parseInt(target.split(":")[1]);
    var sslContext =
        GrpcSslContexts.forClient()
            .trustManager(new File(tlsProps.getCaCert()))
            .keyManager(new File(tlsProps.getCertChain()), new File(tlsProps.getPrivateKey()))
            .build();
    ManagedChannel newChannel =
        NettyChannelBuilder.forAddress(host, port).sslContext(sslContext).build();
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = GameLogicServiceGrpc.newBlockingStub(channel);
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub.ping(PingRequest.newBuilder().build());
  }

  @PreDestroy
  @Override
  public void close() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
    if (channel != null) {
      channel.shutdown();
    }
  }
}
