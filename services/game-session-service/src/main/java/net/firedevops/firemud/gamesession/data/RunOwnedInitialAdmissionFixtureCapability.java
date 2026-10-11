package net.firedevops.firemud.gamesession.data;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    String caCertificateSha256) {
  public static final String CAPABILITY_SCHEMA = "firemud.run-owned-initial-admission-fixture.v1";
  public static final String GAME_SESSION_URI_SAN =
      "spiffe://firemud/ns/dev/sa/game-session-service";
  public static final String WORLD_SLUG = "demo";
  public static final String WORLD_DISPLAY_NAME = "Demo World";
  public static final String REALM_SLUG = "production";
  public static final String REALM_DISPLAY_NAME = "Live Realm";
  public static final String STATE_SCOPE = "SHARED";
  public static final String CHARACTER_CREATION_POLICY = "ALLOW_NEW";
  public static final String SOURCE_RECEIPT_SCHEMA =
      "firemud.run-owned-authored-world-source-receipt.v1";
  public static final String SOURCE_RECEIPT_OWNER_SERVICE = "game-design-service";
  public static final String SOURCE_TARGET_NAMESPACE = "dev";

  private static final int MAX_CAPABILITY_BYTES = 16 * 1024;
  private static final int MAX_SOURCE_RECEIPT_BYTES = 16 * 1024;
  private static final int MAX_CERTIFICATE_FILE_BYTES = 1024 * 1024;
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> ALLOWED_FIELDS =
      Set.of(
          "schema",
          "runId",
          "composeProjectName",
          "operationId",
          "tenantId",
          "gameTemplateId",
          "ownerAccountId",
          "worldSlug",
          "worldDisplayName",
          "realmSlug",
          "realmDisplayName",
          "visible",
          "publicProductionRealm",
          "requiresCharacterSelection",
          "stateScope",
          "characterCreationPolicy",
          "gameSessionLeafSha256",
          "gameSessionUriSan",
          "caCertificateSha256");
  private static final Set<String> SOURCE_RECEIPT_FIELDS =
      Set.of(
          "schema",
          "runId",
          "composeProjectName",
          "fixtureOperationId",
          "ownerService",
          "gameSessionUriSan",
          "gameSessionLeafSha256",
          "caCertificateSha256",
          "sourceEvidence",
          "launchReadEvidence");
  private static final Set<String> SOURCE_EVIDENCE_FIELDS =
      Set.of(
          "schemaVersion",
          "targetNamespace",
          "registrationRequestId",
          "operationId",
          "requestDigest",
          "canonicalTenantId",
          "tenantSlug",
          "worldSlug",
          "worldDisplayName",
          "sourceGameRowId",
          "sourceGameTenantKey",
          "provenanceKind",
          "evidenceDigest");
  static final String LAUNCH_READ_EVIDENCE_SCHEMA = RunOwnedInitialAdmissionLaunchEvidence.SCHEMA;
  private static final Set<String> LAUNCH_READ_EVIDENCE_FIELDS =
      Set.of(
          "schema",
          "gameTemplateId",
          "publishedReleaseBundleBase64",
          "versionAssetArtifactStateBase64",
          "versionStateBase64");
  private static final ObjectMapper STRICT_JSON_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

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
    } catch (InvalidCapabilityException exception) {
      throw invalidSourceReceipt();
    } catch (RuntimeException exception) {
      throw invalidSourceReceipt();
    }
  }

  static byte[] readSourceReceipt(Path path) {
    if (path == null || !path.isAbsolute()) {
      throw invalidSourceReceipt();
    }
    try {
      BasicFileAttributes before =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() <= 0L
          || before.size() > MAX_SOURCE_RECEIPT_BYTES
          || !isReadOnly(path)) {
        throw invalidSourceReceipt();
      }

      ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) before.size());
      try (SeekableByteChannel channel =
              Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
          InputStream input = Channels.newInputStream(channel)) {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read == 0) {
            continue;
          }
          if (bytes.size() + read > MAX_SOURCE_RECEIPT_BYTES) {
            throw invalidSourceReceipt();
          }
          bytes.write(buffer, 0, read);
        }
      }

      BasicFileAttributes after =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() != after.size()
          || bytes.size() != before.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !sameFileKey(before.fileKey(), after.fileKey())
          || !isReadOnly(path)) {
        throw invalidSourceReceipt();
      }
      return bytes.toByteArray();
    } catch (IOException | SecurityException exception) {
      throw invalidSourceReceipt();
    }
  }

  static SourceReceipt parseSourceReceipt(
      byte[] receiptBytes,
      String expectedRunId,
      String expectedComposeProjectName,
      RunOwnedInitialAdmissionFixtureCapability capability,
      CertificatePins certificatePins) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    if (receiptBytes == null
        || receiptBytes.length == 0
        || receiptBytes.length > MAX_SOURCE_RECEIPT_BYTES
        || capability == null
        || certificatePins == null
        || !expectedRunId.equals(capability.runId())
        || !expectedComposeProjectName.equals(capability.composeProjectName())) {
      throw invalidSourceReceipt();
    }

    JsonNode root;
    try {
      root = STRICT_JSON_MAPPER.readTree(decodeUtf8(receiptBytes));
    } catch (CharacterCodingException | JacksonException exception) {
      throw invalidSourceReceipt();
    }
    if (root == null || !root.isObject() || !hasExactFields(root, SOURCE_RECEIPT_FIELDS)) {
      throw invalidSourceReceipt();
    }

    String schema = requiredSourceReceiptText(root, "schema");
    String runId = requiredSourceReceiptText(root, "runId");
    String composeProjectName = requiredSourceReceiptText(root, "composeProjectName");
    String fixtureOperationId = requiredSourceReceiptText(root, "fixtureOperationId");
    String ownerService = requiredSourceReceiptText(root, "ownerService");
    String uriSan = requiredSourceReceiptText(root, "gameSessionUriSan");
    String leafSha256 = requiredSourceReceiptText(root, "gameSessionLeafSha256");
    String caSha256 = requiredSourceReceiptText(root, "caCertificateSha256");
    AuthoredWorldSourceEvidence sourceEvidence = parseSourceEvidence(root.get("sourceEvidence"));
    LaunchReadEvidence launchReadEvidence =
        parseLaunchReadEvidence(root.get("launchReadEvidence"), capability, sourceEvidence);

    if (!SOURCE_RECEIPT_SCHEMA.equals(schema)
        || !runId.equals(expectedRunId)
        || !composeProjectName.equals(expectedComposeProjectName)
        || !fixtureOperationId.equals(capability.operationId())
        || !ownerService.equals(SOURCE_RECEIPT_OWNER_SERVICE)
        || !uriSan.equals(capability.gameSessionUriSan())
        || !leafSha256.equals(capability.gameSessionLeafSha256())
        || !caSha256.equals(capability.caCertificateSha256())
        || !certificatePins.uriSan().equals(uriSan)
        || !certificatePins.leafSha256().equals(leafSha256)
        || !certificatePins.caSha256().equals(caSha256)
        || !SOURCE_TARGET_NAMESPACE.equals(sourceEvidence.targetNamespace())
        || !capability.operationId().equals(sourceEvidence.registrationRequestId().toString())
        || !capability.worldSlug().equals(sourceEvidence.worldSlug())
        || !capability.worldDisplayName().equals(sourceEvidence.worldDisplayName())
        || !Long.toString(capability.tenantId()).equals(sourceEvidence.sourceGameTenantKey())) {
      throw invalidSourceReceipt();
    }

    return new SourceReceipt(
        schema,
        runId,
        composeProjectName,
        fixtureOperationId,
        ownerService,
        uriSan,
        leafSha256,
        caSha256,
        sourceEvidence,
        launchReadEvidence);
  }

  private static LaunchReadEvidence parseLaunchReadEvidence(
      JsonNode evidence,
      RunOwnedInitialAdmissionFixtureCapability capability,
      AuthoredWorldSourceEvidence sourceEvidence) {
    if (evidence == null
        || !evidence.isObject()
        || !hasExactFields(evidence, LAUNCH_READ_EVIDENCE_FIELDS)) {
      throw invalidSourceReceipt();
    }
    String schema = requiredSourceReceiptText(evidence, "schema");
    long gameTemplateId = requiredPositiveSourceReceiptLong(evidence, "gameTemplateId");
    if (!LAUNCH_READ_EVIDENCE_SCHEMA.equals(schema)
        || gameTemplateId != capability.gameTemplateId()) {
      throw invalidSourceReceipt();
    }

    PublishedReleaseBundle bundle =
        parseLaunchReadEvidenceProto(
            requiredSourceReceiptBase64(evidence, "publishedReleaseBundleBase64"),
            PublishedReleaseBundle::parseFrom);
    VersionAssetArtifactState artifactState =
        parseLaunchReadEvidenceProto(
            requiredSourceReceiptBase64(evidence, "versionAssetArtifactStateBase64"),
            VersionAssetArtifactState::parseFrom);
    VersionStateSnapshot versionState =
        parseLaunchReadEvidenceProto(
            requiredSourceReceiptBase64(evidence, "versionStateBase64"),
            VersionStateSnapshot::parseFrom);
    requireNoUnknownFieldsRecursively(bundle);
    requireNoUnknownFieldsRecursively(artifactState);
    requireNoUnknownFieldsRecursively(versionState);
    requireLaunchReadEvidenceIdentity(
        capability, sourceEvidence, bundle, artifactState, versionState);
    return new LaunchReadEvidence(schema, gameTemplateId, bundle, artifactState, versionState);
  }

  private static <T extends Message> T parseLaunchReadEvidenceProto(
      String encoded, ProtoParser<T> parser) {
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length > MAX_SOURCE_RECEIPT_BYTES
          || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
        throw invalidSourceReceipt();
      }
      return parser.parse(decoded);
    } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
      throw invalidSourceReceipt();
    }
  }

  private static void requireNoUnknownFieldsRecursively(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalidSourceReceipt();
    }
    for (Object value : message.getAllFields().values()) {
      requireNoUnknownFieldsRecursively(value);
    }
  }

  static void requireNoUnknownFieldsRecursively(Object value) {
    if (value instanceof Message nested) {
      requireNoUnknownFieldsRecursively(nested);
    } else if (value instanceof Map<?, ?> map) {
      for (Object nested : map.values()) {
        requireNoUnknownFieldsRecursively(nested);
      }
    } else if (value instanceof Iterable<?> values) {
      for (Object nested : values) {
        requireNoUnknownFieldsRecursively(nested);
      }
    }
  }

  private static void requireLaunchReadEvidenceIdentity(
      RunOwnedInitialAdmissionFixtureCapability capability,
      AuthoredWorldSourceEvidence sourceEvidence,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState) {
    String expectedTenantId = Long.toString(capability.tenantId());
    String canonicalTenantId = sourceEvidence.canonicalTenantId().toString();
    if (bundle.getId() <= 0L
        || bundle.getVersionId() <= 0L
        || bundle.getVersionNumber() <= 0
        || !"v1".equals(bundle.getAttestationSchemaVersion())
        || bundle.getManifestHash().isBlank()
        || bundle.getGenerationConfigRevision().isBlank()
        || bundle.getPublishedReleaseBundleRef().isBlank()
        || !isCanonicalNonNilUuid(bundle.getCanonicalTenantId())
        || !canonicalTenantId.equals(bundle.getCanonicalTenantId())
        || !isCanonicalNonNilUuid(bundle.getCanonicalVersionId())
        || (bundle.hasManifestSchemaVersion() && bundle.getManifestSchemaVersion() <= 0)
        || (!bundle.hasManifestSchemaVersion() && bundle.getArtifactDigestsCount() != 0)
        || !expectedTenantId.equals(artifactState.getTenantId())
        || artifactState.getVersionId() != bundle.getVersionId()
        || artifactState.getArtifactState()
            != net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED
        || artifactState.getStateEpoch() <= 0L
        || !bundle.getManifestHash().equals(artifactState.getManifestHash())
        || artifactState.getExportedVersionNumber() != bundle.getVersionNumber()
        || !artifactState
            .getExportedManifestAssetKeysList()
            .containsAll(bundle.getRequiredManifestAssetKeysList())
        || !expectedTenantId.equals(versionState.getTenantId())
        || versionState.getVersionId() != bundle.getVersionId()
        || (versionState.getVersionState()
                != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
            && versionState.getVersionState()
                != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE)
        || versionState.getVersionStateEpoch() <= 0L
        || versionState.getUpdatedAt().isBlank()) {
      throw invalidSourceReceipt();
    }
    requirePublishedArtifactDigests(bundle);
  }

  private static void requirePublishedArtifactDigests(PublishedReleaseBundle bundle) {
    Set<String> usageKeys = new HashSet<>();
    for (var artifact : bundle.getArtifactDigestsList()) {
      String contentDigest = artifact.getContentDigest();
      if (artifact.getUsageKey().isBlank()
          || artifact.getArtifactKind().isBlank()
          || artifact.getContentType().isBlank()
          || artifact.getArtifactSchemaVersion() <= 0
          || !contentDigest.matches("sha256:[0-9a-f]{64}")
          || !artifact
              .getImmutableObjectKey()
              .equals("artifacts/sha256/" + contentDigest.substring("sha256:".length()))
          || !usageKeys.add(artifact.getUsageKey())) {
        throw invalidSourceReceipt();
      }
    }
  }

  private static boolean isCanonicalNonNilUuid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return (parsed.getMostSignificantBits() != 0L || parsed.getLeastSignificantBits() != 0L)
          && parsed.toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static AuthoredWorldSourceEvidence parseSourceEvidence(JsonNode source) {
    if (source == null || !source.isObject() || !hasExactFields(source, SOURCE_EVIDENCE_FIELDS)) {
      throw invalidSourceReceipt();
    }
    int schemaVersion = requiredSourceReceiptInt(source, "schemaVersion");
    String targetNamespace = requiredSourceReceiptText(source, "targetNamespace");
    UUID registrationRequestId = requiredSourceReceiptUuid(source, "registrationRequestId");
    UUID operationId = requiredSourceReceiptUuid(source, "operationId");
    String requestDigest = requiredSourceReceiptText(source, "requestDigest");
    UUID canonicalTenantId = requiredSourceReceiptUuid(source, "canonicalTenantId");
    String tenantSlug = requiredSourceReceiptText(source, "tenantSlug");
    String worldSlug = requiredSourceReceiptText(source, "worldSlug");
    String worldDisplayName = requiredSourceReceiptText(source, "worldDisplayName");
    long sourceGameRowId = requiredSourceReceiptLong(source, "sourceGameRowId");
    String sourceGameTenantKey = requiredSourceReceiptText(source, "sourceGameTenantKey");
    String provenanceKind = requiredSourceReceiptText(source, "provenanceKind");
    String evidenceDigest = requiredSourceReceiptText(source, "evidenceDigest");
    try {
      return new AuthoredWorldSourceEvidence(
          schemaVersion,
          targetNamespace,
          registrationRequestId,
          operationId,
          requestDigest,
          canonicalTenantId,
          tenantSlug,
          worldSlug,
          worldDisplayName,
          sourceGameRowId,
          sourceGameTenantKey,
          provenanceKind,
          evidenceDigest);
    } catch (RuntimeException exception) {
      throw invalidSourceReceipt();
    }
  }

  private static int requiredSourceReceiptInt(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalidSourceReceipt();
    }
    return value.intValue();
  }

  private static long requiredSourceReceiptLong(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalidSourceReceipt();
    }
    return value.longValue();
  }

  private static long requiredPositiveSourceReceiptLong(JsonNode root, String field) {
    long value = requiredSourceReceiptLong(root, field);
    if (value <= 0L) {
      throw invalidSourceReceipt();
    }
    return value;
  }

  private static UUID requiredSourceReceiptUuid(JsonNode root, String field) {
    String text = requiredSourceReceiptText(root, field);
    try {
      UUID uuid = UUID.fromString(text);
      if (!uuid.toString().equals(text)) {
        throw invalidSourceReceipt();
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw invalidSourceReceipt();
    }
  }

  private static String requiredSourceReceiptText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw invalidSourceReceipt();
    }
    String parsed = value.textValue();
    if (parsed.isBlank()
        || parsed.length() > 256
        || !parsed.equals(parsed.trim())
        || parsed.indexOf('\n') >= 0
        || parsed.indexOf('\r') >= 0) {
      throw invalidSourceReceipt();
    }
    return parsed;
  }

  private static String requiredSourceReceiptBase64(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw invalidSourceReceipt();
    }
    String parsed = value.textValue();
    if (parsed.isBlank()
        || parsed.length() > MAX_SOURCE_RECEIPT_BYTES
        || !parsed.equals(parsed.trim())
        || parsed.indexOf('\n') >= 0
        || parsed.indexOf('\r') >= 0) {
      throw invalidSourceReceipt();
    }
    return parsed;
  }

  private static boolean hasExactFields(JsonNode root, Set<String> allowed) {
    Set<String> fields = new HashSet<>();
    root.properties().forEach(property -> fields.add(property.getKey()));
    return fields.equals(allowed);
  }

  static RunOwnedInitialAdmissionFixtureCapability parseAndValidate(
      byte[] capabilityBytes,
      String expectedRunId,
      String expectedComposeProjectName,
      CertificatePins certificatePins) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    if (capabilityBytes == null
        || capabilityBytes.length == 0
        || capabilityBytes.length > MAX_CAPABILITY_BYTES
        || certificatePins == null) {
      throw invalidCapability();
    }

    JsonNode root;
    try {
      String json = decodeUtf8(capabilityBytes);
      root = STRICT_JSON_MAPPER.readTree(json);
    } catch (CharacterCodingException | JacksonException exception) {
      throw invalidCapability();
    }
    if (root == null || !root.isObject() || !exactFields(root)) {
      throw invalidCapability();
    }

    String schema = requiredText(root, "schema");
    String runId = requiredText(root, "runId");
    String composeProjectName = requiredText(root, "composeProjectName");
    String operationId = requiredText(root, "operationId");
    long tenantId = requiredPositiveLong(root, "tenantId");
    long gameTemplateId = requiredPositiveLong(root, "gameTemplateId");
    long ownerAccountId = requiredPositiveLong(root, "ownerAccountId");
    String worldSlug = requiredText(root, "worldSlug");
    String worldDisplayName = requiredText(root, "worldDisplayName");
    String realmSlug = requiredText(root, "realmSlug");
    String realmDisplayName = requiredText(root, "realmDisplayName");
    JsonNode visible = root.get("visible");
    JsonNode publicProductionRealm = root.get("publicProductionRealm");
    JsonNode characterSelection = root.get("requiresCharacterSelection");
    String stateScope = requiredText(root, "stateScope");
    String characterCreationPolicy = requiredText(root, "characterCreationPolicy");
    String leafSha256 = requiredText(root, "gameSessionLeafSha256");
    String uriSan = requiredText(root, "gameSessionUriSan");
    String caSha256 = requiredText(root, "caCertificateSha256");

    if (!CAPABILITY_SCHEMA.equals(schema)
        || !runId.equals(expectedRunId)
        || !composeProjectName.equals(expectedComposeProjectName)
        || !SAFE_ID.matcher(runId).matches()
        || !SAFE_ID.matcher(composeProjectName).matches()
        || !isCanonicalUuid(operationId)
        || !WORLD_SLUG.equals(worldSlug)
        || !WORLD_DISPLAY_NAME.equals(worldDisplayName)
        || !REALM_SLUG.equals(realmSlug)
        || !REALM_DISPLAY_NAME.equals(realmDisplayName)
        || visible == null
        || !visible.isBoolean()
        || !visible.booleanValue()
        || publicProductionRealm == null
        || !publicProductionRealm.isBoolean()
        || !publicProductionRealm.booleanValue()
        || characterSelection == null
        || !characterSelection.isBoolean()
        || characterSelection.booleanValue()
        || !STATE_SCOPE.equals(stateScope)
        || !CHARACTER_CREATION_POLICY.equals(characterCreationPolicy)
        || !SHA_256.matcher(leafSha256).matches()
        || !GAME_SESSION_URI_SAN.equals(uriSan)
        || !SHA_256.matcher(caSha256).matches()
        || !certificatePins.leafSha256().equals(leafSha256)
        || !certificatePins.uriSan().equals(uriSan)
        || !certificatePins.caSha256().equals(caSha256)) {
      throw invalidCapability();
    }

    return new RunOwnedInitialAdmissionFixtureCapability(
        runId,
        composeProjectName,
        operationId,
        tenantId,
        gameTemplateId,
        ownerAccountId,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        true,
        true,
        false,
        stateScope,
        characterCreationPolicy,
        leafSha256,
        uriSan,
        caSha256);
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
    if (path == null || !path.isAbsolute()) {
      throw invalidCapability();
    }
    try {
      BasicFileAttributes before =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() <= 0L
          || before.size() > MAX_CAPABILITY_BYTES
          || !isReadOnly(path)) {
        throw invalidCapability();
      }

      ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) before.size());
      try (SeekableByteChannel channel =
              Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
          InputStream input = Channels.newInputStream(channel)) {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read == 0) {
            continue;
          }
          if (bytes.size() + read > MAX_CAPABILITY_BYTES) {
            throw invalidCapability();
          }
          bytes.write(buffer, 0, read);
        }
      }

      BasicFileAttributes after =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !sameFileKey(before.fileKey(), after.fileKey())
          || !isReadOnly(path)) {
        throw invalidCapability();
      }
      return bytes.toByteArray();
    } catch (IOException | SecurityException exception) {
      throw invalidCapability();
    }
  }

  private static boolean isReadOnly(Path path) throws IOException {
    try {
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      return permissions.contains(PosixFilePermission.OWNER_READ)
          && !permissions.contains(PosixFilePermission.OWNER_WRITE)
          && !permissions.contains(PosixFilePermission.GROUP_WRITE)
          && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
    } catch (UnsupportedOperationException exception) {
      Object readOnly = Files.getAttribute(path, "dos:readonly", LinkOption.NOFOLLOW_LINKS);
      return Boolean.TRUE.equals(readOnly) && !Files.isWritable(path);
    }
  }

  private static boolean exactFields(JsonNode root) {
    Set<String> fields = new HashSet<>();
    root.properties().forEach(property -> fields.add(property.getKey()));
    return fields.equals(ALLOWED_FIELDS);
  }

  private static long requiredPositiveLong(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalidCapability();
    }
    long parsed = value.longValue();
    if (parsed <= 0L) {
      throw invalidCapability();
    }
    return parsed;
  }

  private static String requiredText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw invalidCapability();
    }
    String parsed = value.textValue();
    if (parsed.isBlank()
        || parsed.length() > 256
        || !parsed.equals(parsed.trim())
        || parsed.indexOf('\n') >= 0
        || parsed.indexOf('\r') >= 0) {
      throw invalidCapability();
    }
    return parsed;
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void requireExpectedRunBinding(String runId, String projectName) {
    if (runId == null
        || projectName == null
        || !SAFE_ID.matcher(runId).matches()
        || !SAFE_ID.matcher(projectName).matches()) {
      throw invalidCapability();
    }
  }

  private static boolean isCanonicalUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean sameFileKey(Object before, Object after) {
    return before == null || after == null || before.equals(after);
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
      AuthoredWorldSourceEvidence sourceEvidence,
      LaunchReadEvidence launchReadEvidence) {}

  record LaunchReadEvidence(
      String schema,
      long gameTemplateId,
      PublishedReleaseBundle publishedReleaseBundle,
      VersionAssetArtifactState versionAssetArtifactState,
      VersionStateSnapshot versionState) {}

  @FunctionalInterface
  private interface ProtoParser<T extends Message> {
    T parse(byte[] bytes) throws InvalidProtocolBufferException;
  }

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
