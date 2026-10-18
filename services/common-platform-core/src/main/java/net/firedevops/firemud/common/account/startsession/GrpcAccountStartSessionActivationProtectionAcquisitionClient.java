package net.firedevops.firemud.common.account.startsession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client pinned to the same-namespace Account SPIFFE peer. */
public final class GrpcAccountStartSessionActivationProtectionAcquisitionClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionActivationProtectionAcquisitionServiceGrpc
            .AccountStartSessionActivationProtectionAcquisitionServiceBlockingStub>
    implements AccountStartSessionActivationProtectionAcquisitionClient {
  private static final long CALL_DEADLINE_SECONDS = 60L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcAccountStartSessionActivationProtectionAcquisitionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcAccountStartSessionActivationProtectionAcquisitionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes this explicit dependency only when its owning World service starts it. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account activation protection client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account activation protection client has no gRPC stub");
    }
    initialized = true;
  }

  @Override
  public AccountStartSessionActivationProtectionEvidence acquire(
      AccountStartSessionActivationProtectionAcquisitionInput input) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account activation protection acquisition requires no ambient owner transaction");
    }
    var currentStub = requireStub();
    Objects.requireNonNull(input, "activation protection acquisition input is required");
    input.requireTargetNamespace(workloadNamespace);
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acquireOriginalStartSessionActivationProtection(
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toRequest(
                    input, workloadNamespace));
    try {
      return AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromResponse(
          input, workloadNamespace, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid original StartSession activation protection evidence", invalid);
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
  protected AccountStartSessionActivationProtectionAcquisitionServiceGrpc
          .AccountStartSessionActivationProtectionAcquisitionServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionActivationProtectionAcquisitionServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AccountStartSessionActivationProtectionAcquisitionGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
      initialized = false;
    }
    super.close();
  }

  private AccountStartSessionActivationProtectionAcquisitionServiceGrpc
          .AccountStartSessionActivationProtectionAcquisitionServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Account activation protection client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Account activation protection requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Account activation protection requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account activation protection requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account activation protection " + label + " must be a readable file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account activation protection " + label + " must be an existing readable file");
    }
  }
}
