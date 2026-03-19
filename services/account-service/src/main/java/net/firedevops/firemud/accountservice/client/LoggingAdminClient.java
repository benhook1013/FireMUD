package net.firedevops.firemud.accountservice.client;

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
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;

  private ManagedChannel channel;
  private LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;

  public LoggingAdminClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this.endpoints = copyEndpoints(endpoints);
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
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
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true);
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
