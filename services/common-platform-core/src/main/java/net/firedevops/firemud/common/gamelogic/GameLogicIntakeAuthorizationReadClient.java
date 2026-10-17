package net.firedevops.firemud.common.gamelogic;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/**
 * Explicit file-backed mTLS client for the exact same-namespace Account intake authorization read.
 */
public final class GameLogicIntakeAuthorizationReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountGameLogicIntakeAuthorizationReadServiceGrpc
            .AccountGameLogicIntakeAuthorizationReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GameLogicIntakeAuthorizationReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account GL intake authorization read"),
        channelFactory,
        GameLogicIntakeAuthorizationReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account GL intake authorization read client is closed",
        "Account GL intake authorization read stub unavailable");
  }

  /** Returns a validated HELD result only after exact Account server mTLS identity verification. */
  public GameLogicIntakeAuthorizationReadEvidence read(
      GameLogicIntakeAuthorizationReadEvidence.Request request) {
    requireNoAmbientSql("Account intake transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account GL intake authorization read must use the configured workload namespace");
    }
    var response =
        requireInitializedStub(
                "Account GL intake authorization read client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readHeldGameLogicIntakeAuthorization(
                GameLogicIntakeAuthorizationReadGrpcCodec.toRequest(request));
    try {
      return GameLogicIntakeAuthorizationReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Account returned invalid COMMIT_ORDER evidence", invalid);
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
  protected AccountGameLogicIntakeAuthorizationReadServiceGrpc
          .AccountGameLogicIntakeAuthorizationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountGameLogicIntakeAuthorizationReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(GameLogicIntakeAuthorizationGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
