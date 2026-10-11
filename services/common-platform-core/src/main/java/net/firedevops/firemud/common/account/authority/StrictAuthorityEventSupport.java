package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Shared strict parsing and canonical-evidence primitives for Account authority events. */
final class StrictAuthorityEventSupport {
  static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
  static final Pattern POSITIVE_DECIMAL_PATTERN = Pattern.compile("[1-9][0-9]*");
  static final Pattern NON_NEGATIVE_DECIMAL_PATTERN = Pattern.compile("(?:0|[1-9][0-9]*)");
  private static final Pattern SHA256_DIGEST_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private StrictAuthorityEventSupport() {}

  static ObjectMapper strictJsonMapper() {
    return JSON;
  }

  static boolean hasExactFields(ObjectNode object, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    return actual.equals(expected);
  }

  static void requireExactFields(
      ObjectNode object, Set<String> required, String path, InvalidArgumentFactory invalid) {
    if (!hasExactFields(object, required)) {
      Set<String> actual = new HashSet<>();
      object.fieldNames().forEachRemaining(actual::add);
      Set<String> missing = new HashSet<>(required);
      missing.removeAll(actual);
      Set<String> unexpected = new HashSet<>(actual);
      unexpected.removeAll(required);
      StringBuilder message = new StringBuilder("must contain exactly the declared fields");
      if (!missing.isEmpty()) {
        message.append("; missing ").append(missing);
      }
      if (!unexpected.isEmpty()) {
        message.append("; unexpected ").append(unexpected);
      }
      throw invalid.create(path, message.toString());
    }
  }

  static String requireExactText(
      ObjectNode object,
      String field,
      String expected,
      String path,
      InvalidArgumentFactory invalid) {
    String value = requireText(object, field, path, invalid);
    if (!expected.equals(value)) {
      throw invalid.create(path + "." + field, "must equal " + expected);
    }
    return value;
  }

  static String requireNonEmptyText(
      ObjectNode object, String field, String path, InvalidArgumentFactory invalid) {
    String value = requireText(object, field, path, invalid);
    if (value.isEmpty()) {
      throw invalid.create(path + "." + field, "must be nonempty");
    }
    return value;
  }

  static String requireText(
      ObjectNode object, String field, String path, InvalidArgumentFactory invalid) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid.create(path + "." + field, "must be a required string");
    }
    return value.textValue();
  }

  static String requireUuid(
      ObjectNode object, String field, String path, InvalidArgumentFactory invalid) {
    String value = requireText(object, field, path, invalid);
    if (!isCanonicalUuid(value)) {
      throw invalid.create(path + "." + field, "must be a canonical lowercase non-nil UUID");
    }
    return value;
  }

  static String requirePositiveDecimal(
      ObjectNode object, String field, String path, InvalidArgumentFactory invalid) {
    String value = requireText(object, field, path, invalid);
    if (!isPositiveCanonicalDecimal(value)) {
      throw invalid.create(path + "." + field, "must be a positive canonical decimal string");
    }
    return value;
  }

  static void validateAccountSecurityCutoff(
      JsonNode cutoffNode,
      String eventAuthorityGeneration,
      String eventStreamKey,
      String eventSequence,
      Set<String> cutoffFields,
      InvalidArgumentFactory invalid) {
    if (!(cutoffNode instanceof ObjectNode cutoff)) {
      throw invalid.create("accountSecurityCutoff", "must be a required JSON object");
    }
    requireExactFields(cutoff, cutoffFields, "accountSecurityCutoff", invalid);
    String cutoffGeneration =
        requirePositiveDecimal(
            cutoff, "accountAuthorityGeneration", "accountSecurityCutoff", invalid);
    String cutoffStream = requireText(cutoff, "outboxStreamKey", "accountSecurityCutoff", invalid);
    String cutoffSequence =
        requirePositiveDecimal(cutoff, "outboxSequence", "accountSecurityCutoff", invalid);
    if (!eventAuthorityGeneration.equals(cutoffGeneration)) {
      throw invalid.create(
          "accountSecurityCutoff.accountAuthorityGeneration",
          "must equal event.accountAuthorityGeneration");
    }
    if (!eventStreamKey.equals(cutoffStream)) {
      throw invalid.create(
          "accountSecurityCutoff.outboxStreamKey", "must equal event.outboxStreamKey");
    }
    if (!eventSequence.equals(cutoffSequence)) {
      throw invalid.create(
          "accountSecurityCutoff.outboxSequence", "must equal event.outboxSequence");
    }
  }

  static AccountSecurityCutoffFields accountSecurityCutoffFields(ObjectNode cutoff) {
    return new AccountSecurityCutoffFields(
        cutoff.path("accountAuthorityGeneration").textValue(),
        cutoff.path("outboxStreamKey").textValue(),
        cutoff.path("outboxSequence").textValue());
  }

  static String canonicalJson(ObjectNode object, String failureMessage) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(object.toString()), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException(failureMessage, exception);
    }
  }

  static String digest(ObjectNode object, String canonicalizationFailureMessage) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(object.toString());
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical);
      return "sha256:" + java.util.HexFormat.of().formatHex(hash);
    } catch (IOException exception) {
      throw new IllegalArgumentException(canonicalizationFailureMessage, exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static boolean isCanonicalUuid(String value) {
    return UUID_PATTERN.matcher(value).matches() && !NIL_UUID.equals(value);
  }

  static boolean isPositiveCanonicalDecimal(String value) {
    return POSITIVE_DECIMAL_PATTERN.matcher(value).matches();
  }

  static boolean isNonNegativeCanonicalDecimal(String value) {
    return NON_NEGATIVE_DECIMAL_PATTERN.matcher(value).matches();
  }

  static boolean isCanonicalSha256Digest(String value) {
    return SHA256_DIGEST_PATTERN.matcher(value).matches();
  }

  record AccountSecurityCutoffFields(
      String accountAuthorityGeneration, String outboxStreamKey, String outboxSequence) {}

  @FunctionalInterface
  interface InvalidArgumentFactory {
    IllegalArgumentException create(String path, String message);
  }
}
