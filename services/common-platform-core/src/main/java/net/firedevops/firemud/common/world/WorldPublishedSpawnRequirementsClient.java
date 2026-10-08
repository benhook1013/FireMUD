package net.firedevops.firemud.common.world;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirementsReadServiceGrpc;

/**
 * Explicit file-backed mTLS client for immutable World published spawn-requirement source reads.
 */
public final class WorldPublishedSpawnRequirementsClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldPublishedSpawnRequirementsReadServiceGrpc
            .WorldPublishedSpawnRequirementsReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final String ENTITY_SERVICE = "entity-management-service";
  private static final String WORLD_SERVICE = "world-management-service";

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldPublishedSpawnRequirementsClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireEntityMtls(tlsProperties, workloadNamespace),
        channelFactory,
        WorldPublishedSpawnRequirementsClient.class);
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when the owning Entity service explicitly starts this opt-in client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World spawn-requirements client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("World spawn-requirements client has no gRPC stub");
    }
    initialized = true;
  }

  /** Reads and validates the complete immutable source evidence for one exact selector. */
  public WorldPublishedSpawnRequirementsEvidence read(
      WorldPublishedSpawnRequirementsEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "World spawn-requirements request must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readWorldPublishedSpawnRequirements(
                WorldPublishedSpawnRequirementsGrpcCodec.toRequest(request));
    try {
      return WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid published spawn-requirements evidence", invalid);
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
  protected WorldPublishedSpawnRequirementsReadServiceGrpc
          .WorldPublishedSpawnRequirementsReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + WORLD_SERVICE;
    return WorldPublishedSpawnRequirementsReadServiceGrpc.newBlockingStub(channel)
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

  private WorldPublishedSpawnRequirementsReadServiceGrpc
          .WorldPublishedSpawnRequirementsReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World spawn-requirements client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireEntityMtls(
      CommonGrpcClientProperties tlsProperties, String workloadNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("World spawn-requirements reads require workload mTLS");
    }
    Path certificatePath = requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    requireExactEntityCertificate(certificatePath, workloadNamespace);
    return tlsProperties;
  }

  private static Path requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "World spawn-requirements reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "World spawn-requirements reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World spawn-requirements " + label + " must be a readable file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "World spawn-requirements " + label + " must be an existing readable file");
    }
    return path;
  }

  private static void requireExactEntityCertificate(Path certificatePath, String namespace) {
    try (InputStream input = Files.newInputStream(certificatePath)) {
      X509Certificate certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      String expectedUri = "spiffe://firemud/ns/" + namespace + "/sa/" + ENTITY_SERVICE;
      if (GrpcPeerIdentity.fromCertificate(certificate)
          .filter(identity -> identity.uri().equals(expectedUri))
          .isEmpty()) {
        throw new IllegalArgumentException(
            "World spawn-requirements client certificate must be the exact same-namespace Entity workload identity");
      }
    } catch (IOException | CertificateException invalid) {
      throw new IllegalArgumentException(
          "World spawn-requirements client certificate must contain an exact Entity workload identity",
          invalid);
    }
  }
}
