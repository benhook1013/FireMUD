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
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.worldmanagement.tenant.CanonicalInitialPlayerLocationService;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for Entity's historical PLAYER_CREATED placement receipt. */
public final class EntityPlayerCreatedActorPlacementReceiptClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
            .CanonicalPlayerCreatedRuntimeReferenceServiceBlockingStub>
    implements CanonicalInitialPlayerLocationService.EntityCreatedReceiptReader {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int MAX_WIRE_BYTES = 8 * 1024;
  private static final String ENTITY_SERVICE = "entity-management-service";
  private static final String WORLD_SERVICE = "world-management-service";
  private final String workloadNamespace;

  public EntityPlayerCreatedActorPlacementReceiptClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireWorldMtls(tlsProperties, workloadNamespace),
        channelFactory,
        EntityPlayerCreatedActorPlacementReceiptClient.class);
    this.workloadNamespace = workloadNamespace;
  }

  /** Opens the channel only when the unregistered World owner composition explicitly starts it. */
  public void init() throws SSLException, IOException {
    initOnce(
        "Entity created-placement-receipt client is closed",
        "Entity created-placement-receipt client has no gRPC stub");
  }

  /** Reads and validates the actual immutable Entity receipt outside any ambient World SQL. */
  @Override
  public VerifiedReceipt read(PlayerCreatedActorRuntimeReferenceRequest request) {
    requireNoAmbientOwnerTransaction();
    CanonicalPlayerCreatedRuntimeReferenceEvidence.requireRequest(request);
    if (!workloadNamespace.equals(request.getOrigin().getTargetNamespace())) {
      throw new IllegalArgumentException("Creation origin must use the World workload namespace");
    }
    PlayerCreatedActorRuntimeReferenceResponse response =
        requireInitializedStub(
                "Entity created-placement-receipt client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readPlayerCreatedActorPlacementReceipt(request);
    try {
      CanonicalPlayerCreatedRuntimeReferenceEvidence.requireResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Entity returned no exact creation receipt")
          .withCause(invalid)
          .asRuntimeException();
    }
    return new VerifiedReceipt(response);
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
  protected CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
          .CanonicalPlayerCreatedRuntimeReferenceServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + ENTITY_SERVICE;
    return CanonicalPlayerCreatedRuntimeReferenceServiceGrpc.newBlockingStub(channel)
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
            "Entity created-placement-receipt reads",
            "Entity created-placement-receipt reads require file-backed workload mTLS",
            "Entity created-placement-receipt reads require workload mTLS");
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

  /** Constructible only after the authenticated exact-purpose transport read succeeds. */
  public static final class VerifiedReceipt {
    private final PlayerCreatedActorRuntimeReferenceResponse response;

    private VerifiedReceipt(PlayerCreatedActorRuntimeReferenceResponse response) {
      this.response = response;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Generated protobuf responses are immutable values")
    public PlayerCreatedActorRuntimeReferenceResponse response() {
      return response;
    }
  }

  private void requireNoAmbientOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Entity created-placement-receipt reads must run outside World SQL");
    }
  }
}
