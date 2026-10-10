package net.firedevops.firemud.common.account.startsession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for Account's unregistered original StartSession protection producer. */
public final class AccountStartSessionAdmissionProtectionAcquisitionGrpcClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc
            .AccountStartSessionAdmissionProtectionAcquisitionServiceBlockingStub>
    implements AccountStartSessionAdmissionProtectionAcquisitionClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountStartSessionAdmissionProtectionAcquisitionGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMutualTls(tlsProperties),
        channelFactory,
        AccountStartSessionAdmissionProtectionAcquisitionGrpcClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning Game Session component explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account admission-protection client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account admission-protection stub is unavailable");
    }
    initialized = true;
  }

  /** Acquires exact Account evidence over mTLS, outside ambient SQL and synchronization. */
  @Override
  public AccountStartSessionAdmissionProtectionEvidence acquire(
      AccountStartSessionAdmissionProtectionAcquisitionInput input) {
    Objects.requireNonNull(input, "admission protection acquisition input is required");
    if (net.firedevops.firemud.common.security.SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "Account admission-protection acquisition requires a workload-only caller context");
    }
    if (!workloadNamespace.equals(
        net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple.decode(
                input.originalPostAuthorizationTuple())
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace())) {
      throw new IllegalArgumentException(
          "Admission protection acquisition must use the configured workload namespace");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account admission-protection acquisition must run outside ambient SQL");
    }
    var request =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
            input, workloadNamespace);
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acquireOriginalStartSessionAdmissionProtection(request);
    try {
      return AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
          input, workloadNamespace, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid original StartSession admission-protection evidence", invalid);
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
  protected AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc
          .AccountStartSessionAdmissionProtectionAcquisitionServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_WIRE_BYTES)
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

  private AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc
          .AccountStartSessionAdmissionProtectionAcquisitionServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Account admission-protection client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMutualTls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Account admission-protection acquisition requires mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Account admission-protection acquisition requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account admission-protection acquisition requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account admission-protection acquisition " + label + " must be file-backed", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account admission-protection acquisition "
              + label
              + " must be an existing readable file");
    }
  }
}
