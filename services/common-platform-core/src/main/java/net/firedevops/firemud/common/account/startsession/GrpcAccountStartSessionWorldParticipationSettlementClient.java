package net.firedevops.firemud.common.account.startsession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationSettlementServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client pinned to Account in the configured World namespace. */
public final class GrpcAccountStartSessionWorldParticipationSettlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionWorldParticipationSettlementServiceGrpc
            .AccountStartSessionWorldParticipationSettlementServiceBlockingStub>
    implements AccountStartSessionWorldParticipationSettlementClient {
  private static final long CALL_DEADLINE_SECONDS = 180L;

  private final String workloadNamespace;

  public GrpcAccountStartSessionWorldParticipationSettlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcAccountStartSessionWorldParticipationSettlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes this explicit dependency only when an owning World service starts it. */
  public synchronized void init() throws SSLException, IOException {
    initOnce(
        "Account World participation settlement client is closed",
        "Account World participation settlement client has no gRPC stub");
  }

  @Override
  public AccountStartSessionWorldParticipationSettlementEvidence settle(
      AccountStartSessionWorldParticipationSettlementInput input) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account World participation settlement requires no ambient World transaction");
    }
    Objects.requireNonNull(input, "World participation settlement input required");
    if (!workloadNamespace.equals(input.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account World participation settlement target namespace differs");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .settleOriginalStartSessionWorldParticipation(
                AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(input));
    try {
      return AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(input, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid original StartSession World participation settlement evidence",
          invalid);
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
  protected AccountStartSessionWorldParticipationSettlementServiceGrpc
          .AccountStartSessionWorldParticipationSettlementServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionWorldParticipationSettlementServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AccountStartSessionWorldParticipationSettlementGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  private AccountStartSessionWorldParticipationSettlementServiceGrpc
          .AccountStartSessionWorldParticipationSettlementServiceBlockingStub
      requireStub() {
    return requireInitializedStub(
        "Account World participation settlement client is not initialized and available");
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Account World participation settlement",
        "Account World participation settlement requires workload mTLS",
        "Account World participation settlement requires workload mTLS");
  }
}
