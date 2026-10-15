package net.firedevops.firemud.common.publication;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountPublicationAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for Game Design's exact Account authorization read. */
public final class AccountPublicationAuthorizationReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountPublicationAuthorizationReadServiceGrpc
            .AccountPublicationAuthorizationReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public AccountPublicationAuthorizationReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account publication authorization read"),
        channelFactory,
        AccountPublicationAuthorizationReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account publication authorization read client is closed",
        "Account publication authorization read stub unavailable");
  }

  /** Returns HELD evidence only after exact Account server identity and response verification. */
  public AccountPublicationAuthorizationReadEvidence read(
      AccountPublicationAuthorizationReadEvidence.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account publication authorization read requires no owner transaction");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account publication authorization read must use the configured workload namespace");
    }
    var response =
        requireInitializedStub(
                "Account publication authorization read client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readHeldPublicationAuthorization(
                AccountPublicationAuthorizationReadGrpcCodec.toRequest(request));
    try {
      return AccountPublicationAuthorizationReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid publication authorization evidence", invalid);
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
  protected AccountPublicationAuthorizationReadServiceGrpc
          .AccountPublicationAuthorizationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountPublicationAuthorizationReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
