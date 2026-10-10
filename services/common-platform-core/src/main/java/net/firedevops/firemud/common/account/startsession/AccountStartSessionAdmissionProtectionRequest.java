package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact integrity-only inputs for Account source protection through original StartSession
 * admission.
 *
 * <p>This carrier does not authenticate an issuer, establish current source state, prove an active
 * claim, or itself protect Account writes. It retains the complete original tuple, redemption
 * projection, finite owner lease, Account World participation identity, and World hold identity.
 */
public final class AccountStartSessionAdmissionProtectionRequest {
  public static final String SCHEMA = "account-start-session-admission-protection-request/v1";
  public static final int MAX_CANONICAL_BYTES = 768 * 1024;
  public static final int MAX_PROJECTION_BYTES = 132 * 1024;
  public static final int MAX_HOLD_IDENTITY_BYTES = 64 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern MILLIS_UTC =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
  private static final DateTimeFormatter MILLIS_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "originalPostAuthorizationTupleBytesBase64",
          "accountRedemptionProjectionBytesBase64",
          "gameSessionOwnerMutationId",
          "gameSessionOwnerAttemptId",
          "gameSessionOwnerFence",
          "originalLeaseExpiresAt",
          "accountWorldParticipationId",
          "accountWorldParticipationFence",
          "worldAdmissionHoldIdentityBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final byte[] originalPostAuthorizationTupleBytes;
  private final byte[] accountRedemptionProjectionBytes;
  private final UUID gameSessionOwnerMutationId;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
  private final Instant originalLeaseExpiresAt;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final byte[] worldAdmissionHoldIdentityBytes;
  private final StartSessionPostAuthorizationExecutionTuple originalTuple;
  private final WorldCanonicalInitialAdmissionHold.HoldIdentity worldAdmissionHoldIdentity;
  private final byte[] canonicalBytes;

  private AccountStartSessionAdmissionProtectionRequest(
      byte[] originalPostAuthorizationTupleBytes,
      byte[] accountRedemptionProjectionBytes,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      Instant originalLeaseExpiresAt,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      byte[] worldAdmissionHoldIdentityBytes,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      WorldCanonicalInitialAdmissionHold.HoldIdentity worldAdmissionHoldIdentity,
      byte[] canonicalBytes) {
    this.originalPostAuthorizationTupleBytes = originalPostAuthorizationTupleBytes.clone();
    this.accountRedemptionProjectionBytes = accountRedemptionProjectionBytes.clone();
    this.gameSessionOwnerMutationId = gameSessionOwnerMutationId;
    this.gameSessionOwnerAttemptId = gameSessionOwnerAttemptId;
    this.gameSessionOwnerFence = gameSessionOwnerFence;
    this.originalLeaseExpiresAt = originalLeaseExpiresAt;
    this.accountWorldParticipationId = accountWorldParticipationId;
    this.accountWorldParticipationFence = accountWorldParticipationFence;
    this.worldAdmissionHoldIdentityBytes = worldAdmissionHoldIdentityBytes.clone();
    this.originalTuple = originalTuple;
    this.worldAdmissionHoldIdentity = worldAdmissionHoldIdentity;
    this.canonicalBytes = canonicalBytes.clone();
  }

  /** Creates one canonical request and validates every complete-byte binding. */
  public static AccountStartSessionAdmissionProtectionRequest create(
      byte[] exactCanonicalOriginalPostAuthorizationTupleBytes,
      byte[] exactCanonicalAccountRedemptionProjectionBytes,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      Instant originalLeaseExpiresAt,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      WorldCanonicalInitialAdmissionHold.HoldIdentity worldAdmissionHoldIdentity) {
    byte[] tupleBytes =
        bounded(
            exactCanonicalOriginalPostAuthorizationTupleBytes,
            StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES,
            "original post-authorization tuple");
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    if (!Arrays.equals(tupleBytes, tuple.canonicalBytes())) {
      throw invalid("Original post-authorization tuple bytes are not exact canonical bytes");
    }

    byte[] projectionBytes =
        bounded(
            exactCanonicalAccountRedemptionProjectionBytes,
            MAX_PROJECTION_BYTES,
            "Account redemption projection");
    byte[] expectedProjection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
    if (!MessageDigest.isEqual(expectedProjection, projectionBytes)) {
      throw invalid("Complete Account redemption projection differs from the original tuple");
    }

    requireNonNil(gameSessionOwnerMutationId, "gameSessionOwnerMutationId");
    requireNonNil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
    Objects.requireNonNull(originalLeaseExpiresAt, "originalLeaseExpiresAt is required");
    String expiryText = canonicalExpiry(originalLeaseExpiresAt);
    requireNonNil(accountWorldParticipationId, "accountWorldParticipationId");
    requirePositive(accountWorldParticipationFence, "accountWorldParticipationFence");
    Objects.requireNonNull(worldAdmissionHoldIdentity, "worldAdmissionHoldIdentity is required");
    byte[] holdBytes =
        bounded(
            worldAdmissionHoldIdentity.canonicalBytes(),
            MAX_HOLD_IDENTITY_BYTES,
            "World admission hold identity");
    WorldCanonicalInitialAdmissionHold.HoldIdentity decodedHold =
        WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(holdBytes);
    requireTupleAndHoldBinding(tuple, decodedHold);

    byte[] canonical =
        encode(
            tupleBytes,
            projectionBytes,
            gameSessionOwnerMutationId,
            gameSessionOwnerAttemptId,
            gameSessionOwnerFence,
            expiryText,
            accountWorldParticipationId,
            accountWorldParticipationFence,
            holdBytes);
    return new AccountStartSessionAdmissionProtectionRequest(
        tupleBytes,
        projectionBytes,
        gameSessionOwnerMutationId,
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        originalLeaseExpiresAt,
        accountWorldParticipationId,
        accountWorldParticipationFence,
        holdBytes,
        tuple,
        decodedHold,
        canonical);
  }

  /** Strictly decodes one complete, canonical, bounded request; decoding grants no authority. */
  public static AccountStartSessionAdmissionProtectionRequest decode(byte[] exactCanonicalBytes) {
    byte[] stored =
        bounded(exactCanonicalBytes, MAX_CANONICAL_BYTES, "admission protection request");
    try {
      String json = strictUtf8(stored);
      requireCanonicalJson(stored, json, "admission protection request");
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root, ROOT_FIELDS, "admission protection request");
      if (!SCHEMA.equals(string(root.get("schema"), "schema"))) {
        throw invalid("Unsupported Account admission protection request schema");
      }
      AccountStartSessionAdmissionProtectionRequest result =
          create(
              decodeBase64(
                  root.get("originalPostAuthorizationTupleBytesBase64"),
                  "originalPostAuthorizationTupleBytesBase64"),
              decodeBase64(
                  root.get("accountRedemptionProjectionBytesBase64"),
                  "accountRedemptionProjectionBytesBase64"),
              canonicalUuid(root.get("gameSessionOwnerMutationId"), "gameSessionOwnerMutationId"),
              canonicalUuid(root.get("gameSessionOwnerAttemptId"), "gameSessionOwnerAttemptId"),
              positiveLong(root.get("gameSessionOwnerFence"), "gameSessionOwnerFence"),
              parseExpiry(string(root.get("originalLeaseExpiresAt"), "originalLeaseExpiresAt")),
              canonicalUuid(root.get("accountWorldParticipationId"), "accountWorldParticipationId"),
              positiveLong(
                  root.get("accountWorldParticipationFence"), "accountWorldParticipationFence"),
              WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(
                  decodeBase64(
                      root.get("worldAdmissionHoldIdentityBytesBase64"),
                      "worldAdmissionHoldIdentityBytesBase64")));
      if (!MessageDigest.isEqual(stored, result.canonicalBytes)) {
        throw invalid("Account admission protection request members are inconsistent");
      }
      return result;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Account admission protection request is malformed");
    }
  }

  public byte[] originalPostAuthorizationTupleBytes() {
    return originalPostAuthorizationTupleBytes.clone();
  }

  public byte[] accountRedemptionProjectionBytes() {
    return accountRedemptionProjectionBytes.clone();
  }

  public UUID gameSessionOwnerMutationId() {
    return gameSessionOwnerMutationId;
  }

  public UUID gameSessionOwnerAttemptId() {
    return gameSessionOwnerAttemptId;
  }

  public long gameSessionOwnerFence() {
    return gameSessionOwnerFence;
  }

  public Instant originalLeaseExpiresAt() {
    return originalLeaseExpiresAt;
  }

  public UUID accountWorldParticipationId() {
    return accountWorldParticipationId;
  }

  public long accountWorldParticipationFence() {
    return accountWorldParticipationFence;
  }

  public byte[] worldAdmissionHoldIdentityBytes() {
    return worldAdmissionHoldIdentityBytes.clone();
  }

  public StartSessionPostAuthorizationExecutionTuple originalTuple() {
    return originalTuple;
  }

  public WorldCanonicalInitialAdmissionHold.HoldIdentity worldAdmissionHoldIdentity() {
    return worldAdmissionHoldIdentity;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  private static byte[] encode(
      byte[] tupleBytes,
      byte[] projectionBytes,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence,
      String leaseExpiry,
      UUID participationId,
      long participationFence,
      byte[] holdBytes) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
    root.put(
        "originalPostAuthorizationTupleBytesBase64",
        Base64.getEncoder().encodeToString(tupleBytes));
    root.put(
        "accountRedemptionProjectionBytesBase64",
        Base64.getEncoder().encodeToString(projectionBytes));
    root.put("gameSessionOwnerMutationId", ownerMutationId.toString());
    root.put("gameSessionOwnerAttemptId", ownerAttemptId.toString());
    root.put("gameSessionOwnerFence", Long.toString(ownerFence));
    root.put("originalLeaseExpiresAt", leaseExpiry);
    root.put("accountWorldParticipationId", participationId.toString());
    root.put("accountWorldParticipationFence", Long.toString(participationFence));
    root.put(
        "worldAdmissionHoldIdentityBytesBase64", Base64.getEncoder().encodeToString(holdBytes));
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (canonical.length > MAX_CANONICAL_BYTES) {
        throw invalid("Account admission protection request exceeds its byte limit");
      }
      return canonical;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Account admission protection request cannot be encoded", impossible);
    }
  }

  private static void requireTupleAndHoldBinding(
      StartSessionPostAuthorizationExecutionTuple tuple,
      WorldCanonicalInitialAdmissionHold.HoldIdentity hold) {
    var preTuple = tuple.preAuthorizationTuple();
    var action = preTuple.action();
    var request = hold.request();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(preTuple.actionFamily())
        || !"game-session-service".equals(preTuple.targetOwner())
        || !action.scope().tenantId().equals(request.canonicalTenantId())
        || !action.scope().targetNamespace().equals(request.targetNamespace())
        || !GrpcPeerIdentity.isValidNamespace(action.scope().targetNamespace())) {
      throw invalid("Original StartSession tuple differs from the complete World hold scope");
    }
  }

  private static byte[] decodeBase64(Object value, String field) {
    String text = string(value, field);
    try {
      byte[] decoded = Base64.getDecoder().decode(text);
      if (!Base64.getEncoder().encodeToString(decoded).equals(text)) {
        throw invalid(field + " must use canonical Base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is malformed Base64");
    }
  }

  private static byte[] bounded(byte[] value, int maximum, String name) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw invalid(name + " is absent or exceeds its byte limit");
    }
    return value.clone();
  }

  private static String canonicalExpiry(Instant value) {
    if (value.getNano() % 1_000_000 != 0) {
      throw invalid("originalLeaseExpiresAt must use exact millisecond precision");
    }
    String text = MILLIS_FORMAT.format(value);
    if (!MILLIS_UTC.matcher(text).matches()) {
      throw invalid("originalLeaseExpiresAt must use canonical UTC millisecond text");
    }
    return text;
  }

  private static Instant parseExpiry(String value) {
    if (!MILLIS_UTC.matcher(value).matches()) {
      throw invalid("originalLeaseExpiresAt must use canonical UTC millisecond text");
    }
    try {
      Instant parsed = Instant.parse(value);
      if (!MILLIS_FORMAT.format(parsed).equals(value)) {
        throw invalid("originalLeaseExpiresAt must use canonical UTC millisecond text");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException expected) throw expected;
      throw invalid("originalLeaseExpiresAt is malformed");
    }
  }

  private static long positiveLong(Object value, String field) {
    String text = string(value, field);
    if (!POSITIVE_LONG.matcher(text).matches()) {
      throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
    }
    try {
      long parsed = Long.parseLong(text);
      if (parsed <= 0L || !Long.toString(parsed).equals(text)) {
        throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalid(field + " is outside the supported signed 64-bit range");
    }
  }

  private static UUID canonicalUuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || NIL_UUID.equals(parsed)) {
        throw invalid(field + " must be a canonical lowercase non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical lowercase non-nil UUID");
    }
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (NIL_UUID.equals(value)) throw invalid(field + " must be a non-nil UUID");
    return value;
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) throw invalid(field + " must be positive");
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text)) throw invalid(field + " must be a string");
    return text;
  }

  private static void requireExactFields(
      Map<String, Object> root, Set<String> expected, String label) {
    if (root == null || !root.keySet().equals(expected)) {
      throw invalid(label + " has missing or unsupported fields");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Account admission protection request is not valid UTF-8");
    }
  }

  private static void requireCanonicalJson(byte[] originalBytes, String json, String label)
      throws IOException {
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
    if (!MessageDigest.isEqual(canonical, originalBytes)) {
      throw invalid(label + " is not canonical RFC 8785 JSON");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
