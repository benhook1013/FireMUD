package net.firedevops.firemud.tcpproxy.service;

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
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.tcpproxy.config.GrpcClientProperties;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputRequest;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
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
    copy.setCertChain(src.getCertChain());
    copy.setPrivateKey(src.getPrivateKey());
    copy.setCaCert(src.getCaCert());
    return copy;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    reloadChannel();
    if (tlsFiles != null) {
      watcher =
          TlsCertificateWatcher.createAndStart(
              List.of(
                  tlsFiles.certChain().toPath(),
                  tlsFiles.privateKey().toPath(),
                  tlsFiles.caCert().toPath()),
              this::safeReload);
    } else {
      logger.info("TLS certificates not configured; TcpProxyEventClient will use plaintext");
    }
  }

  private synchronized void safeReload() {
    try {
      reloadChannel();
    } catch (SSLException e) {
      logger.error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException {
    String target = endpoints.getGameSessionService();
    if (target == null || target.isEmpty()) {
      target = "game-session-service:6565";
    }
    String[] parts = target.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6565;
    TlsFiles resolved = resolveTlsFiles();
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(host, port)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true);
    if (resolved != null) {
      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(resolved.caCert())
              .keyManager(resolved.certChain(), resolved.privateKey())
              .build();
      builder = builder.sslContext(sslContext);
    } else {
      builder = builder.usePlaintext();
    }
    ManagedChannel newChannel = builder.build();
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = TcpProxyServiceGrpc.newBlockingStub(channel).withCompression("gzip");
    tlsFiles = resolved;
  }

  public NotifyDisconnectResponse notifyDisconnect(String sessionId, String tenantId) {
    NotifyDisconnectRequest request =
        NotifyDisconnectRequest.newBuilder().setSessionId(sessionId).setTenantId(tenantId).build();
    return stub.notifyDisconnect(request);
  }

  public PushBufferedInputResponse pushBufferedInput(
      String sessionId, List<String> commands, String tenantId) {
    PushBufferedInputRequest request =
        PushBufferedInputRequest.newBuilder()
            .setSessionId(sessionId)
            .addAllCommands(commands)
            .setTenantId(tenantId)
            .build();
    return stub.pushBufferedInput(request);
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

  private TlsFiles resolveTlsFiles() {
    File certChainFile = resolveFile(tlsProps.getCertChain());
    File privateKeyFile = resolveFile(tlsProps.getPrivateKey());
    File caCertFile = resolveFile(tlsProps.getCaCert());
    if (certChainFile == null || privateKeyFile == null || caCertFile == null) {
      return null;
    }
    return new TlsFiles(certChainFile, privateKeyFile, caCertFile);
  }

  private File resolveFile(String configuredPath) {
    if (!StringUtils.hasText(configuredPath)) {
      return null;
    }
    try {
      if (configuredPath.startsWith("classpath:")) {
        String resourcePath = configuredPath.substring("classpath:".length());
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
          logger.debug("Classpath resource {} not found for TLS configuration", resourcePath);
          return null;
        }
        return resource.getFile();
      }
      File file = new File(configuredPath);
      if (file.exists()) {
        return file;
      }
      logger.debug("TLS file {} does not exist", configuredPath);
      return null;
    } catch (IOException e) {
      logger.warn("Failed to resolve TLS file {}", configuredPath, e);
      return null;
    }
  }

  private record TlsFiles(File certChain, File privateKey, File caCert) {}
}
