package net.firedevops.firemud.gamesession.client;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationRequest;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;

/**
 * Typed Game Session client for Account's StartSession redemption RPC, registered only by the
 * disabled-by-default owner authorization configuration.
 *
 * <p>This transport does not acquire claims, retry ambiguous redemption, issue references, dispatch
 * a mutation, or transition an owner attempt to a terminal result. The raw reference exists only in
 * the bounded in-flight request object.
 */
public class StartSessionOperatorRedemptionClient
    extends AbstractReloadingBlockingGrpcClient<
        StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub> {
  private static final int MAX_PRE_AUTHORIZATION_TUPLE_BYTES = 8 * 1_024;
  private static final int MAX_AUTHORIZATION_REFERENCE_BYTES = 128;
  private static final long REDEMPTION_DEADLINE_SECONDS = 5L;
  private static final Pattern OPAQUE_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern AUTHORIZATION_REFERENCE_FINGERPRINT =
      Pattern.compile("arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}");
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final Clock clock;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "This constructor validates input only; managed channels are acquired later by initialize().")
  public StartSessionOperatorRedemptionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    this(endpoints, tlsProps, channelFactory, stubCustomizer, Clock.systemUTC());
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "This constructor validates input only; managed channels are acquired later by initialize().")
  public StartSessionOperatorRedemptionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      Clock clock) {
    super(
        endpoints,
        requireMutualTls(tlsProps),
        channelFactory,
        stubCustomizer,
        StartSessionOperatorRedemptionClient.class);
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /**
   * Opens the managed mutual-TLS channel only when an owning workflow explicitly initializes it.
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
      buildStub(ManagedChannel channel) {
    return applyStubCustomizer(
        StartSessionOperatorAuthorizationServiceGrpc.newBlockingStub(channel)
            .withCompression("gzip"));
  }

  /**
   * Redeems once for the exact newly-created Game Session owner claim. Transport ambiguity is
   * propagated unchanged; this method never retries or alters the pending owner record.
   */
  public RedemptionResult redeem(
      StartSessionPostAuthorizationExecutionTuple tuple,
      String transientOperatorAuthorizationReference,
      AttemptClaim actualGameSessionClaim) {
    Objects.requireNonNull(tuple, "complete post-authorization tuple is required");
    Objects.requireNonNull(actualGameSessionClaim, "Game Session owner attempt claim is required");
    requireExactClaimBinding(tuple, actualGameSessionClaim);
    requireOpaqueReference(transientOperatorAuthorizationReference);
    requireUnexpiredTupleEvidence(tuple);

    byte[] canonicalPreTuple =
        tuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8);
    if (canonicalPreTuple.length == 0
        || canonicalPreTuple.length > MAX_PRE_AUTHORIZATION_TUPLE_BYTES) {
      throw new IllegalArgumentException(
          "canonical StartSession pre-authorization tuple is over its bound");
    }
    RedeemOperatorAuthorizationRequest request =
        RedeemOperatorAuthorizationRequest.newBuilder()
            .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(canonicalPreTuple))
            .setOperatorAuthorizationReference(transientOperatorAuthorizationReference)
            .setAuthorizationReferenceFingerprint(tuple.authorizationReferenceFingerprint())
            .setReservationOwnerId(tuple.reservationOwnerId().toString())
            .setReservationClaimFence(tuple.reservationClaimFence())
            .setOwnerAttemptId(actualGameSessionClaim.ownerAttemptId().toString())
            .setOwnerFence(actualGameSessionClaim.ownerFence())
            .build();

    var response =
        requireStub()
            .withDeadlineAfter(REDEMPTION_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .redeemOperatorAuthorization(request);
    AccountRedemptionProjection projection = validateResponse(response, tuple);
    return new RedemptionResult(actualGameSessionClaim, projection, response.getReplay());
  }

  private StartSessionOperatorAuthorizationServiceGrpc
          .StartSessionOperatorAuthorizationServiceBlockingStub
      requireStub() {
    var value = stub();
    if (value == null) {
      throw new IllegalStateException(
          "Account StartSession redemption client has not been explicitly initialized");
    }
    return value;
  }

  private AccountRedemptionProjection validateResponse(
      RedeemOperatorAuthorizationResponse response,
      StartSessionPostAuthorizationExecutionTuple tuple) {
    try {
      if (response == null || !response.getUnknownFields().asMap().isEmpty()) {
        throw malformedResponse();
      }
      String fingerprint = response.getAuthorizationReferenceFingerprint();
      if (fingerprint == null
          || !AUTHORIZATION_REFERENCE_FINGERPRINT.matcher(fingerprint).matches()
          || !tuple.authorizationReferenceFingerprint().equals(fingerprint)) {
        throw malformedResponse();
      }

      byte[] responseBundle = response.getAuthorityEvidenceBundle().toByteArray();
      byte[] originalBundle = tuple.authorityEvidenceBundleBytes();
      if (responseBundle.length == 0 || !Arrays.equals(responseBundle, originalBundle)) {
        throw malformedResponse();
      }
      UUID issuanceOperationId = canonicalNonNilUuid(response.getIssuanceOperationId());
      long issuanceFence = response.getIssuanceFence();
      if (issuanceFence <= 0L) {
        throw malformedResponse();
      }

      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(responseBundle);
      bundle.requireTupleBinding(tuple.preAuthorizationTuple());
      bundle.requireReferenceBinding(tuple.bundleReference());
      if (!bundle.issuanceOperationId().equals(issuanceOperationId)
          || !tuple.issuanceFence().equals(Long.toString(issuanceFence))
          || !bundle.issuanceFence().equals(Long.toString(issuanceFence))
          || !bundle.expiresAt().isAfter(clock.instant())) {
        throw malformedResponse();
      }
      return new AccountRedemptionProjection(
          fingerprint, responseBundle, issuanceOperationId, issuanceFence);
    } catch (IllegalStateException expected) {
      throw expected;
    } catch (RuntimeException malformed) {
      throw malformedResponse();
    }
  }

  private void requireUnexpiredTupleEvidence(StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    bundle.requireTupleBinding(tuple.preAuthorizationTuple());
    bundle.requireReferenceBinding(tuple.bundleReference());
    if (!bundle.expiresAt().isAfter(clock.instant())) {
      throw new IllegalStateException("Account StartSession authorization evidence has expired");
    }
  }

  private static void requireExactClaimBinding(
      StartSessionPostAuthorizationExecutionTuple tuple, AttemptClaim claim) {
    if (!tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(claim.targetNamespace())
        || !tuple.controlPlaneRequestId().equals(claim.controlPlaneRequestId())
        || claim.ownerAttemptId().equals(NIL_UUID)
        || claim.ownerMutationId().equals(NIL_UUID)
        || claim.claimOwnerId().equals(NIL_UUID)
        || claim.ownerFence() <= 0L) {
      throw new IllegalArgumentException(
          "Game Session owner attempt claim differs from the complete StartSession tuple");
    }
  }

  private static void requireOpaqueReference(String value) {
    if (value == null
        || !OPAQUE_REFERENCE.matcher(value).matches()
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORIZATION_REFERENCE_BYTES) {
      throw new IllegalArgumentException("transient Account authorization reference is malformed");
    }
  }

  private static UUID canonicalNonNilUuid(String value) {
    if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
      throw malformedResponse();
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
        throw malformedResponse();
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw malformedResponse();
    }
  }

  private static CommonGrpcClientProperties requireMutualTls(
      CommonGrpcClientProperties tlsProperties) {
    Objects.requireNonNull(tlsProperties, "gRPC TLS properties are required");
    if (tlsProperties.isPlaintext()
        || blank(tlsProperties.getCertChain())
        || blank(tlsProperties.getPrivateKey())
        || blank(tlsProperties.getCaCert())) {
      throw new IllegalArgumentException(
          "Account StartSession redemption requires configured mutual TLS credentials and CA");
    }
    return tlsProperties;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static IllegalStateException malformedResponse() {
    return new IllegalStateException(
        "Account returned a malformed or tuple-substituted StartSession redemption projection");
  }

  /** Successful response bound to the exact locally reserved owner claim, without raw secret. */
  public record RedemptionResult(
      AttemptClaim gameSessionClaim,
      AccountRedemptionProjection projection,
      boolean accountReplay) {
    public RedemptionResult {
      Objects.requireNonNull(gameSessionClaim, "Game Session owner claim is required");
      Objects.requireNonNull(projection, "Account redemption projection is required");
    }
  }
}
