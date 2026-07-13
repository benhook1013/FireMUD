package net.firedevops.firemud.gamesession.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Defines the deterministic structured envelope used for durable transcript byte accounting. */
final class ResumeTranscriptEntryCanonicalizer {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private ResumeTranscriptEntryCanonicalizer() {}

  static int byteSize(ResumeTranscriptEntry entry) {
    return canonicalJson(entry).getBytes(StandardCharsets.UTF_8).length;
  }

  static String canonicalJson(ResumeTranscriptEntry entry) {
    if (entry.getId() == null) {
      throw new IllegalArgumentException(
          "Transcript ordering token must be assigned before sizing");
    }
    return "{"
        + field("characterId", Long.toString(entry.getCharacterId()))
        + ","
        + field("gameInstanceId", Long.toString(entry.getGameInstanceId()))
        + ","
        + field("occurredAt", quote(TIMESTAMP_FORMAT.format(entry.getAppendedAt())))
        + ","
        + field("orderingToken", Long.toString(entry.getId()))
        + ","
        + field("outputKind", quote(entry.getOutputKind()))
        + ","
        + field("payload", canonicalPayload(entry))
        + ","
        + field("renderedText", quote(entry.getProtocolText()))
        + ","
        + field("tenantId", Long.toString(entry.getTenantId()))
        + "}";
  }

  private static String canonicalPayload(ResumeTranscriptEntry entry) {
    return "{"
        + field("briefRenderPolicy", quote(entry.getBriefRenderPolicy()))
        + ","
        + field("payload", canonicalJsonValue(entry.getPayloadJson()))
        + ","
        + field("payloadType", quote(entry.getPayloadType()))
        + ","
        + field("replayPolicy", quote(entry.getReplayPolicy()))
        + "}";
  }

  private static String canonicalJsonValue(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return "null";
    }
    try {
      return canonicalizeNode(OBJECT_MAPPER.readTree(rawJson));
    } catch (JacksonException ex) {
      return quote(rawJson);
    }
  }

  private static String canonicalizeNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      return node.properties().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(entry -> quote(entry.getKey()) + ":" + canonicalizeNode(entry.getValue()))
          .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (node.isArray()) {
      return node.valueStream()
          .map(ResumeTranscriptEntryCanonicalizer::canonicalizeNode)
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
      return OBJECT_MAPPER.writeValueAsString(Normalizer.normalize(value, Normalizer.Form.NFC));
    } catch (JacksonException ex) {
      throw new IllegalStateException("Unable to serialize canonical transcript value", ex);
    }
  }
}
