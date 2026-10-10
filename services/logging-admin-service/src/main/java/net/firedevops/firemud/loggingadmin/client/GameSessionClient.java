package net.firedevops.firemud.loggingadmin.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.common.grpc.ResolvedGrpcTlsMaterial;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.v1.AuthorizeStartSessionRequest;
import net.firedevops.firemud.gamesession.v1.AuthorizeStartSessionResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import net.firedevops.firemud.gamesession.v1.StartSessionOwnerAuthorizationProgress;
import net.firedevops.firemud.gamesession.v1.StopSessionRequest;
import net.firedevops.firemud.gamesession.v1.StopSessionResponse;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.TransientOwnerExecutionHandoff;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Session Service. */
@Component
public class GameSessionClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionServiceGrpc.GameSessionServiceBlockingStub> {
  private static final long START_SESSION_HANDOFF_DEADLINE_SECONDS = 5L;
  private static final Pattern OPAQUE_OPERATOR_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final CommonGrpcClientProperties transportTlsProperties;

  public GameSessionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameSessionClient.class);
    this.transportTlsProperties = tlsProps.copy();
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionServiceGrpc.GameSessionServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub().ping(PingRequest.newBuilder().build());
  }

  /** Stop a running session by ID. */
  public StopSessionResponse stopSession(long sessionId) {
    StopSessionRequest request =
        StopSessionRequest.newBuilder().setSessionId(Long.toString(sessionId)).build();
    return stub().stopSession(request);
  }

  /**
   * Sends the one transient handoff returned after Logging durably records owner-pending state.
   * This method performs exactly one bounded RPC and never retries an ambiguous owner result.
   */
  public StartSessionOwnerHandoffResult authorizeStartSession(
      TransientOwnerExecutionHandoff handoff) {
    Objects.requireNonNull(handoff, "durable Logging owner handoff is required");
    StartSessionPostAuthorizationExecutionTuple tuple = handoff.postAuthorizationTuple();
    String reference = handoff.accountResponse().operatorAuthorizationReference();
    byte[] tupleBytes = tuple.canonicalBytes();
    if (tupleBytes.length == 0
        || tupleBytes.length > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
        || !OPAQUE_OPERATOR_REFERENCE.matcher(reference).matches()
        || !tuple
            .authenticatedWorkloadIdentity()
            .equals(handoff.accountResponse().authenticatedLoggingWorkloadIdentity())
        || !tuple
            .authorizationReferenceFingerprint()
            .equals(handoff.accountResponse().authorizationReferenceFingerprint())
        || !tuple.bundleReference().equals(handoff.accountResponse().bundleReference())
        || !MessageDigest.isEqual(
            tuple.authorityEvidenceBundleBytes(),
            handoff.accountResponse().authorityEvidenceBundle())) {
      throw new IllegalArgumentException("Logging StartSession handoff is not internally bound");
    }
    String targetNamespace = tuple.preAuthorizationTuple().action().scope().targetNamespace();
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Logging StartSession handoff namespace is invalid");
    }
    requireFileBackedMtls();
    String expectedServerUri =
        "spiffe://firemud/ns/" + targetNamespace + "/sa/game-session-service";

    AuthorizeStartSessionRequest request =
        AuthorizeStartSessionRequest.newBuilder()
            .setCanonicalPostAuthorizationExecutionTupleBytes(
                com.google.protobuf.ByteString.copyFrom(tupleBytes))
            .setOperatorAuthorizationReference(reference)
            .build();
    AuthorizeStartSessionResponse response;
    try {
      response =
          Objects.requireNonNull(stub(), "Game Session client is not initialized")
              .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedServerUri))
              .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedServerUri))
              .withDeadlineAfter(START_SESSION_HANDOFF_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .authorizeStartSession(request);
    } catch (io.grpc.StatusRuntimeException ambiguousTransportOutcome) {
      throw new AmbiguousOwnerHandoffException(ambiguousTransportOutcome.getStatus().getCode());
    }
    return validateOwnerHandoffResponse(tuple, response);
  }

  private void requireFileBackedMtls() {
    if (transportTlsProperties.isPlaintext()) {
      throw new IllegalStateException("StartSession owner handoff requires workload mTLS");
    }
    ResolvedGrpcTlsMaterial material;
    try {
      material = new GrpcTlsMaterialResolver().resolve(transportTlsProperties);
    } catch (IOException | RuntimeException ignored) {
      throw new IllegalStateException(
          "StartSession owner handoff requires readable file-backed workload mTLS");
    }
    if (material == null
        || !isReadableFile(material.certChain())
        || !isReadableFile(material.privateKey())
        || !isReadableFile(material.caCert())) {
      throw new IllegalStateException(
          "StartSession owner handoff requires readable file-backed workload mTLS");
    }
  }

  private static boolean isReadableFile(ResolvedGrpcTlsMaterial.TlsResource resource) {
    Path path = resource == null ? null : resource.watchPath();
    return path != null && Files.isRegularFile(path) && Files.isReadable(path);
  }

  private static StartSessionOwnerHandoffResult validateOwnerHandoffResponse(
      StartSessionPostAuthorizationExecutionTuple tuple, AuthorizeStartSessionResponse response) {
    Objects.requireNonNull(response, "Game Session owner response is required");
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException("Game Session returned an unknown owner-handoff response");
    }
    if (response.hasError()) {
      if (!response.getError().getUnknownFields().asMap().isEmpty()) {
        throw new IllegalStateException("Game Session returned an unknown owner-handoff error");
      }
      throw new OwnerHandoffRejectedException(response.getError().getCode());
    }

    StartSessionOwnerAuthorizationProgress progress = response.getProgress();
    boolean supportedProgress =
        switch (progress) {
          case START_SESSION_OWNER_AUTHORIZATION_PROGRESS_EXACT_REPLAY,
              START_SESSION_OWNER_AUTHORIZATION_PROGRESS_ACCOUNT_OUTCOME_AMBIGUOUS,
              START_SESSION_OWNER_AUTHORIZATION_PROGRESS_ACCOUNT_PROJECTION_ATTACHED ->
              true;
          default -> false;
        };
    if (!tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(response.getTargetNamespace())
        || !tuple.controlPlaneRequestId().equals(response.getControlPlaneRequestId())
        || !tuple.mutationDigest().equals(response.getMutationDigest())
        || !canonicalNonNilUuid(response.getOwnerAttemptId())
        || !canonicalNonNilUuid(response.getOwnerMutationId())
        || response.getOwnerFence() <= 0L
        || !"OWNER_EXECUTION_PENDING".equals(response.getOwnerPhaseState())
        || !supportedProgress) {
      throw new IllegalStateException("Game Session owner response does not match the handoff");
    }
    return new StartSessionOwnerHandoffResult(
        response.getTargetNamespace(),
        response.getControlPlaneRequestId(),
        response.getMutationDigest(),
        UUID.fromString(response.getOwnerAttemptId()),
        UUID.fromString(response.getOwnerMutationId()),
        response.getOwnerFence(),
        response.getOwnerPhaseState(),
        progress);
  }

  private static boolean canonicalNonNilUuid(String value) {
    if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
      return false;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value) && !parsed.equals(new UUID(0L, 0L));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  /** Secret-free exact owner echo returned by the bounded StartSession handoff call. */
  public record StartSessionOwnerHandoffResult(
      String targetNamespace,
      String controlPlaneRequestId,
      String mutationDigest,
      UUID ownerAttemptId,
      UUID ownerMutationId,
      long ownerFence,
      String ownerPhaseState,
      StartSessionOwnerAuthorizationProgress progress) {
    public StartSessionOwnerHandoffResult {
      Objects.requireNonNull(targetNamespace, "targetNamespace");
      Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId");
      Objects.requireNonNull(mutationDigest, "mutationDigest");
      Objects.requireNonNull(ownerAttemptId, "ownerAttemptId");
      Objects.requireNonNull(ownerMutationId, "ownerMutationId");
      Objects.requireNonNull(ownerPhaseState, "ownerPhaseState");
      Objects.requireNonNull(progress, "progress");
    }
  }

  /** Transport ambiguity is surfaced without the raw request or authorization reference. */
  public static final class AmbiguousOwnerHandoffException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final io.grpc.Status.Code statusCode;

    private AmbiguousOwnerHandoffException(io.grpc.Status.Code statusCode) {
      super("Game Session owner-handoff outcome is ambiguous; automatic retry was not attempted");
      this.statusCode = Objects.requireNonNull(statusCode, "statusCode");
    }

    public io.grpc.Status.Code statusCode() {
      return statusCode;
    }
  }

  /** Bounded owner rejection code; no response body or transient reference is retained. */
  public static final class OwnerHandoffRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    private OwnerHandoffRejectedException(String code) {
      super("Game Session rejected the StartSession owner handoff");
      if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
        throw new IllegalArgumentException("owner rejection code is invalid");
      }
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
