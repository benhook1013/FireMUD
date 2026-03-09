package net.firedevops.firemud.accountservice.client;

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
import net.firedevops.firemud.accountservice.config.GrpcClientProperties;
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;

  private ManagedChannel channel;
  private LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub;
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
    return copy;
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
    stub = LoggingAdminServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Log that a new account was created. */
  public void logAccountCreation(long tenantId, long accountId) {
    ApplyModerationActionRequest request =
        ApplyModerationActionRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setAction("ACCOUNT_CREATED")
            .setReason("")
            .build();
    stub.applyModerationAction(request);
  }

  /** Log that a payment transaction occurred. */
  public void logPayment(long tenantId, long accountId, long transactionId) {
    ApplyModerationActionRequest request =
        ApplyModerationActionRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setAction("PAYMENT_TXN")
            .setReason("txId=" + transactionId)
            .build();
    stub.applyModerationAction(request);
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
