package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable, integrity-only World terminal receipt for one original StartSession activation.
 *
 * <p>The receipt preserves the complete first Account activation-protection carrier, the full
 * original World activation result (including its original transport read correlations), and
 * World's positive durable activation fence. Decoding validates bytes and cross-binding only; it
 * does not authenticate Account or World, establish currentness, or itself prove that a database
 * committed the referenced fence. An authenticated owner read must supply that provenance before
 * Account may settle source protection.
 */
public final class WorldOriginalStartSessionActivationTerminalEvidence {
  public static final String SCHEMA =
      "world-original-start-session-activation-terminal-evidence/v1";

  /**
   * Maximum World result is derived from its three independently bounded Base64 children: 144 MiB
   * normalized request, 64 MiB PREPARING evidence, and 64 MiB lifecycle evidence. Their 272 MiB
   * total expands to at most 362 2/3 MiB inside the result, so a 384 MiB result cap leaves room for
   * its JSON envelope. The outer receipt then Base64-encodes at most 200 MiB Account evidence plus
   * at most 362 2/3 MiB result, or about 750 2/9 MiB plus fixed JSON overhead; 768 MiB is the
   * enclosing canonical limit.
   */
  public static final int MAX_CANONICAL_BYTES = 768 * 1024 * 1024;

  public static final int MAX_ACTIVATION_RESULT_BYTES = 384 * 1024 * 1024;
  public static final int MAX_LIFECYCLE_EVIDENCE_BYTES = 64 * 1024 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "activationRequestId",
          "accountProtectionId",
          "accountProtectionFence",
          "worldActivationFence",
          "accountProtectionEvidenceBytesBase64",
          "activationResultBytesBase64",
          "activationResultSha256");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final AccountStartSessionActivationProtectionEvidence accountProtectionEvidence;
  private final WorldCanonicalInstanceActivation.Result activationResult;
  private final long worldActivationFence;
  private final byte[] canonicalBytes;
  private final String terminalDigest;

  private WorldOriginalStartSessionActivationTerminalEvidence(
      AccountStartSessionActivationProtectionEvidence accountProtectionEvidence,
      WorldCanonicalInstanceActivation.Result activationResult,
      long worldActivationFence,
      byte[] canonicalBytes) {
    this.accountProtectionEvidence = accountProtectionEvidence;
    this.activationResult = activationResult;
    this.worldActivationFence = worldActivationFence;
    this.canonicalBytes = canonicalBytes.clone();
    this.terminalDigest = sha256(canonicalBytes);
  }

  /** Creates a full receipt from the exact original protection and World terminal values. */
  public static WorldOriginalStartSessionActivationTerminalEvidence create(
      AccountStartSessionActivationProtectionEvidence accountProtectionEvidence,
      WorldCanonicalInstanceActivation.Result activationResult,
      long worldActivationFence) {
    Objects.requireNonNull(accountProtectionEvidence, "accountProtectionEvidence");
    Objects.requireNonNull(activationResult, "activationResult");
    if (worldActivationFence <= 0L) {
      throw invalid("World activation fence must be positive");
    }
    requireExactBinding(accountProtectionEvidence, activationResult);
    byte[] accountBytes = accountProtectionEvidence.canonicalBytes();
    byte[] resultBytes = activationResult.canonicalBytes();
    requireChildBounds(accountProtectionEvidence, activationResult, accountBytes, resultBytes);
    byte[] canonical =
        encode(
            activationResult.request().activationRequestId(),
            accountProtectionEvidence.accountProtectionId(),
            accountProtectionEvidence.accountProtectionFence(),
            worldActivationFence,
            accountBytes,
            resultBytes,
            sha256(resultBytes));
    return new WorldOriginalStartSessionActivationTerminalEvidence(
        accountProtectionEvidence, activationResult, worldActivationFence, canonical);
  }

  /** Strictly decodes one exact canonical terminal receipt; decoding grants no authority. */
  public static WorldOriginalStartSessionActivationTerminalEvidence decode(
      byte[] exactCanonicalBytes) {
    byte[] stored = bounded(exactCanonicalBytes, MAX_CANONICAL_BYTES, "terminal evidence");
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(stored))
              .toString();
      requireCanonicalJson(stored, json);
      JsonNode root = JSON.readTree(json);
      requireFields(root);
      if (!SCHEMA.equals(text(root, "schema"))) {
        throw invalid("Unsupported World activation terminal evidence schema");
      }
      byte[] accountBytes = decodeBase64(root, "accountProtectionEvidenceBytesBase64");
      byte[] resultBytes =
          bounded(
              decodeBase64(root, "activationResultBytesBase64"),
              MAX_ACTIVATION_RESULT_BYTES,
              "activation result");
      if (!SHA256.matcher(text(root, "activationResultSha256")).matches()
          || !text(root, "activationResultSha256").equals(sha256(resultBytes))) {
        throw invalid("World activation result digest differs from its exact bytes");
      }
      AccountStartSessionActivationProtectionEvidence accountEvidence =
          AccountStartSessionActivationProtectionEvidence.decode(accountBytes);
      WorldCanonicalInstanceActivation.Result result =
          WorldCanonicalInstanceActivation.Result.fromStored(resultBytes);
      requireChildBounds(accountEvidence, result, accountBytes, resultBytes);
      UUID activationRequestId = canonicalUuid(root, "activationRequestId");
      UUID accountProtectionId = canonicalUuid(root, "accountProtectionId");
      long accountProtectionFence = positiveLong(root, "accountProtectionFence");
      long worldActivationFence = positiveLong(root, "worldActivationFence");
      if (!activationRequestId.equals(result.request().activationRequestId())
          || !accountProtectionId.equals(accountEvidence.accountProtectionId())
          || accountProtectionFence != accountEvidence.accountProtectionFence()) {
        throw invalid("World activation terminal identity differs from its retained Account proof");
      }
      requireExactBinding(accountEvidence, result);
      WorldOriginalStartSessionActivationTerminalEvidence decoded =
          new WorldOriginalStartSessionActivationTerminalEvidence(
              accountEvidence, result, worldActivationFence, stored);
      if (!MessageDigest.isEqual(stored, decoded.canonicalBytes)) {
        throw invalid("World activation terminal evidence is not its exact canonical encoding");
      }
      return decoded;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("World activation terminal evidence is malformed");
    }
  }

  public AccountStartSessionActivationProtectionEvidence accountProtectionEvidence() {
    return accountProtectionEvidence;
  }

  public WorldCanonicalInstanceActivation.Result activationResult() {
    return activationResult;
  }

  public long worldActivationFence() {
    return worldActivationFence;
  }

  public UUID activationRequestId() {
    return activationResult.request().activationRequestId();
  }

  public WorldCanonicalInstanceActivation.Outcome outcome() {
    return activationResult.outcome();
  }

  /** SHA-256 of this complete immutable receipt. */
  public String terminalDigest() {
    return terminalDigest;
  }

  /** Exact retained receipt bytes, including original first-request read correlations. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  private static void requireExactBinding(
      AccountStartSessionActivationProtectionEvidence accountEvidence,
      WorldCanonicalInstanceActivation.Result result) {
    var protectedRequest = accountEvidence.request().worldActivationRequest();
    if (!protectedRequest.activationRequestId().equals(result.request().activationRequestId())
        || !Arrays.equals(
            protectedRequest.canonicalRequestBytes(), result.request().canonicalRequestBytes())
        || !Arrays.equals(
            protectedRequest.preparingEvidenceBytes(), result.request().preparingEvidenceBytes())) {
      throw invalid(
          "World terminal result differs from the original Account-protected activation request");
    }
    if (result.outcome() == WorldCanonicalInstanceActivation.Outcome.COMMITTED) {
      if (!"ACTIVE".equals(result.lifecycleEvidence().lifecycleStatus())
          || result.lifecycleEvidence().lifecycleEpoch()
              != Math.addExact(result.request().expectedLifecycleEpoch(), 1L)
          || result.lifecycleEvidence().rowVersion()
              != Math.addExact(result.request().expectedRowVersion(), 1L)) {
        throw invalid("Committed World activation terminal evidence is not the exact ACTIVE CAS");
      }
    } else if (result.terminalCode() == null || result.terminalCode().isBlank()) {
      throw invalid("Fenced World activation ABORTED evidence requires a terminal code");
    }
  }

  private static void requireChildBounds(
      AccountStartSessionActivationProtectionEvidence accountEvidence,
      WorldCanonicalInstanceActivation.Result result,
      byte[] accountBytes,
      byte[] resultBytes) {
    bounded(
        accountBytes,
        AccountStartSessionActivationProtectionEvidence.MAX_CANONICAL_BYTES,
        "Account protection evidence");
    bounded(
        accountEvidence.request().canonicalBytes(),
        AccountStartSessionActivationProtectionRequest.MAX_CANONICAL_BYTES,
        "Account protection request");
    bounded(
        accountEvidence.request().worldActivationRequest().preparingEvidenceBytes(),
        AccountStartSessionActivationProtectionRequest.MAX_WORLD_PREPARING_EVIDENCE_BYTES,
        "PREPARING evidence");
    bounded(
        result.request().canonicalRequestBytes(),
        AccountStartSessionActivationProtectionRequest.MAX_CANONICAL_BYTES,
        "normalized World activation request");
    bounded(
        result.lifecycleEvidence().canonicalBytes(),
        MAX_LIFECYCLE_EVIDENCE_BYTES,
        "lifecycle evidence");
    bounded(resultBytes, MAX_ACTIVATION_RESULT_BYTES, "activation result");
  }

  private static byte[] encode(
      UUID activationRequestId,
      UUID accountProtectionId,
      long accountProtectionFence,
      long worldActivationFence,
      byte[] accountEvidenceBytes,
      byte[] resultBytes,
      String resultDigest) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("activationRequestId", activationRequestId.toString());
    value.put("accountProtectionId", accountProtectionId.toString());
    value.put("accountProtectionFence", Long.toString(accountProtectionFence));
    value.put("worldActivationFence", Long.toString(worldActivationFence));
    value.put(
        "accountProtectionEvidenceBytesBase64",
        Base64.getEncoder().encodeToString(accountEvidenceBytes));
    value.put("activationResultBytesBase64", Base64.getEncoder().encodeToString(resultBytes));
    value.put("activationResultSha256", resultDigest);
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (bytes.length > MAX_CANONICAL_BYTES) {
        throw invalid("World activation terminal evidence exceeds its byte limit");
      }
      return bytes;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "World activation terminal evidence cannot be encoded", impossible);
    }
  }

  private static void requireFields(JsonNode node) {
    if (node == null || !node.isObject() || node.size() != ROOT_FIELDS.size()) {
      throw invalid("World activation terminal evidence has an invalid closed object shape");
    }
    for (String field : node.propertyNames()) {
      if (!ROOT_FIELDS.contains(field))
        throw invalid("World activation terminal evidence has an unknown field");
    }
  }

  private static UUID canonicalUuid(JsonNode node, String field) {
    String text = text(node, field);
    try {
      UUID value = UUID.fromString(text);
      if (!value.toString().equals(text) || NIL_UUID.equals(value)) {
        throw invalid(field + " must be a canonical lowercase non-nil UUID");
      }
      return value;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical lowercase non-nil UUID");
    }
  }

  private static long positiveLong(JsonNode node, String field) {
    String text = text(node, field);
    if (!text.matches("[1-9][0-9]{0,18}")) {
      throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
    }
    try {
      long value = Long.parseLong(text);
      if (value <= 0L || !Long.toString(value).equals(text)) {
        throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
      }
      return value;
    } catch (NumberFormatException malformed) {
      throw invalid(field + " is outside the supported signed 64-bit range");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) throw invalid(field + " must be text");
    return value.textValue();
  }

  private static byte[] decodeBase64(JsonNode node, String field) {
    String value = text(node, field);
    try {
      byte[] bytes = Base64.getDecoder().decode(value);
      if (!Base64.getEncoder().encodeToString(bytes).equals(value)) {
        throw invalid(field + " must use canonical Base64");
      }
      return bytes;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is malformed Base64");
    }
  }

  private static byte[] bounded(byte[] value, int maximum, String label) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw invalid("World activation " + label + " is absent or exceeds its byte limit");
    }
    return value.clone();
  }

  private static void requireCanonicalJson(byte[] bytes, String json) throws IOException {
    if (!MessageDigest.isEqual(Rfc8785CanonicalJson.canonicalizeUtf8(json), bytes)) {
      throw invalid("World activation terminal evidence is not canonical RFC 8785 JSON");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
