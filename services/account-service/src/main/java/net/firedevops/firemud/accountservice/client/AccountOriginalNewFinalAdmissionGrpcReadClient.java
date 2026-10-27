package net.firedevops.firemud.accountservice.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.AuthenticatedFinalAdmissionReadback;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.ExpectedOriginalAdmission;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.OriginalNewFinalAdmissionReadServiceGrpc;

/** Explicit, manually composed Account mTLS client for the concrete original final result. */
public final class AccountOriginalNewFinalAdmissionGrpcReadClient
    extends AbstractReloadingBlockingGrpcClient<
        OriginalNewFinalAdmissionReadServiceGrpc
            .OriginalNewFinalAdmissionReadServiceBlockingStub>
    implements AccountOriginalNewFinalAdmissionReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private final String workloadNamespace;

  public AccountOriginalNewFinalAdmissionGrpcReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Original NEW final admission read"),
        channelFactory,
        AccountOriginalNewFinalAdmissionGrpcReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning Account service explicitly composes this client. */
  public void init() throws SSLException, IOException {
    requireNoAmbientSql("Original NEW final admission client initialization requires no SQL");
    initOnce(
        "Original NEW final admission client is closed",
        "Original NEW final admission client is not initialized");
  }

  @Override
  public AuthenticatedFinalAdmissionReadback readActualOriginalFinalAdmission(
      ExpectedOriginalAdmission expected) {
    requireNoAmbientSql("Original NEW final admission read requires no Account transaction");
    requireWorkloadOnlyContext();
    Objects.requireNonNull(expected, "expected Account original admission required");

    var wireRequest =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toRequest(toSelector(expected));
    var selector = CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.parseRequest(wireRequest);
    var response =
        requireInitializedStub("Original NEW final admission client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readOriginalNewFinalAdmission(wireRequest);
    final CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.FinalResult result;
    try {
      result =
          CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.fromResponse(selector, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Session returned invalid original final-admission evidence", malformed);
    }
    return new AuthenticatedFinalAdmissionReadback(
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.SESSION_SCHEMA_VERSION,
        "COMPLETE",
        expected,
        result.admissionAtMillis(),
        result.continuityExpiresAtMillis(),
        result.canonicalSessionDurationMillis(),
        result.canonicalSessionSha256());
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
  protected OriginalNewFinalAdmissionReadServiceGrpc
          .OriginalNewFinalAdmissionReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return OriginalNewFinalAdmissionReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }

  static CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector toSelector(
      ExpectedOriginalAdmission expected) {
    return new CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector(
        expected.requestId(),
        expected.accountId(),
        expected.tenantId(),
        expected.sessionId(),
        expected.bindingDecisionId(),
        expected.originalLeaseId(),
        expected.originalLeaseFence(),
        expected.originalLeaseEvidenceSha256(),
        expected.tokenSha256(),
        expected.tokenGeneration(),
        expected.issuanceFence(),
        expected.bindingScopeSha256(),
        expected.membershipBaselineSha256(),
        expected.checkpointsSha256(),
        expected.rebindHandleSha256());
  }

  private static void requireWorkloadOnlyContext() {
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) {
      throw new IllegalStateException(
          "Original NEW final admission read requires a workload-only caller context");
    }
  }
}
