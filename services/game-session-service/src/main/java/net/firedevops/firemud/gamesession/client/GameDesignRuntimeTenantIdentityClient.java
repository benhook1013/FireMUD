package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;

/** Unwired Game Session candidate for exact owner-local runtime tenant identity reads. */
public final class GameDesignRuntimeTenantIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int ED25519_SIGNATURE_BYTES = 64;

  private final String workloadNamespace;

  /** Validated owner evidence; it does not prove retained-row agreement or catalog admission. */
  public record LegacyGameSessionTenantAssociationReceipt(
      GameSessionTenantAssociationEvidence evidence,
      String manifestDigest,
      String ed25519Signature) {}

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
    return TenantIdentityServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service"))
        .withCompression("gzip");
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
    if (response == null) {
      throw new IllegalStateException("Game Design runtime tenant identity response is absent");
    }
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

  /**
   * Reads one exact approved retained Game Session association through the authenticated Game
   * Design workload channel. This validates owner evidence only; it does not inspect or persist
   * Game Session's retained rows, enroll a catalog entry, or grant admission.
   */
  public LegacyGameSessionTenantAssociationReceipt resolveLegacyGameSessionTenantAssociation(
      String canonicalTenantId,
      String operationId,
      String legacyGameSessionTenantId,
      String requestId) {
    UUID tenantUuid = parseCanonicalNonNilUuid(canonicalTenantId, "canonical tenant ID");
    UUID operationUuid = parseCanonicalNonNilUuid(operationId, "operation ID");
    String retainedTenantKey =
        parseCanonicalPositiveBigint(legacyGameSessionTenantId, "legacy Game Session tenant ID");
    UUID requestUuid = parseCanonicalNonNilUuid(requestId, "request ID");
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association client is not initialized");
    }

    ResolveLegacyGameSessionTenantAssociationResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveLegacyGameSessionTenantAssociation(
                ResolveLegacyGameSessionTenantAssociationRequest.newBuilder()
                    .setRequestId(requestUuid.toString())
                    .setOperationId(operationUuid.toString())
                    .setCanonicalTenantId(tenantUuid.toString())
                    .setLegacyGameSessionTenantId(retainedTenantKey)
                    .build());
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association response contains "
              + "unsupported fields");
    }
    if (!response.hasManifest()) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association response has no manifest");
    }
    UUID echoedRequestId;
    try {
      echoedRequestId = parseCanonicalNonNilUuid(response.getRequestId(), "response request ID");
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association response request echo is invalid",
          exception);
    }
    if (!requestUuid.equals(echoedRequestId)) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association response does not match "
              + "the exact request echo");
    }

    var manifest = response.getManifest();
    if (!manifest.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association manifest contains "
              + "unsupported fields");
    }
    GameSessionTenantAssociationEvidence evidence;
    try {
      evidence =
          new GameSessionTenantAssociationEvidence(
              manifest.getSchemaVersion(),
              parseCanonicalNonNilUuid(manifest.getOperationId(), "manifest operation ID"),
              manifest.getTargetNamespace(),
              manifest.getSignerKeyId(),
              manifest.getApprovedBy(),
              manifest.getApprovalReference(),
              manifest.getSignedAt(),
              manifest.getLegacyGameSessionTenantId(),
              parseCanonicalNonNilUuid(
                  manifest.getCanonicalTenantId(), "manifest canonical tenant ID"),
              manifest.getSourceGameRowId(),
              manifest.getSourceGameTenantKey(),
              manifest.getProvenanceKind(),
              manifest.getGameSessionEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association manifest is invalid", exception);
    }

    String signature = response.getEd25519Signature();
    byte[] signatureBytes;
    try {
      signatureBytes = Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association signature is invalid", exception);
    }
    if (signatureBytes.length != ED25519_SIGNATURE_BYTES
        || !Base64.getEncoder().encodeToString(signatureBytes).equals(signature)) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association signature is not canonical "
              + "Ed25519 base64");
    }

    String manifestDigest = response.getManifestDigest();
    if (!evidence.manifestDigest().equals(manifestDigest)) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association manifest digest does not match "
              + "its evidence");
    }
    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !operationUuid.equals(evidence.operationId())
        || !tenantUuid.equals(evidence.canonicalTenantId())
        || !retainedTenantKey.equals(evidence.legacyGameSessionTenantId())) {
      throw new IllegalStateException(
          "Game Design retained Game Session tenant association response does not match "
              + "the exact request");
    }
    return new LegacyGameSessionTenantAssociationReceipt(evidence, manifestDigest, signature);
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

  private static String parseCanonicalPositiveBigint(String value, String label) {
    if (value == null || value.length() > 19 || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be canonical positive BIGINT text");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(label + " must be canonical positive BIGINT text");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          label + " must fit a positive PostgreSQL BIGINT", exception);
    }
    return value;
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
