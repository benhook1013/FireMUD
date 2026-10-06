package net.firedevops.firemud.accountservice.client;

import io.grpc.ClientInterceptors;
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
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreatorQualificationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreatorQualificationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;

/** Caller-owned Account candidate for exact fresh-creation readback from Game Design. */
public final class GameDesignFreshTenantIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;

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
    this.serverPeerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(gameDesignServerPeerUri(workloadNamespace));
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
    return TenantIdentityServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(channel, serverPeerIdentityInterceptor))
        .withCompression("gzip");
  }

  private static String gameDesignServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
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

    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException("Game Design fresh tenant evidence contains unknown fields");
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

  /**
   * Reads the immutable Account initiator qualification for one exact source receipt.
   *
   * <p>This candidate consumer validates transport evidence only. It does not capture or
   * authenticate the initiating Account identity; the protected Account creation adapter remains
   * responsible for supplying an authorized binding.
   */
  public FreshTenantCreatorEvidence resolveCreatorQualification(
      UUID readRequestId,
      FreshTenantCreationEvidence expectedCreationEvidence,
      UUID initiatingAccountId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest) {
    if (readRequestId == null || NIL_UUID.equals(readRequestId)) {
      throw new IllegalArgumentException("Canonical nonnil creator read request ID is required");
    }
    if (expectedCreationEvidence == null
        || !workloadNamespace.equals(expectedCreationEvidence.targetNamespace())) {
      throw new IllegalArgumentException(
          "Exact fresh tenant source evidence for the configured namespace is required");
    }
    if (initiatingAccountId == null || NIL_UUID.equals(initiatingAccountId)) {
      throw new IllegalArgumentException("Canonical nonnil initiating Account UUID is required");
    }
    if (accountAuthorizationOperationId == null
        || NIL_UUID.equals(accountAuthorizationOperationId)) {
      throw new IllegalArgumentException(
          "Canonical nonnil Account authorization operation UUID is required");
    }
    if (!GameTenantCreationDigest.isDigest(accountAuthorizationDigest)) {
      throw new IllegalArgumentException("Canonical Account authorization digest is required");
    }
    String expectedCreatorEvidenceDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            1,
            expectedCreationEvidence,
            initiatingAccountId,
            accountAuthorizationOperationId,
            accountAuthorizationDigest);

    ResolveFreshTenantCreatorQualificationResponse response =
        stub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveFreshTenantCreatorQualification(
                ResolveFreshTenantCreatorQualificationRequest.newBuilder()
                    .setReadRequestId(readRequestId.toString())
                    .setCreationRequestId(expectedCreationEvidence.creationRequestId().toString())
                    .setExpectedRequestDigest(expectedCreationEvidence.requestDigest())
                    .setExpectedEvidenceDigest(expectedCreationEvidence.evidenceDigest())
                    .setInitiatingAccountId(initiatingAccountId.toString())
                    .setAccountAuthorizationOperationId(accountAuthorizationOperationId.toString())
                    .setAccountAuthorizationDigest(accountAuthorizationDigest)
                    .setExpectedCreatorEvidenceDigest(expectedCreatorEvidenceDigest)
                    .build());

    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasCreationEvidence()
        || !response.hasCreatorQualification()) {
      throw new IllegalStateException(
          "Game Design creator qualification response is not the exact closed schema");
    }
    if (!response.getCreationEvidence().getUnknownFields().asMap().isEmpty()
        || !response.getCreatorQualification().getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException("Game Design creator qualification contains unknown fields");
    }
    if (!readRequestId.toString().equals(response.getReadRequestId())) {
      throw new IllegalStateException("Game Design did not echo the exact creator read identity");
    }

    FreshTenantCreationEvidence actualCreationEvidence;
    try {
      ResolveFreshTenantCreationResponse source = response.getCreationEvidence();
      actualCreationEvidence =
          new FreshTenantCreationEvidence(
              source.getSchemaVersion(),
              source.getTargetNamespace(),
              parseCanonicalNonNilUuid(source.getCreationRequestId()),
              parseCanonicalNonNilUuid(source.getOperationId()),
              source.getRequestDigest(),
              parseCanonicalNonNilUuid(source.getCanonicalTenantId()),
              source.getSourceGameRowId(),
              source.getSourceGameTenantKey(),
              source.getProvenanceKind(),
              source.getEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Game Design source creation evidence is invalid", exception);
    }
    if (!expectedCreationEvidence.equals(actualCreationEvidence)) {
      throw new IllegalStateException(
          "Game Design creator qualification changed the original source evidence");
    }

    FreshTenantCreatorEvidence qualification;
    try {
      var wireQualification = response.getCreatorQualification();
      qualification =
          new FreshTenantCreatorEvidence(
              wireQualification.getSchemaVersion(),
              actualCreationEvidence,
              parseCanonicalNonNilUuid(wireQualification.getInitiatingAccountId()),
              parseCanonicalNonNilUuid(wireQualification.getAccountAuthorizationOperationId()),
              wireQualification.getAccountAuthorizationDigest(),
              wireQualification.getEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design creator qualification evidence is invalid", exception);
    }
    if (!initiatingAccountId.equals(qualification.initiatingAccountId())
        || !accountAuthorizationOperationId.equals(qualification.accountAuthorizationOperationId())
        || !accountAuthorizationDigest.equals(qualification.accountAuthorizationDigest())
        || !expectedCreatorEvidenceDigest.equals(qualification.evidenceDigest())) {
      throw new IllegalStateException(
          "Game Design creator qualification does not match the exact Account binding");
    }
    return qualification;
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
