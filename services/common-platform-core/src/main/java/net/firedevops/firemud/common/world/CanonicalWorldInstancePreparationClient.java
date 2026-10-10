package net.firedevops.firemud.common.world;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstancePreparationServiceGrpc;

/** Explicit file-backed mTLS client for the unregistered canonical World preparation RPC. */
public final class CanonicalWorldInstancePreparationClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldCanonicalInstancePreparationServiceGrpc
            .WorldCanonicalInstancePreparationServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

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
    if (closed) {
      throw new IllegalStateException("Canonical World preparation client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Canonical World preparation client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Sends the exact committed association selector and validates World-owned lifecycle readback.
   */
  public WorldCanonicalInstanceLifecycleEvidence prepare(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request expectedLifecycleRequest) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(expectedLifecycleRequest, "expectedLifecycleRequest");
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
                CanonicalWorldInstancePreparationGrpcCodec.toRequest(request));
    try {
      return CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
          request, expectedLifecycleRequest, response);
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

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private WorldCanonicalInstancePreparationServiceGrpc
          .WorldCanonicalInstancePreparationServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Canonical World preparation client is not initialized and available");
    }
    return currentStub;
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
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Canonical World preparation requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Canonical World preparation requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Canonical World preparation requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Canonical World preparation " + label + " must be a readable file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Canonical World preparation " + label + " must be an existing readable file");
    }
  }
}
