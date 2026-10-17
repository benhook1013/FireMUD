package net.firedevops.firemud.common.gamelogic;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Explicit file-backed mTLS client for same-namespace Account source permission confirmation. */
public final class GrpcGameLogicIntakeSourceReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountGameLogicIntakeSourceReadServiceGrpc
            .AccountGameLogicIntakeSourceReadServiceBlockingStub>
    implements GameLogicIntakeSourceReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcGameLogicIntakeSourceReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account GL intake source permission"),
        channelFactory,
        GrpcGameLogicIntakeSourceReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account GL intake source permission client is closed",
        "Account GL intake source permission stub unavailable");
  }

  /**
   * Returns exact source permission confirmation after Account server mTLS identity verification.
   */
  public GameLogicIntakeSourceReadEvidence read(GameLogicIntakeSourceReadEvidence.Request request) {
    requireNoAmbientSql("Account intake transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account GL intake source permission must use the configured workload namespace");
    }
    AccountGameLogicIntakeSourceReadServiceGrpc.AccountGameLogicIntakeSourceReadServiceBlockingStub
        initializedStub;
    try {
      initializedStub =
          requireInitializedStub(
              "Account GL intake source permission client is not initialized and available");
    } catch (IllegalStateException unavailable) {
      throw Status.UNAVAILABLE.asRuntimeException();
    }
    var stub = initializedStub.withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    var wire = GameLogicIntakeSourceReadProtoCodec.toRequest(request);
    var response =
        request.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary
            ? stub.readSourceScope(wire)
            : stub.readFinalizedIntake(wire);
    try {
      return GameLogicIntakeSourceReadProtoCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid source permission evidence", invalid);
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
  protected AccountGameLogicIntakeSourceReadServiceGrpc
          .AccountGameLogicIntakeSourceReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountGameLogicIntakeSourceReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
