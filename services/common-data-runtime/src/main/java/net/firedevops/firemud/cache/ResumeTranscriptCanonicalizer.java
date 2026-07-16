package net.firedevops.firemud.cache;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Defines the deterministic envelope used by every reconnect transcript storage tier. */
public final class ResumeTranscriptCanonicalizer {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ObjectMapper STRICT_OBJECT_MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private ResumeTranscriptCanonicalizer() {}

  public static int byteSize(
      long tenantId,
      long gameInstanceId,
      long characterId,
      long orderingToken,
      long appendedAtMs,
      String outputKind,
      String replayPolicy,
      String briefRenderPolicy,
      String payloadType,
      String payloadJson,
      String protocolText) {
    return canonicalJson(
            tenantId,
            gameInstanceId,
            characterId,
            orderingToken,
            appendedAtMs,
            outputKind,
            replayPolicy,
            briefRenderPolicy,
            payloadType,
            payloadJson,
            protocolText)
        .getBytes(StandardCharsets.UTF_8)
        .length;
  }

  public static String canonicalJson(
      long tenantId,
      long gameInstanceId,
      long characterId,
      long orderingToken,
      long appendedAtMs,
      String outputKind,
      String replayPolicy,
      String briefRenderPolicy,
      String payloadType,
      String payloadJson,
      String protocolText) {
    return "{"
        + field("characterId", Long.toString(characterId))
        + ","
        + field("gameInstanceId", Long.toString(gameInstanceId))
        + ","
        + field("occurredAt", quote(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(appendedAtMs))))
        + ","
        + field("orderingToken", Long.toString(orderingToken))
        + ","
        + field("outputKind", quote(outputKind))
        + ","
        + field(
            "payload", canonicalPayload(briefRenderPolicy, payloadJson, payloadType, replayPolicy))
        + ","
        + field("renderedText", quote(protocolText))
        + ","
        + field("tenantId", Long.toString(tenantId))
        + "}";
  }

  private static String canonicalPayload(
      String briefRenderPolicy, String payloadJson, String payloadType, String replayPolicy) {
    return "{"
        + field("briefRenderPolicy", quote(briefRenderPolicy))
        + ","
        + field("payload", canonicalJsonValue(payloadJson))
        + ","
        + field("payloadType", quote(payloadType))
        + ","
        + field("replayPolicy", quote(replayPolicy))
        + "}";
  }

  private static String canonicalJsonValue(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return "null";
    }
    try {
      return canonicalizeNode(STRICT_OBJECT_MAPPER.readTree(rawJson));
    } catch (JacksonException strictFailure) {
      try {
        OBJECT_MAPPER.readTree(rawJson);
      } catch (JacksonException legacyInput) {
        return quote(rawJson);
      }
      throw new IllegalArgumentException("JSON payload contains duplicate keys", strictFailure);
    }
  }

  private static String canonicalizeNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries =
          node.properties().stream()
              .map(entry -> Map.entry(normalize(entry.getKey()), entry.getValue()))
              .sorted(Map.Entry.comparingByKey())
              .toList();
      StringBuilder result = new StringBuilder("{");
      for (int index = 0; index < entries.size(); index++) {
        if (index > 0) {
          result.append(',');
        }
        Map.Entry<String, JsonNode> entry = entries.get(index);
        if (index > 0 && entry.getKey().equals(entries.get(index - 1).getKey())) {
          throw new IllegalArgumentException(
              "JSON object contains duplicate keys after NFC normalization: " + entry.getKey());
        }
        result.append(quote(entry.getKey())).append(':').append(canonicalizeNode(entry.getValue()));
      }
      return result.append('}').toString();
    }
    if (node.isArray()) {
      return node.valueStream()
          .map(ResumeTranscriptCanonicalizer::canonicalizeNode)
          .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    if (node.isTextual()) {
      return quote(node.asString());
    }
    if (node.isFloatingPointNumber()) {
      BigDecimal normalized = node.decimalValue().stripTrailingZeros();
      return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }
    return node.toString();
  }

  private static String field(String name, String value) {
    return quote(name) + ":" + value;
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(normalize(value));
    } catch (JacksonException ex) {
      throw new IllegalStateException("Unable to serialize canonical transcript value", ex);
    }
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC);
  }
}
