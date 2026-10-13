package net.firedevops.firemud.common.gamelogic;

import io.grpc.CompositeCallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for the standalone, unregistered Account intake producer. */
public final class GrpcGameLogicIntakeAuthorizationClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountGameLogicIntakeAuthorizationServiceGrpc
            .AccountGameLogicIntakeAuthorizationServiceBlockingStub>
    implements GameLogicIntakeAuthorizationClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcGameLogicIntakeAuthorizationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcGameLogicIntakeAuthorizationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Canonical workload namespace required");
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Account intake authorization client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null)
      throw new IllegalStateException("Account intake authorization stub unavailable");
    initialized = true;
  }

  @Override
  public GameLogicIntakeAuthorizationEvidence.Result authorize(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential) {
    return invoke(request, originalCreatorCredential, Operation.AUTHORIZE);
  }

  @Override
  public GameLogicIntakeAuthorizationEvidence.Result recover(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential) {
    return invoke(request, originalCreatorCredential, Operation.RECOVER);
  }

  @Override
  public GameLogicIntakeAuthorizationEvidence.Result abort(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential) {
    return invoke(request, originalCreatorCredential, Operation.ABORT);
  }

  private GameLogicIntakeAuthorizationEvidence.Result invoke(
      GameLogicIntakeAuthorizationEvidence.Request request,
      String originalCreatorCredential,
      Operation operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Account intake authorization requires no ambient SQL");
    Objects.requireNonNull(request);
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Account intake authorization namespace mismatch");
    var current = stub();
    if (closed || !initialized || current == null)
      throw new IllegalStateException("Account intake authorization client unavailable");
    try {
      var secured =
          current
              .withCallCredentials(
                  new CompositeCallCredentials(
                      new GrpcServerPeerIdentityCallCredentials(
                          "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service"),
                      credentials(originalCreatorCredential, operation)))
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
      var wireRequest = GameLogicIntakeAuthorizationGrpcCodec.toRequest(request);
      var response =
          switch (operation) {
            case AUTHORIZE -> secured.authorizeIntake(wireRequest);
            case RECOVER -> secured.recoverIntake(wireRequest);
            case ABORT -> secured.abortIntake(wireRequest);
          };
      var result = GameLogicIntakeAuthorizationGrpcCodec.fromResponse(request, response);
      if (operation == Operation.AUTHORIZE
          && result.outcome() != GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED)
        throw new IllegalArgumentException("Authorization response was not finalized");
      return result;
    } catch (StatusRuntimeException unavailable) {
      throw Status.fromCode(unavailable.getStatus().getCode())
          .withDescription("Account Game Logic intake authorization denied or unavailable")
          .asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account returned invalid Game Logic intake authorization evidence");
    }
  }

  private static io.grpc.CallCredentials credentials(
      String originalCreatorCredential, Operation operation) {
    return switch (operation) {
      case AUTHORIZE ->
          AccountGameLogicIntakeAuthorizationCredentials.forAuthorize(originalCreatorCredential);
      case RECOVER ->
          AccountGameLogicIntakeAuthorizationCredentials.forRecover(originalCreatorCredential);
      case ABORT ->
          AccountGameLogicIntakeAuthorizationCredentials.forAbort(originalCreatorCredential);
    };
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
  protected AccountGameLogicIntakeAuthorizationServiceGrpc
          .AccountGameLogicIntakeAuthorizationServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountGameLogicIntakeAuthorizationServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(GameLogicIntakeAuthorizationGrpcCodec.MAX_WIRE_BYTES)
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

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext())
      throw new IllegalArgumentException("Account intake authorization requires workload mTLS");
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:"))
      throw new IllegalArgumentException(
          "Account intake authorization requires file-backed certificate, key, and CA material");
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid Account intake authorization " + label + " path");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path))
      throw new IllegalArgumentException(
          "Account intake authorization " + label + " must be an existing readable file");
  }

  private enum Operation {
    AUTHORIZE,
    RECOVER,
    ABORT
  }
}
