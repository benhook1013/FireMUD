package net.firedevops.firemud.common.account.sourceintake;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for Account's distinct World closure HELD read. */
public final class AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc
            .AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceBlockingStub>
    implements SelectedOwnerIntakeWorldClosureAuthorizationReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed)
      throw new IllegalStateException("Account World closure authorization client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null)
      throw new IllegalStateException("Account World closure authorization stub unavailable");
    initialized = true;
  }

  /** Returns only Account's exact echoed HELD result, outside ambient SQL and synchronization. */
  @Override
  public SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence readHeld(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account World closure authorization read must run outside SQL");
    }
    Objects.requireNonNull(request, "World closure authorization-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException(
          "Account World closure authorization read must use the configured namespace");
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readHeldSelectedOwnerWorldClosureAuthorization(
                SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(request));
    try {
      return SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromResponse(
          request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid selected-owner World closure HELD evidence", invalid);
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
  protected AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc
          .AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc.newBlockingStub(
            channel)
        .withMaxInboundMessageSize(
            SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES)
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

  private AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc
          .AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null)
      throw new IllegalStateException(
          "Account World closure authorization client is not initialized and available");
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext())
      throw new IllegalArgumentException(
          "Account World closure authorization read requires workload mTLS");
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank())
      throw new IllegalArgumentException(
          "Account World closure authorization read requires file-backed certificate, key, and CA material");
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:"))
      throw new IllegalArgumentException(
          "Account World closure authorization read requires file-backed certificate, key, and CA material");
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account World closure authorization read " + label + " must be a file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path))
      throw new IllegalArgumentException(
          "Account World closure authorization read "
              + label
              + " must be an existing readable file");
  }
}
