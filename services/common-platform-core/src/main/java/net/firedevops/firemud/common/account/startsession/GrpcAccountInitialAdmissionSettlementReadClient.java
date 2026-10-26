package net.firedevops.firemud.common.account.startsession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.v1.AccountInitialAdmissionSettlementReadServiceGrpc;

/** Explicit Account-purpose client; no runtime registration or owner authority is created here. */
public final class GrpcAccountInitialAdmissionSettlementReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountInitialAdmissionSettlementReadServiceGrpc
            .AccountInitialAdmissionSettlementReadServiceBlockingStub>
    implements AccountInitialAdmissionSettlementReadClient {
  private static final long CALL_DEADLINE_SECONDS = 180L;
  private final String workloadNamespace;

  public GrpcAccountInitialAdmissionSettlementReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Account admission settlement read"),
        channelFactory,
        GrpcAccountInitialAdmissionSettlementReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Admission settlement client is closed", "Admission settlement client is not initialized");
  }

  @Override
  public AccountInitialAdmissionSettlementReadEvidence read(
      AccountInitialAdmissionSettlementReadInput input) {
    requireNoAmbientSql("Admission settlement read requires no ambient Account transaction");
    Objects.requireNonNull(input, "Admission settlement input required");
    input.requireTargetNamespace(workloadNamespace);
    var request =
        AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, workloadNamespace);
    var response =
        requireInitializedStub("Admission settlement client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getAccountInitialAdmissionSettlement(request);
    try {
      return AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(input, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid admission settlement evidence", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected AccountInitialAdmissionSettlementReadServiceGrpc
          .AccountInitialAdmissionSettlementReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return AccountInitialAdmissionSettlementReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AccountInitialAdmissionSettlementReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }
}
