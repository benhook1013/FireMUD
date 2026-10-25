package net.firedevops.firemud.worldmanagement.client;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.worldmanagement.tenant.CanonicalInitialPlayerLocationService;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for Entity's immutable preseeded-assignment receipt. */
public final class EntityPreseededActorAssignmentReceiptClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceBlockingStub>
    implements CanonicalInitialPlayerLocationService.EntityReceiptReader {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int MAX_WIRE_BYTES = 16 * 1024;
  private static final String ENTITY_SERVICE = "entity-management-service";
  private static final String WORLD_SERVICE = "world-management-service";
  private final String workloadNamespace;

  public EntityPreseededActorAssignmentReceiptClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireWorldMtls(tlsProperties, workloadNamespace),
        channelFactory,
        EntityPreseededActorAssignmentReceiptClient.class);
    this.workloadNamespace = workloadNamespace;
  }

  /** Opens the channel only when the unregistered World owner composition explicitly starts it. */
  public void init() throws SSLException, IOException {
    initOnce(
        "Entity assignment-receipt client is closed",
        "Entity assignment-receipt client has no gRPC stub");
  }

  /** Reads and validates the actual immutable Entity receipt outside any ambient World SQL. */
  @Override
  public ReadPreseededActorAssignmentReceiptResponse read(
      ReadPreseededActorAssignmentReceiptRequest request) {
    requireNoAmbientOwnerTransaction();
    requireRequest(request);
    ReadPreseededActorAssignmentReceiptResponse response =
        requireInitializedStub("Entity assignment-receipt client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readPreseededActorAssignmentReceipt(request);
    requireExactResponse(request, response);
    return response;
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected CanonicalPreseededActorAssignmentServiceGrpc
          .CanonicalPreseededActorAssignmentServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + ENTITY_SERVICE;
    return CanonicalPreseededActorAssignmentServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  private static CommonGrpcClientProperties requireWorldMtls(
      CommonGrpcClientProperties tlsProperties, String workloadNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    CommonGrpcClientProperties result =
        FileBackedGrpcMtlsPolicy.require(
            tlsProperties,
            "Entity assignment-receipt reads",
            "Entity assignment-receipt reads require file-backed workload mTLS",
            "Entity assignment-receipt reads require workload mTLS");
    requireExactWorldCertificate(result.getCertChain(), workloadNamespace);
    return result;
  }

  private static void requireExactWorldCertificate(String configuredPath, String namespace) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException("World workload certificate chain is required");
    }
    Path certificatePath;
    try {
      certificatePath = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("World workload certificate path is invalid", invalid);
    }
    if (!Files.isRegularFile(certificatePath) || !Files.isReadable(certificatePath)) {
      throw new IllegalArgumentException("World workload certificate chain must be readable");
    }
    try (InputStream input = Files.newInputStream(certificatePath)) {
      X509Certificate certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      String expectedUri = "spiffe://firemud/ns/" + namespace + "/sa/" + WORLD_SERVICE;
      if (GrpcPeerIdentity.fromCertificate(certificate)
          .filter(identity -> identity.uri().equals(expectedUri))
          .isEmpty()) {
        throw new IllegalArgumentException(
            "Entity receipt reads require the exact same-namespace World workload certificate");
      }
    } catch (IOException | CertificateException invalid) {
      throw new IllegalArgumentException(
          "World workload certificate chain must contain the exact workload identity", invalid);
    }
  }

  private static void requireRequest(ReadPreseededActorAssignmentReceiptRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_WIRE_BYTES
        || request.getSchemaVersion() != 1
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()
        || !request.getExpectedTarget().getUnknownFields().asMap().isEmpty()
        || !isCanonicalNonNilUuid(request.getAssignmentUuid())
        || !isCanonicalNonNilUuid(request.getCanonicalAccountUuid())
        || !isCanonicalNonNilUuid(request.getCharacterUuid())
        || !isCanonicalNonNilUuid(request.getPlacementOperationUuid())
        || !request.getIntentDigest().matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "A closed exact Entity assignment receipt selector is required");
    }
  }

  private static void requireExactResponse(
      ReadPreseededActorAssignmentReceiptRequest request,
      ReadPreseededActorAssignmentReceiptResponse response) {
    if (!PreseededActorAssignmentReceiptShape.matches(request, response)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Entity returned no exact committed preseeded assignment receipt")
          .asRuntimeException();
    }
  }

  private static boolean isCanonicalNonNilUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      return !parsed.equals(new UUID(0L, 0L)) && parsed.toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private void requireNoAmbientOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Entity assignment-receipt reads must run outside World SQL");
    }
  }
}
