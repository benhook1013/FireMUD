package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence.Request;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalPlayerAdmissionHoldServiceGrpc;

/**
 * Unregistered Account mTLS acquire/read consumer for the distinct World player-serving hold.
 * Returned evidence authenticates original hold readback only; it supplies no current Account
 * authority, admission, release, source finalization, or physical Account COMMIT proof.
 */
public final class WorldGameplayAdmissionHoldClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldCanonicalPlayerAdmissionHoldServiceGrpc
            .WorldCanonicalPlayerAdmissionHoldServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int MAX_RESPONSE_WIRE_BYTES =
      WorldCanonicalPlayerAdmissionHoldEvidence.MAX_EVIDENCE_BYTES + 512;
  private static final String CLIENT_UNAVAILABLE =
      "World player admission hold client is unavailable";
  private final String workloadNamespace;

  public WorldGameplayAdmissionHoldClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "World player admission hold"),
        channelFactory,
        WorldGameplayAdmissionHoldClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Account workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    requireNoAmbientSql(
        "World player admission hold initialization requires no ambient SQL transaction");
    initOnce(
        "World player admission hold client is closed",
        "World player admission hold client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected WorldCanonicalPlayerAdmissionHoldServiceGrpc
          .WorldCanonicalPlayerAdmissionHoldServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expected = "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldCanonicalPlayerAdmissionHoldServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(
                channel, new GrpcServerPeerIdentityClientInterceptor(expected)))
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expected))
        .withMaxInboundMessageSize(MAX_RESPONSE_WIRE_BYTES)
        .withCompression("gzip");
  }

  /** Acquires the original lease-bound hold; no retry creates or extends an admission lease. */
  public WorldCanonicalPlayerAdmissionHoldEvidence acquire(Request original) {
    requireCall(original);
    UUID correlation = freshCorrelation(original);
    var request =
        AcquireCanonicalPlayerAdmissionHoldRequest.newBuilder()
            .setRequestId(correlation.toString())
            .setOriginalLeaseJson(leaseBytes(original))
            .setOriginalLeaseSha256(original.lease().sha256())
            .setExpectedLifecycleEpoch(Long.toString(original.expectedLifecycleEpoch()))
            .setExpectedRowVersion(Long.toString(original.expectedRowVersion()))
            .build();
    var response =
        withPublishedInitializedStub(
            CLIENT_UNAVAILABLE,
            client ->
                client
                    .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .acquireCanonicalPlayerAdmissionHold(request));
    if (response == null || !response.getUnknownFields().asMap().isEmpty()) throw invalid();
    return checked(
        original,
        correlation,
        response.getRequestId(),
        response.getOriginalLeaseSha256(),
        response.getHoldEvidenceJson(),
        response.getHoldEvidenceSha256());
  }

  /** Recovers authenticated original evidence; a missing response remains unresolved. */
  public WorldCanonicalPlayerAdmissionHoldEvidence read(Request original) {
    requireCall(original);
    UUID correlation = freshCorrelation(original);
    var request =
        ReadCanonicalPlayerAdmissionHoldRequest.newBuilder()
            .setRequestId(correlation.toString())
            .setOriginalLeaseJson(leaseBytes(original))
            .setOriginalLeaseSha256(original.lease().sha256())
            .setExpectedLifecycleEpoch(Long.toString(original.expectedLifecycleEpoch()))
            .setExpectedRowVersion(Long.toString(original.expectedRowVersion()))
            .build();
    var response =
        withPublishedInitializedStub(
            CLIENT_UNAVAILABLE,
            client ->
                client
                    .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .readCanonicalPlayerAdmissionHold(request));
    if (response == null || !response.getUnknownFields().asMap().isEmpty()) throw invalid();
    return checked(
        original,
        correlation,
        response.getRequestId(),
        response.getOriginalLeaseSha256(),
        response.getHoldEvidenceJson(),
        response.getHoldEvidenceSha256());
  }

  /** An already retained hold cannot be replaced by another valid hold for the same request. */
  public WorldCanonicalPlayerAdmissionHoldEvidence readExact(
      WorldCanonicalPlayerAdmissionHoldEvidence original) {
    Objects.requireNonNull(original, "Original World hold is required");
    var observed = read(original.request());
    if (!original.holdId().equals(observed.holdId())
        || !original.holdFence().equals(observed.holdFence())
        || !Arrays.equals(original.canonicalBytes(), observed.canonicalBytes())) throw invalid();
    return observed;
  }

  private void requireCall(Request original) {
    requireNoAmbientSql("World hold requires no ambient Account transaction or synchronization");
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) {
      throw new IllegalStateException("World hold requires a workload-only caller context");
    }
    Objects.requireNonNull(original, "Original Account lease request is required");
    if (!workloadNamespace.equals(original.targetNamespace())) {
      throw new IllegalArgumentException(
          "Original lease does not match configured workload namespace");
    }
  }

  private static UUID freshCorrelation(Request original) {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (id.equals(original.attemptId()) || id.equals(original.leaseId()));
    return id;
  }

  private static ByteString leaseBytes(Request original) {
    return ByteString.copyFrom(original.lease().canonicalJson(), StandardCharsets.UTF_8);
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence checked(
      Request original,
      UUID correlation,
      String echo,
      String leaseSha,
      ByteString bytes,
      String sha) {
    try {
      if (!correlation.toString().equals(echo)
          || !original.lease().sha256().equals(leaseSha)
          || bytes.isEmpty()
          || bytes.size() > WorldCanonicalPlayerAdmissionHoldEvidence.MAX_EVIDENCE_BYTES)
        throw invalid();
      var result =
          WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(bytes.toByteArray(), sha);
      if (!original.sameBinding(result.request())
          || !original.lease().canonicalJson().equals(result.request().lease().canonicalJson()))
        throw invalid();
      original.requireExactActiveWorld(result.worldEvidence());
      return result;
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("World admission hold response is invalid", failure);
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("World admission hold response is invalid or unresolved");
  }
}
