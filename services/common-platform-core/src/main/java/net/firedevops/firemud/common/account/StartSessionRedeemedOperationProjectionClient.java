package net.firedevops.firemud.common.account;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;

/** Explicit mTLS client for Account's read-only redeemed StartSession operation projection. */
public final class StartSessionRedeemedOperationProjectionClient
    extends AbstractReloadingBlockingGrpcClient<
        StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private final Clock clock;
  private volatile boolean initialized;
  private volatile boolean closed;

  public StartSessionRedeemedOperationProjectionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    this(endpoints, tlsProperties, channelFactory, workloadNamespace, Clock.systemUTC());
  }

  StartSessionRedeemedOperationProjectionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        StartSessionRedeemedOperationProjectionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("StartSession projection client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("StartSession projection client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Reads Account's immutable projection for the exact previously redeemed owner attempt.
   *
   * <p>This call sends no JWT or opaque authorization reference and does not retry, renew, mutate,
   * or authorize owner execution.
   */
  public ReadRedeemedOperationProjectionResponse read(
      StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple,
      UUID ownerAttemptId,
      long ownerFence) {
    Objects.requireNonNull(postAuthorizationTuple, "post-authorization tuple is required");
    requireNonNil(ownerAttemptId, "ownerAttemptId");
    if (ownerFence <= 0L) {
      throw new IllegalArgumentException("ownerFence must be positive");
    }

    StartSessionPreAuthorizationReservationTuple preTuple =
        postAuthorizationTuple.preAuthorizationTuple();
    String targetNamespace = preTuple.action().scope().targetNamespace();
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "StartSession projection must use the configured workload namespace");
    }

    byte[] canonicalPreTuple = preTuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    ReadRedeemedOperationProjectionRequest request =
        ReadRedeemedOperationProjectionRequest.newBuilder()
            .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(canonicalPreTuple))
            .setAuthorizationReferenceFingerprint(
                postAuthorizationTuple.authorizationReferenceFingerprint())
            .setReservationOwnerId(postAuthorizationTuple.reservationOwnerId().toString())
            .setReservationClaimFence(postAuthorizationTuple.reservationClaimFence())
            .setOwnerAttemptId(ownerAttemptId.toString())
            .setOwnerFence(ownerFence)
            .build();

    ReadRedeemedOperationProjectionResponse response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readRedeemedOperationProjection(request);
    try {
      validateResponse(
          postAuthorizationTuple, ownerAttemptId, ownerFence, canonicalPreTuple, response);
      return response;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned an invalid redeemed StartSession operation projection", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected StartSessionOperatorAuthorizationServiceGrpc
          .StartSessionOperatorAuthorizationServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return StartSessionOperatorAuthorizationServiceGrpc.newBlockingStub(channel)
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

  private StartSessionOperatorAuthorizationServiceGrpc
          .StartSessionOperatorAuthorizationServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "StartSession projection client is not initialized and available");
    }
    return currentStub;
  }

  private void validateResponse(
      StartSessionPostAuthorizationExecutionTuple postTuple,
      UUID ownerAttemptId,
      long ownerFence,
      byte[] canonicalPreTuple,
      ReadRedeemedOperationProjectionResponse response) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasBundleReference()
        || !response.hasReferenceExpiresAt()
        || !response.hasRedeemedAt()
        || !response.getBundleReference().getUnknownFields().asMap().isEmpty()
        || !response.getReferenceExpiresAt().getUnknownFields().asMap().isEmpty()
        || !response.getRedeemedAt().getUnknownFields().asMap().isEmpty()) {
      throw invalid("projection response contains unknown or missing fields");
    }

    StartSessionPreAuthorizationReservationTuple preTuple = postTuple.preAuthorizationTuple();
    if (!preTuple.controlPlaneRequestId().equals(response.getControlPlaneRequestId())
        || !MessageDigest.isEqual(
            canonicalPreTuple, response.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        || !preTuple.mutationDigest().equals(response.getMutationDigest())
        || !postTuple
            .authorizationReferenceFingerprint()
            .equals(response.getAuthorizationReferenceFingerprint())
        || !postTuple.reservationOwnerId().toString().equals(response.getReservationOwnerId())
        || postTuple.reservationClaimFence() != response.getReservationClaimFence()
        || !ownerAttemptId.toString().equals(response.getOwnerAttemptId())
        || ownerFence != response.getOwnerFence()) {
      throw invalid("projection differs from the exact original operation or owner attempt");
    }
    if (response.getReservationClaimFence() <= 0L || response.getOwnerFence() <= 0L) {
      throw invalid("projection fences must be positive");
    }

    String expectedRedeemerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    GrpcPeerIdentity redeemer =
        GrpcPeerIdentity.parseUri(response.getAuthenticatedRedeemerWorkloadIdentity())
            .orElseThrow(() -> invalid("projection redeemer identity is invalid"));
    if (!expectedRedeemerUri.equals(redeemer.uri())
        || !workloadNamespace.equals(redeemer.namespace())
        || !"game-session-service".equals(redeemer.service())) {
      throw invalid("projection redeemer is not the exact same-namespace Game Session workload");
    }

    UUID issuanceOperationId = requireCanonicalNonNilUuid(response.getIssuanceOperationId());
    if (response.getIssuanceFence() <= 0L) {
      throw invalid("projection issuance fence must be positive");
    }

    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(
            response.getAuthorityEvidenceBundle().toByteArray());
    if (!MessageDigest.isEqual(postTuple.authorityEvidenceBundleBytes(), bundle.canonicalBytes())) {
      throw invalid("projection authority bundle changed from the original Account response");
    }
    bundle.requireTupleBinding(preTuple);
    bundle.requireReferenceBinding(postTuple.bundleReference());
    if (!issuanceOperationId.equals(bundle.issuanceOperationId())
        || !Long.toString(response.getIssuanceFence()).equals(bundle.issuanceFence())) {
      throw invalid("projection issuance identity differs from the original Account bundle");
    }

    var protoReference = response.getBundleReference();
    StartSessionAuthorityEvidenceBundle.BundleReference responseReference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            protoReference.getBundleVersion(),
            protoReference.getSourceVersion(),
            protoReference.getSourceFence(),
            protoReference.getLinearization());
    if (!postTuple.bundleReference().equals(responseReference)) {
      throw invalid("projection bundle reference differs from the original Account response");
    }

    Instant referenceExpiresAt = timestamp(response.getReferenceExpiresAt(), "referenceExpiresAt");
    Instant redeemedAt = timestamp(response.getRedeemedAt(), "redeemedAt");
    Instant originalExpiresAt;
    try {
      originalExpiresAt = Instant.parse(bundle.authorizationExpiresAt());
    } catch (RuntimeException malformed) {
      throw invalid("original Account bundle expiry is invalid");
    }
    if (!referenceExpiresAt.equals(originalExpiresAt)
        || !referenceExpiresAt.isAfter(redeemedAt)
        || !referenceExpiresAt.isAfter(clock.instant())) {
      throw invalid("projection reference is expired or differs from the original Account expiry");
    }
  }

  private static Instant timestamp(Timestamp value, String field) {
    if (value.getSeconds() <= 0L || value.getNanos() < 0 || value.getNanos() >= 1_000_000_000) {
      throw invalid(field + " timestamp is invalid");
    }
    try {
      return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    } catch (RuntimeException malformed) {
      throw invalid(field + " timestamp is outside the supported range");
    }
  }

  private static UUID requireCanonicalNonNilUuid(String value) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (RuntimeException malformed) {
      throw invalid("projection issuance operation ID is invalid");
    }
    if (parsed.equals(new UUID(0L, 0L)) || !parsed.toString().equals(value)) {
      throw invalid("projection issuance operation ID is not canonical and non-nil");
    }
    return parsed;
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(field + " must not be nil");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("StartSession projection reads require workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "StartSession projection reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "StartSession projection reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "StartSession projection " + label + " must be a readable file-backed path", malformed);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "StartSession projection " + label + " must be an existing readable file");
    }
  }
}
