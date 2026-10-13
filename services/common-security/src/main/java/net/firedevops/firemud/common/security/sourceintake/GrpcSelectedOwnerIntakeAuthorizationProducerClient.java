package net.firedevops.firemud.common.security.sourceintake;

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
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerCredentials;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence.Request;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence.Result;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerProtoCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for the standalone, unregistered Account selected-owner producer. */
public final class GrpcSelectedOwnerIntakeAuthorizationProducerClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
            .AccountSelectedOwnerIntakeAuthorizationProducerServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcSelectedOwnerIntakeAuthorizationProducerClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcSelectedOwnerIntakeAuthorizationProducerClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account selected-owner producer client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account selected-owner producer stub unavailable");
    }
    initialized = true;
  }

  /**
   * Calls the original Account owner with the same stable request identity on every exact retry.
   */
  public Result authorize(Request request, String originalCreatorCredential) {
    requireNoAmbientContext();
    Objects.requireNonNull(request, "selected-owner producer request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException("Account selected-owner producer namespace mismatch");
    }
    var wireRequest = SelectedOwnerIntakeAuthorizationProducerProtoCodec.toRequest(request);
    var current = stub();
    if (closed || !initialized || current == null) {
      throw new IllegalStateException("Account selected-owner producer client unavailable");
    }
    var credentials =
        SelectedOwnerIntakeAuthorizationProducerCredentials.forAuthorize(originalCreatorCredential);
    try {
      var secured =
          current
              .withCallCredentials(
                  new CompositeCallCredentials(
                      new GrpcServerPeerIdentityCallCredentials(
                          accountServerPeerUri(workloadNamespace)),
                      credentials))
              .withMaxOutboundMessageSize(
                  SelectedOwnerIntakeAuthorizationProducerProtoCodec.MAX_WIRE_BYTES)
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
      var response = secured.authorizeSelectedOwnerIntake(wireRequest);
      return SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(request, response);
    } catch (StatusRuntimeException unavailable) {
      throw Status.fromCode(unavailable.getStatus().getCode())
          .withDescription("Account selected-owner intake authorization denied or unavailable")
          .asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account returned invalid selected-owner intake authorization evidence");
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
  protected AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
          .AccountSelectedOwnerIntakeAuthorizationProducerServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = accountServerPeerUri(workloadNamespace);
    return AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            SelectedOwnerIntakeAuthorizationProducerProtoCodec.MAX_WIRE_BYTES)
        .withMaxOutboundMessageSize(
            SelectedOwnerIntakeAuthorizationProducerProtoCodec.MAX_WIRE_BYTES)
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

  private static void requireNoAmbientContext() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account selected-owner authorization requires no ambient SQL or synchronization");
    }
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "Account selected-owner authorization requires workload-only context");
    }
  }

  private static String accountServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/account-service";
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Account selected-owner authorization requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account selected-owner authorization requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Invalid Account selected-owner authorization " + label + " path");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account selected-owner authorization " + label + " must be an existing readable file");
    }
  }
}
