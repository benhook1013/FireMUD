package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.config.GrpcClientProperties;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.ReportServiceGrpc;
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
    var sslContext =
        GrpcSslContexts.forClient()
            .trustManager(new java.io.File(tlsProps.getCaCert()))
            .keyManager(
                new java.io.File(tlsProps.getCertChain()),
                new java.io.File(tlsProps.getPrivateKey()))
            .build();
    ManagedChannel newChannel =
        NettyChannelBuilder.forAddress(host, port).sslContext(sslContext).build();
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = ReportServiceGrpc.newBlockingStub(channel);
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
