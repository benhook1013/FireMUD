package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/** Closed, bounded canonical JSON codec for the shape-only schema-2 issuer member value. */
final class CanonicalGameplayIssuerMemberV2Codec {
  static final int MAX_VALUE_BYTES = 64 * 1024;
  static final int MAX_COUNTER_DIGITS = 256;

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)");
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(8)
                  .maxStringLength(MAX_VALUE_BYTES)
                  .maxNumberLength(8)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final ObjectMapper JSON =
      new ObjectMapper(JSON_FACTORY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schemaVersion",
          "issuerId",
          "accountId",
          "tenantId",
          "gameInstanceId",
          "sessionId",
          "bindingGeneration",
          "issuerAuthGeneration",
          "issuerIndexLayoutVersion",
          "playableStateNamespaceId",
          "characterId",
          "playableStateScope",
          "runtimeGameInstanceId",
          "regionId",
          "regionEpoch",
          "issuerControlEpoch",
          "layout",
          "reservation",
          "anchor",
          "memberOperation",
          "incarnation");
  private static final Set<String> LAYOUT_FIELDS =
      Set.of(
          "partitionCount",
          "partitionCapacity",
          "mappingIdentity",
          "descriptorBytes",
          "descriptorDigest");
  private static final Set<String> RESERVATION_FIELDS =
      Set.of(
          "reservationId",
          "bindingRef",
          "transitionId",
          "partitionId",
          "lifecycle",
          "reservationFence",
          "inventoryRevision");
  private static final Set<String> ANCHOR_FIELDS =
      Set.of(
          "issuerCoverageOperationId",
          "operationFence",
          "coverageFence",
          "issuerInventorySnapshotRevision",
          "sourceCheckpointBytes",
          "sourceCheckpointDigest",
          "sourceEventBytes",
          "sourceEventDigest");
  private static final Set<String> MEMBER_OPERATION_FIELDS =
      Set.of("operationId", "operationFence", "coverageGeneration");
  private static final Set<String> INCARNATION_FIELDS =
      Set.of(
          "attestorBinding",
          "acceptedAttestationDigest",
          "targetDeploymentEvidence",
          "coordOpsRoAclDigest",
          "coordRedisConfigObservedDigest");
  private static final Set<String> TARGET_DEPLOYMENT_EVIDENCE_FIELDS =
      Set.of(
          "deploymentIdentity",
          "deploymentGeneration",
          "canonicalNodeSet",
          "redisBuildIdentity",
          "luaScriptRegistryVersion",
          "luaScriptRegistryDigest",
          "startupState",
          "bootGeneration",
          "startupGeneration",
          "storageGeneration",
          "aofGeneration");

  private CanonicalGameplayIssuerMemberV2Codec() {}

  static byte[] encode(CanonicalGameplayIssuerMemberV2Value value) {
    Objects.requireNonNull(value, "value");
    try {
      requireCounterLimit(value.bindingGeneration(), "bindingGeneration", false);
      CanonicalGameplayBindingIdentity owner = value.owner();
      requireCounterLimit(owner.issuerAuthGeneration(), "issuerAuthGeneration", false);
      requireCounterLimit(owner.issuerIndexLayoutVersion(), "issuerIndexLayoutVersion", false);
      requireCounterLimit(owner.regionEpoch(), "regionEpoch", false);
      requireCounterLimit(value.issuerControlEpoch(), "issuerControlEpoch", false);
      requireCounterLimit(value.layoutDescriptor().partitionCount(), "partitionCount", false);
      requireCounterLimit(value.layoutDescriptor().partitionCapacity(), "partitionCapacity", false);
      requireCounterLimit(value.reservation().partitionId(), "partitionId", true);
      requireCounterLimit(value.reservation().inventoryRevision(), "inventoryRevision", false);
      requireCounterLimit(value.anchor().operationFence(), "anchor.operationFence", false);
      requireCounterLimit(value.anchor().coverageFence(), "anchor.coverageFence", false);
      requireCounterLimit(
          value.anchor().issuerInventorySnapshotRevision(),
          "issuerInventorySnapshotRevision",
          false);
      requireCounterLimit(
          value.memberOperation().operationFence(), "memberOperation.operationFence", false);
      requireCounterLimit(
          value.memberOperation().coverageGeneration(),
          "memberOperation.coverageGeneration",
          false);
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(toJson(value)));
      if (bytes.length > MAX_VALUE_BYTES) {
        throw invalid("issuer member value exceeds the local 64 KiB resource limit");
      }
      return bytes;
    } catch (IOException failure) {
      throw new IllegalArgumentException("Could not encode canonical issuer member value", failure);
    }
  }

  static CanonicalGameplayIssuerMemberV2Value decode(byte[] encoded) {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_VALUE_BYTES) {
      throw invalid("issuer member value is empty or exceeds the local 64 KiB resource limit");
    }
    String json = decodeUtf8(encoded);
    try {
      JsonNode parsed = JSON.readTree(json);
      ObjectNode root = requireObject(parsed, "issuer member value");
      JsonNode schemaVersion = root.get("schemaVersion");
      if (schemaVersion != null
          && schemaVersion.isIntegralNumber()
          && schemaVersion.canConvertToInt()
          && schemaVersion.intValue() == 1) {
        throw invalid("schema-1 issuer member is unqualified legacy evidence");
      }
      requireFields(root, ROOT_FIELDS, "issuer member value");
      if (!root.path("schemaVersion").isIntegralNumber()
          || !root.path("schemaVersion").canConvertToInt()
          || root.path("schemaVersion").intValue()
              != CanonicalGameplayIssuerMemberV2Value.SCHEMA_VERSION) {
        throw invalid("schemaVersion must be the JSON integer 2");
      }

      String issuerId = text(root, "issuerId");
      UUID accountId = uuid(root, "accountId");
      UUID tenantId = uuid(root, "tenantId");
      UUID gameInstanceId = uuid(root, "gameInstanceId");
      String sessionId = text(root, "sessionId");
      BigInteger bindingGeneration = positive(root, "bindingGeneration");
      BigInteger issuerAuthGeneration = positive(root, "issuerAuthGeneration");
      BigInteger issuerIndexLayoutVersion = positive(root, "issuerIndexLayoutVersion");
      UUID playableStateNamespaceId = uuid(root, "playableStateNamespaceId");
      UUID characterId = uuid(root, "characterId");
      String playableStateScope = text(root, "playableStateScope");
      long runtimeGameInstanceId = positiveLong(root, "runtimeGameInstanceId");
      UUID regionId = uuid(root, "regionId");
      BigInteger regionEpoch = positive(root, "regionEpoch");
      BigInteger issuerControlEpoch = positive(root, "issuerControlEpoch");

      ObjectNode layoutObject = nestedObject(root, "layout", LAYOUT_FIELDS);
      BigInteger partitionCount = positive(layoutObject, "partitionCount");
      BigInteger partitionCapacity = positive(layoutObject, "partitionCapacity");
      String mappingIdentity = text(layoutObject, "mappingIdentity");
      byte[] descriptorBytes = base64(layoutObject, "descriptorBytes");
      String descriptorDigest = text(layoutObject, "descriptorDigest");
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layoutDescriptor =
          new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
              issuerId,
              issuerIndexLayoutVersion,
              partitionCount,
              partitionCapacity,
              mappingIdentity,
              descriptorBytes,
              descriptorDigest);

      ObjectNode reservationObject = nestedObject(root, "reservation", RESERVATION_FIELDS);
      UUID reservationId = uuid(reservationObject, "reservationId");
      String bindingRef = text(reservationObject, "bindingRef");
      UUID transitionId = uuid(reservationObject, "transitionId");
      BigInteger partitionId = nonNegative(reservationObject, "partitionId");
      CanonicalIssuerPartitionReservation.Lifecycle lifecycle =
          enumValue(
              CanonicalIssuerPartitionReservation.Lifecycle.class,
              text(reservationObject, "lifecycle"),
              "reservation lifecycle");
      UUID reservationFence = uuid(reservationObject, "reservationFence");
      BigInteger inventoryRevision = positive(reservationObject, "inventoryRevision");

      ObjectNode anchorObject = nestedObject(root, "anchor", ANCHOR_FIELDS);
      UUID issuerCoverageOperationId = uuid(anchorObject, "issuerCoverageOperationId");
      BigInteger anchorOperationFence = positive(anchorObject, "operationFence");
      BigInteger coverageFence = positive(anchorObject, "coverageFence");
      BigInteger issuerInventorySnapshotRevision =
          positive(anchorObject, "issuerInventorySnapshotRevision");
      byte[] sourceCheckpointBytes = base64(anchorObject, "sourceCheckpointBytes");
      String sourceCheckpointDigest = text(anchorObject, "sourceCheckpointDigest");
      byte[] sourceEventBytes = nullableBase64(anchorObject, "sourceEventBytes");
      String sourceEventDigest = nullableText(anchorObject, "sourceEventDigest");
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint sourceCheckpoint =
          new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
              issuerId,
              issuerAuthGeneration,
              sourceCheckpointBytes,
              sourceCheckpointDigest,
              sourceEventBytes,
              sourceEventDigest);

      ObjectNode operationObject = nestedObject(root, "memberOperation", MEMBER_OPERATION_FIELDS);
      CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation memberOperation =
          new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
              uuid(operationObject, "operationId"),
              positive(operationObject, "operationFence"),
              positive(operationObject, "coverageGeneration"));

      ObjectNode incarnationObject = nestedObject(root, "incarnation", INCARNATION_FIELDS);
      ObjectNode deploymentObject =
          nestedObject(
              incarnationObject, "targetDeploymentEvidence", TARGET_DEPLOYMENT_EVIDENCE_FIELDS);
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
          targetDeploymentEvidence =
              new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
                  text(deploymentObject, "deploymentIdentity"),
                  text(deploymentObject, "deploymentGeneration"),
                  stringArray(deploymentObject, "canonicalNodeSet"),
                  text(deploymentObject, "redisBuildIdentity"),
                  text(deploymentObject, "luaScriptRegistryVersion"),
                  text(deploymentObject, "luaScriptRegistryDigest"),
                  text(deploymentObject, "startupState"),
                  text(deploymentObject, "bootGeneration"),
                  text(deploymentObject, "startupGeneration"),
                  text(deploymentObject, "storageGeneration"),
                  text(deploymentObject, "aofGeneration"));
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation =
          new CanonicalGameplayIssuerMemberV2Value.Incarnation(
              text(incarnationObject, "attestorBinding"),
              text(incarnationObject, "acceptedAttestationDigest"),
              targetDeploymentEvidence,
              text(incarnationObject, "coordOpsRoAclDigest"),
              text(incarnationObject, "coordRedisConfigObservedDigest"));

      CanonicalGameplayBindingIdentity owner =
          new CanonicalGameplayBindingIdentity(
              accountId,
              tenantId,
              playableStateNamespaceId,
              characterId,
              playableStateScope,
              gameInstanceId,
              runtimeGameInstanceId,
              sessionId,
              regionId,
              regionEpoch,
              issuerId,
              issuerAuthGeneration,
              issuerIndexLayoutVersion,
              partitionCount,
              partitionCapacity);
      String expectedBindingRef = new String(owner.bindingRef().bytes(), StandardCharsets.UTF_8);
      if (!expectedBindingRef.equals(bindingRef)) {
        throw invalid("reservation bindingRef must match the exact canonical binding identity");
      }
      CanonicalIssuerPartitionReservation reservation =
          new CanonicalIssuerPartitionReservation(
              reservationId,
              owner,
              bindingGeneration,
              transitionId,
              partitionId,
              lifecycle,
              reservationFence,
              issuerCoverageOperationId,
              anchorOperationFence,
              coverageFence,
              issuerInventorySnapshotRevision,
              inventoryRevision);
      CanonicalGameplayIssuerMemberV2Value value =
          new CanonicalGameplayIssuerMemberV2Value(
              owner,
              bindingGeneration,
              reservation,
              new CanonicalGameplayIssuerMemberV2Value.Anchor(
                  issuerCoverageOperationId,
                  anchorOperationFence,
                  coverageFence,
                  issuerInventorySnapshotRevision),
              memberOperation,
              issuerControlEpoch,
              layoutDescriptor,
              sourceCheckpoint,
              incarnation);

      if (!Arrays.equals(encoded, encode(value))) {
        throw invalid("issuer member JSON must exactly match its canonical emitted bytes");
      }
      return value;
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof IllegalArgumentException invalid) {
        throw invalid;
      }
      throw new IllegalArgumentException("Invalid canonical issuer member value", failure);
    }
  }

  private static ObjectNode toJson(CanonicalGameplayIssuerMemberV2Value value) {
    CanonicalGameplayBindingIdentity owner = value.owner();
    CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout = value.layoutDescriptor();
    CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint source =
        value.sourceCheckpoint();
    CanonicalIssuerPartitionReservation reservation = value.reservation();
    CanonicalGameplayIssuerMemberV2Value.Anchor anchor = value.anchor();
    CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation operation = value.memberOperation();
    CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation = value.incarnation();

    ObjectNode root = JSON.createObjectNode();
    root.put("schemaVersion", CanonicalGameplayIssuerMemberV2Value.SCHEMA_VERSION);
    root.put("issuerId", owner.issuerId());
    root.put("accountId", owner.accountId().toString());
    root.put("tenantId", owner.tenantId().toString());
    root.put("gameInstanceId", owner.gameInstanceId().toString());
    root.put("sessionId", owner.sessionId());
    root.put("bindingGeneration", value.bindingGeneration().toString());
    root.put("issuerAuthGeneration", owner.issuerAuthGeneration().toString());
    root.put("issuerIndexLayoutVersion", owner.issuerIndexLayoutVersion().toString());
    root.put("playableStateNamespaceId", owner.playableStateNamespaceId().toString());
    root.put("characterId", owner.characterId().toString());
    root.put("playableStateScope", owner.playableStateScope());
    root.put("runtimeGameInstanceId", Long.toString(owner.runtimeGameInstanceId()));
    root.put("regionId", owner.regionId().toString());
    root.put("regionEpoch", owner.regionEpoch().toString());
    root.put("issuerControlEpoch", value.issuerControlEpoch().toString());

    ObjectNode layoutObject = root.putObject("layout");
    layoutObject.put("partitionCount", layout.partitionCount().toString());
    layoutObject.put("partitionCapacity", layout.partitionCapacity().toString());
    layoutObject.put("mappingIdentity", layout.mappingIdentity());
    layoutObject.put(
        "descriptorBytes", Base64.getEncoder().encodeToString(layout.exactDescriptorBytes()));
    layoutObject.put("descriptorDigest", layout.descriptorDigest());

    ObjectNode reservationObject = root.putObject("reservation");
    reservationObject.put("reservationId", reservation.reservationId().toString());
    reservationObject.put(
        "bindingRef", new String(owner.bindingRef().bytes(), StandardCharsets.UTF_8));
    reservationObject.put("transitionId", reservation.transitionId().toString());
    reservationObject.put("partitionId", reservation.partitionId().toString());
    reservationObject.put("lifecycle", reservation.lifecycle().name());
    reservationObject.put("reservationFence", reservation.reservationFence().toString());
    reservationObject.put("inventoryRevision", reservation.inventoryRevision().toString());

    ObjectNode anchorObject = root.putObject("anchor");
    anchorObject.put("issuerCoverageOperationId", anchor.issuerCoverageOperationId().toString());
    anchorObject.put("operationFence", anchor.operationFence().toString());
    anchorObject.put("coverageFence", anchor.coverageFence().toString());
    anchorObject.put(
        "issuerInventorySnapshotRevision", anchor.issuerInventorySnapshotRevision().toString());
    anchorObject.put(
        "sourceCheckpointBytes", Base64.getEncoder().encodeToString(source.exactCheckpointBytes()));
    anchorObject.put("sourceCheckpointDigest", source.checkpointDigest());
    putNullableBase64(anchorObject, "sourceEventBytes", source.latestIssuerEventBytes());
    putNullableText(anchorObject, "sourceEventDigest", source.latestIssuerEventDigest());

    ObjectNode operationObject = root.putObject("memberOperation");
    operationObject.put("operationId", operation.operationId().toString());
    operationObject.put("operationFence", operation.operationFence().toString());
    operationObject.put("coverageGeneration", operation.coverageGeneration().toString());

    ObjectNode incarnationObject = root.putObject("incarnation");
    incarnationObject.put("attestorBinding", incarnation.attestorBinding());
    incarnationObject.put("acceptedAttestationDigest", incarnation.acceptedAttestationDigest());
    CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence deployment =
        incarnation.targetDeploymentEvidence();
    ObjectNode deploymentObject = incarnationObject.putObject("targetDeploymentEvidence");
    deploymentObject.put("deploymentIdentity", deployment.deploymentIdentity());
    deploymentObject.put("deploymentGeneration", deployment.deploymentGeneration());
    ArrayNode nodeSet = deploymentObject.putArray("canonicalNodeSet");
    deployment.canonicalNodeSet().forEach(nodeSet::add);
    deploymentObject.put("redisBuildIdentity", deployment.redisBuildIdentity());
    deploymentObject.put("luaScriptRegistryVersion", deployment.luaScriptRegistryVersion());
    deploymentObject.put("luaScriptRegistryDigest", deployment.luaScriptRegistryDigest());
    deploymentObject.put("startupState", deployment.startupState());
    deploymentObject.put("bootGeneration", deployment.bootGeneration());
    deploymentObject.put("startupGeneration", deployment.startupGeneration());
    deploymentObject.put("storageGeneration", deployment.storageGeneration());
    deploymentObject.put("aofGeneration", deployment.aofGeneration());
    incarnationObject.put("coordOpsRoAclDigest", incarnation.coordOpsRoAclDigest());
    incarnationObject.put(
        "coordRedisConfigObservedDigest", incarnation.coordRedisConfigObservedDigest());
    return root;
  }

  private static ObjectNode nestedObject(ObjectNode parent, String name, Set<String> fields) {
    ObjectNode child = requireObject(parent.get(name), name);
    requireFields(child, fields, name);
    return child;
  }

  private static ObjectNode requireObject(JsonNode node, String name) {
    if (!(node instanceof ObjectNode object)) {
      throw invalid(name + " must be an object");
    }
    return object;
  }

  private static void requireFields(ObjectNode object, Set<String> required, String name) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(required)) {
      throw invalid(name + " must contain exactly its required fields");
    }
  }

  private static String text(ObjectNode object, String name) {
    JsonNode node = object.get(name);
    if (node == null || !node.isTextual()) {
      throw invalid(name + " must be a JSON string");
    }
    return node.textValue();
  }

  private static String nullableText(ObjectNode object, String name) {
    JsonNode node = object.get(name);
    if (node == null) {
      throw invalid(name + " is required");
    }
    if (node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw invalid(name + " must be a JSON string or explicit null");
    }
    return node.textValue();
  }

  private static UUID uuid(ObjectNode object, String name) {
    String value = text(object, name);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw invalid(name + " must use canonical lowercase UUID text");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      if (malformed instanceof CodecException) {
        throw malformed;
      }
      throw new IllegalArgumentException(name + " must use canonical UUID text", malformed);
    }
  }

  private static BigInteger positive(ObjectNode object, String name) {
    return decimal(text(object, name), name, true);
  }

  private static BigInteger nonNegative(ObjectNode object, String name) {
    return decimal(text(object, name), name, false);
  }

  private static BigInteger decimal(String value, String name, boolean positive) {
    Pattern grammar = positive ? POSITIVE_DECIMAL : NON_NEGATIVE_DECIMAL;
    if (value.length() > MAX_COUNTER_DIGITS || !grammar.matcher(value).matches()) {
      throw invalid(name + " must be a canonical unsigned decimal string within the local limit");
    }
    return new BigInteger(value);
  }

  private static void requireCounterLimit(BigInteger value, String name, boolean allowZero) {
    int digitCount = value.toString().length();
    if (digitCount > MAX_COUNTER_DIGITS) {
      throw invalid(name + " exceeds the local 256-digit resource limit");
    }
    if (value.signum() < 0 || (!allowZero && value.signum() == 0)) {
      throw invalid(name + " is outside its canonical unsigned decimal range");
    }
  }

  private static long positiveLong(ObjectNode object, String name) {
    BigInteger value = positive(object, name);
    try {
      return value.longValueExact();
    } catch (ArithmeticException tooLarge) {
      throw invalid(name + " exceeds its owner model's positive long range");
    }
  }

  private static byte[] base64(ObjectNode object, String name) {
    String text = text(object, name);
    return decodeCanonicalBase64(text, name);
  }

  private static byte[] nullableBase64(ObjectNode object, String name) {
    String text = nullableText(object, name);
    return text == null ? null : decodeCanonicalBase64(text, name);
  }

  private static byte[] decodeCanonicalBase64(String value, String name) {
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
        throw invalid(name + " must use canonical padded Base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      if (malformed instanceof CodecException) {
        throw malformed;
      }
      throw new IllegalArgumentException(name + " must use canonical padded Base64", malformed);
    }
  }

  private static List<String> stringArray(ObjectNode object, String name) {
    JsonNode node = object.get(name);
    if (!(node instanceof ArrayNode array)) {
      throw invalid(name + " must be an array of strings");
    }
    List<String> values = new ArrayList<>(array.size());
    for (JsonNode item : array) {
      if (!item.isTextual()) {
        throw invalid(name + " must contain only JSON strings");
      }
      values.add(item.textValue());
    }
    return List.copyOf(values);
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
    try {
      E parsed = Enum.valueOf(type, value);
      if (!parsed.name().equals(value)) {
        throw invalid(name + " must use its exact canonical name");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      if (malformed instanceof CodecException) {
        throw malformed;
      }
      throw new IllegalArgumentException(name + " is unsupported", malformed);
    }
  }

  private static void putNullableBase64(ObjectNode object, String name, byte[] value) {
    if (value == null) {
      object.putNull(name);
    } else {
      object.put(name, Base64.getEncoder().encodeToString(value));
    }
  }

  private static void putNullableText(ObjectNode object, String name, String value) {
    if (value == null) {
      object.putNull(name);
    } else {
      object.put(name, value);
    }
  }

  private static String decodeUtf8(byte[] encoded) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException("Issuer member JSON must be valid UTF-8", malformed);
    }
  }

  static String sha256(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new CodecException(message);
  }

  private static final class CodecException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private CodecException(String message) {
      super(message);
    }
  }
}
