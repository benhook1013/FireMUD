package net.firedevops.firemud.accountservice.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;

/** Caller-owned Account candidate for exact fresh-creation readback from Game Design. */
public final class GameDesignFreshTenantIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String workloadNamespace;

  public GameDesignFreshTenantIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireAccountMtls(tlsProps),
        channelFactory,
        GameDesignFreshTenantIdentityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Account workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes the normal Account workload TLS client when the owner explicitly enables it. */
  public void init() throws SSLException, IOException {
    initReloadingClient();
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
    return TenantIdentityServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Reads the exact operation for this namespace without invoking any retained-identity RPC. */
  public FreshTenantCreationEvidence resolveCreation(
      UUID creationRequestId, String expectedRequestDigest) {
    if (creationRequestId == null || NIL_UUID.equals(creationRequestId)) {
      throw new IllegalArgumentException("Canonical nonnil creation request ID is required");
    }
    if (!GameTenantCreationDigest.isDigest(expectedRequestDigest)) {
      throw new IllegalArgumentException("Canonical expected request digest is required");
    }

    ResolveFreshTenantCreationResponse response =
        stub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveFreshTenantCreation(
                ResolveFreshTenantCreationRequest.newBuilder()
                    .setCreationRequestId(creationRequestId.toString())
                    .setExpectedRequestDigest(expectedRequestDigest)
                    .build());

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
      throw new IllegalStateException("Game Design fresh tenant evidence is invalid", exception);
    }

    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !creationRequestId.equals(evidence.creationRequestId())
        || !expectedRequestDigest.equals(evidence.requestDigest())) {
      throw new IllegalStateException(
          "Game Design fresh tenant evidence does not match the exact request");
    }
    return evidence;
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

  private static CommonGrpcClientProperties requireAccountMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Account gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Fresh tenant identity reads require Account workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Fresh tenant identity reads require Account certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Fresh tenant identity reads require file-backed Account workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
