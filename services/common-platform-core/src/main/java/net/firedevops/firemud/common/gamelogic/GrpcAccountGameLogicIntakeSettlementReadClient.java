package net.firedevops.firemud.common.gamelogic;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSettlementServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Explicit file-backed mTLS client for the exact same-namespace Account settlement read. */
public final class GrpcAccountGameLogicIntakeSettlementReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountGameLogicIntakeSettlementServiceGrpc
            .AccountGameLogicIntakeSettlementServiceBlockingStub>
    implements AccountGameLogicIntakeSettlementReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcAccountGameLogicIntakeSettlementReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account intake settlement read"),
        channelFactory,
        GrpcAccountGameLogicIntakeSettlementReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account intake settlement read client is closed",
        "Account intake settlement read stub unavailable");
  }

  /**
   * Returns a validated immutable settlement receipt after exact Account server mTLS verification.
   */
  @Override
  public AccountGameLogicIntakeSettlementReadEvidence read(
      AccountGameLogicIntakeSettlementReadEvidence.Request request) {
    requireNoAmbientSql("Account settlement transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account intake settlement read must use the configured workload namespace");
    }
    AccountGameLogicIntakeSettlementServiceGrpc.AccountGameLogicIntakeSettlementServiceBlockingStub
        initializedStub;
    try {
      initializedStub =
          requireInitializedStub(
              "Account intake settlement read client is not initialized and available");
    } catch (IllegalStateException unavailable) {
      throw Status.UNAVAILABLE.asRuntimeException();
    }
    var response =
        initializedStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .settleGameLogicIntake(AccountGameLogicIntakeSettlementProtoCodec.toRequest(request));
    try {
      return AccountGameLogicIntakeSettlementProtoCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Account returned invalid settlement evidence", invalid);
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
  protected AccountGameLogicIntakeSettlementServiceGrpc
          .AccountGameLogicIntakeSettlementServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountGameLogicIntakeSettlementServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(AccountGameLogicIntakeSettlementEvidence.MAX_WIRE_BYTES)
        .withCompression("gzip");
  }
}
