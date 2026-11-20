package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed World lifecycle observation for a canonical run-owned fixture instance.
 *
 * <p>The fixture has no Human capture, graph, start-location or release attestation. It is
 * identified by the same canonical lifecycle read request, carrying the fixture descriptor digests
 * and, in place of the release-attestation digest, the fixture policy-seal evidence digest. This is one
 * owner snapshot, not admission authority.
 */
public record WorldRunOwnedFixtureLifecycleEvidence(
    WorldCanonicalInstanceLifecycleEvidence.Request request,
    String fixtureProvenanceDigest,
    String preparationInputDigest,
    String lifecycleStatus,
    long lifecycleEpoch,
    long rowVersion) {
  public static final String SCHEMA = "world-run-owned-fixture-lifecycle-evidence/v1";
  private static final int MAX_CANONICAL_BYTES = 64 * 1024;
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> LIFECYCLE_STATUSES =
      Set.of("PREPARING", "ACTIVE", "FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED");
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "requestBytesBase64",
          "fixtureProvenanceDigest",
          "preparationInputDigest",
          "lifecycleStatus",
          "lifecycleEpoch",
          "rowVersion");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  public WorldRunOwnedFixtureLifecycleEvidence {
    Objects.requireNonNull(request, "request");
    requireDigest(fixtureProvenanceDigest, "fixtureProvenanceDigest");
    requireDigest(preparationInputDigest, "preparationInputDigest");
    if (!fixtureProvenanceDigest.equals(request.expectedReleaseAttestationDigest())) {
      throw new IllegalArgumentException(
          "Fixture lifecycle request must carry the fixture provenance digest");
    }
    if (lifecycleStatus == null || !LIFECYCLE_STATUSES.contains(lifecycleStatus)) {
      throw new IllegalArgumentException("Unsupported World lifecycle status");
    }
    if (lifecycleEpoch <= 0 || rowVersion < 0) {
      throw new IllegalArgumentException("World fixture lifecycle epoch or row version is invalid");
    }
  }

  public byte[] canonicalBytes() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("requestBytesBase64", Base64.getEncoder().encodeToString(request.canonicalBytes()));
    value.put("fixtureProvenanceDigest", fixtureProvenanceDigest);
    value.put("preparationInputDigest", preparationInputDigest);
    value.put("lifecycleStatus", lifecycleStatus);
    value.put("lifecycleEpoch", Long.toString(lifecycleEpoch));
    value.put("rowVersion", Long.toString(rowVersion));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "World fixture lifecycle evidence cannot be encoded", impossible);
    }
  }

  /** Decodes only complete, canonical, bounded evidence; this does not authenticate its sender. */
  public static WorldRunOwnedFixtureLifecycleEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0 || stored.length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("World fixture lifecycle evidence exceeds its limit");
    }
    try {
      JsonNode root = JSON.readTree(strictUtf8(stored));
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("World fixture lifecycle evidence must be an object");
      }
      Set<String> actual = new HashSet<>();
      root.propertyNames().forEach(actual::add);
      if (!actual.equals(FIELDS) || !SCHEMA.equals(text(root, "schema"))) {
        throw new IllegalArgumentException(
            "World fixture lifecycle evidence has an unsupported shape");
      }
      String encoded = text(root, "requestBytesBase64");
      byte[] requestBytes = Base64.getDecoder().decode(encoded);
      if (!Base64.getEncoder().encodeToString(requestBytes).equals(encoded)) {
        throw new IllegalArgumentException("requestBytesBase64 must use canonical base64");
      }
      WorldRunOwnedFixtureLifecycleEvidence evidence =
          new WorldRunOwnedFixtureLifecycleEvidence(
              WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(requestBytes),
              text(root, "fixtureProvenanceDigest"),
              text(root, "preparationInputDigest"),
              text(root, "lifecycleStatus"),
              parseLong(text(root, "lifecycleEpoch")),
              parseLong(text(root, "rowVersion")));
      if (!Arrays.equals(stored, evidence.canonicalBytes())) {
        throw new IllegalArgumentException("World fixture lifecycle evidence is not canonical");
      }
      return evidence;
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("World fixture lifecycle evidence is invalid", invalid);
    }
  }

  private static long parseLong(String value) {
    try {
      long parsed = Long.parseLong(value);
      if (!Long.toString(parsed).equals(value)) throw new NumberFormatException(value);
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("World fixture lifecycle number is invalid", invalid);
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !PREFIXED_SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a prefixed SHA-256 digest");
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw new IllegalArgumentException(
          "World fixture lifecycle evidence is not strict UTF-8", invalid);
    }
  }
}
