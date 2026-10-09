package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only Account Coordination view of one committed control-ui token record.
 *
 * <p>This class is deliberately not a Spring bean and contains no Redis mutation path. A caller
 * must supply Account's protected pinned connection provider; each read proves the live ACL user
 * and exact physical cleanup deadline on that same non-reconnecting connection. Registry presence
 * is only one input to Account's committed issuance/current-authority verification.
 */
public final class AccountControlUiRegistryReader {
  public static final String TOKEN_KEY_PREFIX = "session:auth:token:";
  public static final String REQUIRED_ACL_IDENTITY = "account_coord_app";
  public static final int MAX_RECORD_BYTES = 32 * 1024;

  private static final Pattern TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "registryVersion",
          "tokenHash",
          "kid",
          "signerGeneration",
          "issuer",
          "profile",
          "type",
          "audience",
          "accountId",
          "jti",
          "iat",
          "nbf",
          "exp",
          "tokenGeneration",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence",
          "operationId",
          "requestId",
          "requestDigest",
          "state",
          "authoritySourceVersions",
          "authEvidenceBundle",
          "originalSignerReceiptDigest");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final AccountCoordinationPinnedConnectionProvider connections;

  public AccountControlUiRegistryReader(AccountCoordinationPinnedConnectionProvider connections) {
    this.connections =
        Objects.requireNonNull(connections, "private Account Coordination is required");
  }

  /** Reads and validates one active registry value without holding an Account SQL transaction. */
  public byte[] readActive(String tokenHash) {
    outsideSql();
    if (tokenHash == null || !TOKEN_HASH.matcher(tokenHash).matches()) {
      throw denied();
    }
    try (var connection = connections.openPinnedConnection()) {
      if (connection == null
          || !connection.isOpen()
          || connection.getOptions() == null
          || connection.getOptions().isAutoReconnect()) {
        throw denied();
      }
      RedisCommands<byte[], byte[]> commands = connection.sync();
      if (!REQUIRED_ACL_IDENTITY.equals(commands.aclWhoami())) {
        throw denied();
      }
      byte[] key = (TOKEN_KEY_PREFIX + tokenHash).getBytes(StandardCharsets.US_ASCII);
      byte[] exact = commands.get(key);
      Map<String, Object> record = parseActiveRecord(exact, tokenHash);
      long expiresAtMillis = checkedExpiryMillis(record.get("exp"));
      Long physicalExpiry = commands.pexpiretime(key);
      if (physicalExpiry == null || physicalExpiry.longValue() != expiresAtMillis) {
        throw denied();
      }
      return exact.clone();
    } catch (RuntimeException unavailable) {
      throw denied();
    }
  }

  static Map<String, Object> parseActiveRecord(byte[] exact, String tokenHash) {
    if (exact == null || exact.length == 0 || exact.length > MAX_RECORD_BYTES) {
      throw denied();
    }
    try {
      String json =
          StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(exact)).toString();
      if (!Arrays.equals(exact, Rfc8785CanonicalJson.canonicalizeUtf8(json))) {
        throw denied();
      }
      Map<String, Object> fields = JSON.readValue(exact, OBJECT);
      if (fields == null
          || !fields.keySet().equals(FIELDS)
          || !Integer.valueOf(1).equals(number(fields.get("schemaVersion")))
          || !Integer.valueOf(2).equals(number(fields.get("registryVersion")))
          || !tokenHash.equals(text(fields.get("tokenHash")))
          || !"firemud-account-service".equals(text(fields.get("issuer")))
          || !"control-ui".equals(text(fields.get("profile")))
          || !"control-ui".equals(text(fields.get("type")))
          || !"control-ui".equals(text(fields.get("audience")))
          || !"active".equals(text(fields.get("state")))
          || !KID.matcher(text(fields.get("kid"))).matches()
          || !DECIMAL.matcher(text(fields.get("signerGeneration"))).matches()
          || !DIGEST.matcher(text(fields.get("requestDigest"))).matches()
          || !DIGEST.matcher(text(fields.get("originalSignerReceiptDigest"))).matches()
          || !(fields.get("authorityTuple") instanceof Map<?, ?>)
          || !(fields.get("membershipVersion") instanceof Map<?, ?>)
          || !(fields.get("authoritySourceVersions") instanceof Map<?, ?>)
          || !(fields.get("authEvidenceBundle") instanceof Map<?, ?>)) {
        throw denied();
      }
      requireUuid(fields.get("accountId"));
      requireUuid(fields.get("jti"));
      requireUuid(fields.get("operationId"));
      requireUuid(fields.get("requestId"));
      requirePositiveNumber(fields.get("iat"));
      requirePositiveNumber(fields.get("nbf"));
      requirePositiveNumber(fields.get("exp"));
      requirePositiveNumber(fields.get("tokenGeneration"));
      requirePositiveNumber(fields.get("issuanceFence"));
      return Map.copyOf(fields);
    } catch (IOException | RuntimeException malformed) {
      throw denied();
    }
  }

  static long checkedExpiryMillis(Object encodedExpiry) {
    try {
      long exp = requirePositiveNumber(encodedExpiry);
      // The committed control-ui profile uses the exact token expiry and zero cleanup margin.
      return Math.multiplyExact(exp, 1000L);
    } catch (ArithmeticException | IllegalArgumentException malformed) {
      throw denied();
    }
  }

  private static Number number(Object value) {
    return value instanceof Number number ? number : null;
  }

  private static String text(Object value) {
    return value instanceof String text ? text : "";
  }

  private static long requirePositiveNumber(Object value) {
    if (!(value instanceof Number number)) {
      throw denied();
    }
    String encoded = number.toString();
    if (!DECIMAL.matcher(encoded).matches()) {
      throw denied();
    }
    try {
      return Long.parseLong(encoded);
    } catch (NumberFormatException malformed) {
      throw denied();
    }
  }

  private static void requireUuid(Object value) {
    if (!(value instanceof String text)) {
      throw denied();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)
          || parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L) {
        throw denied();
      }
    } catch (IllegalArgumentException malformed) {
      throw denied();
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Account Coordination reads cannot retain Account SQL locks");
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException(
        "Exact Account active control-ui registry evidence unavailable");
  }
}
