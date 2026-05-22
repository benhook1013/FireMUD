package net.firedevops.firemud.tcpproxy.service;

import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.common.grpc.ResolvedGrpcTlsMaterial;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * gRPC client used to notify the Game Session Service about Telnet events.
 *
 * <p>This remains an intentional raw-stub outlier from the normal internal auth-propagation seam:
 * it acts as an ingress-side event bridge rather than a secured internal business client carrying a
 * trusted service identity into protected downstream RPCs.
 */
@Component
public class TcpProxyEventClient implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(TcpProxyEventClient.class);
  private static final String DEFAULT_CHANNEL_TARGET = "dns:///game-session-service:6565";
  private static final long DISCONNECT_NOTIFY_DEADLINE_MS = 2000L;

  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private final GrpcTlsMaterialResolver tlsMaterialResolver;

  private ManagedChannel channel;
  private TcpProxyServiceGrpc.TcpProxyServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;
  private ResolvedGrpcTlsMaterial tlsMaterial;

  public TcpProxyEventClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      GrpcTlsMaterialResolver tlsMaterialResolver) {
    this.endpoints = endpoints.copy();
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
    this.tlsMaterialResolver = tlsMaterialResolver;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    if (tlsMaterial != null) {
      List<Path> watchPaths = tlsMaterial.watchPaths();
      if (!watchPaths.isEmpty()) {
        watcher = TlsCertificateWatcher.createAndStart(watchPaths, this::safeReload);
      } else {
        logger.info("TLS certificates loaded from classpath resources; file watching is disabled");
      }
    } else {
      logger.info("TLS certificates not configured; TcpProxyEventClient will use plaintext");
    }
  }

  public NotifyDisconnectResponse notifyDisconnect(
      String gameInstanceId, String tenantId, String proxyConnectionId, long disconnectSequence) {
    NotifyDisconnectRequest.Builder builder =
        NotifyDisconnectRequest.newBuilder()
            .setProxyConnectionId(proxyConnectionId)
            .setDisconnectSequence(disconnectSequence);
    if (StringUtils.hasText(gameInstanceId)) {
      builder.setSessionId(gameInstanceId);
      builder.setGameInstanceId(gameInstanceId);
    }
    if (StringUtils.hasText(tenantId)) {
      builder.setTenantId(tenantId);
    }
    NotifyDisconnectRequest request = builder.build();
    return stub.withDeadlineAfter(DISCONNECT_NOTIFY_DEADLINE_MS, TimeUnit.MILLISECONDS)
        .notifyDisconnect(request);
  }

  @PreDestroy
  @Override
  public void close() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
    shutdownChannel(channel);
  }

  private synchronized void safeReload() {
    try {
      reloadChannel();
    } catch (Exception e) {
      logger.error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException, IOException {
    String target = endpoints.getGameSessionService();
    if (!StringUtils.hasText(target)) {
      target = DEFAULT_CHANNEL_TARGET;
    } else if (!target.contains("://")) {
      target = "dns:///" + target;
    }
    ResolvedGrpcTlsMaterial resolved = tlsMaterialResolver.resolve(tlsProps);
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true, resolved);
    ManagedChannel previousChannel = channel;
    channel = newChannel;
    stub = TcpProxyServiceGrpc.newBlockingStub(channel).withCompression("gzip");
    tlsMaterial = resolved;
    shutdownChannel(previousChannel);
  }

  private static void shutdownChannel(ManagedChannel channel) {
    if (channel == null) {
      return;
    }
    channel.shutdown();
    try {
      if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    } catch (InterruptedException e) {
      channel.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
