package net.firedevops.firemud.gamesession.client;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.io.IOException;
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
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Game Session workload client for exact Game Design fresh-creation readback. */
public final class GameDesignFreshTenantCreationClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GameDesignFreshTenantCreationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProperties),
        channelFactory,
        GameDesignFreshTenantCreationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.serverPeerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(gameDesignServerPeerUri(workloadNamespace));
  }

  /** Initializes this client only when the owning Game Session composition explicitly uses it. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Game Design fresh-tenant client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Game Design fresh-tenant client has no gRPC stub");
    }
    initialized = true;
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub buildStub(
      ManagedChannel channel) {
    String expectedPeerUri = gameDesignServerPeerUri(workloadNamespace);
    return TenantIdentityServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(channel, serverPeerIdentityInterceptor))
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
      initialized = false;
    }
    super.close();
  }

  private TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub requireStub() {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Game Design fresh-tenant client is not initialized and available");
    }
    return currentStub;
  }

  /**
   * Reads one immutable receipt from Game Design without opening or joining a Game Session SQL
   * transaction. Game Design independently admits only the same-namespace Game Session mTLS peer.
   */
  public FreshTenantCreationEvidence resolveCreation(
      UUID creationRequestId, String expectedRequestDigest) {
    var currentStub = requireStub();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Design fresh-tenant evidence must be resolved outside a Game Session transaction");
    }
    if (creationRequestId == null || NIL_UUID.equals(creationRequestId)) {
      throw new IllegalArgumentException("Canonical nonnil creation request ID is required");
    }
    if (!GameTenantCreationDigest.isDigest(expectedRequestDigest)) {
      throw new IllegalArgumentException("Canonical expected request digest is required");
    }

    ResolveFreshTenantCreationResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveFreshTenantCreation(
                ResolveFreshTenantCreationRequest.newBuilder()
                    .setCreationRequestId(creationRequestId.toString())
                    .setExpectedRequestDigest(expectedRequestDigest)
                    .build());
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalidOwnerEvidence("Game Design fresh tenant evidence contains unknown fields");
    }

    FreshTenantCreationEvidence evidence;
    try {
      evidence =
          new FreshTenantCreationEvidence(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(response.getCreationRequestId()),
              parseCanonicalNonNilUuid(response.getOperationId()),
              response.getRequestDigest(),
              parseCanonicalNonNilUuid(response.getCanonicalTenantId()),
              response.getSourceGameRowId(),
              response.getSourceGameTenantKey(),
              response.getProvenanceKind(),
              response.getEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw invalidOwnerEvidence("Game Design fresh tenant evidence is invalid", exception);
    }

    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !creationRequestId.equals(evidence.creationRequestId())
        || !expectedRequestDigest.equals(evidence.requestDigest())) {
      throw invalidOwnerEvidence(
          "Game Design fresh tenant evidence differs from the exact request");
    }
    return evidence;
  }

  private static String gameDesignServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
  }

  private static UUID parseCanonicalNonNilUuid(String value) {
    if (value == null) {
      throw new IllegalArgumentException("UUID evidence is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("UUID evidence must be canonical lowercase", exception);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("UUID evidence must be canonical and nonnil");
    }
    return parsed;
  }

  private static IllegalStateException invalidOwnerEvidence(String message) {
    return new IllegalStateException(message);
  }

  private static IllegalStateException invalidOwnerEvidence(String message, Throwable cause) {
    return new IllegalStateException(message, cause);
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Fresh tenant identity reads",
        "Game Session gRPC TLS configuration is required",
        "Fresh tenant identity reads require Game Session workload mTLS");
  }
}
