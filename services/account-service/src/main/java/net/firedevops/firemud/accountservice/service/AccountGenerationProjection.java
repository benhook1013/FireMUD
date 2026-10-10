package net.firedevops.firemud.accountservice.service;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec.AccountLogoutAllAuthorityEvent;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.PasswordResetAuthorityEvent;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Closed current-snapshot projection of one durable Account account-generation source. */
public record AccountGenerationProjection(
    String accountId,
    String accountAuthorityGeneration,
    String sourceVersion,
    String outboxStreamKey,
    String outboxSequence,
    Optional<String> sourceEvent) {
  public static final String SCHEMA_VERSION = "account-auth-account-generation-projection/v1";
  public static final String KEY_PREFIX = "session:auth:generation:account:";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:account/";
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "schemaVersion",
          "accountId",
          "accountAuthorityGeneration",
          "sourceVersion",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> ALL_FIELDS =
      Set.of(
          "schemaVersion",
          "accountId",
          "accountAuthorityGeneration",
          "sourceVersion",
          "outboxStreamKey",
          "outboxSequence",
          "sourceEvent");

  public AccountGenerationProjection {
    requireCanonicalAccountId(accountId);
    requirePositiveDecimal(accountAuthorityGeneration, "accountAuthorityGeneration");
    requirePositiveDecimal(sourceVersion, "sourceVersion");
    requireNonNegativeDecimal(outboxSequence, "outboxSequence");
    if (!streamKeyForAccount(accountId).equals(outboxStreamKey)) {
      throw new IllegalArgumentException(
          "Account generation projection stream key is not canonical");
    }
    sourceEvent = Objects.requireNonNull(sourceEvent, "sourceEvent optional is required");
    BigInteger sequence = new BigInteger(outboxSequence);
    if (sequence.signum() == 0) {
      if (!"1".equals(accountAuthorityGeneration)
          || !"1".equals(sourceVersion)
          || sourceEvent.isPresent()) {
        throw new IllegalArgumentException(
            "Account sequence zero requires the original 1/1 source baseline and no event");
      }
    } else {
      if (new BigInteger(accountAuthorityGeneration).compareTo(BigInteger.ONE) <= 0
          || new BigInteger(sourceVersion).compareTo(BigInteger.ONE) <= 0
          || sourceEvent.isEmpty()) {
        throw new IllegalArgumentException(
            "Positive Account source sequence requires advanced counters and its exact event");
      }
      validateSourceEvent(
          sourceEvent.orElseThrow(),
          accountId,
          accountAuthorityGeneration,
          sourceVersion,
          outboxStreamKey,
          outboxSequence);
    }
  }

  /** Builds projection evidence only from Account's owner-local current durable readback. */
  public static AccountGenerationProjection fromSource(AccountSourceSnapshot source) {
    Objects.requireNonNull(source, "Account source snapshot is required");
    AuthorityScope scope = source.sourceState().scope();
    if (scope.kind() != ScopeKind.ACCOUNT || !source.accountId().equals(scope.accountId())) {
      throw new IllegalArgumentException("Account source snapshot has a different authority scope");
    }
    Optional<String> event =
        source.latestEvent().map(AccountGenerationProjection::decodeCanonicalEvent);
    return new AccountGenerationProjection(
        source.accountId().toString(),
        Long.toString(source.sourceState().generation()),
        Long.toString(source.sourceState().sourceVersion()),
        source.outboxStreamKey(),
        Long.toString(source.outboxSequence()),
        event);
  }

  /**
   * Parses the closed wire shape and rejects noncanonical JSON and all alternate representations.
   */
  public static AccountGenerationProjection parse(String json) {
    Objects.requireNonNull(json, "projection JSON is required");
    final JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException(
          "Account generation projection JSON is malformed", exception);
    }
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Account generation projection must be a JSON object");
    }
    Set<String> fields = new HashSet<>();
    object.propertyNames().forEach(fields::add);
    if (!fields.contains("sourceEvent") && !fields.equals(REQUIRED_FIELDS)) {
      throw new IllegalArgumentException(
          "Account generation projection has unknown or missing fields");
    }
    if (fields.contains("sourceEvent") && !fields.equals(ALL_FIELDS)) {
      throw new IllegalArgumentException(
          "Account generation projection has unknown or missing fields");
    }
    if (!SCHEMA_VERSION.equals(requireText(object, "schemaVersion"))) {
      throw new IllegalArgumentException(
          "Account generation projection schemaVersion is unsupported");
    }
    Optional<String> event =
        fields.contains("sourceEvent")
            ? Optional.of(requireText(object, "sourceEvent"))
            : Optional.empty();
    AccountGenerationProjection projection =
        new AccountGenerationProjection(
            requireText(object, "accountId"),
            requireText(object, "accountAuthorityGeneration"),
            requireText(object, "sourceVersion"),
            requireText(object, "outboxStreamKey"),
            requireText(object, "outboxSequence"),
            event);
    if (!projection.toJson().equals(json)) {
      throw new IllegalArgumentException("Account generation projection JSON is not canonical");
    }
    return projection;
  }

  public String key() {
    return KEY_PREFIX + accountId;
  }

  public String toJson() {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("accountId", accountId);
    object.put("accountAuthorityGeneration", accountAuthorityGeneration);
    object.put("sourceVersion", sourceVersion);
    object.put("outboxStreamKey", outboxStreamKey);
    object.put("outboxSequence", outboxSequence);
    sourceEvent.ifPresent(value -> object.put("sourceEvent", value));
    try {
      return JSON.writeValueAsString(object);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(
          "Account generation projection cannot be serialized", exception);
    }
  }

  public BigInteger generationValue() {
    return new BigInteger(accountAuthorityGeneration);
  }

  public BigInteger sourceVersionValue() {
    return new BigInteger(sourceVersion);
  }

  public BigInteger outboxSequenceValue() {
    return new BigInteger(outboxSequence);
  }

  static String keyForAccount(UUID accountId) {
    Objects.requireNonNull(accountId, "Account UUID is required");
    if (new UUID(0L, 0L).equals(accountId)) {
      throw new IllegalArgumentException("A canonical non-nil Account UUID is required");
    }
    return KEY_PREFIX + accountId;
  }

  private static String streamKeyForAccount(String accountId) {
    return STREAM_PREFIX + accountId;
  }

  private static String decodeCanonicalEvent(Event event) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(event.payload()))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Account source event is not valid UTF-8", exception);
    }
  }

  private static void validateSourceEvent(
      String eventJson,
      String accountId,
      String generation,
      String sourceVersion,
      String streamKey,
      String sequence) {
    final JsonNode root;
    try {
      root = JSON.readTree(eventJson);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Account source event JSON is malformed", exception);
    }
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Account source event must be a JSON object");
    }
    String canonicalEvent;
    switch (requireText(object, "schemaVersion")) {
      case PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION -> {
        PasswordResetAuthorityEvent reset = PasswordResetAuthorityEventV1Codec.verify(eventJson);
        canonicalEvent = new String(reset.canonicalJsonUtf8(), StandardCharsets.UTF_8);
        requireEventBinding(
            accountId,
            generation,
            sourceVersion,
            streamKey,
            sequence,
            reset.accountId(),
            reset.accountAuthorityGeneration(),
            reset.sourceVersion(),
            reset.outboxStreamKey(),
            reset.outboxSequence());
      }
      case AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION -> {
        AccountLogoutAllAuthorityEvent logout =
            AccountLogoutAllAuthorityEventV1Codec.verify(eventJson);
        canonicalEvent = new String(logout.canonicalJsonUtf8(), StandardCharsets.UTF_8);
        requireEventBinding(
            accountId,
            generation,
            sourceVersion,
            streamKey,
            sequence,
            logout.accountId(),
            logout.accountAuthorityGeneration(),
            logout.sourceVersion(),
            logout.outboxStreamKey(),
            logout.outboxSequence());
      }
      case AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION -> {
        var security = AccountSecurityStateAuthorityEventV1Codec.verify(eventJson);
        canonicalEvent = security.canonicalJson();
        requireEventBinding(
            accountId,
            generation,
            sourceVersion,
            streamKey,
            sequence,
            security.accountId(),
            security.accountAuthorityGeneration(),
            security.sourceVersion(),
            security.outboxStreamKey(),
            security.outboxSequence());
      }
      default -> {
        throw new IllegalArgumentException(
            "Account source event is not one of the declared closed schemas");
      }
    }
    if (!canonicalEvent.equals(eventJson)) {
      throw new IllegalArgumentException("Account source event JSON is not canonical");
    }
  }

  private static void requireEventBinding(
      String expectedAccount,
      String expectedGeneration,
      String expectedSourceVersion,
      String expectedStream,
      String expectedSequence,
      String actualAccount,
      String actualGeneration,
      String actualSourceVersion,
      String actualStream,
      String actualSequence) {
    if (!expectedAccount.equals(actualAccount)
        || !expectedGeneration.equals(actualGeneration)
        || !expectedSourceVersion.equals(actualSourceVersion)
        || !expectedStream.equals(actualStream)
        || !expectedSequence.equals(actualSequence)) {
      throw new IllegalArgumentException(
          "Account source event does not match its current projection checkpoint");
    }
  }

  private static String requireText(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(
          "Account generation projection " + field + " must be a string");
    }
    return value.textValue();
  }

  private static void requireCanonicalAccountId(String value) {
    Objects.requireNonNull(value, "accountId is required");
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(
            "Account generation projection accountId is not canonical");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Account generation projection accountId must be a lowercase canonical non-nil UUID",
          malformed);
    }
  }

  private static void requirePositiveDecimal(String value, String field) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a positive canonical decimal string");
    }
  }

  private static void requireNonNegativeDecimal(String value, String field) {
    if (value == null || !NON_NEGATIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field + " must be a non-negative canonical decimal string");
    }
  }
}
