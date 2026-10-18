package net.firedevops.firemud.common.publication;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationSettlementServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for Account-owned selected-publication settlement. */
public final class AccountSelectedPublicationSettlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedPublicationSettlementServiceGrpc
            .AccountSelectedPublicationSettlementServiceBlockingStub> {
  // Covers both bounded owner reads plus Account persistence, receipt construction, and readback.
  private static final long CALL_DEADLINE_SECONDS = 30L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountSelectedPublicationSettlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AccountSelectedPublicationSettlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account publication settlement client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account publication settlement stub unavailable");
    }
    initialized = true;
  }

  /** Returns the exact original six-frame receipt after Account peer and receipt validation. */
  public byte[] settle(AccountSelectedPublicationSettlementGrpcCodec.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account publication settlement requires no owner transaction");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account publication settlement must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .settleSelectedPublication(
                AccountSelectedPublicationSettlementGrpcCodec.toRequest(request));
    try {
      return AccountSelectedPublicationSettlementGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned an invalid publication settlement receipt", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountSelectedPublicationSettlementServiceGrpc
          .AccountSelectedPublicationSettlementServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountSelectedPublicationSettlementServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip")
        .withMaxInboundMessageSize(AccountSelectedPublicationSettlementGrpcCodec.MAX_MESSAGE_BYTES);
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
      initialized = false;
    }
    super.close();
  }

  private AccountSelectedPublicationSettlementServiceGrpc
          .AccountSelectedPublicationSettlementServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Account publication settlement client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Account publication settlement requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Account publication settlement requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account publication settlement requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account publication settlement " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account publication settlement " + label + " must be an existing readable file");
    }
  }
}
