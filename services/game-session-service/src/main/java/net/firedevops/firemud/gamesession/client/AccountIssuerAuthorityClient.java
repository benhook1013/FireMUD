package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Unwired Game Session client for authenticated Account issuer-source readback only. */
public final class AccountIssuerAuthorityClient
    extends AbstractReloadingBlockingGrpcClient<
        IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final String READBACK_SCHEMA_VERSION = "account-auth-issuer-source-readback/v1";
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";

  private final String workloadNamespace;
  private final String expectedIssuerId;

  /**
   * Creates a source-only client. Construction does not initialize a channel or enable the RPC. The
   * supplied customizer is the existing Game Session internal-RPC middleware seam.
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

    IssuerAuthoritySourceSnapshot wireSnapshot = response.getSourceSnapshot();
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
    BigInteger currentSequence =
        parseNonnegativeDecimal(wireSnapshot.getOutboxSequence(), "outbox sequence");
    IssuerGenerationAuthorityEvent latestEvent = null;
    if (currentSequence.signum() == 0) {
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
      if (!currentSequence.equals(
              parseResponsePositiveDecimal(latestEvent.outboxSequence(), "event sequence"))
          || !generation.equals(
              parseResponsePositiveDecimal(latestEvent.issuerAuthGeneration(), "event generation"))
          || !sourceVersion.equals(
              parseResponsePositiveDecimal(latestEvent.sourceVersion(), "event source version"))) {
        throw invalidResponse("latest event does not match the complete source checkpoint");
      }
    }

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
              sourceScope,
              streamKey);
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

    SourceSnapshot snapshot =
        new SourceSnapshot(
            expectedIssuerId,
            sourceScope,
            streamKey,
            generation.toString(),
            sourceVersion.toString(),
            currentSequence.toString(),
            latestEvent);
    return new SourceReadback(requestId.toString(), workloadNamespace, snapshot, requestedEvent);
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
}
