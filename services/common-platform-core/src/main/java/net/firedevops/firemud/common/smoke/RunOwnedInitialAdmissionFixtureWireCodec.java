package net.firedevops.firemud.common.smoke;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict, bounded decoding for the immutable run-owned initial-admission fixture wire files.
 *
 * <p>The parsed records are wire data only. They do not prove file immutability, process identity,
 * TLS identity, or authorization and must not be used as trusted service carriers without the
 * owning service's independent checks.
 */
public final class RunOwnedInitialAdmissionFixtureWireCodec {
  public static final String CAPABILITY_SCHEMA = "firemud.run-owned-initial-admission-fixture.v1";
  public static final String SOURCE_RECEIPT_SCHEMA =
      "firemud.run-owned-authored-world-source-receipt.v1";
  public static final String SOURCE_RECEIPT_OWNER_SERVICE = "game-design-service";
  public static final String SOURCE_TARGET_NAMESPACE = "dev";
  public static final String LAUNCH_READ_EVIDENCE_SCHEMA =
      "firemud.run-owned-v1-launch-read-evidence.v1";
  public static final String GAME_SESSION_URI_SAN =
      "spiffe://firemud/ns/dev/sa/game-session-service";
  public static final String WORLD_MANAGEMENT_URI_SAN =
      "spiffe://firemud/ns/dev/sa/world-management-service";
  public static final String WORLD_SLUG = "demo";
  public static final String WORLD_DISPLAY_NAME = "Demo World";
  public static final String REALM_SLUG = "production";
  public static final String REALM_DISPLAY_NAME = "Live Realm";
  public static final String STATE_SCOPE = "SHARED";
  public static final String CHARACTER_CREATION_POLICY = "ALLOW_NEW";

  private static final int MAX_WIRE_BYTES = 16 * 1024;
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> CAPABILITY_FIELDS =
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
          "caCertificateSha256",
          "worldManagementUriSan",
          "worldManagementLeafSha256");
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
          "worldManagementUriSan",
          "worldManagementLeafSha256",
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
  private static final Set<String> LAUNCH_READ_EVIDENCE_FIELDS =
      Set.of(
          "schema",
          "gameTemplateId",
          "publishedReleaseBundleBase64",
          "versionAssetArtifactStateBase64",
          "versionStateBase64",
          "launchDescriptorBase64");
  private static final ObjectMapper STRICT_JSON_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RunOwnedInitialAdmissionFixtureWireCodec() {}

  /**
   * Parses the capability envelope as untrusted data; caller bindings and certificates are checked
   * elsewhere.
   */
  public static Capability parseCapability(byte[] capabilityBytes) {
    JsonNode root = parseRoot(capabilityBytes);
    if (!hasExactFields(root, CAPABILITY_FIELDS)) {
      throw invalidWireData();
    }

    Capability capability =
        new Capability(
            requiredText(root, "schema"),
            requiredText(root, "runId"),
            requiredText(root, "composeProjectName"),
            requiredText(root, "operationId"),
            requiredPositiveLong(root, "tenantId"),
            requiredPositiveLong(root, "gameTemplateId"),
            requiredPositiveLong(root, "ownerAccountId"),
            requiredText(root, "worldSlug"),
            requiredText(root, "worldDisplayName"),
            requiredText(root, "realmSlug"),
            requiredText(root, "realmDisplayName"),
            requiredBoolean(root, "visible"),
            requiredBoolean(root, "publicProductionRealm"),
            requiredBoolean(root, "requiresCharacterSelection"),
            requiredText(root, "stateScope"),
            requiredText(root, "characterCreationPolicy"),
            requiredText(root, "gameSessionLeafSha256"),
            requiredText(root, "gameSessionUriSan"),
            requiredText(root, "caCertificateSha256"),
            requiredText(root, "worldManagementUriSan"),
            requiredText(root, "worldManagementLeafSha256"));
    requireValidCapability(capability);
    return capability;
  }

  /**
   * Parses the receipt and its owner snapshots as untrusted data for the exact parsed capability.
   */
  public static SourceReceipt parseSourceReceipt(byte[] receiptBytes, Capability capability) {
    try {
      requireValidCapability(capability);
      JsonNode root = parseRoot(receiptBytes);
      if (!hasExactFields(root, SOURCE_RECEIPT_FIELDS)) {
        throw invalidWireData();
      }

      String schema = requiredText(root, "schema");
      String runId = requiredText(root, "runId");
      String composeProjectName = requiredText(root, "composeProjectName");
      String fixtureOperationId = requiredText(root, "fixtureOperationId");
      String ownerService = requiredText(root, "ownerService");
      String gameSessionUriSan = requiredText(root, "gameSessionUriSan");
      String gameSessionLeafSha256 = requiredText(root, "gameSessionLeafSha256");
      String caCertificateSha256 = requiredText(root, "caCertificateSha256");
      String worldManagementUriSan = requiredText(root, "worldManagementUriSan");
      String worldManagementLeafSha256 = requiredText(root, "worldManagementLeafSha256");
      AuthoredWorldSourceEvidence sourceEvidence = parseSourceEvidence(root.get("sourceEvidence"));
      LaunchReadEvidence launchReadEvidence =
          parseLaunchReadEvidence(root.get("launchReadEvidence"), capability, sourceEvidence);

      if (!SOURCE_RECEIPT_SCHEMA.equals(schema)
          || !runId.equals(capability.runId())
          || !composeProjectName.equals(capability.composeProjectName())
          || !fixtureOperationId.equals(capability.operationId())
          || !SOURCE_RECEIPT_OWNER_SERVICE.equals(ownerService)
          || !gameSessionUriSan.equals(capability.gameSessionUriSan())
          || !gameSessionLeafSha256.equals(capability.gameSessionLeafSha256())
          || !caCertificateSha256.equals(capability.caCertificateSha256())
          || !worldManagementUriSan.equals(capability.worldManagementUriSan())
          || !worldManagementLeafSha256.equals(capability.worldManagementLeafSha256())
          || !SOURCE_TARGET_NAMESPACE.equals(sourceEvidence.targetNamespace())
          || !capability.operationId().equals(sourceEvidence.registrationRequestId().toString())
          || !capability.worldSlug().equals(sourceEvidence.worldSlug())
          || !capability.worldDisplayName().equals(sourceEvidence.worldDisplayName())
          || !Long.toString(capability.tenantId()).equals(sourceEvidence.sourceGameTenantKey())) {
        throw invalidWireData();
      }

      return new SourceReceipt(
          schema,
          runId,
          composeProjectName,
          fixtureOperationId,
          ownerService,
          gameSessionUriSan,
          gameSessionLeafSha256,
          caCertificateSha256,
          worldManagementUriSan,
          worldManagementLeafSha256,
          sourceEvidence,
          launchReadEvidence);
    } catch (InvalidProtocolBufferException | RuntimeException exception) {
      if (exception instanceof InvalidWireDataException invalid) {
        throw invalid;
      }
      throw invalidWireData();
    }
  }

  private static LaunchReadEvidence parseLaunchReadEvidence(
      JsonNode evidence, Capability capability, AuthoredWorldSourceEvidence sourceEvidence)
      throws InvalidProtocolBufferException {
    if (evidence == null
        || !evidence.isObject()
        || !hasExactFields(evidence, LAUNCH_READ_EVIDENCE_FIELDS)) {
      throw invalidWireData();
    }
    String schema = requiredText(evidence, "schema");
    long gameTemplateId = requiredPositiveLong(evidence, "gameTemplateId");
    if (!LAUNCH_READ_EVIDENCE_SCHEMA.equals(schema)
        || gameTemplateId != capability.gameTemplateId()) {
      throw invalidWireData();
    }

    PublishedReleaseBundle bundle =
        parseProto(
            requiredBase64(evidence, "publishedReleaseBundleBase64"),
            PublishedReleaseBundle::parseFrom);
    VersionAssetArtifactState artifactState =
        parseProto(
            requiredBase64(evidence, "versionAssetArtifactStateBase64"),
            VersionAssetArtifactState::parseFrom);
    VersionStateSnapshot versionState =
        parseProto(requiredBase64(evidence, "versionStateBase64"), VersionStateSnapshot::parseFrom);
    LaunchDescriptor launchDescriptor =
        parseProto(requiredBase64(evidence, "launchDescriptorBase64"), LaunchDescriptor::parseFrom);
    requireNoUnknownFieldsRecursively(bundle);
    requireNoUnknownFieldsRecursively(artifactState);
    requireNoUnknownFieldsRecursively(versionState);
    requireNoUnknownFieldsRecursively(launchDescriptor);
    requireLaunchReadEvidenceIdentity(
        capability, sourceEvidence, bundle, artifactState, versionState);
    requireLaunchDescriptorIdentity(
        capability, sourceEvidence, bundle, artifactState, versionState, launchDescriptor);
    return new LaunchReadEvidence(
        schema, gameTemplateId, bundle, artifactState, versionState, launchDescriptor);
  }

  private static void requireLaunchDescriptorIdentity(
      Capability capability,
      AuthoredWorldSourceEvidence sourceEvidence,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState,
      LaunchDescriptor launchDescriptor) {
    AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            SOURCE_TARGET_NAMESPACE,
            capability.operationId(),
            sourceEvidence.canonicalTenantId(),
            sourceEvidence.worldSlug(),
            sourceEvidence.operationId(),
            sourceEvidence.evidenceDigest(),
            capability.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
            expectedRequest, launchDescriptor);
    if (!descriptor.canonicalTenantId().equals(sourceEvidence.canonicalTenantId())
        || !descriptor.worldSlug().equals(sourceEvidence.worldSlug())
        || !descriptor.authoredWorldSourceOperationId().equals(sourceEvidence.operationId())
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(sourceEvidence.evidenceDigest())
        || descriptor.gameTemplateId() != capability.gameTemplateId()
        || descriptor.versionId() != bundle.getVersionId()
        || descriptor.versionId() != artifactState.getVersionId()
        || descriptor.versionId() != versionState.getVersionId()
        || descriptor.releaseBundleId() != bundle.getId()
        || !descriptor.publishedReleaseBundleRef().equals(bundle.getPublishedReleaseBundleRef())
        || !descriptor.generationConfigRevision().equals(bundle.getGenerationConfigRevision())
        || descriptor.versionStateEpoch() != versionState.getVersionStateEpoch()) {
      throw invalidWireData();
    }
  }

  private static void requireLaunchReadEvidenceIdentity(
      Capability capability,
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
      throw invalidWireData();
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
        throw invalidWireData();
      }
    }
  }

  private static AuthoredWorldSourceEvidence parseSourceEvidence(JsonNode source) {
    if (source == null || !source.isObject() || !hasExactFields(source, SOURCE_EVIDENCE_FIELDS)) {
      throw invalidWireData();
    }
    try {
      return new AuthoredWorldSourceEvidence(
          requiredInt(source, "schemaVersion"),
          requiredText(source, "targetNamespace"),
          requiredUuid(source, "registrationRequestId"),
          requiredUuid(source, "operationId"),
          requiredText(source, "requestDigest"),
          requiredUuid(source, "canonicalTenantId"),
          requiredText(source, "tenantSlug"),
          requiredText(source, "worldSlug"),
          requiredText(source, "worldDisplayName"),
          requiredLong(source, "sourceGameRowId"),
          requiredText(source, "sourceGameTenantKey"),
          requiredText(source, "provenanceKind"),
          requiredText(source, "evidenceDigest"));
    } catch (RuntimeException exception) {
      throw invalidWireData();
    }
  }

  private static void requireValidCapability(Capability capability) {
    if (capability == null
        || !CAPABILITY_SCHEMA.equals(capability.schema())
        || !SAFE_ID.matcher(capability.runId()).matches()
        || !SAFE_ID.matcher(capability.composeProjectName()).matches()
        || !isCanonicalUuid(capability.operationId())
        || capability.tenantId() <= 0L
        || capability.gameTemplateId() <= 0L
        || capability.ownerAccountId() <= 0L
        || !WORLD_SLUG.equals(capability.worldSlug())
        || !WORLD_DISPLAY_NAME.equals(capability.worldDisplayName())
        || !REALM_SLUG.equals(capability.realmSlug())
        || !REALM_DISPLAY_NAME.equals(capability.realmDisplayName())
        || !capability.visible()
        || !capability.publicProductionRealm()
        || capability.requiresCharacterSelection()
        || !STATE_SCOPE.equals(capability.stateScope())
        || !CHARACTER_CREATION_POLICY.equals(capability.characterCreationPolicy())
        || !SHA_256.matcher(capability.gameSessionLeafSha256()).matches()
        || !GAME_SESSION_URI_SAN.equals(capability.gameSessionUriSan())
        || !SHA_256.matcher(capability.caCertificateSha256()).matches()
        || !WORLD_MANAGEMENT_URI_SAN.equals(capability.worldManagementUriSan())
        || !SHA_256.matcher(capability.worldManagementLeafSha256()).matches()) {
      throw invalidWireData();
    }
  }

  private static JsonNode parseRoot(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_WIRE_BYTES) {
      throw invalidWireData();
    }
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = STRICT_JSON_MAPPER.readTree(json);
      if (root == null || !root.isObject()) {
        throw invalidWireData();
      }
      return root;
    } catch (CharacterCodingException | JacksonException exception) {
      throw invalidWireData();
    }
  }

  private static <T extends Message> T parseProto(String encoded, ProtoParser<T> parser)
      throws InvalidProtocolBufferException {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw invalidWireData();
    }
    if (decoded.length > MAX_WIRE_BYTES
        || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
      throw invalidWireData();
    }
    return parser.parse(decoded);
  }

  private static void requireNoUnknownFieldsRecursively(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalidWireData();
    }
    for (Object value : message.getAllFields().values()) {
      requireNoUnknownFieldsRecursively(value);
    }
  }

  private static void requireNoUnknownFieldsRecursively(Object value) {
    if (value instanceof Message message) {
      requireNoUnknownFieldsRecursively(message);
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

  private static boolean hasExactFields(JsonNode root, Set<String> allowed) {
    Set<String> fields = new HashSet<>();
    root.properties().forEach(property -> fields.add(property.getKey()));
    return fields.equals(allowed);
  }

  private static long requiredPositiveLong(JsonNode root, String field) {
    long value = requiredLong(root, field);
    if (value <= 0L) {
      throw invalidWireData();
    }
    return value;
  }

  private static long requiredLong(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalidWireData();
    }
    return value.longValue();
  }

  private static int requiredInt(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalidWireData();
    }
    return value.intValue();
  }

  private static boolean requiredBoolean(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalidWireData();
    }
    return value.booleanValue();
  }

  private static String requiredText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw invalidWireData();
    }
    String parsed = value.textValue();
    if (parsed.isBlank()
        || parsed.length() > 256
        || !parsed.equals(parsed.trim())
        || parsed.indexOf('\n') >= 0
        || parsed.indexOf('\r') >= 0) {
      throw invalidWireData();
    }
    return parsed;
  }

  private static String requiredBase64(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw invalidWireData();
    }
    String parsed = value.textValue();
    if (parsed.isBlank()
        || parsed.length() > MAX_WIRE_BYTES
        || !parsed.equals(parsed.trim())
        || parsed.indexOf('\n') >= 0
        || parsed.indexOf('\r') >= 0) {
      throw invalidWireData();
    }
    return parsed;
  }

  private static UUID requiredUuid(JsonNode root, String field) {
    String value = requiredText(root, field);
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        throw invalidWireData();
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw invalidWireData();
    }
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (RuntimeException exception) {
      return false;
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

  private static InvalidWireDataException invalidWireData() {
    return new InvalidWireDataException();
  }

  @FunctionalInterface
  private interface ProtoParser<T extends Message> {
    T parse(byte[] bytes) throws InvalidProtocolBufferException;
  }

  /** Parsed capability wire data. It is not a verified or trusted service capability. */
  public record Capability(
      String schema,
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
      String worldManagementLeafSha256) {}

  /** Parsed source-receipt wire data. It is not a verified or trusted service carrier. */
  public record SourceReceipt(
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
      LaunchReadEvidence launchReadEvidence) {}

  /** Parsed launch projections and the actual immutable owner-issued descriptor message. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable value objects.")
  public record LaunchReadEvidence(
      String schema,
      long gameTemplateId,
      PublishedReleaseBundle publishedReleaseBundle,
      VersionAssetArtifactState versionAssetArtifactState,
      VersionStateSnapshot versionState,
      LaunchDescriptor launchDescriptor) {}

  /** Content-free wire failure so callers cannot log receipt or capability contents. */
  public static final class InvalidWireDataException extends IllegalArgumentException {
    private InvalidWireDataException() {
      super("Run-owned initial-admission fixture wire data is invalid");
    }
  }
}
