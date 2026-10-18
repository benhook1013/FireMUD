package net.firedevops.firemud.common.gamelogic;

import io.grpc.CompositeCallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** File-backed mTLS client for the standalone, unregistered Account intake producer. */
public final class GrpcGameLogicIntakeAuthorizationClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountGameLogicIntakeAuthorizationServiceGrpc
            .AccountGameLogicIntakeAuthorizationServiceBlockingStub>
    implements GameLogicIntakeAuthorizationClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcGameLogicIntakeAuthorizationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account intake authorization"),
        channelFactory,
        GrpcGameLogicIntakeAuthorizationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Canonical workload namespace required");
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account intake authorization client is closed",
        "Account intake authorization stub unavailable");
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
    requireNoAmbientSql("Account intake authorization requires no ambient SQL");
    Objects.requireNonNull(request);
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Account intake authorization namespace mismatch");
    AccountGameLogicIntakeAuthorizationServiceGrpc
            .AccountGameLogicIntakeAuthorizationServiceBlockingStub
        current;
    try {
      current = requireInitializedStub("Account intake authorization client unavailable");
    } catch (IllegalStateException unavailable) {
      throw Status.UNAVAILABLE.asRuntimeException();
    }
    var credentials = credentials(originalCreatorCredential, operation);
    var wireRequest = GameLogicIntakeAuthorizationGrpcCodec.toRequest(request);
    try {
      var secured =
          current
              .withCallCredentials(
                  new CompositeCallCredentials(
                      new GrpcServerPeerIdentityCallCredentials(
                          "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service"),
                      credentials))
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
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

  private enum Operation {
    AUTHORIZE,
    RECOVER,
    ABORT
  }
}
