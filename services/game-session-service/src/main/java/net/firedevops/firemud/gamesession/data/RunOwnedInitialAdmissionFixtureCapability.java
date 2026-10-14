package net.firedevops.firemud.gamesession.data;

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
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import org.springframework.core.io.ClassPathResource;

/** Strict, run-bound authority to activate the local initial-admission fixture coordinator. */
record RunOwnedInitialAdmissionFixtureCapability(
    String runId,
    String composeProjectName,
    String operationId,
    long tenantId,
    long gameTemplateId,
    long ownerAccountId,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String gameSessionLeafSha256,
    String gameSessionUriSan,
    String caCertificateSha256,
    String worldManagementUriSan,
    String worldManagementLeafSha256,
    String claimDigest) {
  public static final String CAPABILITY_SCHEMA =
      RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA;
  public static final String GAME_SESSION_URI_SAN =
      RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN;
  public static final String WORLD_MANAGEMENT_URI_SAN =
      RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN;
  public static final String WORLD_SLUG = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_SLUG;
  public static final String WORLD_DISPLAY_NAME =
      RunOwnedInitialAdmissionFixtureWireCodec.WORLD_DISPLAY_NAME;
  public static final String REALM_SLUG = RunOwnedInitialAdmissionFixtureWireCodec.REALM_SLUG;
  public static final String REALM_DISPLAY_NAME =
      RunOwnedInitialAdmissionFixtureWireCodec.REALM_DISPLAY_NAME;
  public static final String STATE_SCOPE = RunOwnedInitialAdmissionFixtureWireCodec.STATE_SCOPE;
  public static final String CHARACTER_CREATION_POLICY =
      RunOwnedInitialAdmissionFixtureWireCodec.CHARACTER_CREATION_POLICY;
  public static final String SOURCE_RECEIPT_SCHEMA =
      RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA;
  public static final String SOURCE_RECEIPT_OWNER_SERVICE =
      RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE;
  public static final String SOURCE_TARGET_NAMESPACE =
      RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE;

  private static final int MAX_CAPABILITY_BYTES = 16 * 1024;
  private static final int MAX_SOURCE_RECEIPT_BYTES = 16 * 1024;
  private static final int MAX_CERTIFICATE_FILE_BYTES = 1024 * 1024;
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
  static final String LAUNCH_READ_EVIDENCE_SCHEMA =
      RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA;

  /**
   * Loads and validates the declared local capability against the active run/project and the actual
   * certificate files configured for this Game Session process.
   */
  static RunOwnedInitialAdmissionFixtureCapability load(
      Path capabilityPath,
      String expectedRunId,
      String expectedComposeProjectName,
      CommonGrpcClientProperties grpcProperties) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    byte[] capabilityBytes = readCapability(capabilityPath);
    CertificatePins certificatePins = configuredCertificatePins(grpcProperties);
    return parseAndValidate(
        capabilityBytes, expectedRunId, expectedComposeProjectName, certificatePins);
  }

  /** Loads the local Game Design receipt only when it is closed, immutable, and run/TLS-bound. */
  static SourceReceipt loadSourceReceipt(
      Path receiptPath,
      String expectedRunId,
      String expectedComposeProjectName,
      RunOwnedInitialAdmissionFixtureCapability capability,
      CommonGrpcClientProperties grpcProperties) {
    try {
      return parseSourceReceipt(
          readSourceReceipt(receiptPath),
          expectedRunId,
          expectedComposeProjectName,
          capability,
          configuredCertificatePins(grpcProperties));
    } catch (RuntimeException exception) {
      throw invalidSourceReceipt();
    }
  }

  static byte[] readSourceReceipt(Path path) {
    return RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
        path,
        MAX_SOURCE_RECEIPT_BYTES,
        RunOwnedInitialAdmissionFixtureCapability::invalidSourceReceipt);
  }

  static SourceReceipt parseSourceReceipt(
      byte[] receiptBytes,
      String expectedRunId,
      String expectedComposeProjectName,
      RunOwnedInitialAdmissionFixtureCapability capability,
      CertificatePins certificatePins) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    if (capability == null
        || certificatePins == null
        || !expectedRunId.equals(capability.runId())
        || !expectedComposeProjectName.equals(capability.composeProjectName())) {
      throw invalidSourceReceipt();
    }

    try {
      var parsed =
          RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(
              receiptBytes, toWireCapability(capability));
      if (!expectedRunId.equals(parsed.runId())
          || !expectedComposeProjectName.equals(parsed.composeProjectName())
          || !certificatePins.uriSan().equals(parsed.gameSessionUriSan())
          || !certificatePins.leafSha256().equals(parsed.gameSessionLeafSha256())
          || !certificatePins.caSha256().equals(parsed.caCertificateSha256())) {
        throw invalidSourceReceipt();
      }

      var parsedLaunchEvidence = parsed.launchReadEvidence();
      return new SourceReceipt(
          parsed.schema(),
          parsed.runId(),
          parsed.composeProjectName(),
          parsed.fixtureOperationId(),
          parsed.ownerService(),
          parsed.gameSessionUriSan(),
          parsed.gameSessionLeafSha256(),
          parsed.caCertificateSha256(),
          parsed.worldManagementUriSan(),
          parsed.worldManagementLeafSha256(),
          parsed.sourceEvidence(),
          new LaunchReadEvidence(
              parsedLaunchEvidence.schema(),
              parsedLaunchEvidence.gameTemplateId(),
              parsedLaunchEvidence.publishedReleaseBundle(),
              parsedLaunchEvidence.versionAssetArtifactState(),
              parsedLaunchEvidence.versionState(),
              parsedLaunchEvidence.launchDescriptor()),
          parsed.creationEvidence(),
          parsed.fixturePolicySeal());
    } catch (RuntimeException exception) {
      throw invalidSourceReceipt();
    }
  }

  static RunOwnedInitialAdmissionFixtureWireCodec.Capability toWireCapability(
      RunOwnedInitialAdmissionFixtureCapability capability) {
    return new RunOwnedInitialAdmissionFixtureWireCodec.Capability(
        CAPABILITY_SCHEMA,
        capability.runId(),
        capability.composeProjectName(),
        capability.operationId(),
        capability.tenantId(),
        capability.gameTemplateId(),
        capability.ownerAccountId(),
        capability.worldSlug(),
        capability.worldDisplayName(),
        capability.realmSlug(),
        capability.realmDisplayName(),
        capability.visible(),
        capability.publicProductionRealm(),
        capability.requiresCharacterSelection(),
        capability.stateScope(),
        capability.characterCreationPolicy(),
        capability.gameSessionLeafSha256(),
        capability.gameSessionUriSan(),
        capability.caCertificateSha256(),
        capability.worldManagementUriSan(),
        capability.worldManagementLeafSha256(),
        capability.claimDigest());
  }

  static RunOwnedInitialAdmissionFixtureCapability parseAndValidate(
      byte[] capabilityBytes,
      String expectedRunId,
      String expectedComposeProjectName,
      CertificatePins certificatePins) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    if (certificatePins == null) {
      throw invalidCapability();
    }

    try {
      var parsed = RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(capabilityBytes);
      if (!expectedRunId.equals(parsed.runId())
          || !expectedComposeProjectName.equals(parsed.composeProjectName())
          || !certificatePins.leafSha256().equals(parsed.gameSessionLeafSha256())
          || !certificatePins.uriSan().equals(parsed.gameSessionUriSan())
          || !certificatePins.caSha256().equals(parsed.caCertificateSha256())) {
        throw invalidCapability();
      }
      return new RunOwnedInitialAdmissionFixtureCapability(
          parsed.runId(),
          parsed.composeProjectName(),
          parsed.operationId(),
          parsed.tenantId(),
          parsed.gameTemplateId(),
          parsed.ownerAccountId(),
          parsed.worldSlug(),
          parsed.worldDisplayName(),
          parsed.realmSlug(),
          parsed.realmDisplayName(),
          parsed.visible(),
          parsed.publicProductionRealm(),
          parsed.requiresCharacterSelection(),
          parsed.stateScope(),
          parsed.characterCreationPolicy(),
          parsed.gameSessionLeafSha256(),
          parsed.gameSessionUriSan(),
          parsed.caCertificateSha256(),
          parsed.worldManagementUriSan(),
          parsed.worldManagementLeafSha256(),
          parsed.claimDigest());
    } catch (RuntimeException exception) {
      throw invalidCapability();
    }
  }

  static CertificatePins certificatePins(X509Certificate leaf, X509Certificate ca) {
    if (leaf == null || ca == null || ca.getBasicConstraints() < 0) {
      throw invalidCapability();
    }
    String uriSan =
        GrpcPeerIdentity.fromCertificate(leaf)
            .map(GrpcPeerIdentity::uri)
            .filter(GAME_SESSION_URI_SAN::equals)
            .orElseThrow(RunOwnedInitialAdmissionFixtureCapability::invalidCapability);
    try {
      leaf.checkValidity();
      ca.checkValidity();
      leaf.verify(ca.getPublicKey());
      return new CertificatePins(fingerprint(leaf), uriSan, fingerprint(ca));
    } catch (GeneralSecurityException exception) {
      throw invalidCapability();
    }
  }

  private static CertificatePins configuredCertificatePins(
      CommonGrpcClientProperties grpcProperties) {
    if (grpcProperties == null
        || grpcProperties.isPlaintext()
        || !hasText(grpcProperties.getCertChain())
        || !hasText(grpcProperties.getCaCert())) {
      throw invalidCapability();
    }
    try {
      List<X509Certificate> chain =
          certificates(readConfiguredTlsFile(grpcProperties.getCertChain()));
      List<X509Certificate> authorities =
          certificates(readConfiguredTlsFile(grpcProperties.getCaCert()));
      if (chain.isEmpty() || authorities.size() != 1) {
        throw invalidCapability();
      }
      return certificatePins(chain.getFirst(), authorities.getFirst());
    } catch (IOException | RuntimeException exception) {
      throw invalidCapability();
    }
  }

  private static byte[] readConfiguredTlsFile(String configuredPath) throws IOException {
    byte[] bytes;
    if (configuredPath.startsWith("classpath:")) {
      ClassPathResource resource =
          new ClassPathResource(configuredPath.substring("classpath:".length()));
      try (InputStream input = resource.getInputStream()) {
        bytes = input.readNBytes(MAX_CERTIFICATE_FILE_BYTES + 1);
      }
    } else {
      try (InputStream input = Files.newInputStream(Path.of(configuredPath))) {
        bytes = input.readNBytes(MAX_CERTIFICATE_FILE_BYTES + 1);
      }
    }
    if (bytes.length == 0 || bytes.length > MAX_CERTIFICATE_FILE_BYTES) {
      throw new IOException("Configured TLS certificate material is not bounded");
    }
    return bytes;
  }

  private static List<X509Certificate> certificates(byte[] pemBytes) throws IOException {
    try {
      Collection<? extends Certificate> certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(pemBytes));
      return certificates.stream()
          .filter(X509Certificate.class::isInstance)
          .map(X509Certificate.class::cast)
          .toList();
    } catch (java.security.cert.CertificateException exception) {
      throw new IOException("Configured TLS certificate material could not be parsed", exception);
    }
  }

  private static String fingerprint(X509Certificate certificate) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException exception) {
      throw invalidCapability();
    }
  }

  static byte[] readCapability(Path path) {
    return RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
        path, MAX_CAPABILITY_BYTES, RunOwnedInitialAdmissionFixtureCapability::invalidCapability);
  }

  private static void requireExpectedRunBinding(String runId, String projectName) {
    if (runId == null
        || projectName == null
        || !SAFE_ID.matcher(runId).matches()
        || !SAFE_ID.matcher(projectName).matches()) {
      throw invalidCapability();
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static InvalidCapabilityException invalidCapability() {
    return new InvalidCapabilityException();
  }

  private static InvalidSourceReceiptException invalidSourceReceipt() {
    return new InvalidSourceReceiptException();
  }

  @Override
  public String toString() {
    return "RunOwnedInitialAdmissionFixtureCapability[verified]";
  }

  record CertificatePins(String leafSha256, String uriSan, String caSha256) {}

  record SourceReceipt(
      String schema,
      String runId,
      String composeProjectName,
      String fixtureOperationId,
      String ownerService,
      String gameSessionUriSan,
      String gameSessionLeafSha256,
      String caCertificateSha256,
      String worldManagementUriSan,
      String worldManagementLeafSha256,
      AuthoredWorldSourceEvidence sourceEvidence,
      LaunchReadEvidence launchReadEvidence,
      FreshTenantCreationEvidence creationEvidence,
      RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal fixturePolicySeal) {}

  record LaunchReadEvidence(
      String schema,
      long gameTemplateId,
      PublishedReleaseBundle publishedReleaseBundle,
      VersionAssetArtifactState versionAssetArtifactState,
      VersionStateSnapshot versionState,
      LaunchDescriptor launchDescriptor) {}

  /** Generic, content-free failure so callers cannot accidentally log capability contents. */
  public static final class InvalidCapabilityException extends IllegalArgumentException {
    private InvalidCapabilityException() {
      super("Run-owned initial-admission fixture capability is invalid");
    }
  }

  /** Generic, content-free failure so callers cannot accidentally log source receipt contents. */
  public static final class InvalidSourceReceiptException extends IllegalArgumentException {
    private InvalidSourceReceiptException() {
      super("Run-owned authored-world source receipt is invalid");
    }
  }
}
