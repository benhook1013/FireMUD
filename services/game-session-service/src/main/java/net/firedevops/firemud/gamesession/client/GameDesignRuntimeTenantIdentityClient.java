package net.firedevops.firemud.gamesession.client;

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
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;

/** Unwired Game Session candidate for exact owner-local runtime tenant identity reads. */
public final class GameDesignRuntimeTenantIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GameDesignRuntimeTenantIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProps),
        channelFactory,
        GameDesignRuntimeTenantIdentityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes the client only when an explicit caller owns activation of this handoff. */
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

  /** Reads persisted Game Design identity and validates every returned owner field. */
  public RuntimeTenantIdentityEvidence resolveRuntimeTenantIdentity(
      String canonicalTenantId, String requestId) {
    UUID tenantUuid = parseCanonicalNonNilUuid(canonicalTenantId, "canonical tenant ID");
    UUID requestUuid = parseCanonicalNonNilUuid(requestId, "request ID");
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException(
          "Game Design runtime tenant identity client is not initialized");
    }

    ResolveRuntimeTenantIdentityResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveRuntimeTenantIdentity(
                ResolveRuntimeTenantIdentityRequest.newBuilder()
                    .setCanonicalTenantId(tenantUuid.toString())
                    .setRequestId(requestUuid.toString())
                    .build());
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException(
          "Game Design runtime tenant identity response contains unsupported fields");
    }

    RuntimeTenantIdentityEvidence evidence;
    try {
      evidence =
          new RuntimeTenantIdentityEvidence(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(response.getRequestId(), "response request ID"),
              parseCanonicalNonNilUuid(
                  response.getCanonicalTenantId(), "response canonical tenant ID"),
              response.getSourceGameRowId(),
              response.getSourceGameTenantKey(),
              response.getProvenanceKind());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design runtime tenant identity response is invalid", exception);
    }

    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !requestUuid.equals(evidence.requestId())
        || !tenantUuid.equals(evidence.canonicalTenantId())) {
      throw new IllegalStateException(
          "Game Design runtime tenant identity response does not match the exact request");
    }
    return evidence;
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required", exception);
    }
    if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Game Session gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Runtime tenant identity reads require Game Session workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Runtime tenant identity reads require Game Session certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Runtime tenant identity reads require file-backed Game Session workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
