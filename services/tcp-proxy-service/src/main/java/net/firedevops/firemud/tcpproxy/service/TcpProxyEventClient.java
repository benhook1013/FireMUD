package net.firedevops.firemud.tcpproxy.service;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
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

/** gRPC client used to notify the Game Session Service about Telnet events. */
@Component
public class TcpProxyEventClient implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(TcpProxyEventClient.class);
  private static final String DEFAULT_CHANNEL_TARGET = "dns:///game-session-service:6565";
  private static final long DISCONNECT_NOTIFY_DEADLINE_MS = 2000L;

  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private final GrpcTlsMaterialResolver tlsMaterialResolver;
  private final BlockingGrpcStubCustomizer stubCustomizer;

  private volatile ManagedChannel channel;
  private volatile TcpProxyServiceGrpc.TcpProxyServiceBlockingStub stub;
  private volatile TlsCertificateWatcher watcher;
  private volatile ResolvedGrpcTlsMaterial tlsMaterial;
  private final AtomicBoolean closing = new AtomicBoolean();

  public TcpProxyEventClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      GrpcTlsMaterialResolver tlsMaterialResolver,
      BlockingGrpcStubCustomizer stubCustomizer) {
    this.endpoints = endpoints.copy();
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
    this.tlsMaterialResolver = tlsMaterialResolver;
    this.stubCustomizer = stubCustomizer;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    try {
      ResolvedGrpcTlsMaterial initialMaterial = tlsMaterialResolver.resolve(tlsProps);
      List<Path> watchPaths = initialMaterial == null ? List.of() : initialMaterial.watchPaths();
      if (!watchPaths.isEmpty()) {
        // Register the directories before building the first channel so a rotation during startup
        // is queued and replayed after the initial channel is published.
        watcher = new TlsCertificateWatcher(watchPaths, this::safeReload);
      }
      reloadChannel(initialMaterial);
      if (watcher != null) {
        watcher.start();
      } else if (tlsMaterial != null) {
        logger.info("TLS certificates loaded from classpath resources; file watching is disabled");
      } else {
        logger.info("TLS certificates not configured; TcpProxyEventClient will use plaintext");
      }
    } catch (Throwable failure) {
      try {
        close();
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      if (failure instanceof SSLException sslException) {
        throw sslException;
      }
      if (failure instanceof IOException ioException) {
        throw ioException;
      }
      if (failure instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("TcpProxyEventClient initialization failed", failure);
    }
  }

  public NotifyDisconnectResponse notifyDisconnect(
      String gameInstanceId, String tenantId, String proxyConnectionId, long disconnectSequence) {
    NotifyDisconnectRequest.Builder builder =
        NotifyDisconnectRequest.newBuilder()
            .setProxyConnectionId(proxyConnectionId)
            .setDisconnectSequence(disconnectSequence);
    if (StringUtils.hasText(gameInstanceId)) {
      builder.setGameInstanceId(gameInstanceId);
    }
    if (StringUtils.hasText(tenantId)) {
      builder.setTenantId(tenantId);
    }
    NotifyDisconnectRequest request = builder.build();
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub currentStub = stub;
    if (closing.get() || currentStub == null) {
      throw Status.UNAVAILABLE
          .withDescription("TcpProxyEventClient is closed or not initialized")
          .asRuntimeException();
    }
    return currentStub
        .withDeadlineAfter(DISCONNECT_NOTIFY_DEADLINE_MS, TimeUnit.MILLISECONDS)
        .notifyDisconnect(request);
  }

  @PreDestroy
  @Override
  public void close() throws IOException {
    if (!closing.compareAndSet(false, true)) {
      return;
    }
    TlsCertificateWatcher watcherToClose;
    ManagedChannel channelToClose;
    synchronized (this) {
      watcherToClose = watcher;
      watcher = null;
      channelToClose = channel;
      channel = null;
      stub = null;
      tlsMaterial = null;
    }

    IOException closeFailure = null;
    if (watcherToClose != null) {
      try {
        watcherToClose.close();
      } catch (IOException e) {
        closeFailure = e;
      }
    }
    shutdownChannel(channelToClose);
    if (closeFailure != null) {
      throw closeFailure;
    }
  }

  private synchronized void safeReload() {
    if (closing.get()) {
      return;
    }
    try {
      reloadChannel();
    } catch (Exception e) {
      logger.error("Failed to reload gRPC channel", e);
      if (e instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException, IOException {
    reloadChannel(tlsMaterialResolver.resolve(tlsProps));
  }

  private void reloadChannel(ResolvedGrpcTlsMaterial resolved) throws SSLException, IOException {
    if (closing.get()) {
      return;
    }
    String target = endpoints.getGameSessionService();
    if (!StringUtils.hasText(target)) {
      target = DEFAULT_CHANNEL_TARGET;
    } else if (!target.contains("://")) {
      target = "dns:///" + target;
    }
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true, resolved);
    TcpProxyServiceGrpc.TcpProxyServiceBlockingStub newStub;
    try {
      newStub =
          stubCustomizer.customize(
              TcpProxyServiceGrpc.newBlockingStub(newChannel).withCompression("gzip"));
    } catch (RuntimeException ex) {
      shutdownChannel(newChannel);
      throw ex;
    }
    ManagedChannel channelToShutdown;
    synchronized (this) {
      if (closing.get()) {
        channelToShutdown = newChannel;
      } else {
        channelToShutdown = channel;
        channel = newChannel;
        stub = newStub;
        tlsMaterial = resolved;
      }
    }
    shutdownChannel(channelToShutdown);
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
