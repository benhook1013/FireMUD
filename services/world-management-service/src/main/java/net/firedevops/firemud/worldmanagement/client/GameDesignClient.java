package net.firedevops.firemud.worldmanagement.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import net.firedevops.firemud.worldmanagement.config.GrpcClientProperties;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Design Service. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration and channel references are not exposed")
public class GameDesignClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private ManagedChannel channel;
  private GameDesignServiceGrpc.GameDesignServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;

  public GameDesignClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    watcher =
        TlsCertificateWatcher.createAndStart(
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
      net.firedevops.firemud.common.LoggingUtil.getLogger(GameDesignClient.class)
          .error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException {
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
    ManagedChannel newChannel =
        NettyChannelBuilder.forAddress(host, port)
            .sslContext(sslContext)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build();
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = GameDesignServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Returns published versions for the given tenant. */
  public ListVersionsResponse listVersions(long tenantId) {
    ListVersionsRequest request =
        ListVersionsRequest.newBuilder().setTenantId(String.valueOf(tenantId)).build();
    return stub.listVersions(request);
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
