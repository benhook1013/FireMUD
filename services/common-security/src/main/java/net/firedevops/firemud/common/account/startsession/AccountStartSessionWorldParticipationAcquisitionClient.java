package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for Account's unregistered World-participation acquisition. */
public final class AccountStartSessionWorldParticipationAcquisitionClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionWorldParticipationAcquisitionServiceGrpc
            .AccountStartSessionWorldParticipationAcquisitionServiceBlockingStub> {
  private static final int MAX_REQUEST_BYTES =
      WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
          + StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
          + 2 * 1024;
  private static final int MAX_RESPONSE_BYTES =
      MAX_REQUEST_BYTES
          + OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.MAX_RESPONSE_BYTES
          + 2 * 1024;
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountStartSessionWorldParticipationAcquisitionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AccountStartSessionWorldParticipationAcquisitionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning World component explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account World-participation client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account World-participation stub is unavailable");
    }
    initialized = true;
  }

  /** Acquires and validates the exact original participation response outside ambient SQL. */
  public AcquireStartSessionWorldParticipationResponse acquire(
      AcquireStartSessionWorldParticipationRequest request) {
    requireWorkloadOnlyContext();
    validateRequest(request, workloadNamespace);
    try {
      var response =
          requireStub()
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .acquireStartSessionWorldParticipation(request);
      validateResponse(request, workloadNamespace, response);
      return response;
    } catch (StatusRuntimeException failure) {
      throw Status.fromCode(failure.getStatus().getCode()).asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account returned invalid original StartSession World participation evidence", malformed);
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
  protected AccountStartSessionWorldParticipationAcquisitionServiceGrpc
          .AccountStartSessionWorldParticipationAcquisitionServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionWorldParticipationAcquisitionServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(MAX_RESPONSE_BYTES)
        .withMaxOutboundMessageSize(MAX_REQUEST_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
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

  private AccountStartSessionWorldParticipationAcquisitionServiceGrpc
          .AccountStartSessionWorldParticipationAcquisitionServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Account World-participation client is not initialized and available");
    }
    return currentStub;
  }

  private static void requireWorkloadOnlyContext() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account World-participation acquisition requires no ambient SQL or synchronization");
    }
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "Account World-participation acquisition requires a workload-only caller context");
    }
  }

  private static void validateRequest(
      AcquireStartSessionWorldParticipationRequest request, String expectedNamespace) {
    Objects.requireNonNull(request, "World-participation acquisition request is required");
    requireClosed(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || request.getSchemaVersion() != 1
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace())) {
      throw new IllegalArgumentException("Unsupported or oversized World-participation request");
    }
    UUID acquisitionId = canonicalUuid(request.getAcquisitionRequestId(), "acquisitionRequestId");
    UUID mutationId = canonicalUuid(request.getGameSessionOwnerMutationId(), "ownerMutationId");
    UUID attemptId = canonicalUuid(request.getGameSessionOwnerAttemptId(), "ownerAttemptId");
    UUID instanceId = canonicalUuid(request.getCanonicalGameInstanceId(), "gameInstanceId");
    if (request.getGameSessionOwnerFence() <= 0L) {
      throw new IllegalArgumentException("Game Session owner fence must be positive");
    }
    byte[] tupleBytes = request.getOriginalPostAuthorizationTuple().toByteArray();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    if (tupleBytes.length == 0
        || tupleBytes.length > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
        || !MessageDigest.isEqual(tupleBytes, tuple.canonicalBytes())
        || !StartSessionOperatorAction.ACTION_FAMILY.equals(
            tuple.preAuthorizationTuple().actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(
            tuple.preAuthorizationTuple().targetOwner())
        || !expectedNamespace.equals(
            tuple.preAuthorizationTuple().action().scope().targetNamespace())) {
      throw new IllegalArgumentException("Exact canonical original StartSession tuple required");
    }
    Set<UUID> occupied = new HashSet<>();
    occupied.add(mutationId);
    occupied.add(attemptId);
    occupied.add(instanceId);
    occupied.add(tuple.reservationOwnerId());
    occupied.add(tuple.preAuthorizationTuple().actor().accountId());
    occupied.add(tuple.preAuthorizationTuple().action().scope().tenantId());
    occupied.add(
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
            .issuanceOperationId());
    if (tuple.controlPlaneRequestId().equals(acquisitionId.toString())
        || occupied.contains(acquisitionId)) {
      throw new IllegalArgumentException(
          "Acquisition correlation must be independent of retained operation identities");
    }
    String preparation = request.getPreparationInputJson();
    if (preparation.isBlank()
        || !StandardCharsets.UTF_8.newEncoder().canEncode(preparation)
        || preparation.getBytes(StandardCharsets.UTF_8).length
            > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
        || !SHA256.matcher(request.getPreparationInputDigest()).matches()
        || !request
            .getPreparationInputDigest()
            .equals(digestPrefixed(preparation.getBytes(StandardCharsets.UTF_8)))) {
      throw new IllegalArgumentException(
          "Exact bounded World preparation input and digest required");
    }
    var canonical =
        AcquireStartSessionWorldParticipationRequest.newBuilder()
            .setSchemaVersion(1)
            .setAcquisitionRequestId(acquisitionId.toString())
            .setTargetNamespace(expectedNamespace)
            .setOriginalPostAuthorizationTuple(request.getOriginalPostAuthorizationTuple())
            .setGameSessionOwnerMutationId(mutationId.toString())
            .setGameSessionOwnerAttemptId(attemptId.toString())
            .setGameSessionOwnerFence(request.getGameSessionOwnerFence())
            .setCanonicalGameInstanceId(instanceId.toString())
            .setPreparationInputJson(preparation)
            .setPreparationInputDigest(request.getPreparationInputDigest())
            .build();
    if (!canonical.equals(request)) {
      throw new IllegalArgumentException("World-participation request is not canonical");
    }
  }

  private static void validateResponse(
      AcquireStartSessionWorldParticipationRequest expected,
      String expectedNamespace,
      AcquireStartSessionWorldParticipationResponse response) {
    Objects.requireNonNull(response, "Account World-participation response is required");
    requireClosed(response, "response");
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.hasRequest()
        || !response.hasOriginalAttemptEvidence()) {
      throw new IllegalArgumentException("Missing or oversized World-participation response");
    }
    requireClosed(response.getRequest(), "request echo");
    if (!expected.equals(response.getRequest())
        || !expectedNamespace.equals(response.getRequest().getTargetNamespace())) {
      throw new IllegalArgumentException("Account changed the exact acquisition request");
    }
    canonicalUuid(response.getAccountWorldParticipationId(), "accountWorldParticipationId");
    if (response.getAccountWorldParticipationFence() <= 0L) {
      throw new IllegalArgumentException("Account World participation fence must be positive");
    }
    var original =
        StartSessionPostAuthorizationExecutionTuple.decode(
            expected.getOriginalPostAuthorizationTuple().toByteArray());
    var observation = response.getOriginalAttemptEvidence();
    requireClosed(observation, "original attempt evidence");
    var observedRequest =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(observation.getRequest());
    var exactObservation =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
            observedRequest, observation);
    if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
            exactObservation.phaseState())
        || !expectedNamespace.equals(observedRequest.targetNamespace())
        || !MessageDigest.isEqual(
            expected.getOriginalPostAuthorizationTuple().toByteArray(),
            observedRequest.canonicalPostAuthorizationTuple())
        || !canonicalUuid(expected.getGameSessionOwnerMutationId(), "ownerMutationId")
            .equals(observedRequest.expectedOwnerMutationId())
        || !canonicalUuid(expected.getGameSessionOwnerAttemptId(), "ownerAttemptId")
            .equals(observedRequest.expectedOwnerAttemptId())
        || expected.getGameSessionOwnerFence() != observedRequest.expectedOwnerFence()
        || !MessageDigest.isEqual(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(original),
            exactObservation.accountRedemptionProjection())
        || !observation.equals(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(exactObservation))) {
      throw new IllegalArgumentException(
          "Account changed the original tuple, Game Session attempt, or full redemption projection");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "World-participation " + label + " contains unknown fields");
    }
  }

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static String digestPrefixed(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Account World-participation acquisition requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account World-participation acquisition requires file-backed mTLS material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account World-participation " + label + " must be file-backed", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account World-participation " + label + " must be an existing readable file");
    }
  }
}
