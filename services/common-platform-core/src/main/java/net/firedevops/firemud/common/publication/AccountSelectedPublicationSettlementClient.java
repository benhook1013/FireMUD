package net.firedevops.firemud.common.publication;

import io.grpc.ManagedChannel;
import java.io.IOException;
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

/** Explicit file-backed mTLS client for Account-owned selected-publication settlement. */
public final class AccountSelectedPublicationSettlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedPublicationSettlementServiceGrpc
            .AccountSelectedPublicationSettlementServiceBlockingStub> {
  // Covers both bounded owner reads plus Account persistence, receipt construction, and readback.
  private static final long CALL_DEADLINE_SECONDS = 30L;

  private final String workloadNamespace;

  public AccountSelectedPublicationSettlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Account publication settlement"),
        channelFactory,
        AccountSelectedPublicationSettlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account publication settlement client is closed",
        "Account publication settlement stub unavailable");
  }

  /** Returns the exact original six-frame receipt after Account peer and receipt validation. */
  public byte[] settle(AccountSelectedPublicationSettlementGrpcCodec.Request request) {
    requireNoAmbientSql("Account publication settlement requires no owner transaction");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account publication settlement must use the configured workload namespace");
    }
    var response =
        requireInitializedStub(
                "Account publication settlement client is not initialized and available")
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
}
