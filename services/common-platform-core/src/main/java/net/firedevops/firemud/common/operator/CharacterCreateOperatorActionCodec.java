package net.firedevops.firemud.common.operator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.AuthoredSource;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Catalog;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Core;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Descriptor;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.ExpectedVersion;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Mutation;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.PlayableStateScope;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Pointer;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Policy;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Rule;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Scope;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Target;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict passive codec for the approved public-production CHARACTER_CREATE/1 action family. */
public final class CharacterCreateOperatorActionCodec {
  public static final int MAX_RAW_BYTES = 2 * 1_024 * 1_024;
  public static final int MAX_NESTING_DEPTH = 16;
  public static final int MAX_OBJECT_MEMBERS = 16;
  public static final int MAX_RAW_STRING_TOKEN_BYTES = 1_572_864;
  public static final int MAX_IDENTITY_STRING_BYTES = 1_024;
  public static final int MAX_EXACT_UTF8_SOURCE_BYTES = 1_048_576;
  public static final int MAX_POLICY_BYTES = 4_096;
  public static final int MAX_DESCRIPTOR_BYTES = 4_096;
  public static final int MAX_WORKFLOW_ID_BYTES = 1_024;
  public static final int MAX_LOGICAL_REVISION_BYTES = 512;
  public static final int MAX_WORKFLOW_ID_CHARACTERS = 256;
  public static final int MAX_LOGICAL_REVISION_CHARACTERS = 128;

  private static final String EXACT_UTF8_ENCODING = "utf8-base64url-unpadded";
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern NAMESPACE_PATTERN =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
  private static final JsonMapper SOURCE_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final List<String> TOP_LEVEL_FIELDS =
      List.of(
          "actionFamilySchemaId",
          "actionFamilySchemaVersion",
          "scope",
          "target",
          "expectedVersion",
          "mutation");

  private CharacterCreateOperatorActionCodec() {}

  /** Decodes only the exact ordered public-production action shape. */
  public static CharacterCreateOperatorAction decode(byte[] rawJson) {
    JsonValue root = new StrictJsonParser(rawJson).parseDocument();
    JsonObject object = requireObject(root, "CHARACTER_CREATE action");
    Map<String, JsonValue> members = orderedMembers(object, TOP_LEVEL_FIELDS, "action");
    String schemaId = requiredString(members.get("actionFamilySchemaId"), "actionFamilySchemaId");
    String schemaVersion =
        requiredString(members.get("actionFamilySchemaVersion"), "actionFamilySchemaVersion");
    if (!CharacterCreateOperatorAction.ACTION_FAMILY_SCHEMA_ID.equals(schemaId)
        || !CharacterCreateOperatorAction.ACTION_FAMILY_SCHEMA_VERSION.equals(schemaVersion)) {
      throw invalid("unsupported CHARACTER_CREATE action-family schema pair");
    }

    CharacterCreateOperatorAction action =
        new CharacterCreateOperatorAction(
            readScope(members.get("scope")),
            readTarget(members.get("target")),
            readExpectedVersion(members.get("expectedVersion")),
            readMutation(members.get("mutation")),
            CharacterCreateOperatorAction.AuditReason.ABSENT);
    canonicalPreimage(action);
    return action;
  }

  /** Encodes the exact ordered raw action JSON, omitting the schema-declared absent audit slot. */
  public static byte[] encode(CharacterCreateOperatorAction action) {
    canonicalPreimage(Objects.requireNonNull(action, "action is required"));
    RawJsonWriter writer = new RawJsonWriter();
    writer.beginObject();
    writer.stringProperty("actionFamilySchemaId", action.actionFamilySchemaId());
    writer.stringProperty("actionFamilySchemaVersion", action.actionFamilySchemaVersion());
    writer.name("scope");
    writeScope(writer, action.scope());
    writer.name("target");
    writeTarget(writer, action.target());
    writer.name("expectedVersion");
    writeExpectedVersion(writer, action.expectedVersion());
    writer.name("mutation");
    writeMutation(writer, action.mutation());
    writer.endObject();
    return writer.toByteArray();
  }

  /** Returns the exact ADR 0047 mutationDigest/v1 preimage for a typed action. */
  public static byte[] canonicalPreimage(CharacterCreateOperatorAction action) {
    validate(Objects.requireNonNull(action, "action is required"));
    List<MutationDigestV1.Member> fields =
        List.of(
            member("actionFamilySchemaId", string(action.actionFamilySchemaId())),
            member("actionFamilySchemaVersion", string(action.actionFamilySchemaVersion())),
            member("scope", scopeValue(action.scope())),
            member("target", targetValue(action.target())),
            member("expectedVersion", expectedVersionValue(action.expectedVersion())),
            member("mutation", mutationValue(action.mutation())),
            member("auditReason", MutationDigestV1.absent()));
    return MutationDigestV1.characterCreatePreimage(fields);
  }

  /** Decodes raw action JSON and returns its exact approved digest preimage. */
  public static byte[] canonicalPreimage(byte[] rawJson) {
    return canonicalPreimage(decode(rawJson));
  }

  /** Returns the lowercase hexadecimal SHA-256 mutation digest for a typed action. */
  public static String mutationDigest(CharacterCreateOperatorAction action) {
    return MutationDigestV1.sha256HexCharacterCreate(canonicalPreimage(action));
  }

  /** Decodes raw action JSON and returns its lowercase hexadecimal SHA-256 mutation digest. */
  public static String mutationDigest(byte[] rawJson) {
    return mutationDigest(decode(rawJson));
  }

  private static Scope readScope(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "scope"),
            List.of(
                "targetNamespace", "tenantId", "playableStateNamespaceId", "playableStateScope"),
            "scope");
    String namespace = requiredString(members.get("targetNamespace"), "targetNamespace");
    if (namespace.length() > 63 || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
      throw invalid("targetNamespace must be a canonical workload namespace");
    }
    String scopeText = requiredString(members.get("playableStateScope"), "playableStateScope");
    PlayableStateScope playableStateScope;
    try {
      playableStateScope = PlayableStateScope.valueOf(scopeText);
    } catch (IllegalArgumentException unsupported) {
      throw invalid("playableStateScope is unsupported");
    }
    return new Scope(
        namespace,
        canonicalUuid(requiredString(members.get("tenantId"), "tenantId"), "tenantId"),
        canonicalUuid(
            requiredString(members.get("playableStateNamespaceId"), "playableStateNamespaceId"),
            "playableStateNamespaceId"),
        playableStateScope);
  }

  private static Target readTarget(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "target"),
            List.of(
                "accountId", "realmId", "worldSlug", "realmSlug", "gameInstanceId", "versionId"),
            "target");
    return new Target(
        canonicalUuid(requiredString(members.get("accountId"), "accountId"), "accountId"),
        canonicalUuid(requiredString(members.get("realmId"), "realmId"), "realmId"),
        canonicalSlug(requiredString(members.get("worldSlug"), "worldSlug"), "worldSlug"),
        canonicalSlug(requiredString(members.get("realmSlug"), "realmSlug"), "realmSlug"),
        canonicalUuid(
            requiredString(members.get("gameInstanceId"), "gameInstanceId"), "gameInstanceId"),
        canonicalUuid(requiredString(members.get("versionId"), "versionId"), "versionId"));
  }

  private static ExpectedVersion readExpectedVersion(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "expectedVersion"),
            List.of(
                "catalogRevision",
                "pointerVersion",
                "activeLifecycleEpoch",
                "evaluatedAt",
                "connectScopeExpiresAt"),
            "expectedVersion");
    return new ExpectedVersion(
        positiveLongString(members.get("catalogRevision"), "catalogRevision"),
        positiveLongString(members.get("pointerVersion"), "pointerVersion"),
        positiveLongString(members.get("activeLifecycleEpoch"), "activeLifecycleEpoch"),
        canonicalInstant(members.get("evaluatedAt"), "evaluatedAt"),
        canonicalInstant(members.get("connectScopeExpiresAt"), "connectScopeExpiresAt"));
  }

  private static Mutation readMutation(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "mutation"),
            List.of(
                "purpose",
                "entryPolicy",
                "catalog",
                "pointer",
                "authoredSource",
                "canonicalCreationInput"),
            "mutation");
    return new Mutation(
        requiredString(members.get("purpose"), "purpose"),
        requiredString(members.get("entryPolicy"), "entryPolicy"),
        readCatalog(members.get("catalog")),
        readPointer(members.get("pointer")),
        readAuthoredSource(members.get("authoredSource")),
        requiredString(members.get("canonicalCreationInput"), "canonicalCreationInput"));
  }

  private static Catalog readCatalog(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "catalog"),
            List.of(
                "tenantId",
                "tenantSlug",
                "worldSlug",
                "worldDisplayName",
                "realmId",
                "realmSlug",
                "realmDisplayName",
                "visible",
                "publicProduction",
                "stateScope",
                "playableStateNamespaceId",
                "characterCreationPolicy",
                "catalogRevision"),
            "catalog");
    return new Catalog(
        canonicalUuid(requiredString(members.get("tenantId"), "catalog.tenantId"), "tenantId"),
        canonicalSlug(
            requiredString(members.get("tenantSlug"), "catalog.tenantSlug"), "tenantSlug"),
        canonicalSlug(requiredString(members.get("worldSlug"), "catalog.worldSlug"), "worldSlug"),
        readExactUtf8(members.get("worldDisplayName"), 512, "worldDisplayName"),
        canonicalUuid(requiredString(members.get("realmId"), "catalog.realmId"), "realmId"),
        canonicalSlug(requiredString(members.get("realmSlug"), "catalog.realmSlug"), "realmSlug"),
        readExactUtf8(members.get("realmDisplayName"), 512, "realmDisplayName"),
        requiredBoolean(members.get("visible"), "catalog.visible"),
        requiredBoolean(members.get("publicProduction"), "catalog.publicProduction"),
        requiredString(members.get("stateScope"), "catalog.stateScope"),
        canonicalUuid(
            requiredString(
                members.get("playableStateNamespaceId"), "catalog.playableStateNamespaceId"),
            "playableStateNamespaceId"),
        requiredString(members.get("characterCreationPolicy"), "catalog.characterCreationPolicy"),
        positiveLongString(members.get("catalogRevision"), "catalog.catalogRevision"));
  }

  private static Pointer readPointer(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "pointer"),
            List.of(
                "tenantId",
                "realmId",
                "worldSlug",
                "realmSlug",
                "admissionState",
                "admissibleGameInstanceId",
                "pointerVersion",
                "catalogRevision",
                "updatedAt"),
            "pointer");
    return new Pointer(
        canonicalUuid(requiredString(members.get("tenantId"), "pointer.tenantId"), "tenantId"),
        canonicalUuid(requiredString(members.get("realmId"), "pointer.realmId"), "realmId"),
        canonicalSlug(requiredString(members.get("worldSlug"), "pointer.worldSlug"), "worldSlug"),
        canonicalSlug(requiredString(members.get("realmSlug"), "pointer.realmSlug"), "realmSlug"),
        requiredString(members.get("admissionState"), "admissionState"),
        canonicalUuid(
            requiredString(members.get("admissibleGameInstanceId"), "admissibleGameInstanceId"),
            "admissibleGameInstanceId"),
        positiveLongString(members.get("pointerVersion"), "pointer.pointerVersion"),
        positiveLongString(members.get("catalogRevision"), "pointer.catalogRevision"),
        canonicalInstant(members.get("updatedAt"), "pointer.updatedAt"));
  }

  private static AuthoredSource readAuthoredSource(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "authoredSource"),
            List.of(
                "schema",
                "bindingId",
                "bindingDigest",
                "canonicalTenantId",
                "canonicalVersionId",
                "worldSourceOperationId",
                "worldSourceEvidenceDigest",
                "worldSlug",
                "publicationWorkflowId",
                "selectedCommitId",
                "selectedCommitDigest",
                "policy",
                "core",
                "descriptor",
                "disposition",
                "safeFeedback"),
            "authoredSource");
    return new AuthoredSource(
        requiredString(members.get("schema"), "authoredSource.schema"),
        canonicalUuid(requiredString(members.get("bindingId"), "bindingId"), "bindingId"),
        requiredString(members.get("bindingDigest"), "bindingDigest"),
        canonicalUuid(
            requiredString(members.get("canonicalTenantId"), "canonicalTenantId"),
            "canonicalTenantId"),
        canonicalUuid(
            requiredString(members.get("canonicalVersionId"), "canonicalVersionId"),
            "canonicalVersionId"),
        canonicalUuid(
            requiredString(members.get("worldSourceOperationId"), "worldSourceOperationId"),
            "worldSourceOperationId"),
        requiredString(members.get("worldSourceEvidenceDigest"), "worldSourceEvidenceDigest"),
        canonicalSlug(requiredString(members.get("worldSlug"), "worldSlug"), "worldSlug"),
        boundedExactText(
            readExactUtf8(
                members.get("publicationWorkflowId"),
                MAX_WORKFLOW_ID_BYTES,
                "publicationWorkflowId"),
            MAX_WORKFLOW_ID_CHARACTERS,
            MAX_WORKFLOW_ID_BYTES,
            "publicationWorkflowId"),
        canonicalUuid(
            requiredString(members.get("selectedCommitId"), "selectedCommitId"),
            "selectedCommitId"),
        requiredString(members.get("selectedCommitDigest"), "selectedCommitDigest"),
        readPolicy(members.get("policy")),
        readCore(members.get("core")),
        readDescriptor(members.get("descriptor")),
        readRule(members.get("disposition"), "disposition"),
        readRule(members.get("safeFeedback"), "safeFeedback"));
  }

  private static Policy readPolicy(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "authoredSource.policy"),
            List.of(
                "commitId",
                "revisionId",
                "logicalRevisionId",
                "canonicalPolicyJson",
                "contentDigest"),
            "authoredSource.policy");
    return new Policy(
        canonicalUuid(requiredString(members.get("commitId"), "policy.commitId"), "commitId"),
        canonicalUuid(requiredString(members.get("revisionId"), "policy.revisionId"), "revisionId"),
        boundedExactText(
            readExactUtf8(
                members.get("logicalRevisionId"), MAX_LOGICAL_REVISION_BYTES, "logicalRevisionId"),
            MAX_LOGICAL_REVISION_CHARACTERS,
            MAX_LOGICAL_REVISION_BYTES,
            "logicalRevisionId"),
        readExactUtf8(members.get("canonicalPolicyJson"), MAX_POLICY_BYTES, "canonicalPolicyJson"),
        requiredString(members.get("contentDigest"), "policy.contentDigest"));
  }

  private static Core readCore(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "authoredSource.core"),
            List.of("canonicalJson"),
            "authoredSource.core");
    return new Core(
        readExactUtf8(
            members.get("canonicalJson"), MAX_EXACT_UTF8_SOURCE_BYTES, "core.canonicalJson"));
  }

  private static Descriptor readDescriptor(JsonValue value) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "authoredSource.descriptor"),
            List.of("canonicalJson", "digest"),
            "authoredSource.descriptor");
    return new Descriptor(
        readExactUtf8(
            members.get("canonicalJson"), MAX_DESCRIPTOR_BYTES, "descriptor.canonicalJson"),
        requiredString(members.get("digest"), "descriptor.digest"));
  }

  private static Rule readRule(JsonValue value, String label) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, "authoredSource." + label),
            List.of(
                "key",
                "definitionJson",
                "definitionDigest",
                "sourceCommitId",
                "sourceCommitDigest",
                "revisionId",
                "revisionOrder"),
            "authoredSource." + label);
    return new Rule(
        requiredString(members.get("key"), label + ".key"),
        readExactUtf8(
            members.get("definitionJson"), MAX_EXACT_UTF8_SOURCE_BYTES, label + ".definitionJson"),
        requiredString(members.get("definitionDigest"), label + ".definitionDigest"),
        canonicalUuid(
            requiredString(members.get("sourceCommitId"), label + ".sourceCommitId"),
            "sourceCommitId"),
        requiredString(members.get("sourceCommitDigest"), label + ".sourceCommitDigest"),
        canonicalUuid(
            requiredString(members.get("revisionId"), label + ".revisionId"), "revisionId"),
        nonnegativeDecimalString(members.get("revisionOrder"), label + ".revisionOrder"));
  }

  private static Map<String, JsonValue> orderedMembers(
      JsonObject object, List<String> declaredOrder, String label) {
    if (object.members().size() > MAX_OBJECT_MEMBERS) {
      throw invalid(label + " exceeds the object-member limit");
    }
    Map<String, JsonValue> normalized = new LinkedHashMap<>();
    for (JsonMember member : object.members()) {
      String key = normalize(member.key().value(), MAX_IDENTITY_STRING_BYTES, label + " key");
      if (normalized.containsKey(key)) {
        throw invalid(label + " contains duplicate or NFC-colliding keys");
      }
      if (!declaredOrder.contains(key)) {
        throw invalid(label + " contains an unknown key");
      }
      normalized.put(key, member.value());
    }
    if (!new ArrayList<>(normalized.keySet()).equals(declaredOrder)) {
      throw invalid(label + " keys are missing or out of declared order");
    }
    return normalized;
  }

  private static JsonObject requireObject(JsonValue value, String label) {
    if (!(value instanceof JsonObject object)) throw invalid(label + " must be an object");
    return object;
  }

  private static String requiredString(JsonValue value, String label) {
    if (!(value instanceof JsonString string)) throw invalid(label + " must be a string");
    return normalize(string.value(), MAX_IDENTITY_STRING_BYTES, label);
  }

  private static boolean requiredBoolean(JsonValue value, String label) {
    if (!(value instanceof JsonBoolean bool)) throw invalid(label + " must be a boolean");
    return bool.value();
  }

  private static String readExactUtf8(JsonValue value, int byteLimit, String label) {
    Map<String, JsonValue> members =
        orderedMembers(
            requireObject(value, label + " ExactUtf8"),
            List.of("encoding", "bytes"),
            label + " ExactUtf8");
    String encoding = requiredString(members.get("encoding"), label + ".encoding");
    if (!EXACT_UTF8_ENCODING.equals(encoding)) {
      throw invalid(label + " uses an unsupported ExactUtf8 encoding");
    }
    if (!(members.get("bytes") instanceof JsonString encoded)) {
      throw invalid(label + ".bytes must be a base64url string");
    }
    String payload = encoded.value();
    int maximumPayloadLength = unpaddedBase64Length(byteLimit);
    if (payload.length() > maximumPayloadLength) {
      throw invalid(label + ".bytes exceeds its encoded byte limit");
    }
    for (int index = 0; index < payload.length(); index++) {
      char valueCharacter = payload.charAt(index);
      if (!(valueCharacter >= 'A' && valueCharacter <= 'Z')
          && !(valueCharacter >= 'a' && valueCharacter <= 'z')
          && !(valueCharacter >= '0' && valueCharacter <= '9')
          && valueCharacter != '-'
          && valueCharacter != '_') {
        throw invalid(label + ".bytes must contain unpadded ASCII base64url");
      }
    }
    final byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(payload);
    } catch (IllegalArgumentException malformed) {
      throw invalid(label + ".bytes is not valid base64url");
    }
    if (decoded.length > byteLimit
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(payload)) {
      throw invalid(label + ".bytes is noncanonical or exceeds its decoded byte limit");
    }
    try {
      CharBuffer text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(decoded));
      return text.toString();
    } catch (CharacterCodingException malformed) {
      throw invalid(label + ".bytes does not contain valid UTF-8");
    }
  }

  private static int unpaddedBase64Length(int decodedByteLimit) {
    int remainder = decodedByteLimit % 3;
    return (decodedByteLimit / 3) * 4 + (remainder == 0 ? 0 : remainder + 1);
  }

  private static String canonicalUuid(String value, String label) {
    if (!UUID_PATTERN.matcher(value).matches()) {
      throw invalid(label + " must be a canonical lowercase UUID");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid(label + " must be a canonical lowercase UUID");
    }
    if ((parsed.getMostSignificantBits() | parsed.getLeastSignificantBits()) == 0L) {
      throw invalid(label + " must not be nil");
    }
    return value;
  }

  private static String canonicalSlug(String value, String label) {
    if (value.length() > 64 || !SLUG_PATTERN.matcher(value).matches()) {
      throw invalid(label + " must be a canonical lowercase slug");
    }
    return value;
  }

  private static String positiveLongString(JsonValue value, String label) {
    String text = requiredString(value, label);
    if (!POSITIVE_LONG.matcher(text).matches()) {
      throw invalid(label + " must be a positive canonical signed-long decimal string");
    }
    try {
      if (Long.parseLong(text) <= 0L) throw invalid(label + " must be positive");
    } catch (NumberFormatException outOfRange) {
      throw invalid(label + " exceeds the positive signed-long range");
    }
    return text;
  }

  private static String nonnegativeDecimalString(JsonValue value, String label) {
    if (!(value instanceof JsonString string)) throw invalid(label + " must be a string");
    String text = string.value();
    requireUnicodeScalars(text, label);
    if (utf8Strict(text, label).length > AuthoredInitialActorCoreProjection.MAX_BYTES
        || !NONNEGATIVE_DECIMAL.matcher(text).matches()) {
      throw invalid(label + " must be a bounded nonnegative canonical decimal string");
    }
    return text;
  }

  private static String boundedExactText(
      String value, int maxCharacters, int maxUtf8Bytes, String label) {
    if (value.isBlank()
        || value.length() > maxCharacters
        || utf8Strict(value, label).length > maxUtf8Bytes) {
      throw invalid(label + " exceeds its retained text bounds");
    }
    return value;
  }

  private static Instant canonicalInstant(JsonValue value, String label) {
    String text = requiredString(value, label);
    if (!text.endsWith("Z")) throw invalid(label + " must be a canonical UTC Instant");
    try {
      Instant instant = Instant.parse(text);
      if (!instant.toString().equals(text)) {
        throw invalid(label + " must preserve canonical Instant precision");
      }
      return instant;
    } catch (DateTimeParseException malformed) {
      throw invalid(label + " must be a canonical UTC Instant");
    }
  }

  private static String normalize(String value, int maxUtf8Bytes, String label) {
    requireUnicodeScalars(value, label);
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    requireUnicodeScalars(normalized, label);
    if (utf8Strict(normalized, label).length > maxUtf8Bytes) {
      throw invalid(label + " exceeds its normalized UTF-8 byte limit");
    }
    return normalized;
  }

  private static void validate(CharacterCreateOperatorAction action) {
    Objects.requireNonNull(action.scope(), "scope is required");
    Objects.requireNonNull(action.target(), "target is required");
    Objects.requireNonNull(action.expectedVersion(), "expectedVersion is required");
    Objects.requireNonNull(action.mutation(), "mutation is required");
    if (action.auditReason() != CharacterCreateOperatorAction.AuditReason.ABSENT) {
      throw invalid("auditReason must retain the schema-declared absent value");
    }
    Scope scope = action.scope();
    if (scope.targetNamespace().length() > 63
        || !NAMESPACE_PATTERN.matcher(scope.targetNamespace()).matches()) {
      throw invalid("targetNamespace must be a canonical workload namespace");
    }
    canonicalUuid(scope.tenantId(), "tenantId");
    canonicalUuid(scope.playableStateNamespaceId(), "playableStateNamespaceId");
    Objects.requireNonNull(scope.playableStateScope(), "playableStateScope is required");
    Target target = action.target();
    canonicalUuid(target.accountId(), "accountId");
    canonicalUuid(target.realmId(), "realmId");
    canonicalSlug(target.worldSlug(), "worldSlug");
    canonicalSlug(target.realmSlug(), "realmSlug");
    canonicalUuid(target.gameInstanceId(), "gameInstanceId");
    canonicalUuid(target.versionId(), "versionId");

    ExpectedVersion expected = action.expectedVersion();
    positiveLongString(new JsonString(expected.catalogRevision()), "catalogRevision");
    positiveLongString(new JsonString(expected.pointerVersion()), "pointerVersion");
    positiveLongString(new JsonString(expected.activeLifecycleEpoch()), "activeLifecycleEpoch");
    Objects.requireNonNull(expected.evaluatedAt(), "evaluatedAt is required");
    Objects.requireNonNull(expected.connectScopeExpiresAt(), "connectScopeExpiresAt is required");

    Mutation mutation = action.mutation();
    if (!"CHARACTER_CREATE".equals(mutation.purpose())
        || !"PLAYER_CREATED".equals(mutation.entryPolicy())
        || !"{}".equals(mutation.canonicalCreationInput())) {
      throw invalid("mutation must be PLAYER_CREATED with the canonical empty-object input");
    }
    validateCatalog(mutation.catalog(), scope, target, expected);
    validatePointer(mutation.pointer(), mutation.catalog(), target, expected);
    validateAuthoredSource(mutation.authoredSource(), mutation.catalog(), scope, target);
  }

  private static void validateCatalog(
      Catalog catalog, Scope scope, Target target, ExpectedVersion expected) {
    canonicalUuid(catalog.tenantId(), "catalog.tenantId");
    canonicalSlug(catalog.tenantSlug(), "catalog.tenantSlug");
    canonicalSlug(catalog.worldSlug(), "catalog.worldSlug");
    canonicalUuid(catalog.realmId(), "catalog.realmId");
    canonicalSlug(catalog.realmSlug(), "catalog.realmSlug");
    canonicalUuid(catalog.playableStateNamespaceId(), "catalog.playableStateNamespaceId");
    positiveLongString(new JsonString(catalog.catalogRevision()), "catalog.catalogRevision");
    if (!catalog.visible() || !catalog.publicProduction()) {
      throw invalid("only visible public-production catalog entries are supported");
    }
    if (!"PLAYER_CREATED".equals(catalog.characterCreationPolicy())) {
      throw invalid("catalog characterCreationPolicy must be PLAYER_CREATED");
    }
    if (!"SHARED".equals(catalog.stateScope()) && !"ISOLATED".equals(catalog.stateScope())) {
      throw invalid("catalog stateScope is unsupported");
    }
    if (!catalog.tenantId().equals(scope.tenantId())
        || !catalog.playableStateNamespaceId().equals(scope.playableStateNamespaceId())
        || !catalog.realmId().equals(target.realmId())
        || !catalog.worldSlug().equals(target.worldSlug())
        || !catalog.realmSlug().equals(target.realmSlug())
        || !catalog.catalogRevision().equals(expected.catalogRevision())
        || !scope.playableStateScope().catalogValue().equals(catalog.stateScope())) {
      throw invalid("catalog identifiers, selectors, scope, or revision contradict the action");
    }
  }

  private static void validatePointer(
      Pointer pointer, Catalog catalog, Target target, ExpectedVersion expected) {
    canonicalUuid(pointer.tenantId(), "pointer.tenantId");
    canonicalUuid(pointer.realmId(), "pointer.realmId");
    canonicalSlug(pointer.worldSlug(), "pointer.worldSlug");
    canonicalSlug(pointer.realmSlug(), "pointer.realmSlug");
    canonicalUuid(pointer.admissibleGameInstanceId(), "admissibleGameInstanceId");
    positiveLongString(new JsonString(pointer.pointerVersion()), "pointer.pointerVersion");
    positiveLongString(new JsonString(pointer.catalogRevision()), "pointer.catalogRevision");
    Objects.requireNonNull(pointer.updatedAt(), "pointer.updatedAt is required");
    if (!"OPEN".equals(pointer.admissionState())
        || !pointer.tenantId().equals(catalog.tenantId())
        || !pointer.realmId().equals(catalog.realmId())
        || !pointer.worldSlug().equals(target.worldSlug())
        || !pointer.realmSlug().equals(target.realmSlug())
        || !pointer.admissibleGameInstanceId().equals(target.gameInstanceId())
        || !pointer.pointerVersion().equals(expected.pointerVersion())
        || !pointer.catalogRevision().equals(catalog.catalogRevision())) {
      throw invalid("admission pointer identifiers or snapshot contradict the action");
    }
  }

  private static void validateAuthoredSource(
      AuthoredSource source, Catalog catalog, Scope scope, Target target) {
    Objects.requireNonNull(source, "authoredSource is required");
    boundedExactText(
        source.publicationWorkflowId(),
        MAX_WORKFLOW_ID_CHARACTERS,
        MAX_WORKFLOW_ID_BYTES,
        "publicationWorkflowId");
    boundedExactText(
        source.policy().logicalRevisionId(),
        MAX_LOGICAL_REVISION_CHARACTERS,
        MAX_LOGICAL_REVISION_BYTES,
        "logicalRevisionId");
    if (!AuthoredInitialActorCoreProjection.SCHEMA.equals(source.schema())) {
      throw invalid("authoredSource schema is unsupported");
    }
    byte[] projectionBytes = canonicalProjection(source);
    AuthoredInitialActorCoreProjection projection =
        AuthoredInitialActorCoreProjection.fromStored(
            projectionBytes, prefixedSha256(projectionBytes));
    if (!Arrays.equals(projection.canonicalBytes(), projectionBytes)) {
      throw invalid("authored projection bytes changed during source validation");
    }
    RealmEntryPolicy policy =
        RealmEntryPolicy.parseCanonical(source.policy().canonicalPolicyJson(), SOURCE_JSON);
    if (policy.schemaVersion() != RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || policy.creationDescriptor() == null
        || policy.initialActorCore() == null
        || !source.canonicalTenantId().equals(scope.tenantId())
        || !source.canonicalVersionId().equals(target.versionId())
        || !source.worldSlug().equals(target.worldSlug())
        || !policy.worldSlug().equals(target.worldSlug())
        || !policy.worldDisplayName().equals(catalog.worldDisplayName())
        || !policy.realmSlug().equals(target.realmSlug())
        || !policy.realmDisplayName().equals(catalog.realmDisplayName())
        || policy.visible() != catalog.visible()
        || policy.publicProduction() != catalog.publicProduction()
        || !policy.stateScope().name().equals(catalog.stateScope())
        || !policy.entryPolicy().name().equals(catalog.characterCreationPolicy())
        || !policy.initialActorCore().canonicalJson().equals(source.core().canonicalJson())
        || !policy.creationDescriptor().canonicalJson().equals(source.descriptor().canonicalJson())
        || !policy.creationDescriptor().digest().equals(source.descriptor().digest())) {
      throw invalid("authored policy, core, descriptor, catalog, or target values contradict");
    }
  }

  private static byte[] canonicalProjection(AuthoredSource source) {
    SourceJsonWriter writer = new SourceJsonWriter();
    writer.beginObject();
    writer.stringProperty("bindingDigest", source.bindingDigest());
    writer.stringProperty("bindingId", source.bindingId());
    writer.stringProperty("canonicalTenantId", source.canonicalTenantId());
    writer.stringProperty("canonicalVersionId", source.canonicalVersionId());
    writer.beginPropertyObject("core");
    writer.stringProperty("canonicalJson", source.core().canonicalJson());
    writer.endObject();
    writer.beginPropertyObject("descriptor");
    writer.stringProperty("canonicalJson", source.descriptor().canonicalJson());
    writer.stringProperty("digest", source.descriptor().digest());
    writer.endObject();
    writeProjectionRule(writer, "disposition", source.disposition());
    writer.beginPropertyObject("policy");
    writer.stringProperty("canonicalPolicyJson", source.policy().canonicalPolicyJson());
    writer.stringProperty("commitId", source.policy().commitId());
    writer.stringProperty("contentDigest", source.policy().contentDigest());
    writer.stringProperty("logicalRevisionId", source.policy().logicalRevisionId());
    writer.stringProperty("revisionId", source.policy().revisionId());
    writer.endObject();
    writer.stringProperty("publicationWorkflowId", source.publicationWorkflowId());
    writeProjectionRule(writer, "safeFeedback", source.safeFeedback());
    writer.stringProperty("schema", source.schema());
    writer.stringProperty("selectedCommitDigest", source.selectedCommitDigest());
    writer.stringProperty("selectedCommitId", source.selectedCommitId());
    writer.stringProperty("worldSlug", source.worldSlug());
    writer.stringProperty("worldSourceEvidenceDigest", source.worldSourceEvidenceDigest());
    writer.stringProperty("worldSourceOperationId", source.worldSourceOperationId());
    writer.endObject();
    return writer.toByteArray();
  }

  private static void writeProjectionRule(SourceJsonWriter writer, String name, Rule rule) {
    writer.beginPropertyObject(name);
    writer.stringProperty("definitionDigest", rule.definitionDigest());
    writer.stringProperty("definitionJson", rule.definitionJson());
    writer.stringProperty("key", rule.key());
    writer.stringProperty("revisionId", rule.revisionId());
    writer.stringProperty("revisionOrder", rule.revisionOrder());
    writer.stringProperty("sourceCommitDigest", rule.sourceCommitDigest());
    writer.stringProperty("sourceCommitId", rule.sourceCommitId());
    writer.endObject();
  }

  private static MutationDigestV1.Value scopeValue(Scope scope) {
    return object(
        member("targetNamespace", string(scope.targetNamespace())),
        member("tenantId", string(scope.tenantId())),
        member("playableStateNamespaceId", string(scope.playableStateNamespaceId())),
        member("playableStateScope", string(scope.playableStateScope().name())));
  }

  private static MutationDigestV1.Value targetValue(Target target) {
    return object(
        member("accountId", string(target.accountId())),
        member("realmId", string(target.realmId())),
        member("worldSlug", string(target.worldSlug())),
        member("realmSlug", string(target.realmSlug())),
        member("gameInstanceId", string(target.gameInstanceId())),
        member("versionId", string(target.versionId())));
  }

  private static MutationDigestV1.Value expectedVersionValue(ExpectedVersion expected) {
    return object(
        member("catalogRevision", string(expected.catalogRevision())),
        member("pointerVersion", string(expected.pointerVersion())),
        member("activeLifecycleEpoch", string(expected.activeLifecycleEpoch())),
        member("playtestLifecycleId", MutationDigestV1.absent()),
        member("playtestStateGeneration", MutationDigestV1.absent()),
        member("evaluatedAt", string(expected.evaluatedAt().toString())),
        member("connectScopeExpiresAt", string(expected.connectScopeExpiresAt().toString())));
  }

  private static MutationDigestV1.Value mutationValue(Mutation mutation) {
    return object(
        member("purpose", string(mutation.purpose())),
        member("entryPolicy", string(mutation.entryPolicy())),
        member("catalog", catalogValue(mutation.catalog())),
        member("pointer", pointerValue(mutation.pointer())),
        member("authoredSource", authoredSourceValue(mutation.authoredSource())),
        member("canonicalCreationInput", string(mutation.canonicalCreationInput())));
  }

  private static MutationDigestV1.Value catalogValue(Catalog catalog) {
    return object(
        member("tenantId", string(catalog.tenantId())),
        member("tenantSlug", string(catalog.tenantSlug())),
        member("worldSlug", string(catalog.worldSlug())),
        member("worldDisplayName", exactUtf8(catalog.worldDisplayName(), 512, "worldDisplayName")),
        member("realmId", string(catalog.realmId())),
        member("realmSlug", string(catalog.realmSlug())),
        member("realmDisplayName", exactUtf8(catalog.realmDisplayName(), 512, "realmDisplayName")),
        member("visible", MutationDigestV1.bool(catalog.visible())),
        member("publicProduction", MutationDigestV1.bool(catalog.publicProduction())),
        member("stateScope", string(catalog.stateScope())),
        member("playableStateNamespaceId", string(catalog.playableStateNamespaceId())),
        member("characterCreationPolicy", string(catalog.characterCreationPolicy())),
        member("catalogRevision", string(catalog.catalogRevision())));
  }

  private static MutationDigestV1.Value pointerValue(Pointer pointer) {
    return object(
        member("tenantId", string(pointer.tenantId())),
        member("realmId", string(pointer.realmId())),
        member("worldSlug", string(pointer.worldSlug())),
        member("realmSlug", string(pointer.realmSlug())),
        member("playtestLifecycleId", MutationDigestV1.absent()),
        member("playtestStateGeneration", MutationDigestV1.absent()),
        member("admissionState", string(pointer.admissionState())),
        member("admissibleGameInstanceId", string(pointer.admissibleGameInstanceId())),
        member("pointerVersion", string(pointer.pointerVersion())),
        member("catalogRevision", string(pointer.catalogRevision())),
        member("updatedAt", string(pointer.updatedAt().toString())));
  }

  private static MutationDigestV1.Value authoredSourceValue(AuthoredSource source) {
    return object(
        member("schema", string(source.schema())),
        member("bindingId", string(source.bindingId())),
        member("bindingDigest", string(source.bindingDigest())),
        member("canonicalTenantId", string(source.canonicalTenantId())),
        member("canonicalVersionId", string(source.canonicalVersionId())),
        member("worldSourceOperationId", string(source.worldSourceOperationId())),
        member("worldSourceEvidenceDigest", string(source.worldSourceEvidenceDigest())),
        member("worldSlug", string(source.worldSlug())),
        member(
            "publicationWorkflowId",
            exactUtf8(
                source.publicationWorkflowId(), MAX_WORKFLOW_ID_BYTES, "publicationWorkflowId")),
        member("selectedCommitId", string(source.selectedCommitId())),
        member("selectedCommitDigest", string(source.selectedCommitDigest())),
        member("policy", policyValue(source.policy())),
        member(
            "core",
            object(
                member(
                    "canonicalJson",
                    exactUtf8(
                        source.core().canonicalJson(),
                        MAX_EXACT_UTF8_SOURCE_BYTES,
                        "core.canonicalJson")))),
        member("descriptor", descriptorValue(source.descriptor())),
        member("disposition", ruleValue(source.disposition(), "disposition")),
        member("safeFeedback", ruleValue(source.safeFeedback(), "safeFeedback")));
  }

  private static MutationDigestV1.Value policyValue(Policy policy) {
    return object(
        member("commitId", string(policy.commitId())),
        member("revisionId", string(policy.revisionId())),
        member(
            "logicalRevisionId",
            exactUtf8(policy.logicalRevisionId(), MAX_LOGICAL_REVISION_BYTES, "logicalRevisionId")),
        member(
            "canonicalPolicyJson",
            exactUtf8(policy.canonicalPolicyJson(), MAX_POLICY_BYTES, "canonicalPolicyJson")),
        member("contentDigest", string(policy.contentDigest())));
  }

  private static MutationDigestV1.Value descriptorValue(Descriptor descriptor) {
    return object(
        member(
            "canonicalJson",
            exactUtf8(
                descriptor.canonicalJson(), MAX_DESCRIPTOR_BYTES, "descriptor.canonicalJson")),
        member("digest", string(descriptor.digest())));
  }

  private static MutationDigestV1.Value ruleValue(Rule rule, String label) {
    return object(
        member("key", string(rule.key())),
        member(
            "definitionJson",
            exactUtf8(
                rule.definitionJson(), MAX_EXACT_UTF8_SOURCE_BYTES, label + ".definitionJson")),
        member("definitionDigest", string(rule.definitionDigest())),
        member("sourceCommitId", string(rule.sourceCommitId())),
        member("sourceCommitDigest", string(rule.sourceCommitDigest())),
        member("revisionId", string(rule.revisionId())),
        member("revisionOrder", string(rule.revisionOrder())));
  }

  private static MutationDigestV1.Value exactUtf8(String value, int byteLimit, String label) {
    byte[] bytes = utf8Strict(value, label);
    if (bytes.length > byteLimit) throw invalid(label + " exceeds its ExactUtf8 byte limit");
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    if (payload.length() > unpaddedBase64Length(byteLimit)) {
      throw invalid(label + " exceeds its ExactUtf8 encoded limit");
    }
    return object(
        member("encoding", string(EXACT_UTF8_ENCODING)),
        member(
            "bytes",
            MutationDigestV1.string(
                payload, MutationDigestV1.MAX_CHARACTER_CREATE_NORMALIZED_STRING_BYTES)));
  }

  private static MutationDigestV1.Value string(String value) {
    return MutationDigestV1.string(value, MAX_IDENTITY_STRING_BYTES);
  }

  private static MutationDigestV1.Value object(MutationDigestV1.Member... members) {
    return MutationDigestV1.object(List.of(members));
  }

  private static MutationDigestV1.Member member(String key, MutationDigestV1.Value value) {
    return MutationDigestV1.member(key, value);
  }

  private static void writeScope(RawJsonWriter writer, Scope scope) {
    writer.beginObject();
    writer.stringProperty("targetNamespace", scope.targetNamespace());
    writer.stringProperty("tenantId", scope.tenantId());
    writer.stringProperty("playableStateNamespaceId", scope.playableStateNamespaceId());
    writer.stringProperty("playableStateScope", scope.playableStateScope().name());
    writer.endObject();
  }

  private static void writeTarget(RawJsonWriter writer, Target target) {
    writer.beginObject();
    writer.stringProperty("accountId", target.accountId());
    writer.stringProperty("realmId", target.realmId());
    writer.stringProperty("worldSlug", target.worldSlug());
    writer.stringProperty("realmSlug", target.realmSlug());
    writer.stringProperty("gameInstanceId", target.gameInstanceId());
    writer.stringProperty("versionId", target.versionId());
    writer.endObject();
  }

  private static void writeExpectedVersion(RawJsonWriter writer, ExpectedVersion expected) {
    writer.beginObject();
    writer.stringProperty("catalogRevision", expected.catalogRevision());
    writer.stringProperty("pointerVersion", expected.pointerVersion());
    writer.stringProperty("activeLifecycleEpoch", expected.activeLifecycleEpoch());
    writer.stringProperty("evaluatedAt", expected.evaluatedAt().toString());
    writer.stringProperty("connectScopeExpiresAt", expected.connectScopeExpiresAt().toString());
    writer.endObject();
  }

  private static void writeMutation(RawJsonWriter writer, Mutation mutation) {
    writer.beginObject();
    writer.stringProperty("purpose", mutation.purpose());
    writer.stringProperty("entryPolicy", mutation.entryPolicy());
    writer.name("catalog");
    writeCatalog(writer, mutation.catalog());
    writer.name("pointer");
    writePointer(writer, mutation.pointer());
    writer.name("authoredSource");
    writeAuthoredSource(writer, mutation.authoredSource());
    writer.stringProperty("canonicalCreationInput", mutation.canonicalCreationInput());
    writer.endObject();
  }

  private static void writeCatalog(RawJsonWriter writer, Catalog catalog) {
    writer.beginObject();
    writer.stringProperty("tenantId", catalog.tenantId());
    writer.stringProperty("tenantSlug", catalog.tenantSlug());
    writer.stringProperty("worldSlug", catalog.worldSlug());
    writer.exactUtf8Property(
        "worldDisplayName", catalog.worldDisplayName(), 512, "worldDisplayName");
    writer.stringProperty("realmId", catalog.realmId());
    writer.stringProperty("realmSlug", catalog.realmSlug());
    writer.exactUtf8Property(
        "realmDisplayName", catalog.realmDisplayName(), 512, "realmDisplayName");
    writer.booleanProperty("visible", catalog.visible());
    writer.booleanProperty("publicProduction", catalog.publicProduction());
    writer.stringProperty("stateScope", catalog.stateScope());
    writer.stringProperty("playableStateNamespaceId", catalog.playableStateNamespaceId());
    writer.stringProperty("characterCreationPolicy", catalog.characterCreationPolicy());
    writer.stringProperty("catalogRevision", catalog.catalogRevision());
    writer.endObject();
  }

  private static void writePointer(RawJsonWriter writer, Pointer pointer) {
    writer.beginObject();
    writer.stringProperty("tenantId", pointer.tenantId());
    writer.stringProperty("realmId", pointer.realmId());
    writer.stringProperty("worldSlug", pointer.worldSlug());
    writer.stringProperty("realmSlug", pointer.realmSlug());
    writer.stringProperty("admissionState", pointer.admissionState());
    writer.stringProperty("admissibleGameInstanceId", pointer.admissibleGameInstanceId());
    writer.stringProperty("pointerVersion", pointer.pointerVersion());
    writer.stringProperty("catalogRevision", pointer.catalogRevision());
    writer.stringProperty("updatedAt", pointer.updatedAt().toString());
    writer.endObject();
  }

  private static void writeAuthoredSource(RawJsonWriter writer, AuthoredSource source) {
    writer.beginObject();
    writer.stringProperty("schema", source.schema());
    writer.stringProperty("bindingId", source.bindingId());
    writer.stringProperty("bindingDigest", source.bindingDigest());
    writer.stringProperty("canonicalTenantId", source.canonicalTenantId());
    writer.stringProperty("canonicalVersionId", source.canonicalVersionId());
    writer.stringProperty("worldSourceOperationId", source.worldSourceOperationId());
    writer.stringProperty("worldSourceEvidenceDigest", source.worldSourceEvidenceDigest());
    writer.stringProperty("worldSlug", source.worldSlug());
    writer.exactUtf8Property(
        "publicationWorkflowId",
        source.publicationWorkflowId(),
        MAX_WORKFLOW_ID_BYTES,
        "publicationWorkflowId");
    writer.stringProperty("selectedCommitId", source.selectedCommitId());
    writer.stringProperty("selectedCommitDigest", source.selectedCommitDigest());
    writer.name("policy");
    writer.beginObject();
    writer.stringProperty("commitId", source.policy().commitId());
    writer.stringProperty("revisionId", source.policy().revisionId());
    writer.exactUtf8Property(
        "logicalRevisionId",
        source.policy().logicalRevisionId(),
        MAX_LOGICAL_REVISION_BYTES,
        "logicalRevisionId");
    writer.exactUtf8Property(
        "canonicalPolicyJson",
        source.policy().canonicalPolicyJson(),
        MAX_POLICY_BYTES,
        "canonicalPolicyJson");
    writer.stringProperty("contentDigest", source.policy().contentDigest());
    writer.endObject();
    writer.name("core");
    writer.beginObject();
    writer.exactUtf8Property(
        "canonicalJson",
        source.core().canonicalJson(),
        MAX_EXACT_UTF8_SOURCE_BYTES,
        "core.canonicalJson");
    writer.endObject();
    writer.name("descriptor");
    writer.beginObject();
    writer.exactUtf8Property(
        "canonicalJson",
        source.descriptor().canonicalJson(),
        MAX_DESCRIPTOR_BYTES,
        "descriptor.canonicalJson");
    writer.stringProperty("digest", source.descriptor().digest());
    writer.endObject();
    writeRule(writer, source.disposition(), "disposition");
    writeRule(writer, source.safeFeedback(), "safeFeedback");
    writer.endObject();
  }

  private static void writeRule(RawJsonWriter writer, Rule rule, String label) {
    writer.name(label);
    writer.beginObject();
    writer.stringProperty("key", rule.key());
    writer.exactUtf8Property(
        "definitionJson",
        rule.definitionJson(),
        MAX_EXACT_UTF8_SOURCE_BYTES,
        label + ".definitionJson");
    writer.stringProperty("definitionDigest", rule.definitionDigest());
    writer.stringProperty("sourceCommitId", rule.sourceCommitId());
    writer.stringProperty("sourceCommitDigest", rule.sourceCommitDigest());
    writer.stringProperty("revisionId", rule.revisionId());
    writer.stringProperty("revisionOrder", rule.revisionOrder());
    writer.endObject();
  }

  private static final class RawJsonWriter {
    private final StringBuilder output = new StringBuilder(512);
    private final Deque<RawObjectState> objects = new ArrayDeque<>();
    private boolean rootWritten;

    private void beginObject() {
      beforeValue();
      append('{');
      objects.push(new RawObjectState());
    }

    private void endObject() {
      RawObjectState state = objects.pop();
      if (state.awaitingValue) throw invalid("encoder ended an incomplete raw object");
      append('}');
    }

    private void name(String name) {
      RawObjectState state = objects.peek();
      if (state == null || state.awaitingValue) throw invalid("encoder object member is misplaced");
      if (!state.first) append(',');
      state.first = false;
      appendQuoted(name);
      append(':');
      state.awaitingValue = true;
    }

    private void stringProperty(String name, String value) {
      name(name);
      stringValue(value);
    }

    private void booleanProperty(String name, boolean value) {
      name(name);
      beforeValue();
      append(value ? "true" : "false");
    }

    private void exactUtf8Property(String name, String value, int byteLimit, String label) {
      byte[] bytes = utf8Strict(value, label);
      if (bytes.length > byteLimit) throw invalid(label + " exceeds its ExactUtf8 byte limit");
      String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      if (payload.length() > unpaddedBase64Length(byteLimit)) {
        throw invalid(label + " exceeds its ExactUtf8 encoded limit");
      }
      name(name);
      beginObject();
      stringProperty("encoding", EXACT_UTF8_ENCODING);
      stringProperty("bytes", payload);
      endObject();
    }

    private void stringValue(String value) {
      beforeValue();
      appendQuoted(value);
    }

    private void beforeValue() {
      if (objects.isEmpty()) {
        if (rootWritten) throw invalid("encoder produced multiple raw JSON roots");
        rootWritten = true;
        return;
      }
      RawObjectState state = objects.peek();
      if (!state.awaitingValue) throw invalid("encoder value has no raw JSON member name");
      state.awaitingValue = false;
    }

    private void appendQuoted(String value) {
      append('"');
      for (int index = 0; index < value.length(); index++) {
        char current = value.charAt(index);
        if (current > 0x7f) throw invalid("raw action string is not ASCII");
        switch (current) {
          case '"' -> append("\\\"");
          case '\\' -> append("\\\\");
          case '\b' -> append("\\b");
          case '\f' -> append("\\f");
          case '\n' -> append("\\n");
          case '\r' -> append("\\r");
          case '\t' -> append("\\t");
          default -> {
            if (current < 0x20) {
              append("\\u00");
              append(Character.forDigit((current >>> 4) & 0x0f, 16));
              append(Character.forDigit(current & 0x0f, 16));
            } else {
              append(current);
            }
          }
        }
      }
      append('"');
    }

    private void append(String value) {
      if ((long) output.length() + value.length() > MAX_RAW_BYTES) {
        throw invalid("encoded action exceeds its raw byte limit");
      }
      output.append(value);
    }

    private void append(char value) {
      if (output.length() >= MAX_RAW_BYTES)
        throw invalid("encoded action exceeds its raw byte limit");
      output.append(value);
    }

    private byte[] toByteArray() {
      if (!rootWritten || !objects.isEmpty()) throw invalid("encoder produced incomplete raw JSON");
      return output.toString().getBytes(StandardCharsets.US_ASCII);
    }
  }

  private static final class RawObjectState {
    private boolean first = true;
    private boolean awaitingValue;
  }

  /** Writes the known closed public projection in RFC 8785 key order with a hard byte ceiling. */
  private static final class SourceJsonWriter {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream(1_024);
    private final Deque<SourceObjectState> objects = new ArrayDeque<>();

    private void beginObject() {
      appendAscii("{");
      objects.push(new SourceObjectState());
    }

    private void beginPropertyObject(String name) {
      propertyPrefix(name);
      appendAscii("{");
      objects.push(new SourceObjectState());
    }

    private void endObject() {
      if (objects.isEmpty()) throw invalid("projection encoder ended an unopened object");
      objects.pop();
      appendAscii("}");
    }

    private void stringProperty(String name, String value) {
      propertyPrefix(name);
      appendQuoted(value);
    }

    private void propertyPrefix(String name) {
      SourceObjectState state = objects.peek();
      if (state == null) throw invalid("projection member is misplaced");
      if (!state.first) appendAscii(",");
      state.first = false;
      appendQuoted(name);
      appendAscii(":");
    }

    private void appendQuoted(String value) {
      appendAscii("\"");
      for (int index = 0; index < value.length(); index++) {
        int codePoint = value.codePointAt(index);
        if (Character.isHighSurrogate(value.charAt(index))) {
          if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
            throw invalid("projection source contains malformed Unicode");
          }
          index++;
        } else if (Character.isLowSurrogate(value.charAt(index))) {
          throw invalid("projection source contains malformed Unicode");
        }
        switch (codePoint) {
          case '"' -> appendAscii("\\\"");
          case '\\' -> appendAscii("\\\\");
          case '\b' -> appendAscii("\\b");
          case '\f' -> appendAscii("\\f");
          case '\n' -> appendAscii("\\n");
          case '\r' -> appendAscii("\\r");
          case '\t' -> appendAscii("\\t");
          default -> {
            if (codePoint < 0x20) {
              appendAscii("\\u00");
              appendAscii(Character.forDigit((codePoint >>> 4) & 0x0f, 16));
              appendAscii(Character.forDigit(codePoint & 0x0f, 16));
            } else {
              appendCodePoint(codePoint);
            }
          }
        }
      }
      appendAscii("\"");
    }

    private void appendCodePoint(int codePoint) {
      if (codePoint <= 0x7f) {
        appendByte(codePoint);
      } else if (codePoint <= 0x7ff) {
        appendByte(0xc0 | (codePoint >>> 6));
        appendByte(0x80 | (codePoint & 0x3f));
      } else if (codePoint <= 0xffff) {
        appendByte(0xe0 | (codePoint >>> 12));
        appendByte(0x80 | ((codePoint >>> 6) & 0x3f));
        appendByte(0x80 | (codePoint & 0x3f));
      } else {
        appendByte(0xf0 | (codePoint >>> 18));
        appendByte(0x80 | ((codePoint >>> 12) & 0x3f));
        appendByte(0x80 | ((codePoint >>> 6) & 0x3f));
        appendByte(0x80 | (codePoint & 0x3f));
      }
    }

    private void appendAscii(String value) {
      for (int index = 0; index < value.length(); index++) {
        char current = value.charAt(index);
        if (current > 0x7f) throw invalid("projection JSON syntax must be ASCII");
        appendByte(current);
      }
    }

    private void appendAscii(char value) {
      if (value > 0x7f) throw invalid("projection JSON syntax must be ASCII");
      appendByte(value);
    }

    private void appendByte(int value) {
      if (output.size() >= AuthoredInitialActorCoreProjection.MAX_BYTES) {
        throw invalid("complete authored projection exceeds its 1 MiB bound");
      }
      output.write(value);
    }

    private byte[] toByteArray() {
      if (!objects.isEmpty()) throw invalid("projection encoder produced incomplete JSON");
      return output.toByteArray();
    }
  }

  private static final class SourceObjectState {
    private boolean first = true;
  }

  private sealed interface JsonValue permits JsonString, JsonBoolean, JsonNull, JsonObject {}

  private record JsonString(String value) implements JsonValue {}

  private record JsonBoolean(boolean value) implements JsonValue {}

  private enum JsonNull implements JsonValue {
    INSTANCE
  }

  private record JsonMember(JsonString key, JsonValue value) {}

  private record JsonObject(List<JsonMember> members) implements JsonValue {
    private JsonObject {
      members = List.copyOf(members);
    }
  }

  /** Performs a byte-only bounds pass before allocating the parsed strings and object tree. */
  private static final class StrictJsonParser {
    private final byte[] input;
    private int position;

    private StrictJsonParser(byte[] input) {
      if (input == null || input.length == 0 || input.length > MAX_RAW_BYTES) {
        throw invalid("raw action JSON must fit its 2 MiB family bound");
      }
      this.input = input;
      new ActionBoundsScanner(input).scanDocument();
    }

    private JsonValue parseDocument() {
      skipWhitespace();
      JsonValue value = parseValue(0);
      skipWhitespace();
      if (position != input.length) throw invalid("raw action contains trailing JSON tokens");
      return value;
    }

    private JsonValue parseValue(int parentDepth) {
      skipWhitespace();
      if (position >= input.length) throw invalid("raw action ended before a JSON value");
      int next = unsigned(input[position]);
      if (next == '"') return new JsonString(parseString());
      if (next == '{') {
        if (parentDepth + 1 > MAX_NESTING_DEPTH) {
          throw invalid("raw action exceeds the nesting-depth limit");
        }
        return parseObject(parentDepth + 1);
      }
      if (next == 't') {
        readLiteral("true");
        return new JsonBoolean(true);
      }
      if (next == 'f') {
        readLiteral("false");
        return new JsonBoolean(false);
      }
      if (next == 'n') {
        readLiteral("null");
        return JsonNull.INSTANCE;
      }
      if (next == '[') throw invalid("raw action arrays are unsupported");
      throw invalid("raw action numbers and unsupported JSON values are forbidden");
    }

    private JsonObject parseObject(int depth) {
      position++;
      skipWhitespace();
      List<JsonMember> members = new ArrayList<>();
      if (consume('}')) return new JsonObject(members);
      while (true) {
        skipWhitespace();
        if (position >= input.length || unsigned(input[position]) != '"') {
          throw invalid("raw action object keys must be strings");
        }
        String key = parseString();
        skipWhitespace();
        require(':');
        JsonValue value = parseValue(depth);
        members.add(new JsonMember(new JsonString(key), value));
        skipWhitespace();
        if (consume('}')) return new JsonObject(members);
        require(',');
      }
    }

    private String parseString() {
      int openingQuote = position++;
      int segmentStart = position;
      StringBuilder decoded = new StringBuilder();
      boolean hasEscape = false;
      while (position < input.length) {
        int current = unsigned(input[position]);
        if (current == '"') {
          if (hasEscape) appendUtf8Slice(decoded, segmentStart, position - segmentStart);
          String value =
              hasEscape
                  ? decoded.toString()
                  : decodeUtf8Slice(openingQuote + 1, position - openingQuote - 1);
          position++;
          requireUnicodeScalars(value, "raw action string");
          return value;
        }
        if (current != '\\') {
          position++;
          continue;
        }

        hasEscape = true;
        appendUtf8Slice(decoded, segmentStart, position - segmentStart);
        position++;
        if (position >= input.length) throw invalid("raw action string ends inside an escape");
        int escaped = unsigned(input[position++]);
        switch (escaped) {
          case '"' -> decoded.append('"');
          case '\\' -> decoded.append('\\');
          case '/' -> decoded.append('/');
          case 'b' -> decoded.append('\b');
          case 'f' -> decoded.append('\f');
          case 'n' -> decoded.append('\n');
          case 'r' -> decoded.append('\r');
          case 't' -> decoded.append('\t');
          case 'u' -> {
            char first = readHexCodeUnit();
            if (Character.isHighSurrogate(first)) {
              if (position + 1 >= input.length
                  || unsigned(input[position]) != '\\'
                  || unsigned(input[position + 1]) != 'u') {
                throw invalid("raw action string contains an unmatched Unicode surrogate");
              }
              position += 2;
              char second = readHexCodeUnit();
              if (!Character.isLowSurrogate(second)) {
                throw invalid("raw action string contains an unmatched Unicode surrogate");
              }
              decoded.append(first).append(second);
            } else if (Character.isLowSurrogate(first)) {
              throw invalid("raw action string contains an unmatched Unicode surrogate");
            } else {
              decoded.append(first);
            }
          }
          default -> throw invalid("raw action string contains an invalid escape");
        }
        segmentStart = position;
      }
      throw invalid("raw action contains an unterminated string");
    }

    private char readHexCodeUnit() {
      if (position + 4 > input.length) throw invalid("raw action has an incomplete Unicode escape");
      int value = 0;
      for (int index = 0; index < 4; index++) {
        int hex = hex(unsigned(input[position++]));
        if (hex < 0) throw invalid("raw action has an invalid Unicode escape");
        value = (value << 4) | hex;
      }
      return (char) value;
    }

    private void appendUtf8Slice(StringBuilder output, int start, int length) {
      output.append(decodeUtf8Slice(start, length));
    }

    private String decodeUtf8Slice(int start, int length) {
      try {
        CharBuffer decoded =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input, start, length));
        return decoded.toString();
      } catch (CharacterCodingException malformed) {
        throw invalid("raw action string is not valid UTF-8");
      }
    }

    private void readLiteral(String literal) {
      for (int index = 0; index < literal.length(); index++) {
        if (position >= input.length || unsigned(input[position++]) != literal.charAt(index)) {
          throw invalid("raw action contains an invalid JSON literal");
        }
      }
    }

    private void skipWhitespace() {
      while (position < input.length) {
        int current = unsigned(input[position]);
        if (current != ' ' && current != '\t' && current != '\r' && current != '\n') return;
        position++;
      }
    }

    private boolean consume(char expected) {
      if (position < input.length && unsigned(input[position]) == expected) {
        position++;
        return true;
      }
      return false;
    }

    private void require(char expected) {
      if (!consume(expected)) throw invalid("raw action has invalid JSON structure");
    }
  }

  /** Scans only JSON structure and token sizes; no strings or tree nodes are allocated here. */
  private static final class ActionBoundsScanner {
    private final byte[] input;
    private int position;

    private ActionBoundsScanner(byte[] input) {
      this.input = input;
    }

    private void scanDocument() {
      skipWhitespace();
      scanValue(0);
      skipWhitespace();
      if (position != input.length) throw invalid("raw action contains trailing JSON tokens");
    }

    private void scanValue(int parentDepth) {
      skipWhitespace();
      if (position >= input.length) throw invalid("raw action ended before a JSON value");
      int next = unsigned(input[position]);
      if (next == '"') {
        scanString();
      } else if (next == '{') {
        int depth = parentDepth + 1;
        if (depth > MAX_NESTING_DEPTH) throw invalid("raw action exceeds the nesting-depth limit");
        scanObject(depth);
      } else if (next == '[') {
        throw invalid("raw action arrays are unsupported");
      } else if (next == 't') {
        scanLiteral("true");
      } else if (next == 'f') {
        scanLiteral("false");
      } else if (next == 'n') {
        scanLiteral("null");
      } else {
        throw invalid("raw action numbers and unsupported JSON values are forbidden");
      }
    }

    private void scanObject(int depth) {
      position++;
      skipWhitespace();
      if (consume('}')) return;
      int members = 0;
      while (true) {
        skipWhitespace();
        if (position >= input.length || unsigned(input[position]) != '"') {
          throw invalid("raw action object keys must be strings");
        }
        if (++members > MAX_OBJECT_MEMBERS)
          throw invalid("raw action exceeds the object-member limit");
        scanString();
        skipWhitespace();
        require(':');
        scanValue(depth);
        skipWhitespace();
        if (consume('}')) return;
        require(',');
      }
    }

    private void scanString() {
      int start = position++;
      while (position < input.length) {
        int current = unsigned(input[position++]);
        if (current == '"') {
          if (position - start > MAX_RAW_STRING_TOKEN_BYTES) {
            throw invalid("raw action string token exceeds its 1.5 MiB bound");
          }
          return;
        }
        if (current == '\\') {
          if (position >= input.length) throw invalid("raw action string ends inside an escape");
          int escaped = unsigned(input[position++]);
          if (escaped == 'u') {
            for (int digit = 0; digit < 4; digit++) {
              if (position >= input.length || hex(unsigned(input[position++])) < 0) {
                throw invalid("raw action contains an invalid Unicode escape");
              }
            }
          } else if (escaped != '"'
              && escaped != '\\'
              && escaped != '/'
              && escaped != 'b'
              && escaped != 'f'
              && escaped != 'n'
              && escaped != 'r'
              && escaped != 't') {
            throw invalid("raw action contains an invalid string escape");
          }
        } else if (current < 0x20) {
          throw invalid("raw action contains an unescaped control character");
        }
        if (position - start > MAX_RAW_STRING_TOKEN_BYTES) {
          throw invalid("raw action string token exceeds its 1.5 MiB bound");
        }
      }
      throw invalid("raw action contains an unterminated string");
    }

    private void scanLiteral(String literal) {
      for (int index = 0; index < literal.length(); index++) {
        if (position >= input.length || unsigned(input[position++]) != literal.charAt(index)) {
          throw invalid("raw action contains an invalid JSON literal");
        }
      }
    }

    private void skipWhitespace() {
      while (position < input.length) {
        int current = unsigned(input[position]);
        if (current != ' ' && current != '\t' && current != '\r' && current != '\n') return;
        position++;
      }
    }

    private boolean consume(char expected) {
      if (position < input.length && unsigned(input[position]) == expected) {
        position++;
        return true;
      }
      return false;
    }

    private void require(char expected) {
      if (!consume(expected)) throw invalid("raw action has invalid JSON structure");
    }
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static int hex(int value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  }

  private static void requireUnicodeScalars(String value, String label) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw invalid(label + " contains malformed Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw invalid(label + " contains malformed Unicode");
      }
    }
  }

  private static byte[] utf8Strict(String value, String label) {
    requireUnicodeScalars(value, label);
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
      throw invalid(label + " is not valid UTF-8");
    }
  }

  private static String prefixedSha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
