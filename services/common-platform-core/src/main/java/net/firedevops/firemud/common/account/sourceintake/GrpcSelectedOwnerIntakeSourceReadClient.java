package net.firedevops.firemud.common.account.sourceintake;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for preliminary selected-owner source-scope confirmation. */
public final class GrpcSelectedOwnerIntakeSourceReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedOwnerIntakeSourceReadServiceGrpc
            .AccountSelectedOwnerIntakeSourceReadServiceBlockingStub>
    implements SelectedOwnerIntakeSourceReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcSelectedOwnerIntakeSourceReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcSelectedOwnerIntakeSourceReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Account selected-owner read client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null)
      throw new IllegalStateException("Account selected-owner read stub unavailable");
    initialized = true;
  }

  /** Returns Account's exact preliminary confirmation after authenticating its server identity. */
  @Override
  public SelectedOwnerIntakeSourceReadEvidence read(
      SelectedOwnerIntakeSourceReadEvidence.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Account selected-owner read must run outside SQL");
    Objects.requireNonNull(request, "source-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException(
          "Account selected-owner read must use the configured workload namespace");
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSourceScope(SelectedOwnerIntakeSourceReadProtoCodec.toRequest(request));
    try {
      return SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid preliminary selected-owner source-read evidence", invalid);
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
  protected AccountSelectedOwnerIntakeSourceReadServiceGrpc
          .AccountSelectedOwnerIntakeSourceReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountSelectedOwnerIntakeSourceReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(SelectedOwnerIntakeSourceReadProtoCodec.MAX_WIRE_BYTES)
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

  private AccountSelectedOwnerIntakeSourceReadServiceGrpc
          .AccountSelectedOwnerIntakeSourceReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null)
      throw new IllegalStateException(
          "Account selected-owner read client is not initialized and available");
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext())
      throw new IllegalArgumentException("Account selected-owner read requires workload mTLS");
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank())
      throw new IllegalArgumentException(
          "Account selected-owner read requires file-backed certificate, key, and CA material");
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:"))
      throw new IllegalArgumentException(
          "Account selected-owner read requires file-backed certificate, key, and CA material");
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account selected-owner read " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path))
      throw new IllegalArgumentException(
          "Account selected-owner read " + label + " must be an existing readable file");
  }
}
