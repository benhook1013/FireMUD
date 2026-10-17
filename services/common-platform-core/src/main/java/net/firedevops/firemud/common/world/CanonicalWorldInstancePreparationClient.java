package net.firedevops.firemud.common.world;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalWorldInstancePreparationGrpcCodec.OriginalOperationInput;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstancePreparationServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for the unregistered canonical World preparation RPC. */
public final class CanonicalWorldInstancePreparationClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldCanonicalInstancePreparationServiceGrpc
            .WorldCanonicalInstancePreparationServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public CanonicalWorldInstancePreparationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        CanonicalWorldInstancePreparationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    initOnce(
        "Canonical World preparation client is closed",
        "Canonical World preparation client has no gRPC stub");
  }

  /** Sends the exact owner operation outside SQL and validates the full request/readback echo. */
  public WorldCanonicalInstanceLifecycleEvidence prepare(
      Request request,
      OriginalOperationInput operationInput,
      WorldCanonicalInstanceLifecycleEvidence.Request expectedLifecycleRequest) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(operationInput, "operationInput");
    Objects.requireNonNull(expectedLifecycleRequest, "expectedLifecycleRequest");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical World preparation must acquire Account participation outside SQL");
    }
    if (!workloadNamespace.equals(request.targetNamespace())
        || !workloadNamespace.equals(expectedLifecycleRequest.targetNamespace())) {
      throw new IllegalArgumentException(
          "Canonical preparation requests must use the configured workload namespace");
    }
    requireSameSelector(request, expectedLifecycleRequest);

    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .prepareCanonicalWorldInstance(
                CanonicalWorldInstancePreparationGrpcCodec.toRequest(request, operationInput));
    try {
      return CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
          request, operationInput, expectedLifecycleRequest, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid canonical instance preparation evidence", invalid);
    }
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
  protected WorldCanonicalInstancePreparationServiceGrpc
          .WorldCanonicalInstancePreparationServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldCanonicalInstancePreparationServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  private WorldCanonicalInstancePreparationServiceGrpc
          .WorldCanonicalInstancePreparationServiceBlockingStub
      requireStub() {
    return requireInitializedStub(
        "Canonical World preparation client is not initialized and available");
  }

  private static void requireSameSelector(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    if (!request.readRequestId().equals(lifecycleRequest.readRequestId())
        || !request.targetNamespace().equals(lifecycleRequest.targetNamespace())
        || !request.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        || !request.worldSlug().equals(lifecycleRequest.worldSlug())
        || !request.gameInstanceUuid().equals(lifecycleRequest.canonicalGameInstanceId())
        || !request.controlPlaneRequestId().equals(lifecycleRequest.controlPlaneRequestId())
        || !request
            .expectedDescriptorRequestDigest()
            .equals(lifecycleRequest.expectedDescriptorRequestDigest())
        || !request
            .expectedDescriptorResultDigest()
            .equals(lifecycleRequest.expectedDescriptorResultDigest())
        || !request
            .expectedReleaseAttestationEvidenceDigest()
            .equals(lifecycleRequest.expectedReleaseAttestationDigest())) {
      throw new IllegalArgumentException(
          "Canonical preparation selector differs from the expected World lifecycle request");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Canonical World preparation",
        "Canonical World preparation requires workload mTLS",
        "Canonical World preparation requires workload mTLS");
  }
}
