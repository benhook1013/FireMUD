package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerAuthorityProjectionV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionInstallationAcknowledgmentDigestV1;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller.InstallationReceipt;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ProjectionSnapshot;

/** Unwired Game Session client for authenticated Account issuer-source evidence. */
public final class AccountIssuerAuthorityClient
    extends AbstractReloadingBlockingGrpcClient<
        IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final String READBACK_SCHEMA_VERSION = "account-auth-issuer-source-readback/v1";
  private static final String CAPTURE_SCHEMA_VERSION = "account-auth-issuer-projection-capture/v1";
  private static final String INSTALLATION_ACK_SCHEMA_VERSION =
      "account-auth-issuer-projection-installation-ack/v1";
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";

  private final String workloadNamespace;
  private final String expectedIssuerId;

  /**
   * Creates an unwired issuer-authority client. Construction does not initialize a channel or
   * enable its RPCs. The supplied customizer is the existing Game Session internal-RPC middleware
   * seam.
   */
  public AccountIssuerAuthorityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      String workloadNamespace,
      String expectedIssuerId) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProps),
        channelFactory,
        stubCustomizer,
        AccountIssuerAuthorityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    if (expectedIssuerId == null || expectedIssuerId.isBlank()) {
      throw new IllegalArgumentException("Exact Account issuer identity is required");
    }
    if (stubCustomizer == null) {
      throw new IllegalArgumentException("Game Session internal gRPC stub customizer is required");
    }
    this.workloadNamespace = workloadNamespace;
    this.expectedIssuerId = expectedIssuerId;
  }

  /** Initializes this client only when an explicit owner activates the source handoff. */
  public void init() throws SSLException, IOException {
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
  protected IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return applyStubCustomizer(
        IssuerAuthorityServiceGrpc.newBlockingStub(channel)
            .withInterceptors(
                new GrpcServerPeerIdentityClientInterceptor(
                    "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service"))
            .withCompression("gzip"));
  }

  /** Reads Account's current issuer checkpoint without selecting an individual event. */
  public SourceReadback readCurrent(String canonicalRequestId) {
    UUID requestId = parseCanonicalNonNilUuid(canonicalRequestId, "request ID");
    return read(requestId, null);
  }

  /** Reads one exact historical event alongside Account's current issuer checkpoint. */
  public SourceReadback readCommittedEvent(
      String canonicalRequestId, String canonicalPositiveOutboxSequence) {
    UUID requestId = parseCanonicalNonNilUuid(canonicalRequestId, "request ID");
    BigInteger requestedSequence =
        parseCanonicalPositiveDecimal(canonicalPositiveOutboxSequence, "requested outbox sequence");
    return read(requestId, requestedSequence);
  }

  /**
   * Captures Account's immutable issuer-projection reconciliation receipt. A receipt is historical
   * source evidence only; this method does not install or authorize a Game Session projection.
   */
  public ProjectionCaptureReceipt captureProjection(String canonicalRequestId) {
    UUID requestId = parseCanonicalNonNilUuid(canonicalRequestId, "request ID");
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Account issuer authority client is not initialized");
    }

    CaptureIssuerProjectionForRuntimeRequest request =
        CaptureIssuerProjectionForRuntimeRequest.newBuilder()
            .setIssuerId(expectedIssuerId)
            .setRequestId(requestId.toString())
            .build();
    CaptureIssuerProjectionForRuntimeResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .captureIssuerProjectionForRuntime(request);
    return verifyCaptureResponse(response, requestId);
  }

  /**
   * Durably acknowledges only the installer's privately verified local receipt. The returned
   * evidence is historical Account readback, not current readiness or recipient authorization.
   */
  public InstallationAcknowledgmentReceipt acknowledgeInstallation(
      InstallationReceipt installationReceipt) {
    VerifiedInstallation installation = verifyInstallationReceipt(installationReceipt);
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Account issuer authority client is not initialized");
    }

    AcknowledgeIssuerProjectionForRuntimeRequest request =
        AcknowledgeIssuerProjectionForRuntimeRequest.newBuilder()
            .setIssuerId(expectedIssuerId)
            .setCaptureOperationId(installation.operationId().toString())
            .setCaptureRequestId(installation.requestId().toString())
            .setCaptureRequestDigestVersion(IssuerProjectionReconciliationRequestDigestV1.VERSION)
            .setCaptureRequestDigest(installation.captureRequestDigest())
            .setProjectionKey(installation.projectionKey())
            .setInstalledProjectionJson(installation.projectionJson())
            .build();
    AcknowledgeIssuerProjectionForRuntimeResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acknowledgeIssuerProjectionForRuntime(request);
    return verifyInstallationAcknowledgmentResponse(response, installation);
  }

  private VerifiedInstallation verifyInstallationReceipt(InstallationReceipt receipt) {
    if (receipt == null) {
      throw invalidInstallationReceipt("receipt is absent");
    }

    UUID operationId = receipt.operationId();
    UUID requestId = receipt.requestId();
    if (operationId == null || requestId == null || isNil(operationId) || isNil(requestId)) {
      throw invalidInstallationReceipt("capture identity is not canonical non-nil UUID evidence");
    }

    SourceSnapshot captured = receipt.capturedSource();
    ProjectionSnapshot projectionSnapshot = receipt.projectionSnapshot();
    if (captured == null || projectionSnapshot == null) {
      throw invalidInstallationReceipt("captured source or exact installed projection is absent");
    }

    String caller = expectedGameSessionWorkloadIdentity();
    String expectedProjectionKey;
    try {
      expectedProjectionKey = IssuerAuthorityProjectionRedisContract.keyForIssuer(expectedIssuerId);
    } catch (IllegalArgumentException malformedIssuer) {
      throw invalidInstallationReceipt("configured issuer cannot derive its projection key");
    }
    if (!expectedIssuerId.equals(captured.issuerId())
        || !expectedProjectionKey.equals(projectionSnapshot.key())) {
      throw invalidInstallationReceipt("issuer or installed projection key changed");
    }

    String expectedCaptureDigest =
        IssuerProjectionReconciliationRequestDigestV1.digest(
            expectedIssuerId, caller, expectedProjectionKey, requestId);
    if (!expectedCaptureDigest.equals(receipt.requestDigest())) {
      throw invalidInstallationReceipt("original capture request digest changed");
    }

    verifyCapturedSource(captured);
    if (projectionSnapshot.json() == null) {
      throw invalidInstallationReceipt("exact stored projection readback is absent");
    }
    String projectionJson = projectionSnapshot.json();
    byte[] projectionBytes = encodeUtf8Strict(projectionJson, "installed projection");
    final IssuerAuthorityProjectionV1Codec.Projection projection;
    try {
      projection = IssuerAuthorityProjectionV1Codec.verify(projectionJson);
    } catch (IllegalArgumentException malformed) {
      throw invalidInstallationReceipt("installed projection is not a closed valid projection");
    }

    if (!expectedIssuerId.equals(projection.issuerId())
        || !captured.sourceScope().equals("issuer/" + projection.issuerId())
        || !captured.outboxStreamKey().equals(projection.streamKey())
        || !new BigInteger(captured.issuerAuthGeneration()).equals(projection.generation())
        || !new BigInteger(captured.sourceVersion()).equals(projection.sourceVersion())
        || !new BigInteger(captured.outboxSequence()).equals(projection.sequence())
        || projection.latestEvent().isPresent() != captured.latestEvent().isPresent()
        || (captured.latestEvent().isPresent()
            && !captured
                .latestEvent()
                .orElseThrow()
                .canonicalJson()
                .equals(projection.latestEvent().orElseThrow().canonicalJson()))) {
      throw invalidInstallationReceipt(
          "installed projection does not preserve the complete captured checkpoint and event");
    }

    String requestDigest =
        IssuerProjectionInstallationAcknowledgmentDigestV1.digest(
            expectedIssuerId,
            caller,
            expectedProjectionKey,
            operationId,
            requestId,
            IssuerProjectionReconciliationRequestDigestV1.VERSION,
            expectedCaptureDigest,
            projectionJson);
    return new VerifiedInstallation(
        operationId,
        requestId,
        caller,
        expectedProjectionKey,
        expectedCaptureDigest,
        projectionJson,
        projectionBytes,
        requestDigest);
  }

  private void verifyCapturedSource(SourceSnapshot captured) {
    String expectedSourceScope = "issuer/" + expectedIssuerId;
    String expectedStreamKey =
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + expectedSourceScope;
    if (captured == null
        || !expectedIssuerId.equals(captured.issuerId())
        || !expectedSourceScope.equals(captured.sourceScope())
        || !expectedStreamKey.equals(captured.outboxStreamKey())) {
      throw invalidInstallationReceipt("captured source scope or issuer changed");
    }
    BigInteger generation =
        parseInstallationPositiveDecimal(captured.issuerAuthGeneration(), "issuer generation");
    BigInteger sourceVersion =
        parseInstallationPositiveDecimal(captured.sourceVersion(), "source version");
    BigInteger sequence =
        parseInstallationNonnegativeDecimal(captured.outboxSequence(), "outbox sequence");
    IssuerGenerationAuthorityEvent latest = captured.latestEvent().orElse(null);
    if (sequence.signum() == 0) {
      if (!BigInteger.ONE.equals(generation)
          || !BigInteger.ONE.equals(sourceVersion)
          || latest != null) {
        throw invalidInstallationReceipt("captured zero checkpoint is not the original baseline");
      }
      return;
    }
    if (latest == null) {
      throw invalidInstallationReceipt("positive captured checkpoint has no canonical event");
    }
    final IssuerGenerationAuthorityEvent verified;
    try {
      verified =
          verifyEvent(
              latest.canonicalJson(),
              "captured source event",
              expectedIssuerId,
              captured.sourceScope(),
              captured.outboxStreamKey());
    } catch (IllegalStateException malformed) {
      throw invalidInstallationReceipt("captured source event is invalid", malformed);
    }
    if (!sequence.equals(
            parseInstallationPositiveDecimal(verified.outboxSequence(), "event sequence"))
        || !generation.equals(
            parseInstallationPositiveDecimal(verified.issuerAuthGeneration(), "event generation"))
        || !sourceVersion.equals(
            parseInstallationPositiveDecimal(verified.sourceVersion(), "event source version"))) {
      throw invalidInstallationReceipt("captured event does not match its complete checkpoint");
    }
  }

  private InstallationAcknowledgmentReceipt verifyInstallationAcknowledgmentResponse(
      AcknowledgeIssuerProjectionForRuntimeResponse response, VerifiedInstallation installation) {
    if (response == null) {
      throw invalidInstallationAcknowledgmentResponse("response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty() || response.getAllFields().size() != 14) {
      throw invalidInstallationAcknowledgmentResponse(
          "response contains unsupported, missing, or defaulted fields");
    }

    UUID acknowledgmentId;
    try {
      acknowledgmentId =
          parseCanonicalNonNilUuid(response.getAcknowledgmentId(), "acknowledgment ID");
    } catch (IllegalArgumentException malformed) {
      throw invalidInstallationAcknowledgmentResponse(
          "acknowledgment ID is not a canonical non-nil UUID", malformed);
    }

    String expectedProjectionSha256 = sha256Hex(installation.projectionBytes());
    if (!INSTALLATION_ACK_SCHEMA_VERSION.equals(response.getSchemaVersion())
        || !workloadNamespace.equals(response.getTargetNamespace())
        || !expectedIssuerId.equals(response.getIssuerId())
        || !installation.callerWorkloadIdentity().equals(response.getCallerWorkloadIdentity())
        || !installation.projectionKey().equals(response.getProjectionKey())
        || !installation.operationId().toString().equals(response.getCaptureOperationId())
        || !installation.requestId().toString().equals(response.getCaptureRequestId())
        || response.getCaptureRequestDigestVersion()
            != IssuerProjectionReconciliationRequestDigestV1.VERSION
        || !installation.captureRequestDigest().equals(response.getCaptureRequestDigest())
        || response.getRequestDigestVersion()
            != IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION
        || !installation.requestDigest().equals(response.getRequestDigest())
        || !installation.projectionJson().equals(response.getInstalledProjectionJson())
        || !expectedProjectionSha256.equals(response.getInstalledProjectionSha256())) {
      throw invalidInstallationAcknowledgmentResponse(
          "schema, namespace, acknowledgment bindings, digest, or exact projection changed");
    }

    return new InstallationAcknowledgmentReceipt(
        acknowledgmentId,
        installation.operationId(),
        installation.requestId(),
        expectedIssuerId,
        workloadNamespace,
        installation.callerWorkloadIdentity(),
        installation.projectionKey(),
        installation.captureRequestDigest(),
        IssuerProjectionReconciliationRequestDigestV1.VERSION,
        installation.requestDigest(),
        IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION,
        installation.projectionJson(),
        expectedProjectionSha256);
  }

  private static BigInteger parseInstallationPositiveDecimal(String value, String label) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw invalidInstallationReceipt(label + " is not a canonical positive decimal");
    }
    return new BigInteger(value);
  }

  private static BigInteger parseInstallationNonnegativeDecimal(String value, String label) {
    if (value == null || !value.matches("(?:0|[1-9][0-9]*)")) {
      throw invalidInstallationReceipt(label + " is not a canonical nonnegative decimal");
    }
    return new BigInteger(value);
  }

  private static boolean isNil(UUID value) {
    return new UUID(0L, 0L).equals(value);
  }

  private static byte[] encodeUtf8Strict(String value, String label) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException malformed) {
      throw invalidInstallationReceipt(label + " is not exact valid UTF-8 text", malformed);
    }
  }

  private static String sha256Hex(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private SourceReadback read(UUID requestId, BigInteger requestedSequence) {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Account issuer authority client is not initialized");
    }

    ReadIssuerAuthorityForRuntimeRequest.Builder request =
        ReadIssuerAuthorityForRuntimeRequest.newBuilder()
            .setIssuerId(expectedIssuerId)
            .setRequestId(requestId.toString());
    if (requestedSequence != null) {
      request.setRequestedOutboxSequence(requestedSequence.toString());
    }
    ReadIssuerAuthorityForRuntimeResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readIssuerAuthorityForRuntime(request.build());
    return verifyResponse(response, requestId, requestedSequence);
  }

  private SourceReadback verifyResponse(
      ReadIssuerAuthorityForRuntimeResponse response,
      UUID requestId,
      BigInteger requestedSequence) {
    if (response == null) {
      throw invalidResponse("response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalidResponse("response contains unsupported fields");
    }
    if (!response.hasSourceSnapshot()) {
      throw invalidResponse("response has no source snapshot");
    }
    if (response.hasRequestedEventCanonicalJson() != (requestedSequence != null)) {
      throw invalidResponse("requested-event presence does not match the exact selector");
    }
    if (!READBACK_SCHEMA_VERSION.equals(response.getSchemaVersion())
        || !workloadNamespace.equals(response.getTargetNamespace())
        || !requestId.toString().equals(response.getRequestId())) {
      throw invalidResponse("response schema, namespace, or request echo changed");
    }

    SourceSnapshot snapshot = verifySnapshot(response.getSourceSnapshot());
    BigInteger generation = new BigInteger(snapshot.issuerAuthGeneration());
    BigInteger sourceVersion = new BigInteger(snapshot.sourceVersion());
    BigInteger currentSequence = new BigInteger(snapshot.outboxSequence());
    IssuerGenerationAuthorityEvent latestEvent = snapshot.latestEvent().orElse(null);

    IssuerGenerationAuthorityEvent requestedEvent = null;
    if (requestedSequence != null) {
      if (!response.hasRequestedEventCanonicalJson()) {
        throw invalidResponse("historical selection has no requested event");
      }
      requestedEvent =
          verifyEvent(
              response.getRequestedEventCanonicalJson(),
              "requested historical event",
              expectedIssuerId,
              snapshot.sourceScope(),
              snapshot.outboxStreamKey());
      BigInteger selectedEventSequence =
          parseResponsePositiveDecimal(requestedEvent.outboxSequence(), "requested event sequence");
      BigInteger selectedGeneration =
          parseResponsePositiveDecimal(
              requestedEvent.issuerAuthGeneration(), "requested event generation");
      BigInteger selectedVersion =
          parseResponsePositiveDecimal(
              requestedEvent.sourceVersion(), "requested event source version");
      if (!requestedSequence.equals(selectedEventSequence)
          || selectedEventSequence.compareTo(currentSequence) > 0
          || selectedGeneration.compareTo(generation) > 0
          || selectedVersion.compareTo(sourceVersion) > 0) {
        throw invalidResponse(
            "requested historical event is not exact or is ahead of the snapshot");
      }
      if (selectedEventSequence.equals(currentSequence)
          && (latestEvent == null
              || !requestedEvent.canonicalJson().equals(latestEvent.canonicalJson()))) {
        throw invalidResponse("current-sequence historical event differs from the latest event");
      }
    }

    return new SourceReadback(requestId.toString(), workloadNamespace, snapshot, requestedEvent);
  }

  private ProjectionCaptureReceipt verifyCaptureResponse(
      CaptureIssuerProjectionForRuntimeResponse response, UUID requestId) {
    if (response == null) {
      throw invalidCaptureResponse("response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalidCaptureResponse("response contains unsupported fields");
    }

    UUID operationUUID;
    try {
      operationUUID = parseCanonicalNonNilUuid(response.getOperationId(), "operation ID");
    } catch (IllegalArgumentException exception) {
      throw invalidCaptureResponse("operation ID is not a canonical non-nil UUID", exception);
    }

    String callerWorkloadIdentity = expectedGameSessionWorkloadIdentity();
    String expectedProjectionKey = PROJECTION_KEY_PREFIX + expectedIssuerId;
    String expectedRequestDigest =
        IssuerProjectionReconciliationRequestDigestV1.digest(
            expectedIssuerId, callerWorkloadIdentity, expectedProjectionKey, requestId);
    if (!CAPTURE_SCHEMA_VERSION.equals(response.getSchemaVersion())
        || !workloadNamespace.equals(response.getTargetNamespace())
        || !requestId.toString().equals(response.getRequestId())
        || !expectedIssuerId.equals(response.getIssuerId())
        || !callerWorkloadIdentity.equals(response.getCallerWorkloadIdentity())
        || !expectedProjectionKey.equals(response.getProjectionKey())
        || response.getRequestDigestVersion() != 1
        || !expectedRequestDigest.equals(response.getRequestDigest())) {
      throw invalidCaptureResponse("schema, identity, binding, or request digest changed");
    }
    if (!response.hasCapturedSourceSnapshot()) {
      throw invalidCaptureResponse("captured source snapshot is absent");
    }

    SourceSnapshot capturedSource = verifySnapshot(response.getCapturedSourceSnapshot());
    return new ProjectionCaptureReceipt(
        operationUUID,
        requestId,
        expectedIssuerId,
        callerWorkloadIdentity,
        expectedProjectionKey,
        response.getRequestDigestVersion(),
        expectedRequestDigest,
        capturedSource);
  }

  /** Validates the complete issuer source snapshot without fabricating a read RPC envelope. */
  private SourceSnapshot verifySnapshot(IssuerAuthoritySourceSnapshot wireSnapshot) {
    if (wireSnapshot == null) {
      throw invalidResponse("source snapshot is absent");
    }
    if (!wireSnapshot.getUnknownFields().asMap().isEmpty()) {
      throw invalidResponse("source snapshot contains unsupported fields");
    }
    if (!expectedIssuerId.equals(wireSnapshot.getIssuerId())) {
      throw invalidResponse("source snapshot issuer identity changed");
    }

    String sourceScope = "issuer/" + expectedIssuerId;
    String streamKey = IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + sourceScope;
    if (!sourceScope.equals(wireSnapshot.getSourceScope())
        || !streamKey.equals(wireSnapshot.getOutboxStreamKey())) {
      throw invalidResponse("source snapshot scope or stream changed");
    }

    BigInteger generation =
        parseResponsePositiveDecimal(
            wireSnapshot.getIssuerAuthGeneration(), "issuer auth generation");
    BigInteger sourceVersion =
        parseResponsePositiveDecimal(wireSnapshot.getSourceVersion(), "source version");
    BigInteger sequence =
        parseNonnegativeDecimal(wireSnapshot.getOutboxSequence(), "outbox sequence");
    IssuerGenerationAuthorityEvent latestEvent = null;
    if (sequence.signum() == 0) {
      if (!BigInteger.ONE.equals(generation)
          || !BigInteger.ONE.equals(sourceVersion)
          || wireSnapshot.hasLatestEventCanonicalJson()) {
        throw invalidResponse("zero checkpoint is not the proved positive source baseline");
      }
    } else {
      if (!wireSnapshot.hasLatestEventCanonicalJson()) {
        throw invalidResponse("positive checkpoint has no complete latest event");
      }
      latestEvent =
          verifyEvent(
              wireSnapshot.getLatestEventCanonicalJson(),
              "latest source event",
              expectedIssuerId,
              sourceScope,
              streamKey);
      if (!sequence.equals(
              parseResponsePositiveDecimal(latestEvent.outboxSequence(), "event sequence"))
          || !generation.equals(
              parseResponsePositiveDecimal(latestEvent.issuerAuthGeneration(), "event generation"))
          || !sourceVersion.equals(
              parseResponsePositiveDecimal(latestEvent.sourceVersion(), "event source version"))) {
        throw invalidResponse("latest event does not match the complete source checkpoint");
      }
    }
    return new SourceSnapshot(
        expectedIssuerId,
        sourceScope,
        streamKey,
        generation.toString(),
        sourceVersion.toString(),
        sequence.toString(),
        latestEvent);
  }

  private String expectedGameSessionWorkloadIdentity() {
    return "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
  }

  private static IssuerGenerationAuthorityEvent verifyEvent(
      String wireJson,
      String label,
      String expectedIssuer,
      String expectedScope,
      String expectedStream) {
    try {
      IssuerGenerationAuthorityEvent event = IssuerGenerationAuthorityEventV1Codec.verify(wireJson);
      if (!wireJson.equals(event.canonicalJson())) {
        throw new IllegalArgumentException("event JSON is not exact canonical JSON");
      }
      UUID eventRequestId = parseCanonicalNonNilUuid(event.requestId(), label + " request ID");
      if (!event.issuerId().equals(expectedIssuer)
          || !event.sourceScope().equals(expectedScope)
          || !event.outboxStreamKey().equals(expectedStream)
          || !event.eventId().equals(EVENT_ID_PREFIX + eventRequestId)) {
        throw new IllegalArgumentException("event identity or source binding changed");
      }
      return event;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(label + " is invalid", exception);
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    final UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
    if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    return parsed;
  }

  private static BigInteger parseCanonicalPositiveDecimal(String value, String label) {
    BigInteger parsed = parsePositiveDecimal(value, label);
    return parsed;
  }

  private static BigInteger parsePositiveDecimal(String value, String label) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be a canonical positive decimal string");
    }
    return new BigInteger(value);
  }

  private static BigInteger parseResponsePositiveDecimal(String value, String label) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw invalidResponse(label + " must be a canonical positive decimal string");
    }
    return new BigInteger(value);
  }

  private static BigInteger parseNonnegativeDecimal(String value, String label) {
    if (value == null || !value.matches("(?:0|[1-9][0-9]*)")) {
      throw invalidResponse(label + " must be a canonical zero-or-positive decimal string");
    }
    return new BigInteger(value);
  }

  private static IllegalStateException invalidResponse(String message) {
    return new IllegalStateException("Account issuer authority response " + message);
  }

  private static IllegalStateException invalidCaptureResponse(String message) {
    return new IllegalStateException("Account issuer projection capture response " + message);
  }

  private static IllegalStateException invalidCaptureResponse(
      String message, IllegalArgumentException cause) {
    return new IllegalStateException(
        "Account issuer projection capture response " + message, cause);
  }

  private static IllegalArgumentException invalidInstallationReceipt(String message) {
    return new IllegalArgumentException("Game Session issuer installation receipt " + message);
  }

  private static IllegalArgumentException invalidInstallationReceipt(
      String message, Throwable cause) {
    return new IllegalArgumentException(
        "Game Session issuer installation receipt " + message, cause);
  }

  private static IllegalStateException invalidInstallationAcknowledgmentResponse(String message) {
    return new IllegalStateException(
        "Account issuer projection installation acknowledgment response " + message);
  }

  private static IllegalStateException invalidInstallationAcknowledgmentResponse(
      String message, IllegalArgumentException cause) {
    return new IllegalStateException(
        "Account issuer projection installation acknowledgment response " + message, cause);
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Game Session gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Issuer source readback requires Game Session workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Issuer source readback requires Game Session certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Issuer source readback requires file-backed Game Session workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * Immutable Account snapshot fields created only after the authenticated response is verified.
   */
  public static final class SourceSnapshot {
    private final String issuerId;
    private final String sourceScope;
    private final String outboxStreamKey;
    private final String issuerAuthGeneration;
    private final String sourceVersion;
    private final String outboxSequence;
    private final IssuerGenerationAuthorityEvent latestEvent;

    private SourceSnapshot(
        String issuerId,
        String sourceScope,
        String outboxStreamKey,
        String issuerAuthGeneration,
        String sourceVersion,
        String outboxSequence,
        IssuerGenerationAuthorityEvent latestEvent) {
      this.issuerId = issuerId;
      this.sourceScope = sourceScope;
      this.outboxStreamKey = outboxStreamKey;
      this.issuerAuthGeneration = issuerAuthGeneration;
      this.sourceVersion = sourceVersion;
      this.outboxSequence = outboxSequence;
      this.latestEvent = latestEvent;
    }

    public String issuerId() {
      return issuerId;
    }

    public String sourceScope() {
      return sourceScope;
    }

    public String outboxStreamKey() {
      return outboxStreamKey;
    }

    public String issuerAuthGeneration() {
      return issuerAuthGeneration;
    }

    public String sourceVersion() {
      return sourceVersion;
    }

    public String outboxSequence() {
      return outboxSequence;
    }

    public Optional<IssuerGenerationAuthorityEvent> latestEvent() {
      return Optional.ofNullable(latestEvent);
    }
  }

  /** Immutable result for current or historical source readback, minted only after verification. */
  public static final class SourceReadback {
    private final String requestId;
    private final String targetNamespace;
    private final SourceSnapshot sourceSnapshot;
    private final IssuerGenerationAuthorityEvent requestedEvent;

    private SourceReadback(
        String requestId,
        String targetNamespace,
        SourceSnapshot sourceSnapshot,
        IssuerGenerationAuthorityEvent requestedEvent) {
      this.requestId = requestId;
      this.targetNamespace = targetNamespace;
      this.sourceSnapshot = sourceSnapshot;
      this.requestedEvent = requestedEvent;
    }

    public String requestId() {
      return requestId;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public SourceSnapshot sourceSnapshot() {
      return sourceSnapshot;
    }

    public Optional<IssuerGenerationAuthorityEvent> requestedEvent() {
      return Optional.ofNullable(requestedEvent);
    }
  }

  /** Immutable authenticated Account capture receipt; it is not projection-install authority. */
  public static final class ProjectionCaptureReceipt {
    private final UUID operationUUID;
    private final UUID requestUUID;
    private final String issuerId;
    private final String callerWorkloadIdentity;
    private final String projectionKey;
    private final int requestDigestVersion;
    private final String requestDigest;
    private final SourceSnapshot capturedSource;

    private ProjectionCaptureReceipt(
        UUID operationUUID,
        UUID requestUUID,
        String issuerId,
        String callerWorkloadIdentity,
        String projectionKey,
        int requestDigestVersion,
        String requestDigest,
        SourceSnapshot capturedSource) {
      this.operationUUID = operationUUID;
      this.requestUUID = requestUUID;
      this.issuerId = issuerId;
      this.callerWorkloadIdentity = callerWorkloadIdentity;
      this.projectionKey = projectionKey;
      this.requestDigestVersion = requestDigestVersion;
      this.requestDigest = requestDigest;
      this.capturedSource = capturedSource;
    }

    public UUID operationUUID() {
      return operationUUID;
    }

    public UUID requestUUID() {
      return requestUUID;
    }

    public String issuerId() {
      return issuerId;
    }

    public String callerWorkloadIdentity() {
      return callerWorkloadIdentity;
    }

    public String projectionKey() {
      return projectionKey;
    }

    public int requestDigestVersion() {
      return requestDigestVersion;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public SourceSnapshot capturedSource() {
      return capturedSource;
    }
  }

  private record VerifiedInstallation(
      UUID operationId,
      UUID requestId,
      String callerWorkloadIdentity,
      String projectionKey,
      String captureRequestDigest,
      String projectionJson,
      byte[] projectionBytes,
      String requestDigest) {}

  /** Immutable historical Account acknowledgment, not current readiness or recipient authority. */
  public static final class InstallationAcknowledgmentReceipt {
    private final UUID acknowledgmentId;
    private final UUID captureOperationId;
    private final UUID captureRequestId;
    private final String issuerId;
    private final String targetNamespace;
    private final String callerWorkloadIdentity;
    private final String projectionKey;
    private final String captureRequestDigest;
    private final int captureRequestDigestVersion;
    private final String requestDigest;
    private final int requestDigestVersion;
    private final String installedProjectionJson;
    private final String installedProjectionSha256;

    private InstallationAcknowledgmentReceipt(
        UUID acknowledgmentId,
        UUID captureOperationId,
        UUID captureRequestId,
        String issuerId,
        String targetNamespace,
        String callerWorkloadIdentity,
        String projectionKey,
        String captureRequestDigest,
        int captureRequestDigestVersion,
        String requestDigest,
        int requestDigestVersion,
        String installedProjectionJson,
        String installedProjectionSha256) {
      this.acknowledgmentId = acknowledgmentId;
      this.captureOperationId = captureOperationId;
      this.captureRequestId = captureRequestId;
      this.issuerId = issuerId;
      this.targetNamespace = targetNamespace;
      this.callerWorkloadIdentity = callerWorkloadIdentity;
      this.projectionKey = projectionKey;
      this.captureRequestDigest = captureRequestDigest;
      this.captureRequestDigestVersion = captureRequestDigestVersion;
      this.requestDigest = requestDigest;
      this.requestDigestVersion = requestDigestVersion;
      this.installedProjectionJson = installedProjectionJson;
      this.installedProjectionSha256 = installedProjectionSha256;
    }

    public UUID acknowledgmentId() {
      return acknowledgmentId;
    }

    public UUID captureOperationId() {
      return captureOperationId;
    }

    public UUID captureRequestId() {
      return captureRequestId;
    }

    public String issuerId() {
      return issuerId;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public String callerWorkloadIdentity() {
      return callerWorkloadIdentity;
    }

    public String projectionKey() {
      return projectionKey;
    }

    public String captureRequestDigest() {
      return captureRequestDigest;
    }

    public int captureRequestDigestVersion() {
      return captureRequestDigestVersion;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public int requestDigestVersion() {
      return requestDigestVersion;
    }

    public String installedProjectionJson() {
      return installedProjectionJson;
    }

    public String installedProjectionSha256() {
      return installedProjectionSha256;
    }
  }
}
