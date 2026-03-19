package net.firedevops.firemud.loggingadmin.client;

import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.DeleteAccountRequest;
import net.firedevops.firemud.account.v1.DeleteAccountResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.loggingadmin.config.GrpcClientProperties;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Account Service. */
@Component
public class AccountClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private ManagedChannel channel;
  private AccountServiceGrpc.AccountServiceBlockingStub stub;
  private TlsCertificateWatcher watcher;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      GrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this.endpoints = copyEndpoints(endpoints);
    this.tlsProps = copyTlsProps(tlsProps);
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
    if (tlsProps.isPlaintext()) {
      watcher = null;
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
      net.firedevops.firemud.common.LoggingUtil.getLogger(AccountClient.class)
          .error("Failed to reload gRPC channel", e);
    }
  }

  private void reloadChannel() throws SSLException {
    String target = endpoints.getAccountService();
    if (target == null || target.isEmpty()) {
      target = "account-service:6565";
    }
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true);
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Permanently delete the account. */
  public DeleteAccountResponse deleteAccount(long tenantId, long accountId) {
    DeleteAccountRequest request =
        DeleteAccountRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .build();
    return stub.deleteAccount(request);
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
