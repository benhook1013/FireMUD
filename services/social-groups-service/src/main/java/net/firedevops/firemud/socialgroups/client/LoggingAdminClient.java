package net.firedevops.firemud.socialgroups.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.ReportServiceGrpc;
import net.firedevops.firemud.socialgroups.config.GrpcClientProperties;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;

  private ManagedChannel channel;
  private ReportServiceGrpc.ReportServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;

  public LoggingAdminClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
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
    copy.setPlaintext(src.isPlaintext());
    return copy;
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
      net.firedevops.firemud.common.LoggingUtil.getLogger(LoggingAdminClient.class)
          .error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException {
    String target = endpoints.getLoggingAdminService();
    if (target == null || target.isEmpty()) {
      target = "logging-admin-service:6565";
    }
    String[] parts = target.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6565;
    ManagedChannel newChannel;
    if (tlsProps.isPlaintext()) {
      newChannel =
          ManagedChannelBuilder.forAddress(host, port)
              .usePlaintext()
              .keepAliveTime(30, TimeUnit.SECONDS)
              .keepAliveTimeout(5, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .build();
    } else {
      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(new java.io.File(tlsProps.getCaCert()))
              .keyManager(
                  new java.io.File(tlsProps.getCertChain()),
                  new java.io.File(tlsProps.getPrivateKey()))
              .build();
      newChannel =
          NettyChannelBuilder.forAddress(host, port)
              .sslContext(sslContext)
              .keepAliveTime(30, TimeUnit.SECONDS)
              .keepAliveTimeout(5, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .build();
    }
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = ReportServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /**
   * Create a moderation report for a chat message.
   *
   * @param tenantId tenant identifier
   * @param accountId account that sent the message
   * @param description details of the violation
   */
  public void reportChatViolation(long tenantId, long accountId, String description) {
    CreateReportRequest request =
        CreateReportRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setReporterAccountId(Long.toString(accountId))
            .setTargetAccountId(Long.toString(accountId))
            .setType("CHAT_PROFANITY")
            .setDescription(description)
            .build();
    stub.createReport(request);
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
