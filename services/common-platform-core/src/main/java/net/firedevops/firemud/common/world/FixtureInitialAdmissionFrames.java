package net.firedevops.firemud.common.world;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed, bounded value decoding only. These bytes never authenticate an owner. */
final class FixtureInitialAdmissionFrames {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_BYTES = 65536;

  private FixtureInitialAdmissionFrames() {}

  static WorldCanonicalInstanceFixtureActivationRequest activation(byte[] bytes) {
    JsonNode root = read(bytes);
    JsonNode r = root.get("request");
    var result =
        new WorldCanonicalInstanceFixtureActivationRequest(
            UUID.fromString(text(r, "activationRequestId")),
            UUID.fromString(text(r, "canonicalTenantId")),
            UUID.fromString(text(r, "canonicalGameInstanceId")),
            text(r, "fixturePolicySealEvidenceDigest"),
            Long.parseLong(text(r, "expectedLifecycleEpoch")),
            Long.parseLong(text(r, "expectedRowVersion")));
    exact(bytes, result.canonicalBytes());
    return result;
  }

  static WorldFixtureInitialAdmissionHoldRequest hold(byte[] bytes) {
    JsonNode root = read(bytes);
    var result =
        new WorldFixtureInitialAdmissionHoldRequest(
            WorldCanonicalInitialAdmissionHold.Request.fromStored(
                binary(root, "selectorBytesBase64")),
            activation(binary(root, "activationRequestBytesBase64")));
    exact(bytes, result.canonicalBytes());
    return result;
  }

  static WorldFixtureInitialAdmissionTerminalProof terminal(byte[] bytes) {
    JsonNode root = read(bytes);
    JsonNode abort = root.get("positiveDurableAbort");
    if (abort == null || !abort.isBoolean()) throw invalid();
    var result =
        new WorldFixtureInitialAdmissionTerminalProof(
            hold(binary(root, "requestBytesBase64")),
            UUID.fromString(text(root, "holdId")),
            UUID.fromString(text(root, "holdFence")),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.valueOf(text(root, "outcome")),
            number(root, "pointerVersion"),
            number(root, "auditEventId"),
            abort.booleanValue(),
            root.has("terminalAt") && root.get("terminalAt").isNull()
                ? null
                : Instant.parse(text(root, "terminalAt")));
    exact(bytes, result.canonicalBytes());
    return result;
  }

  private static JsonNode read(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
    try {
      JsonNode root = JSON.readTree(bytes);
      if (root == null || !root.isObject()) throw invalid();
      return root;
    } catch (tools.jackson.core.JacksonException failure) {
      throw new IllegalArgumentException("Invalid fixture initial-admission frame", failure);
    }
  }

  private static String text(JsonNode root, String field) {
    if (root == null || !root.isObject()) throw invalid();
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual()) throw invalid();
    return value.asText();
  }

  private static byte[] binary(JsonNode root, String field) {
    return Base64.getDecoder().decode(text(root, field));
  }

  private static Long number(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null) throw invalid();
    return value.isNull() ? null : Long.valueOf(text(root, field));
  }

  private static void exact(byte[] original, byte[] canonical) {
    // Re-encoding rejects unknown fields, duplicate keys, noncanonical numbers/UUIDs,
    // schema/authority substitution, trailing bytes and malformed UTF-8.
    if (!Arrays.equals(original, canonical)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or noncanonical fixture initial-admission frame");
  }
}
