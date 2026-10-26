package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Closed, bounded RFC 8785 codec for the separate issuer binding-local acknowledgement. */
final class CanonicalGameplayIssuerBindingLocalAcknowledgementCodec {
  static final int MAX_ACKNOWLEDGEMENT_BYTES = 1024 * 1024;
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,255}");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]{0,255})");
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(8)
                  .maxStringLength(MAX_ACKNOWLEDGEMENT_BYTES)
                  .maxNumberLength(8)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final ObjectMapper JSON =
      new ObjectMapper(JSON_FACTORY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schemaVersion",
          "scope",
          "issuerBindingLocalAcknowledgementGeneration",
          "globalIssuerCoverageGeneration",
          "operation",
          "binding",
          "reservation",
          "anchor",
          "memberOperation",
          "currentSource",
          "physicalReadback",
          "capacity",
          "sqlCurrentnessDigest");
  private static final Set<String> OPERATION_FIELDS =
      Set.of("issuerId", "operationId", "requestId", "transitionId", "candidateAccountIndexFence");
  private static final Set<String> BINDING_FIELDS =
      Set.of("bindingRef", "bindingGeneration", "memberValueBytes", "memberValueDigest");
  private static final Set<String> RESERVATION_FIELDS =
      Set.of(
          "reservationId",
          "transitionId",
          "reservationFence",
          "partitionId",
          "lifecycle",
          "issuerCoverageOperationId",
          "issuerCoverageOperationFence",
          "coverageFence",
          "inventorySnapshotRevision",
          "inventoryRevision");
  private static final Set<String> ANCHOR_FIELDS =
      Set.of(
          "bootstrapOperationId",
          "issuerCoverageOperationId",
          "operationFence",
          "coverageFence",
          "inventorySnapshotRevision",
          "globalCoverageGeneration",
          "controlEpoch",
          "layoutVersion",
          "partitionCount",
          "partitionCapacity",
          "mappingIdentity",
          "layoutDescriptorBytes",
          "layoutDescriptorDigest",
          "exactBytes",
          "exactDigest");
  private static final Set<String> MEMBER_OPERATION_FIELDS =
      Set.of("operationId", "operationFence", "generation");
  private static final Set<String> CURRENT_SOURCE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "checkpointBytes",
          "checkpointDigest",
          "eventBytes",
          "eventDigest",
          "incarnationBytes",
          "incarnationDigest");
  private static final Set<String> PHYSICAL_READBACK_FIELDS =
      Set.of(
          "bindingValueBytes",
          "bindingValueDigest",
          "capacityValueBytes",
          "capacityValueDigest",
          "partitionFieldSetDigest",
          "scriptOutcome",
          "fieldCount",
          "reservationCount",
          "bindingCount",
          "countedCapacity");
  private static final Set<String> CAPACITY_FIELDS =
      Set.of(
          "partitionId",
          "partitionCapacity",
          "baselineCountedCapacity",
          "baselineReservationCount",
          "baselineBindingCount",
          "sqlPartitionCapacity",
          "sqlAggregateCapacity",
          "issuerAggregateBound");

  private CanonicalGameplayIssuerBindingLocalAcknowledgementCodec() {}

  static byte[] encode(CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    try {
      ObjectNode root = JSON.createObjectNode();
      root.put("schemaVersion", CanonicalGameplayIssuerBindingLocalAcknowledgement.SCHEMA_VERSION);
      root.put("scope", CanonicalGameplayIssuerBindingLocalAcknowledgement.SCOPE);
      root.put(
          "issuerBindingLocalAcknowledgementGeneration",
          evidence.issuerBindingLocalAcknowledgementGeneration().toString());
      root.put(
          "globalIssuerCoverageGeneration",
          CanonicalGameplayIssuerBindingLocalAcknowledgement.GLOBAL_COVERAGE_GENERATION);

      var operation = root.putObject("operation");
      operation.put("issuerId", evidence.issuerId());
      operation.put("operationId", evidence.operationId().toString());
      operation.put("requestId", evidence.requestId().toString());
      operation.put("transitionId", evidence.transitionId().toString());
      operation.put("candidateAccountIndexFence", evidence.candidateAccountIndexFence().toString());

      var binding = root.putObject("binding");
      binding.put("bindingRef", encodeBase64(evidence.bindingRef()));
      binding.put("bindingGeneration", evidence.bindingGeneration().toString());
      binding.put("memberValueBytes", encodeBase64(evidence.memberValueBytes()));
      binding.put("memberValueDigest", evidence.memberValueDigest());

      var reservation = root.putObject("reservation");
      var r = evidence.reservation();
      reservation.put("reservationId", r.reservationId().toString());
      reservation.put("transitionId", r.transitionId().toString());
      reservation.put("reservationFence", r.reservationFence().toString());
      reservation.put("partitionId", r.partitionId().toString());
      reservation.put("lifecycle", r.lifecycle());
      reservation.put("issuerCoverageOperationId", r.issuerCoverageOperationId().toString());
      reservation.put("issuerCoverageOperationFence", r.issuerCoverageOperationFence().toString());
      reservation.put("coverageFence", r.coverageFence().toString());
      reservation.put("inventorySnapshotRevision", r.inventorySnapshotRevision().toString());
      reservation.put("inventoryRevision", r.inventoryRevision().toString());

      var anchor = root.putObject("anchor");
      var a = evidence.anchor();
      anchor.put("bootstrapOperationId", a.bootstrapOperationId().toString());
      anchor.put("issuerCoverageOperationId", a.issuerCoverageOperationId().toString());
      anchor.put("operationFence", a.operationFence().toString());
      anchor.put("coverageFence", a.coverageFence().toString());
      anchor.put("inventorySnapshotRevision", a.inventorySnapshotRevision().toString());
      anchor.put("globalCoverageGeneration", a.globalCoverageGeneration().toString());
      anchor.put("controlEpoch", a.controlEpoch().toString());
      anchor.put("layoutVersion", a.layoutVersion().toString());
      anchor.put("partitionCount", a.partitionCount().toString());
      anchor.put("partitionCapacity", a.partitionCapacity().toString());
      anchor.put("mappingIdentity", a.mappingIdentity());
      anchor.put("layoutDescriptorBytes", encodeBase64(a.layoutDescriptorBytes()));
      anchor.put("layoutDescriptorDigest", a.layoutDescriptorDigest());
      anchor.put("exactBytes", encodeBase64(a.exactBytes()));
      anchor.put("exactDigest", a.exactDigest());

      var memberOperation = root.putObject("memberOperation");
      memberOperation.put("operationId", evidence.memberOperation().operationId().toString());
      memberOperation.put("operationFence", evidence.memberOperation().operationFence().toString());
      memberOperation.put("generation", evidence.memberOperation().generation().toString());

      var source = root.putObject("currentSource");
      var s = evidence.currentSource();
      source.put("issuerAuthGeneration", s.issuerAuthGeneration().toString());
      source.put("checkpointBytes", encodeBase64(s.checkpointBytes()));
      source.put("checkpointDigest", s.checkpointDigest());
      source.put("eventBytes", s.eventBytes() == null ? null : encodeBase64(s.eventBytes()));
      source.put("eventDigest", s.eventDigest());
      source.put("incarnationBytes", encodeBase64(s.incarnationBytes()));
      source.put("incarnationDigest", s.incarnationDigest());

      var physical = root.putObject("physicalReadback");
      var p = evidence.physicalReadback();
      physical.put("bindingValueBytes", encodeBase64(p.bindingValueBytes()));
      physical.put("bindingValueDigest", p.bindingValueDigest());
      physical.put("capacityValueBytes", encodeBase64(p.capacityValueBytes()));
      physical.put("capacityValueDigest", p.capacityValueDigest());
      physical.put("partitionFieldSetDigest", p.partitionFieldSetDigest());
      physical.put("scriptOutcome", p.scriptOutcome());
      physical.put("fieldCount", p.fieldCount());
      physical.put("reservationCount", p.reservationCount());
      physical.put("bindingCount", p.bindingCount());
      physical.put("countedCapacity", p.countedCapacity());

      var capacity = root.putObject("capacity");
      var c = evidence.capacity();
      capacity.put("partitionId", c.partitionId().toString());
      capacity.put("partitionCapacity", c.partitionCapacity().toString());
      capacity.put("baselineCountedCapacity", c.baselineCountedCapacity().toString());
      capacity.put("baselineReservationCount", c.baselineReservationCount().toString());
      capacity.put("baselineBindingCount", c.baselineBindingCount().toString());
      capacity.put("sqlPartitionCapacity", c.sqlPartitionCapacity().toString());
      capacity.put("sqlAggregateCapacity", c.sqlAggregateCapacity().toString());
      capacity.put("issuerAggregateBound", c.issuerAggregateBound().toString());
      root.put("sqlCurrentnessDigest", evidence.sqlCurrentnessDigest());

      byte[] result = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (result.length == 0 || result.length > MAX_ACKNOWLEDGEMENT_BYTES) {
        throw invalid("Issuer binding-local acknowledgement exceeds its 1 MiB bound");
      }
      return result;
    } catch (IOException failure) {
      throw new IllegalArgumentException(
          "Could not encode issuer binding-local acknowledgement", failure);
    }
  }

  static CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence decode(byte[] encoded) {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_ACKNOWLEDGEMENT_BYTES) {
      throw invalid("Issuer binding-local acknowledgement is empty or exceeds its bound");
    }
    JsonNode root = parse(encoded);
    requireFields(root, ROOT_FIELDS, "root");
    if (intValue(root, "schemaVersion")
            != CanonicalGameplayIssuerBindingLocalAcknowledgement.SCHEMA_VERSION
        || !text(root, "scope").equals(CanonicalGameplayIssuerBindingLocalAcknowledgement.SCOPE)
        || !text(root, "globalIssuerCoverageGeneration")
            .equals(
                CanonicalGameplayIssuerBindingLocalAcknowledgement.GLOBAL_COVERAGE_GENERATION)) {
      throw invalid("Issuer binding-local acknowledgement version or scope is unsupported");
    }

    JsonNode operation = object(root, "operation", OPERATION_FIELDS);
    JsonNode binding = object(root, "binding", BINDING_FIELDS);
    JsonNode reservation = object(root, "reservation", RESERVATION_FIELDS);
    JsonNode anchor = object(root, "anchor", ANCHOR_FIELDS);
    JsonNode memberOperation = object(root, "memberOperation", MEMBER_OPERATION_FIELDS);
    JsonNode currentSource = object(root, "currentSource", CURRENT_SOURCE_FIELDS);
    JsonNode physicalReadback = object(root, "physicalReadback", PHYSICAL_READBACK_FIELDS);
    JsonNode capacity = object(root, "capacity", CAPACITY_FIELDS);

    CanonicalGameplayIssuerBindingLocalAcknowledgement.Reservation reservationValue =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Reservation(
            uuid(reservation, "reservationId"),
            uuid(reservation, "transitionId"),
            uuid(reservation, "reservationFence"),
            nonNegative(reservation, "partitionId"),
            text(reservation, "lifecycle"),
            uuid(reservation, "issuerCoverageOperationId"),
            positive(reservation, "issuerCoverageOperationFence"),
            positive(reservation, "coverageFence"),
            positive(reservation, "inventorySnapshotRevision"),
            positive(reservation, "inventoryRevision"));
    CanonicalGameplayIssuerBindingLocalAcknowledgement.Anchor anchorValue =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Anchor(
            uuid(anchor, "bootstrapOperationId"),
            uuid(anchor, "issuerCoverageOperationId"),
            positive(anchor, "operationFence"),
            positive(anchor, "coverageFence"),
            positive(anchor, "inventorySnapshotRevision"),
            positive(anchor, "globalCoverageGeneration"),
            positive(anchor, "controlEpoch"),
            positive(anchor, "layoutVersion"),
            positive(anchor, "partitionCount"),
            positive(anchor, "partitionCapacity"),
            text(anchor, "mappingIdentity"),
            base64(anchor, "layoutDescriptorBytes"),
            digest(anchor, "layoutDescriptorDigest"),
            base64(anchor, "exactBytes"),
            digest(anchor, "exactDigest"));
    CanonicalGameplayIssuerBindingLocalAcknowledgement.MemberOperation memberOperationValue =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.MemberOperation(
            uuid(memberOperation, "operationId"),
            positive(memberOperation, "operationFence"),
            positive(memberOperation, "generation"));
    CanonicalGameplayIssuerBindingLocalAcknowledgement.CurrentSource currentSourceValue =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.CurrentSource(
            positive(currentSource, "issuerAuthGeneration"),
            base64(currentSource, "checkpointBytes"),
            digest(currentSource, "checkpointDigest"),
            nullableBase64(currentSource, "eventBytes"),
            nullableDigest(currentSource, "eventDigest"),
            base64(currentSource, "incarnationBytes"),
            digest(currentSource, "incarnationDigest"));
    CanonicalGameplayIssuerBindingLocalAcknowledgement.PhysicalReadback physicalReadbackValue =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.PhysicalReadback(
            base64(physicalReadback, "bindingValueBytes"),
            digest(physicalReadback, "bindingValueDigest"),
            base64(physicalReadback, "capacityValueBytes"),
            digest(physicalReadback, "capacityValueDigest"),
            digest(physicalReadback, "partitionFieldSetDigest"),
            intValue(physicalReadback, "scriptOutcome"),
            nonNegativeInt(physicalReadback, "fieldCount"),
            nonNegativeInt(physicalReadback, "reservationCount"),
            nonNegativeInt(physicalReadback, "bindingCount"),
            nonNegativeInt(physicalReadback, "countedCapacity"));
    CanonicalGameplayIssuerBindingLocalAcknowledgement.Capacity capacityValue =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Capacity(
            nonNegative(capacity, "partitionId"),
            positive(capacity, "partitionCapacity"),
            nonNegative(capacity, "baselineCountedCapacity"),
            nonNegative(capacity, "baselineReservationCount"),
            nonNegative(capacity, "baselineBindingCount"),
            positive(capacity, "sqlPartitionCapacity"),
            nonNegative(capacity, "sqlAggregateCapacity"),
            positive(capacity, "issuerAggregateBound"));

    var evidence =
        new CanonicalGameplayIssuerBindingLocalAcknowledgement.Evidence(
            uuid(operation, "operationId"),
            positive(root, "issuerBindingLocalAcknowledgementGeneration"),
            uuid(operation, "requestId"),
            uuid(operation, "transitionId"),
            uuid(operation, "candidateAccountIndexFence"),
            text(operation, "issuerId"),
            base64(binding, "bindingRef"),
            positive(binding, "bindingGeneration"),
            base64(binding, "memberValueBytes"),
            digest(binding, "memberValueDigest"),
            reservationValue,
            anchorValue,
            memberOperationValue,
            currentSourceValue,
            physicalReadbackValue,
            capacityValue,
            digest(root, "sqlCurrentnessDigest"));
    if (!Arrays.equals(encoded, encode(evidence))) {
      throw invalid("Issuer binding-local acknowledgement is not canonical");
    }
    return evidence;
  }

  private static JsonNode parse(byte[] encoded) {
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(encoded))
              .toString();
      JsonNode root = JSON.readTree(json);
      if (root == null || !root.isObject()) throw invalid("Acknowledgement root must be an object");
      return root;
    } catch (IOException malformed) {
      throw new IllegalArgumentException(
          "Issuer binding-local acknowledgement is malformed", malformed);
    }
  }

  private static JsonNode object(JsonNode parent, String name, Set<String> fields) {
    JsonNode value = parent.get(name);
    requireFields(value, fields, name);
    return value;
  }

  private static void requireFields(JsonNode value, Set<String> expected, String name) {
    if (value == null || !value.isObject()) throw invalid(name + " must be an object");
    Set<String> actual = new java.util.HashSet<>();
    Iterator<String> names = value.fieldNames();
    while (names.hasNext()) actual.add(names.next());
    if (!actual.equals(expected)) throw invalid(name + " contains missing or unknown fields");
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
      throw invalid(field + " must be nonempty text");
    }
    return value.textValue();
  }

  private static int intValue(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid(field + " must be an exact integer");
    }
    return value.intValue();
  }

  private static int nonNegativeInt(JsonNode object, String field) {
    int value = intValue(object, field);
    if (value < 0) throw invalid(field + " must not be negative");
    return value;
  }

  private static BigInteger positive(JsonNode object, String field) {
    return decimal(text(object, field), field, true);
  }

  private static BigInteger nonNegative(JsonNode object, String field) {
    return decimal(text(object, field), field, false);
  }

  private static BigInteger decimal(String value, String name, boolean positive) {
    Pattern pattern = positive ? POSITIVE_DECIMAL : NON_NEGATIVE_DECIMAL;
    if (!pattern.matcher(value).matches()) throw invalid(name + " is not canonical decimal");
    return new BigInteger(value);
  }

  private static UUID uuid(JsonNode object, String field) {
    String value = text(object, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw invalid(field + " is not a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is not a canonical non-nil UUID", malformed);
    }
  }

  private static String digest(JsonNode object, String field) {
    String value = text(object, field);
    if (!SHA256.matcher(value).matches()) throw invalid(field + " is not canonical SHA-256");
    return value;
  }

  private static String nullableDigest(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual() || !SHA256.matcher(value.textValue()).matches()) {
      throw invalid(field + " is not canonical SHA-256 or null");
    }
    return value.textValue();
  }

  private static byte[] base64(JsonNode object, String field) {
    String value = text(object, field);
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      if (decoded.length == 0 || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
        throw invalid(field + " is not canonical padded Base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is not canonical padded Base64", malformed);
    }
  }

  private static byte[] nullableBase64(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw invalid(field + " must be canonical Base64 or null");
    ObjectNode wrapper = JSON.createObjectNode();
    wrapper.set(field, value);
    return base64(wrapper, field);
  }

  private static String encodeBase64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
