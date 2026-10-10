package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable operation correlation only; this request does not establish caller authorization. */
public record AccountSecurityStateMutationRequest(
    UUID requestId,
    UUID accountUuid,
    byte[] callerProofBinding,
    long expectedGeneration,
    long expectedSourceVersion,
    List<String> mutationKinds,
    AccountState desiredState) {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final Set<String> CALLER_FIELDS =
      Set.of("schemaVersion", "actorAccountUuid", "ownerOperationId", "ownerEvidenceDigest");

  public AccountSecurityStateMutationRequest {
    requireUuid(requestId);
    requireUuid(accountUuid);
    if (expectedGeneration <= 0 || expectedSourceVersion <= 0) {
      throw new IllegalArgumentException("Original positive source counters are required");
    }
    callerProofBinding = canonicalCallerBinding(callerProofBinding);
    mutationKinds = List.copyOf(Objects.requireNonNull(mutationKinds));
    Objects.requireNonNull(desiredState);
    // The shared source codec owns the complete state and family vocabulary.
    var validated =
        AccountSecurityStateAuthorityEventV1Codec.seal(
            eventPreimage(requestId, accountUuid, "1", "1", "1", mutationKinds, desiredState));
    desiredState = validated.accountState();
    mutationKinds = validated.mutationKinds();
  }

  @Override
  public byte[] callerProofBinding() {
    return callerProofBinding.clone();
  }

  @Override
  public List<String> mutationKinds() {
    return List.copyOf(mutationKinds);
  }

  public byte[] canonicalDesiredState() {
    return canonicalState(desiredState);
  }

  public static byte[] canonicalState(AccountState state) {
    Objects.requireNonNull(state);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(state));
    } catch (IOException | tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Account state cannot be canonicalized", exception);
    }
  }

  public static AccountState parseCanonicalState(byte[] bytes) {
    try {
      String text = new String(Objects.requireNonNull(bytes), StandardCharsets.UTF_8);
      JsonNode node = JSON.readTree(text);
      var validated =
          AccountSecurityStateAuthorityEventV1Codec.seal(
              eventPreimage(
                  UUID.fromString("11111111-1111-4111-8111-111111111111"),
                  UUID.fromString("22222222-2222-4222-8222-222222222222"),
                  "1",
                  "1",
                  "1",
                  List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED"),
                  JSON.convertValue(node, new TypeReference<Map<String, Object>>() {})));
      if (!Arrays.equals(bytes, canonicalState(validated.accountState()))) {
        throw new IllegalArgumentException("Stored Account state is not canonical");
      }
      return validated.accountState();
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Stored Account state is malformed", exception);
    }
  }

  public static Map<String, Object> eventPreimage(
      UUID requestId,
      UUID accountUuid,
      String generation,
      String sourceVersion,
      String sequence,
      List<String> mutationKinds,
      Object state) {
    String stream = "account:auth-authority:v1:account/" + accountUuid;
    return Map.ofEntries(
        Map.entry("schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
        Map.entry("eventId", AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId),
        Map.entry("requestId", requestId.toString()),
        Map.entry("accountId", accountUuid.toString()),
        Map.entry("sourceScope", "account/" + accountUuid),
        Map.entry("outboxStreamKey", stream),
        Map.entry("outboxSequence", sequence),
        Map.entry("accountAuthorityGeneration", generation),
        Map.entry("sourceVersion", sourceVersion),
        Map.entry(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration",
                generation,
                "outboxStreamKey",
                stream,
                "outboxSequence",
                sequence)),
        Map.entry("mutationKinds", mutationKinds),
        Map.entry("accountState", state));
  }

  private static byte[] canonicalCallerBinding(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 16_384) {
      throw new IllegalArgumentException("Bounded original caller/proof correlation is required");
    }
    try {
      String text = new String(bytes, StandardCharsets.UTF_8);
      if (!Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(text))) {
        throw new IllegalArgumentException("Caller/proof correlation must be canonical JSON");
      }
      JsonNode node = JSON.readTree(text);
      Set<String> fields = new java.util.HashSet<>();
      node.propertyNames().forEach(fields::add);
      if (!node.isObject() || !fields.equals(CALLER_FIELDS)) {
        throw new IllegalArgumentException(
            "Caller correlation must contain exactly its identity fields");
      }
      if (!"account-security-state-caller-correlation/v1"
          .equals(node.path("schemaVersion").textValue())) {
        throw new IllegalArgumentException("Unsupported caller correlation version");
      }
      for (String field : List.of("actorAccountUuid", "ownerOperationId")) {
        JsonNode id = node.get(field);
        if (!id.isTextual()
            || !id.textValue()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
          throw new IllegalArgumentException("Canonical correlation UUIDs are required");
        }
        requireUuid(UUID.fromString(id.textValue()));
      }
      if (!node.get("ownerEvidenceDigest").isTextual()
          || !node.get("ownerEvidenceDigest").textValue().matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "Original owner evidence digest correlation is required");
      }
      return bytes.clone();
    } catch (IOException | tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Caller/proof correlation is malformed", exception);
    }
  }

  private static void requireUuid(UUID uuid) {
    if (uuid == null || uuid.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(
          "Canonical non-nil operation and Account UUIDs are required");
    }
  }
}
