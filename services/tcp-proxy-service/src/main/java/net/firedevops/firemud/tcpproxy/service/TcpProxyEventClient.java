package net.firedevops.firemud.tcpproxy.service;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.tcpproxy.config.GrpcClientProperties;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.slf4j.Logger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** gRPC client used to notify the Game Session Service about Telnet events. */
@Component
public class TcpProxyEventClient implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(TcpProxyEventClient.class);

  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private ManagedChannel channel;
  private TcpProxyServiceGrpc.TcpProxyServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;
  private TlsFiles tlsFiles;

  public TcpProxyEventClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = copyEndpoints(endpoints);
    this.tlsProps = copyTlsProps(tlsProps);
  }

  private static ServiceEndpointsProperties copyEndpoints(ServiceEndpointsProperties src) {
    var copy = new ServiceEndpointsProperties();
    copy.setAccountService(src.getAccountService());
    copy.setGameSessionService(src.getGameSessionService());
    copy.setGameDesignService(src.getGameDesignService());
    copy.setGameLogicService(src.getGameLogicService());
    copy.setWorldManagementService(src.getWorldManagementService());
    copy.setEntityManagementService(src.getEntityManagementService());
    copy.setLoggingAdminService(src.getLoggingAdminService());
    copy.setAutomationScriptingService(src.getAutomationScriptingService());
    return copy;
  }

  private static GrpcClientProperties copyTlsProps(GrpcClientProperties src) {
    var copy = new GrpcClientProperties();
    copy.setPlaintext(src.isPlaintext());
    copy.setCertChain(src.getCertChain());
    copy.setPrivateKey(src.getPrivateKey());
    copy.setCaCert(src.getCaCert());
    return copy;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    if (tlsFiles != null) {
      List<Path> watchPaths = tlsFiles.watchPaths();
      if (!watchPaths.isEmpty()) {
        watcher = TlsCertificateWatcher.createAndStart(watchPaths, this::safeReload);
      } else {
        logger.info("TLS certificates loaded from classpath resources; file watching is disabled");
      }
    } else {
      logger.info("TLS certificates not configured; TcpProxyEventClient will use plaintext");
    }
  }

  private synchronized void safeReload() {
    try {
      reloadChannel();
    } catch (Exception e) {
      logger.error("Failed to reload gRPC channel", e);
    }
  }

  private static final String DEFAULT_CHANNEL_TARGET = "dns:///game-session-service:6565";

  private void reloadChannel() throws SSLException, IOException {
    String target = endpoints.getGameSessionService();
    if (!StringUtils.hasText(target)) {
      target = DEFAULT_CHANNEL_TARGET;
    } else if (!target.contains("://")) {
      target = "dns:///" + target;
    }
    TlsFiles resolved = resolveTlsFiles();
    NettyChannelBuilder builder =
        NettyChannelBuilder.forTarget(target)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true);
    if (tlsProps.isPlaintext()) {
      builder = builder.usePlaintext();
      resolved = null;
    } else if (resolved != null) {
      try (InputStream certChainStream = resolved.certChain().openStream();
          InputStream privateKeyStream = resolved.privateKey().openStream();
          InputStream caCertStream = resolved.caCert().openStream()) {
        var sslContext =
            GrpcSslContexts.forClient()
                .trustManager(caCertStream)
                .keyManager(certChainStream, privateKeyStream)
                .build();
        builder = builder.sslContext(sslContext);
      }
    } else {
      builder = builder.usePlaintext();
    }
    ManagedChannel newChannel = builder.build();
    ManagedChannel previousChannel = channel;
    channel = newChannel;
    stub = TcpProxyServiceGrpc.newBlockingStub(channel).withCompression("gzip");
    tlsFiles = resolved;
    shutdownChannel(previousChannel);
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
    return stub.notifyDisconnect(request);
  }

  @PreDestroy
  @Override
  public void close() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
    shutdownChannel(channel);
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

  private TlsFiles resolveTlsFiles() throws IOException {
    if (tlsProps.isPlaintext()) {
      return null;
    }
    TlsResource certChain = resolveTlsResource(tlsProps.getCertChain(), "certChain");
    TlsResource privateKey = resolveTlsResource(tlsProps.getPrivateKey(), "privateKey");
    TlsResource caCert = resolveTlsResource(tlsProps.getCaCert(), "caCert");
    if (certChain == null && privateKey == null && caCert == null) {
      return null;
    }
    if (certChain == null || privateKey == null || caCert == null) {
      throw new IOException("TLS configuration must specify certChain, privateKey, and caCert");
    }
    return new TlsFiles(certChain, privateKey, caCert);
  }

  private TlsResource resolveTlsResource(String configuredPath, String propertyName)
      throws IOException {
    if (!StringUtils.hasText(configuredPath)) {
      return null;
    }
    if (configuredPath.startsWith("classpath:")) {
      String resourcePath = configuredPath.substring("classpath:".length());
      ClassPathResource resource = new ClassPathResource(resourcePath);
      if (!resource.exists()) {
        throw new IOException("Classpath resource not found for TLS property " + propertyName);
      }
      return new TlsResource(resource::getInputStream, null);
    }
    File file = new File(configuredPath);
    if (!file.exists()) {
      throw new IOException(
          "TLS file does not exist for property " + propertyName + ": " + configuredPath);
    }
    Path path = file.toPath();
    return new TlsResource(() -> Files.newInputStream(path), path);
  }

  private record TlsFiles(TlsResource certChain, TlsResource privateKey, TlsResource caCert) {
    List<Path> watchPaths() {
      List<Path> paths = new ArrayList<>();
      addIfPresent(paths, certChain.watchPath());
      addIfPresent(paths, privateKey.watchPath());
      addIfPresent(paths, caCert.watchPath());
      return paths;
    }

    private static void addIfPresent(List<Path> paths, Path path) {
      if (path != null) {
        paths.add(path);
      }
    }
  }

  private record TlsResource(TlsInputStreamSupplier streamSupplier, Path watchPath) {
    InputStream openStream() throws IOException {
      return streamSupplier.open();
    }
  }

  @FunctionalInterface
  private interface TlsInputStreamSupplier {
    InputStream open() throws IOException;
  }
}
