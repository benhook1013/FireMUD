package net.firedevops.firemud.common.security.sourceintake;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeSettlementServiceGrpc;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence.Request;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence.Result;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for the standalone, unregistered Account settlement command. */
public final class GrpcSelectedOwnerIntakeSettlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedOwnerIntakeSettlementServiceGrpc
            .AccountSelectedOwnerIntakeSettlementServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcSelectedOwnerIntakeSettlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcSelectedOwnerIntakeSettlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Account settlement client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) throw new IllegalStateException("Account settlement stub unavailable");
    initialized = true;
  }

  /** Settles the complete existing authorization under its original durable identity. */
  public Result settle(Request request) {
    requireNoAmbientContext();
    Objects.requireNonNull(request, "Selected-owner settlement request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException("Account selected-owner settlement namespace mismatch");
    }
    var wireRequest = SelectedOwnerIntakeSettlementGrpcCodec.toRequest(request);
    var current = stub();
    if (closed || !initialized || current == null) {
      throw new IllegalStateException("Account selected-owner settlement client unavailable");
    }
    try {
      var secured =
          current
              .withCallCredentials(
                  new GrpcServerPeerIdentityCallCredentials(
                      accountServerPeerUri(workloadNamespace)))
              .withMaxOutboundMessageSize(SelectedOwnerIntakeSettlementGrpcCodec.MAX_REQUEST_BYTES)
              .withMaxInboundMessageSize(SelectedOwnerIntakeSettlementGrpcCodec.MAX_RESPONSE_BYTES)
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
      var response = secured.settleSelectedOwnerIntake(wireRequest);
      return SelectedOwnerIntakeSettlementGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException unavailable) {
      throw Status.fromCode(unavailable.getStatus().getCode())
          .withDescription("Account selected-owner settlement denied or unavailable")
          .asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account returned invalid selected-owner settlement evidence");
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
  protected AccountSelectedOwnerIntakeSettlementServiceGrpc
          .AccountSelectedOwnerIntakeSettlementServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = accountServerPeerUri(workloadNamespace);
    return AccountSelectedOwnerIntakeSettlementServiceGrpc.newBlockingStub(channel)
        .withMaxOutboundMessageSize(SelectedOwnerIntakeSettlementGrpcCodec.MAX_REQUEST_BYTES)
        .withMaxInboundMessageSize(SelectedOwnerIntakeSettlementGrpcCodec.MAX_RESPONSE_BYTES)
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
          "Account selected-owner settlement requires no ambient SQL or synchronization");
    }
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "Account selected-owner settlement requires workload-only context");
    }
  }

  private static String accountServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/account-service";
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Account selected-owner settlement requires workload mTLS");
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
          "Account selected-owner settlement requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Invalid Account selected-owner settlement " + label + " path");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account selected-owner settlement " + label + " must be an existing readable file");
    }
  }
}
