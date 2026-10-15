package net.firedevops.firemud.worldmanagement.data;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient.LaunchDescriptorReadback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fixed-server reader and verifier for the run-owned World launch fixture.
 *
 * <p>Request data cannot select paths, principals, or evidence. Every call rereads and revalidates
 * the configured immutable files and reads the exact authenticated Game Design and Game Session
 * owner projections. The Game Session STARTING association is provenance only; it grants no World
 * materialization or activation authority.
 */
@Component
public final class RunOwnedCanonicalWorldLaunchEvidenceProvider {
  private static final Logger logger =
      LoggerFactory.getLogger(RunOwnedCanonicalWorldLaunchEvidenceProvider.class);
  private static final int MAX_WIRE_BYTES = 16 * 1024;
  private static final int MAX_CERTIFICATE_BYTES = 1024 * 1024;
  private static final String GAME_SESSION_URI_SAN =
      RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN;
  private static final String WORLD_MANAGEMENT_URI_SAN =
      RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN;
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

  private final boolean enabled;
  private final String capabilityPath;
  private final String sourceReceiptPath;
  private final String runId;
  private final String composeProjectName;
  private final CommonGrpcClientProperties grpcProperties;
  private final GameDesignClient gameDesignClient;
  private final ServiceEndpointsProperties serviceEndpoints;
  private final GrpcChannelFactory grpcChannelFactory;
  private final GameSessionFixtureClientFactory gameSessionFixtureClientFactory;
  private final String workloadNamespace;
  private volatile CanonicalGameInstanceLaunchAssociationClient gameSessionFixtureClient;
  private boolean closed;

  @Autowired
  public RunOwnedCanonicalWorldLaunchEvidenceProvider(
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}") boolean enabled,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:}") String capabilityPath,
      @Value("${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:}") String sourceReceiptPath,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      GameDesignClient gameDesignClient,
      ServiceEndpointsProperties serviceEndpoints,
      GrpcChannelFactory grpcChannelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this(
        enabled,
        capabilityPath,
        sourceReceiptPath,
        runId,
        composeProjectName,
        grpcProperties,
        gameDesignClient,
        serviceEndpoints,
        grpcChannelFactory,
        workloadNamespace,
        (endpoints, properties, factory, namespace) ->
            new CanonicalGameInstanceLaunchAssociationClient(
                endpoints, properties, factory, namespace));
  }

  RunOwnedCanonicalWorldLaunchEvidenceProvider(
      boolean enabled,
      String capabilityPath,
      String sourceReceiptPath,
      String runId,
      String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      GameDesignClient gameDesignClient,
      ServiceEndpointsProperties serviceEndpoints,
      GrpcChannelFactory grpcChannelFactory,
      String workloadNamespace,
      GameSessionFixtureClientFactory gameSessionFixtureClientFactory) {
    this.enabled = enabled;
    this.capabilityPath = capabilityPath;
    this.sourceReceiptPath = sourceReceiptPath;
    this.runId = runId;
    this.composeProjectName = composeProjectName;
    this.grpcProperties = Objects.requireNonNull(grpcProperties, "grpcProperties");
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient, "gameDesignClient");
    this.serviceEndpoints = serviceEndpoints == null ? null : serviceEndpoints.copy();
    this.grpcChannelFactory = grpcChannelFactory;
    this.gameSessionFixtureClientFactory =
        Objects.requireNonNull(gameSessionFixtureClientFactory, "gameSessionFixtureClientFactory");
    this.workloadNamespace = workloadNamespace;
  }

  /** Whether the server selected the run-owned fixture mode at startup. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Independently loads the fixed evidence and verifies both authenticated owner readbacks.
   * Returned Game Session STARTING state is source evidence only and does not authorize World
   * writes or activation.
   */
  public RunOwnedCanonicalWorldLaunchEvidence requireAuthenticatedEvidence() {
    VerificationStage stage = VerificationStage.ENABLED;
    try {
      if (!enabled) {
        throw invalidEvidence();
      }

      stage = VerificationStage.RUN_BINDING;
      requireExpectedRunBinding(runId, composeProjectName);

      stage = VerificationStage.CAPABILITY_FILE;
      byte[] capabilityBytes = readImmutableWireFile(capabilityPath);
      stage = VerificationStage.RECEIPT_FILE;
      byte[] receiptBytes = readImmutableWireFile(sourceReceiptPath);

      stage = VerificationStage.WIRE_PARSE;
      var capability = RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(capabilityBytes);
      var receipt =
          RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(receiptBytes, capability);
      if (!runId.equals(capability.runId())
          || !composeProjectName.equals(capability.composeProjectName())
          || !runId.equals(receipt.runId())
          || !composeProjectName.equals(receipt.composeProjectName())) {
        throw invalidEvidence();
      }

      stage = VerificationStage.WORLD_CERTIFICATE_PINS;
      CertificatePins worldPins = configuredWorldCertificatePins(grpcProperties);
      if (!GAME_SESSION_URI_SAN.equals(capability.gameSessionUriSan())
          || !WORLD_MANAGEMENT_URI_SAN.equals(capability.worldManagementUriSan())
          || !worldPins.uriSan().equals(capability.worldManagementUriSan())
          || !worldPins.leafSha256().equals(capability.worldManagementLeafSha256())
          || !worldPins.caSha256().equals(capability.caCertificateSha256())
          || !worldPins.caSha256().equals(receipt.caCertificateSha256())
          || !worldPins.uriSan().equals(receipt.worldManagementUriSan())
          || !worldPins.leafSha256().equals(receipt.worldManagementLeafSha256())) {
        throw invalidEvidence();
      }

      stage = VerificationStage.GAME_SESSION_PEER;
      requireAuthenticatedGameSessionPeer(capability);

      try {
        stage = VerificationStage.DESCRIPTOR_PARSE;
        AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest =
            expectedDescriptorRequest(capability, receipt);
        AuthoredWorldLaunchDescriptorEvidence receiptDescriptor =
            AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
                expectedRequest, receipt.launchReadEvidence().launchDescriptor());

        stage = VerificationStage.OWNER_DESCRIPTOR_READ;
        LaunchDescriptorReadback readback =
            gameDesignClient.getLaunchDescriptor(
                new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                    freshReadRequestId(expectedRequest),
                    expectedRequest,
                    receiptDescriptor.resultDigest()));

        stage = VerificationStage.OWNER_DESCRIPTOR_COMPARE;
        if (!receiptDescriptor.equals(readback.evidence())
            || !receipt.launchReadEvidence().launchDescriptor().equals(readback.message())) {
          throw invalidEvidence();
        }

        stage = VerificationStage.GAME_SESSION_FIXTURE_READ;
        var fixtureReadRequest =
            new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
                freshFixtureReadRequestId(UUID.fromString(capability.operationId())),
                receipt.sourceEvidence().targetNamespace(),
                UUID.fromString(capability.operationId()),
                receipt.fixturePolicySeal().evidenceDigest());
        CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult gameSessionAssociation =
            gameSessionFixtureClient()
                .readFixture(fixtureReadRequest, capability.gameSessionLeafSha256());
        stage = VerificationStage.GAME_SESSION_FIXTURE_COMPARE;

        return new RunOwnedCanonicalWorldLaunchEvidence(
            capability,
            receipt,
            receiptDescriptor,
            capabilityBytes,
            receiptBytes,
            capability.gameSessionLeafSha256(),
            worldPins.uriSan(),
            worldPins.leafSha256(),
            worldPins.caSha256(),
            gameSessionAssociation);
      } catch (RuntimeException exception) {
        if (exception instanceof InvalidRunOwnedCanonicalWorldLaunchEvidenceException invalid) {
          throw invalid;
        }
        if (isRetryableOwnerReadFailure(stage, exception)) {
          throw exception;
        }
        throw invalidEvidence();
      }
    } catch (RuntimeException exception) {
      logger.warn("Run-owned World launch evidence verification rejected; stage={}", stage);
      throw exception;
    }
  }

  private static boolean isRetryableOwnerReadFailure(
      VerificationStage stage, RuntimeException exception) {
    if (!(exception instanceof StatusRuntimeException statusException)
        || (stage != VerificationStage.OWNER_DESCRIPTOR_READ
            && stage != VerificationStage.GAME_SESSION_FIXTURE_READ)) {
      return false;
    }
    return statusException.getStatus().getCode() == Status.Code.UNAVAILABLE
        || statusException.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED;
  }

  private static UUID freshFixtureReadRequestId(UUID fixtureOperationId) {
    UUID readRequestId;
    do {
      readRequestId = UUID.randomUUID();
    } while (readRequestId.equals(fixtureOperationId));
    return readRequestId;
  }

  private synchronized CanonicalGameInstanceLaunchAssociationClient gameSessionFixtureClient() {
    if (closed) {
      throw invalidEvidence();
    }
    if (gameSessionFixtureClient != null) {
      return gameSessionFixtureClient;
    }
    if (serviceEndpoints == null
        || grpcChannelFactory == null
        || !StringUtils.hasText(workloadNamespace)) {
      throw invalidEvidence();
    }
    CanonicalGameInstanceLaunchAssociationClient created =
        gameSessionFixtureClientFactory.create(
            serviceEndpoints, grpcProperties, grpcChannelFactory, workloadNamespace);
    try {
      created.init();
      gameSessionFixtureClient = created;
      return created;
    } catch (IOException | RuntimeException failure) {
      try {
        created.close();
      } catch (IOException ignored) {
        // Keep the fixed content-free rejection; certificate paths never enter logs.
      }
      throw invalidEvidence();
    }
  }

  @PreDestroy
  public synchronized void closeGameSessionFixtureClient() {
    closed = true;
    CanonicalGameInstanceLaunchAssociationClient current = gameSessionFixtureClient;
    gameSessionFixtureClient = null;
    if (current != null) {
      try {
        current.close();
      } catch (IOException failure) {
        logger.warn("Run-owned World Game Session owner client did not close cleanly");
      }
    }
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request expectedDescriptorRequest(
      RunOwnedInitialAdmissionFixtureWireCodec.Capability capability,
      RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt receipt) {
    var source = receipt.sourceEvidence();
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE,
        capability.operationId(),
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        capability.gameTemplateId(),
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static UUID freshReadRequestId(AuthoredWorldLaunchDescriptorEvidence.Request request) {
    UUID readRequestId;
    do {
      readRequestId = UUID.randomUUID();
    } while (readRequestId.equals(request.authoredWorldSourceOperationId())
        || readRequestId.toString().equals(request.controlPlaneRequestId()));
    return readRequestId;
  }

  private static void requireAuthenticatedGameSessionPeer(
      RunOwnedInitialAdmissionFixtureWireCodec.Capability capability) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    GrpcPeerCertificateEvidence certificate = GrpcPeerCertificateEvidence.current();
    if (peer == null
        || certificate == null
        || !GAME_SESSION_URI_SAN.equals(capability.gameSessionUriSan())
        || !peer.uri().equals(GAME_SESSION_URI_SAN)
        || !peer.isService("game-session-service")
        || !peer.isInNamespace("dev")
        || !certificate.leafSha256().equals(capability.gameSessionLeafSha256())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw invalidEvidence();
    }
  }

  private static CertificatePins configuredWorldCertificatePins(
      CommonGrpcClientProperties properties) {
    if (properties.isPlaintext()
        || blank(properties.getCertChain())
        || blank(properties.getCaCert())) {
      throw invalidEvidence();
    }
    try {
      List<X509Certificate> chain = certificates(readConfiguredTlsFile(properties.getCertChain()));
      List<X509Certificate> authorities =
          certificates(readConfiguredTlsFile(properties.getCaCert()));
      if (chain.isEmpty() || authorities.size() != 1) {
        throw invalidEvidence();
      }
      X509Certificate leaf = chain.getFirst();
      X509Certificate ca = authorities.getFirst();
      String uriSan =
          GrpcPeerIdentity.fromCertificate(leaf)
              .map(GrpcPeerIdentity::uri)
              .filter(WORLD_MANAGEMENT_URI_SAN::equals)
              .orElseThrow(RunOwnedCanonicalWorldLaunchEvidenceProvider::invalidEvidence);
      if (ca.getBasicConstraints() < 0) {
        throw invalidEvidence();
      }
      leaf.checkValidity();
      ca.checkValidity();
      leaf.verify(ca.getPublicKey());
      return new CertificatePins(fingerprint(leaf), uriSan, fingerprint(ca));
    } catch (IOException | GeneralSecurityException | RuntimeException exception) {
      if (exception instanceof InvalidRunOwnedCanonicalWorldLaunchEvidenceException invalid) {
        throw invalid;
      }
      throw invalidEvidence();
    }
  }

  private static byte[] readConfiguredTlsFile(String configuredPath) throws IOException {
    byte[] bytes;
    if (configuredPath.startsWith("classpath:")) {
      ClassPathResource resource =
          new ClassPathResource(configuredPath.substring("classpath:".length()));
      try (InputStream input = resource.getInputStream()) {
        bytes = input.readNBytes(MAX_CERTIFICATE_BYTES + 1);
      }
    } else {
      try (InputStream input = Files.newInputStream(Path.of(configuredPath))) {
        bytes = input.readNBytes(MAX_CERTIFICATE_BYTES + 1);
      }
    }
    if (bytes.length == 0 || bytes.length > MAX_CERTIFICATE_BYTES) {
      throw new IOException("Configured certificate file is outside the accepted size bound");
    }
    return bytes;
  }

  private static List<X509Certificate> certificates(byte[] encoded) throws IOException {
    try {
      Collection<? extends Certificate> parsed =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(encoded));
      return parsed.stream()
          .filter(X509Certificate.class::isInstance)
          .map(X509Certificate.class::cast)
          .toList();
    } catch (java.security.cert.CertificateException exception) {
      throw new IOException("Configured certificate could not be parsed", exception);
    }
  }

  private static String fingerprint(X509Certificate certificate) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException exception) {
      throw invalidEvidence();
    }
  }

  private static byte[] readImmutableWireFile(String configuredPath) {
    if (blank(configuredPath)) {
      throw invalidEvidence();
    }
    Path path;
    try {
      path = Path.of(configuredPath);
    } catch (RuntimeException exception) {
      throw invalidEvidence();
    }
    if (!path.isAbsolute()) {
      throw invalidEvidence();
    }
    return RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
        path, MAX_WIRE_BYTES, RunOwnedCanonicalWorldLaunchEvidenceProvider::invalidEvidence);
  }

  private static void requireExpectedRunBinding(String expectedRunId, String expectedProject) {
    if (!safeId(expectedRunId) || !safeId(expectedProject)) {
      throw invalidEvidence();
    }
  }

  private static boolean safeId(String value) {
    return value != null && SAFE_ID.matcher(value).matches();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static InvalidRunOwnedCanonicalWorldLaunchEvidenceException invalidEvidence() {
    return new InvalidRunOwnedCanonicalWorldLaunchEvidenceException();
  }

  private record CertificatePins(String leafSha256, String uriSan, String caSha256) {}

  @FunctionalInterface
  interface GameSessionFixtureClientFactory {
    CanonicalGameInstanceLaunchAssociationClient create(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProperties,
        GrpcChannelFactory channelFactory,
        String workloadNamespace);
  }

  private enum VerificationStage {
    ENABLED,
    RUN_BINDING,
    CAPABILITY_FILE,
    RECEIPT_FILE,
    WIRE_PARSE,
    WORLD_CERTIFICATE_PINS,
    GAME_SESSION_PEER,
    DESCRIPTOR_PARSE,
    OWNER_DESCRIPTOR_READ,
    OWNER_DESCRIPTOR_COMPARE,
    GAME_SESSION_FIXTURE_READ,
    GAME_SESSION_FIXTURE_COMPARE
  }

  /** Content-free failure so malformed or secret-bearing fixture bytes never reach logs. */
  public static final class InvalidRunOwnedCanonicalWorldLaunchEvidenceException
      extends IllegalArgumentException {
    private InvalidRunOwnedCanonicalWorldLaunchEvidenceException() {
      super("Run-owned World launch evidence is invalid");
    }
  }
}
