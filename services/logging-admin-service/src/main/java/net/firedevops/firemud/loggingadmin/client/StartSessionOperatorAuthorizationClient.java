package net.firedevops.firemud.loggingadmin.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;

/**
 * Explicitly initialized, typed Logging & Admin client for Account's StartSession issue/recover
 * boundary. This class does not activate a route or authorize a mutation; callers must establish
 * the current Logging reservation claim before calling it.
 */
public class StartSessionOperatorAuthorizationClient
    extends AbstractReloadingBlockingGrpcClient<
        StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub> {
  private static final int MAX_TUPLE_BYTES = 8 * 1_024;
  private static final int MAX_CONTROL_UI_TOKEN_BYTES = 16 * 1_024;
  private static final int MAX_REFERENCE_BYTES = 128;
  private static final long RPC_DEADLINE_SECONDS = 5L;
  private static final long MAX_PROTO_TIMESTAMP_SECONDS = 253_402_300_799L;
  private static final Pattern OPAQUE_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern REFERENCE_FINGERPRINT =
      Pattern.compile("arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}");

  private final Clock clock;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Construction validates and copies inputs only; managed channels and watchers are acquired by initialize().")
  public StartSessionOperatorAuthorizationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    this(endpoints, tlsProps, channelFactory, stubCustomizer, Clock.systemUTC());
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Construction validates and copies inputs only; managed channels and watchers are acquired by initialize().")
  public StartSessionOperatorAuthorizationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      Clock clock) {
    super(
        endpoints,
        tlsProps,
        channelFactory,
        stubCustomizer,
        StartSessionOperatorAuthorizationClient.class);
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /**
   * Opens the standard managed TLS channel only when an owning workflow explicitly initializes it.
   */
  public void initialize() throws SSLException, IOException {
    initReloadingClient();
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
      buildStub(io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        StartSessionOperatorAuthorizationServiceGrpc.newBlockingStub(channel)
            .withCompression("gzip"));
  }

  /** Issues the original human tenant-admin reference using the current Logging claim tuple. */
  public AuthorizationReference issueHuman(
      StartSessionPreAuthorizationReservationTuple tuple,
      String controlUiJwt,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence) {
    byte[] tupleBytes = canonicalTupleBytes(tuple);
    requireVisibleAsciiToken(controlUiJwt);
    requireOwnerFence(reservationOwnerId, reservationClaimFence, "reservation owner");
    requireOwnerFence(currentClaimOwnerId, currentClaimFence, "current claim owner");
    IssueHumanOperatorAuthorizationReferenceRequest request =
        IssueHumanOperatorAuthorizationReferenceRequest.newBuilder()
            .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
            .setControlUiJwt(controlUiJwt)
            .setReservationOwnerId(reservationOwnerId.toString())
            .setReservationClaimFence(reservationClaimFence)
            .setCurrentClaimOwnerId(currentClaimOwnerId.toString())
            .setCurrentClaimFence(currentClaimFence)
            .build();
    IssueHumanOperatorAuthorizationReferenceResponse response =
        requireStub()
            .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .issueHumanOperatorAuthorizationReference(request);
    return validateResponse(
        response.getOperatorAuthorizationReference(),
        response.getAuthorizationReferenceFingerprint(),
        response.hasExpiresAt(),
        response.getExpiresAt(),
        response.getAuthorityEvidenceBundle().toByteArray(),
        response.hasBundleReference(),
        response.getBundleReference(),
        response.getUnknownFields().asMap().isEmpty(),
        response.getAuthenticatedLoggingWorkloadIdentity(),
        tuple);
  }

  /** Looks up and recovers only the exact original issuance response under a fresh claim. */
  public AuthorizationReference recover(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence) {
    byte[] tupleBytes = canonicalTupleBytes(tuple);
    requireOwnerFence(reservationOwnerId, reservationClaimFence, "reservation owner");
    requireOwnerFence(currentClaimOwnerId, currentClaimFence, "current claim owner");
    RecoverOperatorAuthorizationReferenceRequest request =
        RecoverOperatorAuthorizationReferenceRequest.newBuilder()
            .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
            .setReservationOwnerId(reservationOwnerId.toString())
            .setReservationClaimFence(reservationClaimFence)
            .setCurrentClaimOwnerId(currentClaimOwnerId.toString())
            .setCurrentClaimFence(currentClaimFence)
            .build();
    RecoverOperatorAuthorizationReferenceResponse response =
        requireStub()
            .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .recoverOperatorAuthorizationReference(request);
    return validateResponse(
        response.getOperatorAuthorizationReference(),
        response.getAuthorizationReferenceFingerprint(),
        response.hasExpiresAt(),
        response.getExpiresAt(),
        response.getAuthorityEvidenceBundle().toByteArray(),
        response.hasBundleReference(),
        response.getBundleReference(),
        response.getUnknownFields().asMap().isEmpty(),
        response.getAuthenticatedLoggingWorkloadIdentity(),
        tuple);
  }

  private StartSessionOperatorAuthorizationServiceGrpc
          .StartSessionOperatorAuthorizationServiceBlockingStub
      requireStub() {
    var value = stub();
    if (value == null) {
      throw new IllegalStateException("Account authorization client has not been initialized");
    }
    return value;
  }

  private AuthorizationReference validateResponse(
      String opaqueReference,
      String fingerprint,
      boolean hasExpiry,
      Timestamp expiryTimestamp,
      byte[] authorityEvidenceBundle,
      boolean hasBundleReference,
      AuthorityEvidenceBundleReference wireReference,
      boolean hasNoUnknownFields,
      String authenticatedLoggingWorkloadIdentity,
      StartSessionPreAuthorizationReservationTuple tuple) {
    try {
      if (!hasNoUnknownFields || !hasExpiry || !hasBundleReference) {
        throw malformedResponse();
      }
      requireAuthenticatedLoggingIdentity(
          authenticatedLoggingWorkloadIdentity, tuple.action().scope().targetNamespace());
      if (!OPAQUE_REFERENCE.matcher(opaqueReference).matches()
          || opaqueReference.getBytes(StandardCharsets.UTF_8).length > MAX_REFERENCE_BYTES
          || !REFERENCE_FINGERPRINT.matcher(fingerprint).matches()) {
        throw malformedResponse();
      }
      if (!expiryTimestamp.getUnknownFields().asMap().isEmpty()) {
        throw malformedResponse();
      }
      Instant expiresAt = exactExpiry(expiryTimestamp);
      if (!expiresAt.isAfter(clock.instant())) {
        throw new IllegalStateException("Account authorization response is expired");
      }
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(authorityEvidenceBundle);
      bundle.requireTupleBinding(tuple);
      if (!expiresAt.equals(bundle.expiresAt())) {
        throw malformedResponse();
      }
      StartSessionAuthorityEvidenceBundle.BundleReference reference =
          new StartSessionAuthorityEvidenceBundle.BundleReference(
              wireReference.getBundleVersion(),
              wireReference.getSourceVersion(),
              wireReference.getSourceFence(),
              wireReference.getLinearization());
      bundle.requireReferenceBinding(reference);
      if (!wireReference.getUnknownFields().asMap().isEmpty()) {
        throw malformedResponse();
      }
      return new AuthorizationReference(
          opaqueReference,
          fingerprint,
          expiresAt,
          authorityEvidenceBundle,
          reference,
          authenticatedLoggingWorkloadIdentity,
          bundle);
    } catch (IllegalStateException expected) {
      throw expected;
    } catch (RuntimeException malformed) {
      throw malformedResponse();
    }
  }

  private static Instant exactExpiry(Timestamp timestamp) {
    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();
    if (seconds <= 0L
        || seconds > MAX_PROTO_TIMESTAMP_SECONDS
        || nanos < 0
        || nanos >= 1_000_000_000
        || nanos % 1_000_000 != 0) {
      throw malformedResponse();
    }
    return Instant.ofEpochSecond(seconds, nanos);
  }

  private static void requireAuthenticatedLoggingIdentity(
      String identityText, String targetNamespace) {
    GrpcPeerIdentity identity =
        GrpcPeerIdentity.parseUri(identityText)
            .orElseThrow(StartSessionOperatorAuthorizationClient::malformedResponse);
    if (!identityText.equals(identity.uri())
        || !"logging-admin-service".equals(identity.service())
        || !targetNamespace.equals(identity.namespace())) {
      throw malformedResponse();
    }
  }

  private static byte[] canonicalTupleBytes(StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    byte[] bytes = tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0 || bytes.length > MAX_TUPLE_BYTES) {
      throw new IllegalArgumentException("StartSession tuple exceeds its supported bound");
    }
    return bytes;
  }

  private static void requireVisibleAsciiToken(String token) {
    if (token == null
        || token.isEmpty()
        || token.getBytes(StandardCharsets.UTF_8).length > MAX_CONTROL_UI_TOKEN_BYTES
        || token.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
      throw new IllegalArgumentException("control-ui token is invalid or over its supported bound");
    }
  }

  private static void requireOwnerFence(UUID owner, long fence, String field) {
    if (owner == null || owner.equals(new UUID(0L, 0L)) || fence <= 0L) {
      throw new IllegalArgumentException(field + " and positive fence are required");
    }
  }

  private static IllegalStateException malformedResponse() {
    return new IllegalStateException(
        "Account returned a malformed StartSession authorization response");
  }

  /** Response identity with defensive bundle bytes and a redacted diagnostic representation. */
  public static final class AuthorizationReference {
    private final String operatorAuthorizationReference;
    private final String authorizationReferenceFingerprint;
    private final Instant expiresAt;
    private final byte[] authorityEvidenceBundle;
    private final StartSessionAuthorityEvidenceBundle.BundleReference bundleReference;
    private final String authenticatedLoggingWorkloadIdentity;
    private final StartSessionAuthorityEvidenceBundle decodedBundle;

    private AuthorizationReference(
        String operatorAuthorizationReference,
        String authorizationReferenceFingerprint,
        Instant expiresAt,
        byte[] authorityEvidenceBundle,
        StartSessionAuthorityEvidenceBundle.BundleReference bundleReference,
        String authenticatedLoggingWorkloadIdentity,
        StartSessionAuthorityEvidenceBundle decodedBundle) {
      this.operatorAuthorizationReference = operatorAuthorizationReference;
      this.authorizationReferenceFingerprint = authorizationReferenceFingerprint;
      this.expiresAt = expiresAt;
      this.authorityEvidenceBundle = authorityEvidenceBundle.clone();
      this.bundleReference = bundleReference;
      this.authenticatedLoggingWorkloadIdentity = authenticatedLoggingWorkloadIdentity;
      this.decodedBundle = decodedBundle;
    }

    public String operatorAuthorizationReference() {
      return operatorAuthorizationReference;
    }

    public String authorizationReferenceFingerprint() {
      return authorizationReferenceFingerprint;
    }

    public Instant expiresAt() {
      return expiresAt;
    }

    public byte[] authorityEvidenceBundle() {
      return authorityEvidenceBundle.clone();
    }

    public StartSessionAuthorityEvidenceBundle.BundleReference bundleReference() {
      return bundleReference;
    }

    /** Account's exact authenticated Logging peer identity echoed after successful verification. */
    public String authenticatedLoggingWorkloadIdentity() {
      return authenticatedLoggingWorkloadIdentity;
    }

    public StartSessionAuthorityEvidenceBundle decodedBundle() {
      return decodedBundle;
    }

    @Override
    public String toString() {
      return "AuthorizationReference[operatorAuthorizationReference=<redacted>, "
          + "authorizationReferenceFingerprint=<redacted>, expiresAt="
          + expiresAt
          + ", authorityEvidenceBundle=<redacted>, bundleReference="
          + bundleReference
          + ", authenticatedLoggingWorkloadIdentity="
          + authenticatedLoggingWorkloadIdentity
          + "]";
    }
  }
}
