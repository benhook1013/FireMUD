package net.firedevops.firemud.common.account.startsession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionSettlementServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client pinned to Account in the configured namespace. */
public final class GrpcAccountStartSessionActivationProtectionSettlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionActivationProtectionSettlementServiceGrpc
            .AccountStartSessionActivationProtectionSettlementServiceBlockingStub>
    implements AccountStartSessionActivationProtectionSettlementClient {
  private static final long CALL_DEADLINE_SECONDS = 180L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcAccountStartSessionActivationProtectionSettlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcAccountStartSessionActivationProtectionSettlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes this explicit dependency only when its owning World service starts it. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account activation settlement client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account activation settlement client has no gRPC stub");
    }
    initialized = true;
  }

  @Override
  public AccountStartSessionActivationProtectionSettlementEvidence settle(
      AccountStartSessionActivationProtectionSettlementInput input) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account activation settlement requires no ambient World transaction");
    }
    Objects.requireNonNull(input, "activation settlement input required");
    if (!workloadNamespace.equals(input.targetNamespace())) {
      throw new IllegalArgumentException("Account activation settlement target namespace differs");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .settleOriginalStartSessionActivationProtection(
                AccountStartSessionActivationProtectionSettlementGrpcCodec.toRequest(input));
    try {
      return AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
          input, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid original StartSession activation settlement evidence", invalid);
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
  protected AccountStartSessionActivationProtectionSettlementServiceGrpc
          .AccountStartSessionActivationProtectionSettlementServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionActivationProtectionSettlementServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AccountStartSessionActivationProtectionSettlementGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private AccountStartSessionActivationProtectionSettlementServiceGrpc
          .AccountStartSessionActivationProtectionSettlementServiceBlockingStub
      requireStub() {
    var current = stub();
    if (closed || !initialized || current == null) {
      throw new IllegalStateException(
          "Account activation settlement client is not initialized and available");
    }
    return current;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Account activation settlement requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Account activation settlement requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account activation settlement requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account activation settlement " + label + " must be a readable file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account activation settlement " + label + " must be an existing readable file");
    }
  }
}
