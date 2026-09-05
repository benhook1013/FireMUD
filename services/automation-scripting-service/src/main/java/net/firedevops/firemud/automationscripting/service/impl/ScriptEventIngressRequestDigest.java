package net.firedevops.firemud.automationscripting.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Computes the immutable event-scope idempotency digest defined by ADR 0172. */
final class ScriptEventIngressRequestDigest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ScriptEventIngressRequestDigest() {}

  static String compute(
      TriggerScriptEventRequest request, String schemaVersion, String sourceService) {
    StringBuilder preimage = new StringBuilder("script-event-ingress-v1|");
    append(preimage, "tenantId", request.getTenantId());
    append(preimage, "gameInstanceId", request.getGameInstanceId());
    append(preimage, "regionId", request.getRegionId());
    append(preimage, "regionEpoch", Long.toString(request.getRegionEpoch()));
    append(preimage, "entityId", request.getEntityId());
    append(preimage, "scriptId", request.getScriptId());
    append(preimage, "pluginId", request.getPluginId());
    append(preimage, "pluginVersionId", request.getPluginVersionId());
    append(preimage, "eventType", request.getEventType());
    append(preimage, "eventSchemaVersion", schemaVersion);
    append(preimage, "scriptPatchVersion", request.getScriptPatchVersion());
    append(preimage, "scriptPinEpoch", Long.toString(request.getScriptPinEpoch()));
    append(preimage, "scriptPinControlPlaneRequestId", request.getScriptPinControlPlaneRequestId());
    append(preimage, "scriptEventId", request.getScriptEventId());
    append(preimage, "triggerMode", request.getTriggerMode().name());
    append(preimage, "dueTickId", Long.toString(request.getDueTickId()));
    append(preimage, "dueAtMs", Long.toString(request.getDueAtMs()));
    append(preimage, "dryRun", Boolean.toString(request.getIsDryRun()));
    append(preimage, "readSnapshotToken", request.getReadSnapshotToken());
    append(preimage, "playableStateScope", request.getPlayableStateScope().name());
    append(preimage, "sourceService", sourceService);
    // Routing selectors are retained as provenance, but intentionally omitted from dedupe
    // identity: a pointer/slug observation changing must not recreate one producer event.
    append(preimage, "payload", canonicalJson(request.getPayloadJson()));
    return sha256(preimage.toString());
  }

  private static void append(StringBuilder preimage, String name, String value) {
    String normalized = value == null ? "" : value;
    byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
    preimage
        .append(name)
        .append('=')
        .append(bytes.length)
        .append(':')
        .append(normalized)
        .append('|');
  }

  private static String canonicalJson(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return "null";
    }
    try {
      return canonicalize(OBJECT_MAPPER.readTree(rawJson));
    } catch (JacksonException ex) {
      // Validation will reject malformed built-in payloads. Preserve malformed/custom input in
      // the digest so a retry cannot alias a different rejected request.
      return quote(rawJson);
    }
  }

  private static String canonicalize(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
      node.properties().forEach(entry -> entries.add(Map.entry(entry.getKey(), entry.getValue())));
      entries.sort(Map.Entry.comparingByKey());
      StringBuilder result = new StringBuilder("{");
      for (int index = 0; index < entries.size(); index++) {
        if (index > 0) {
          result.append(',');
        }
        Map.Entry<String, JsonNode> entry = entries.get(index);
        if (index > 0 && entry.getKey().equals(entries.get(index - 1).getKey())) {
          throw new IllegalArgumentException("JSON object contains duplicate keys");
        }
        result.append(quote(entry.getKey())).append(':').append(canonicalize(entry.getValue()));
      }
      return result.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder result = new StringBuilder("[");
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) {
          result.append(',');
        }
        result.append(canonicalize(node.get(index)));
      }
      return result.append(']').toString();
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

  private static String quote(String value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JacksonException ex) {
      throw new IllegalStateException("failed to canonicalize ingress digest value", ex);
    }
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
