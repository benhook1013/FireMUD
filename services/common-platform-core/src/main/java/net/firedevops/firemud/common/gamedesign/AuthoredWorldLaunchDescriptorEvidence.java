package net.firedevops.firemud.common.gamedesign;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;

/** Closed immutable launch binding evidence for an exact Game Design authored-world source. */
public record AuthoredWorldLaunchDescriptorEvidence(
    int schemaVersion,
    String targetNamespace,
    String controlPlaneRequestId,
    UUID canonicalTenantId,
    String worldSlug,
    UUID authoredWorldSourceOperationId,
    String authoredWorldSourceEvidenceDigest,
    long gameTemplateId,
    boolean requestedScriptPatchVersionPresent,
    String requestedScriptPatchVersion,
    boolean sourceVersionIdPresent,
    Long sourceVersionId,
    boolean targetVersionIdPresent,
    Long targetVersionId,
    boolean requestedRuntimeFlagsJsonPresent,
    String requestedRuntimeFlagsJson,
    String requestDigest,
    String launchDescriptorId,
    long versionId,
    boolean scriptPatchVersionPresent,
    String scriptPatchVersion,
    String runtimeFlagsJson,
    String generationConfigRevision,
    long versionStateEpoch,
    long releaseBundleId,
    String publishedReleaseBundleRef,
    boolean remapSetIdPresent,
    String remapSetId,
    String resultDigest) {
  public static final int SCHEMA_VERSION = 1;
  private static final String REQUEST_DOMAIN =
      "game-design-authored-world-launch-descriptor-request/v1";
  private static final String RESULT_DOMAIN =
      "game-design-authored-world-launch-descriptor-result/v1";
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private record Field(String name, String value) {}

  /** The exact request values, including presence for every optional field. */
  public record Request(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID authoredWorldSourceOperationId,
      String authoredWorldSourceEvidenceDigest,
      long gameTemplateId,
      boolean requestedScriptPatchVersionPresent,
      String requestedScriptPatchVersion,
      boolean sourceVersionIdPresent,
      Long sourceVersionId,
      boolean targetVersionIdPresent,
      Long targetVersionId,
      boolean requestedRuntimeFlagsJsonPresent,
      String requestedRuntimeFlagsJson) {
    public Request {
      validateRequest(
          targetNamespace,
          controlPlaneRequestId,
          canonicalTenantId,
          worldSlug,
          authoredWorldSourceOperationId,
          authoredWorldSourceEvidenceDigest,
          gameTemplateId,
          requestedScriptPatchVersionPresent,
          requestedScriptPatchVersion,
          sourceVersionIdPresent,
          sourceVersionId,
          targetVersionIdPresent,
          targetVersionId,
          requestedRuntimeFlagsJsonPresent,
          requestedRuntimeFlagsJson);
    }

    public String requestDigest() {
      return AuthoredWorldLaunchDescriptorEvidence.requestDigest(this);
    }
  }

  public AuthoredWorldLaunchDescriptorEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported authored-world launch descriptor schema");
    }
    Request request =
        new Request(
            targetNamespace,
            controlPlaneRequestId,
            canonicalTenantId,
            worldSlug,
            authoredWorldSourceOperationId,
            authoredWorldSourceEvidenceDigest,
            gameTemplateId,
            requestedScriptPatchVersionPresent,
            requestedScriptPatchVersion,
            sourceVersionIdPresent,
            sourceVersionId,
            targetVersionIdPresent,
            targetVersionId,
            requestedRuntimeFlagsJsonPresent,
            requestedRuntimeFlagsJson);
    requireOptionalIdentifier(scriptPatchVersionPresent, scriptPatchVersion, "scriptPatchVersion");
    requireOptionalIdentifier(remapSetIdPresent, remapSetId, "remapSetId");
    requireText(launchDescriptorId, "launchDescriptorId");
    requirePositive(versionId, "versionId");
    if (targetVersionIdPresent && targetVersionId.longValue() != versionId) {
      throw new IllegalArgumentException("Resolved versionId does not match the requested target");
    }
    if (requestedScriptPatchVersionPresent
        && (!scriptPatchVersionPresent
            || !requestedScriptPatchVersion.equals(scriptPatchVersion))) {
      throw new IllegalArgumentException(
          "Resolved scriptPatchVersion does not match the requested override");
    }
    requireText(runtimeFlagsJson, "runtimeFlagsJson");
    requireText(generationConfigRevision, "generationConfigRevision");
    if (versionStateEpoch <= 0) {
      throw new IllegalArgumentException("versionStateEpoch must be positive");
    }
    requirePositive(releaseBundleId, "releaseBundleId");
    requireText(publishedReleaseBundleRef, "publishedReleaseBundleRef");
    requireDigest(requestDigest, "requestDigest");
    if (!request.requestDigest().equals(requestDigest)) {
      throw new IllegalArgumentException("Request digest does not match the exact request tuple");
    }
    requireDigest(resultDigest, "resultDigest");
  }

  /** Recomputes both closed digests after construction or deserialization. */
  public void requireValid() {
    if (!requestDigest(this.request()).equals(requestDigest)
        || !resultDigest(this).equals(resultDigest)) {
      throw new IllegalArgumentException("Result digest does not match the immutable descriptor");
    }
  }

  public Request request() {
    return new Request(
        targetNamespace,
        controlPlaneRequestId,
        canonicalTenantId,
        worldSlug,
        authoredWorldSourceOperationId,
        authoredWorldSourceEvidenceDigest,
        gameTemplateId,
        requestedScriptPatchVersionPresent,
        requestedScriptPatchVersion,
        sourceVersionIdPresent,
        sourceVersionId,
        targetVersionIdPresent,
        targetVersionId,
        requestedRuntimeFlagsJsonPresent,
        requestedRuntimeFlagsJson);
  }

  public static AuthoredWorldLaunchDescriptorEvidence create(
      Request request,
      String launchDescriptorId,
      long versionId,
      boolean scriptPatchVersionPresent,
      String scriptPatchVersion,
      String runtimeFlagsJson,
      String generationConfigRevision,
      long versionStateEpoch,
      long releaseBundleId,
      String publishedReleaseBundleRef,
      boolean remapSetIdPresent,
      String remapSetId) {
    Objects.requireNonNull(request, "request");
    AuthoredWorldLaunchDescriptorEvidence provisional =
        new AuthoredWorldLaunchDescriptorEvidence(
            SCHEMA_VERSION,
            request.targetNamespace(),
            request.controlPlaneRequestId(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.authoredWorldSourceOperationId(),
            request.authoredWorldSourceEvidenceDigest(),
            request.gameTemplateId(),
            request.requestedScriptPatchVersionPresent(),
            request.requestedScriptPatchVersion(),
            request.sourceVersionIdPresent(),
            request.sourceVersionId(),
            request.targetVersionIdPresent(),
            request.targetVersionId(),
            request.requestedRuntimeFlagsJsonPresent(),
            request.requestedRuntimeFlagsJson(),
            request.requestDigest(),
            launchDescriptorId,
            versionId,
            scriptPatchVersionPresent,
            scriptPatchVersion,
            runtimeFlagsJson,
            generationConfigRevision,
            versionStateEpoch,
            releaseBundleId,
            publishedReleaseBundleRef,
            remapSetIdPresent,
            remapSetId,
            "sha256:" + "0".repeat(64));
    AuthoredWorldLaunchDescriptorEvidence evidence =
        new AuthoredWorldLaunchDescriptorEvidence(
            provisional.schemaVersion(),
            provisional.targetNamespace(),
            provisional.controlPlaneRequestId(),
            provisional.canonicalTenantId(),
            provisional.worldSlug(),
            provisional.authoredWorldSourceOperationId(),
            provisional.authoredWorldSourceEvidenceDigest(),
            provisional.gameTemplateId(),
            provisional.requestedScriptPatchVersionPresent(),
            provisional.requestedScriptPatchVersion(),
            provisional.sourceVersionIdPresent(),
            provisional.sourceVersionId(),
            provisional.targetVersionIdPresent(),
            provisional.targetVersionId(),
            provisional.requestedRuntimeFlagsJsonPresent(),
            provisional.requestedRuntimeFlagsJson(),
            provisional.requestDigest(),
            provisional.launchDescriptorId(),
            provisional.versionId(),
            provisional.scriptPatchVersionPresent(),
            provisional.scriptPatchVersion(),
            provisional.runtimeFlagsJson(),
            provisional.generationConfigRevision(),
            provisional.versionStateEpoch(),
            provisional.releaseBundleId(),
            provisional.publishedReleaseBundleRef(),
            provisional.remapSetIdPresent(),
            provisional.remapSetId(),
            resultDigest(provisional));
    evidence.requireValid();
    return evidence;
  }

  public static String requestDigest(Request request) {
    Objects.requireNonNull(request, "request");
    return digest(requestPreimage(request));
  }

  /** Computes the result digest while deliberately excluding only {@code resultDigest}. */
  public static String resultDigest(AuthoredWorldLaunchDescriptorEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    return digest(resultPreimage(evidence));
  }

  /** Returns the exact UTF-8 request preimage for shared producer and consumer proof. */
  public static byte[] requestPreimage(Request request) {
    return preimage(
        REQUEST_DOMAIN,
        field("schemaVersion", Integer.toString(SCHEMA_VERSION)),
        field("targetNamespace", request.targetNamespace()),
        field("controlPlaneRequestId", request.controlPlaneRequestId()),
        field("canonicalTenantId", request.canonicalTenantId().toString()),
        field("worldSlug", request.worldSlug()),
        field(
            "authoredWorldSourceOperationId", request.authoredWorldSourceOperationId().toString()),
        field("authoredWorldSourceEvidenceDigest", request.authoredWorldSourceEvidenceDigest()),
        field("gameTemplateId", Long.toString(request.gameTemplateId())),
        optionalPresence(
            "requestedScriptPatchVersion", request.requestedScriptPatchVersionPresent()),
        optionalValue(
            "requestedScriptPatchVersion",
            request.requestedScriptPatchVersionPresent(),
            request.requestedScriptPatchVersion()),
        optionalPresence("sourceVersionId", request.sourceVersionIdPresent()),
        optionalValue(
            "sourceVersionId",
            request.sourceVersionIdPresent(),
            decimal(request.sourceVersionId())),
        optionalPresence("targetVersionId", request.targetVersionIdPresent()),
        optionalValue(
            "targetVersionId",
            request.targetVersionIdPresent(),
            decimal(request.targetVersionId())),
        optionalPresence("requestedRuntimeFlagsJson", request.requestedRuntimeFlagsJsonPresent()),
        optionalValue(
            "requestedRuntimeFlagsJson",
            request.requestedRuntimeFlagsJsonPresent(),
            request.requestedRuntimeFlagsJson()));
  }

  /** Returns the exact UTF-8 result preimage, excluding only the result digest itself. */
  public static byte[] resultPreimage(AuthoredWorldLaunchDescriptorEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    return preimage(
        RESULT_DOMAIN,
        field("schemaVersion", Integer.toString(evidence.schemaVersion())),
        field("targetNamespace", evidence.targetNamespace()),
        field("controlPlaneRequestId", evidence.controlPlaneRequestId()),
        field("canonicalTenantId", evidence.canonicalTenantId().toString()),
        field("worldSlug", evidence.worldSlug()),
        field(
            "authoredWorldSourceOperationId", evidence.authoredWorldSourceOperationId().toString()),
        field("authoredWorldSourceEvidenceDigest", evidence.authoredWorldSourceEvidenceDigest()),
        field("gameTemplateId", Long.toString(evidence.gameTemplateId())),
        optionalPresence(
            "requestedScriptPatchVersion", evidence.requestedScriptPatchVersionPresent()),
        optionalValue(
            "requestedScriptPatchVersion",
            evidence.requestedScriptPatchVersionPresent(),
            evidence.requestedScriptPatchVersion()),
        optionalPresence("sourceVersionId", evidence.sourceVersionIdPresent()),
        optionalValue(
            "sourceVersionId",
            evidence.sourceVersionIdPresent(),
            decimal(evidence.sourceVersionId())),
        optionalPresence("targetVersionId", evidence.targetVersionIdPresent()),
        optionalValue(
            "targetVersionId",
            evidence.targetVersionIdPresent(),
            decimal(evidence.targetVersionId())),
        optionalPresence("requestedRuntimeFlagsJson", evidence.requestedRuntimeFlagsJsonPresent()),
        optionalValue(
            "requestedRuntimeFlagsJson",
            evidence.requestedRuntimeFlagsJsonPresent(),
            evidence.requestedRuntimeFlagsJson()),
        field("requestDigest", evidence.requestDigest()),
        field("launchDescriptorId", evidence.launchDescriptorId()),
        field("versionId", Long.toString(evidence.versionId())),
        optionalPresence("scriptPatchVersion", evidence.scriptPatchVersionPresent()),
        optionalValue(
            "scriptPatchVersion",
            evidence.scriptPatchVersionPresent(),
            evidence.scriptPatchVersion()),
        field("runtimeFlagsJson", evidence.runtimeFlagsJson()),
        field("generationConfigRevision", evidence.generationConfigRevision()),
        field("versionStateEpoch", Long.toString(evidence.versionStateEpoch())),
        field("releaseBundleId", Long.toString(evidence.releaseBundleId())),
        field("publishedReleaseBundleRef", evidence.publishedReleaseBundleRef()),
        optionalPresence("remapSetId", evidence.remapSetIdPresent()),
        optionalValue("remapSetId", evidence.remapSetIdPresent(), evidence.remapSetId()));
  }

  private static void validateRequest(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      long gameTemplateId,
      boolean requestedPatchPresent,
      String requestedPatch,
      boolean sourceVersionPresent,
      Long sourceVersion,
      boolean targetVersionPresent,
      Long targetVersion,
      boolean runtimeFlagsPresent,
      String runtimeFlags) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be a canonical DNS label");
    }
    Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId");
    if (controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("controlPlaneRequestId is required");
    }
    if (strictUtf8(controlPlaneRequestId).length > 64) {
      throw new IllegalArgumentException("controlPlaneRequestId exceeds its stored byte bound");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    requireNonNil(sourceOperationId, "authoredWorldSourceOperationId");
    requireDigest(sourceEvidenceDigest, "authoredWorldSourceEvidenceDigest");
    requirePositive(gameTemplateId, "gameTemplateId");
    requireOptionalIdentifier(requestedPatchPresent, requestedPatch, "requestedScriptPatchVersion");
    requireOptional(sourceVersionPresent, sourceVersion, "sourceVersionId");
    if (sourceVersionPresent) {
      requirePositive(sourceVersion, "sourceVersionId");
    }
    requireOptional(targetVersionPresent, targetVersion, "targetVersionId");
    if (targetVersionPresent) {
      requirePositive(targetVersion, "targetVersionId");
    }
    requireOptional(runtimeFlagsPresent, runtimeFlags, "requestedRuntimeFlagsJson");
  }

  private static Field optionalPresence(String name, boolean present) {
    return field(name + ".present", Boolean.toString(present));
  }

  private static Field optionalValue(String name, boolean present, String value) {
    return field(name + ".value", present ? Objects.requireNonNull(value, name) : "");
  }

  private static Field field(String name, String value) {
    return new Field(name, value);
  }

  private static String digest(byte[] preimage) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] preimage(String domain, Field... fields) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    writeSegment(bytes, domain);
    for (Field field : fields) {
      writeSegment(bytes, field.name());
      writeSegment(bytes, field.value());
    }
    return bytes.toByteArray();
  }

  private static void writeSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = strictUtf8(value);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static byte[] strictUtf8(String value) {
    Objects.requireNonNull(value, "value");
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
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Digest inputs must contain valid Unicode", exception);
    }
  }

  private static String decimal(Long value) {
    return value == null ? "" : Long.toString(value);
  }

  private static void requireOptional(boolean present, Object value, String name) {
    if (present != (value != null)) {
      throw new IllegalArgumentException(name + " presence does not match its value");
    }
    if (value instanceof String text) {
      strictUtf8(text);
    }
  }

  private static void requireOptionalIdentifier(boolean present, String value, String name) {
    requireOptional(present, value, name);
    if (present && value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank when present");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositive(Long value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    strictUtf8(value);
  }

  private static void requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
    }
  }
}
