package net.firedevops.firemud.worldmanagement.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Design Service. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration and channel references are not exposed")
public class GameDesignClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private ManagedChannel channel;
  private GameDesignServiceGrpc.GameDesignServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;

  public GameDesignClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
    this.channelFactory = channelFactory;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    if (tlsProps.isPlaintext()) {
      return;
    }
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
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true);
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
