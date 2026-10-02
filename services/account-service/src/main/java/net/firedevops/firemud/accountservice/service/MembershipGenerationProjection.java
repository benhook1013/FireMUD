package net.firedevops.firemud.accountservice.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader.MembershipSourceSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;

/** Closed current-snapshot projection of one already-enrolled Account membership source. */
public record MembershipGenerationProjection(
    String accountId,
    String tenantId,
    String membershipAuthorityGeneration,
    String sourceVersion,
    Map<String, String> membershipVersion,
    String outboxStreamKey,
    String outboxSequence,
    Optional<String> sourceEvent) {
  public static final String SCHEMA_VERSION = "account-auth-membership-generation-projection/v1";
  public static final String KEY_PREFIX = "session:auth:generation:membership:";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:membership/";
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "schemaVersion",
          "accountId",
          "tenantId",
          "membershipAuthorityGeneration",
          "sourceVersion",
          "membershipVersion",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> ALL_FIELDS =
      Set.of(
          "schemaVersion",
          "accountId",
          "tenantId",
          "membershipAuthorityGeneration",
          "sourceVersion",
          "membershipVersion",
          "outboxStreamKey",
          "outboxSequence",
          "sourceEvent");

  public MembershipGenerationProjection {
    requireCanonicalUuid(accountId, "accountId");
    requireCanonicalUuid(tenantId, "tenantId");
    requirePositiveDecimal(membershipAuthorityGeneration, "membershipAuthorityGeneration");
    requirePositiveDecimal(sourceVersion, "sourceVersion");
    requireNonNegativeDecimal(outboxSequence, "outboxSequence");
    membershipVersion = Map.copyOf(Objects.requireNonNull(membershipVersion, "membershipVersion"));
    if (membershipVersion.size() != 1 || !membershipVersion.containsKey(tenantId)) {
      throw new IllegalArgumentException(
          "Membership projection version map must contain exactly its tenantId key");
    }
    requirePositiveDecimal(membershipVersion.get(tenantId), "membershipVersion");
    if (!streamKeyForPair(accountId, tenantId).equals(outboxStreamKey)) {
      throw new IllegalArgumentException("Membership projection stream key is not canonical");
    }
    sourceEvent = Objects.requireNonNull(sourceEvent, "sourceEvent optional is required");

    BigInteger sequence = new BigInteger(outboxSequence);
    if (sequence.signum() == 0) {
      if (!"1".equals(membershipAuthorityGeneration)
          || !"1".equals(sourceVersion)
          || !"1".equals(membershipVersion.get(tenantId))
          || sourceEvent.isPresent()) {
        throw new IllegalArgumentException(
            "Membership sequence zero requires the original 1/1 generation/source baseline, "
                + "version 1 and no event");
      }
    } else {
      if (sourceEvent.isEmpty()) {
        throw new IllegalArgumentException(
            "Positive membership source sequence requires its exact event");
      }
      validateSourceEvent(
          sourceEvent.orElseThrow(),
          accountId,
          tenantId,
          membershipAuthorityGeneration,
          membershipVersion,
          outboxStreamKey,
          outboxSequence);
    }
  }

  /** Builds a candidate only from the owner-local, existing-only Account source reader. */
  public static MembershipGenerationProjection fromSource(MembershipSourceSnapshot source) {
    Objects.requireNonNull(source, "Account membership source snapshot is required");
    AuthorityScope expectedScope = AuthorityScope.membership(source.accountId(), source.tenantId());
    if (!expectedScope.equals(source.sourceState().scope())
        || source.sourceState().scope().kind() != ScopeKind.MEMBERSHIP
        || source.sourceState().generation() <= 0L
        || source.sourceState().sourceVersion() <= 0L) {
      throw new IllegalArgumentException("Membership source snapshot has a different scope");
    }

    RuntimeMembershipSnapshotDto snapshot = source.snapshot();
    String accountId = source.accountId().toString();
    String tenantId = source.tenantId().toString();
    String streamKey = streamKeyForPair(accountId, tenantId);
    List<OutboxCheckpointEntry> membershipCheckpoints =
        snapshot.outboxCheckpoints().stream()
            .filter(checkpoint -> streamKey.equals(checkpoint.outboxStreamKey()))
            .toList();
    if (membershipCheckpoints.size() != 1) {
      throw new IllegalArgumentException(
          "Membership source snapshot must contain one exact membership checkpoint");
    }
    OutboxCheckpointEntry checkpoint = membershipCheckpoints.getFirst();
    List<OutboxSourceEvidence> membershipEvidence =
        snapshot.outboxSourceEvidence().stream()
            .filter(evidence -> streamKey.equals(evidence.outboxStreamKey()))
            .toList();
    MembershipEvent event = snapshot.requireConsistentSourceEvent();

    Optional<String> eventJson;
    if (event == null) {
      if (!"0".equals(checkpoint.outboxSequence())
          || !membershipEvidence.isEmpty()
          || snapshot.membershipExists()
          || !"MISSING".equals(snapshot.membershipBaseline().membershipLifecycleState())
          || !snapshot.roles().isEmpty()
          || source.sourceState().generation() != 1L
          || source.sourceState().sourceVersion() != 1L
          || !"1".equals(snapshot.membershipBaseline().membershipVersion().get(tenantId))) {
        throw new IllegalArgumentException(
            "Membership source without an event is not the proved existing sequence-zero baseline");
      }
      eventJson = Optional.empty();
    } else {
      String canonicalJson = event.canonicalJson();
      MembershipEvent verified = MembershipAuthorityEventV1Codec.verify(canonicalJson);
      if (!Arrays.equals(
              verified.canonicalJsonUtf8(), canonicalJson.getBytes(StandardCharsets.UTF_8))
          || !verified.canonicalJson().equals(canonicalJson)
          || membershipEvidence.size() != 1
          || !verified.eventId().equals(membershipEvidence.getFirst().eventId())
          || !verified.eventDigest().equals(membershipEvidence.getFirst().eventDigest())
          || !verified.outboxSequence().equals(membershipEvidence.getFirst().outboxSequence())
          || !canonicalJson.equals(membershipEvidence.getFirst().canonicalEventJson())
          || !verified.outboxSequence().equals(checkpoint.outboxSequence())
          || !snapshot.membershipExists()) {
        throw new IllegalArgumentException(
            "Membership source event differs from its exact canonical checkpoint evidence");
      }
      eventJson = Optional.of(canonicalJson);
    }

    return new MembershipGenerationProjection(
        accountId,
        tenantId,
        Long.toString(source.sourceState().generation()),
        Long.toString(source.sourceState().sourceVersion()),
        snapshot.membershipBaseline().membershipVersion(),
        streamKey,
        checkpoint.outboxSequence(),
        eventJson);
  }

  /** Parses the closed wire shape, requiring canonical JSON and all exact source bindings. */
  public static MembershipGenerationProjection parse(String json) {
    Objects.requireNonNull(json, "projection JSON is required");
    final JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Membership generation projection JSON is malformed", exception);
    }
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Membership generation projection must be a JSON object");
    }
    Set<String> fields = new HashSet<>();
    object.fieldNames().forEachRemaining(fields::add);
    if (!fields.contains("sourceEvent") && !fields.equals(REQUIRED_FIELDS)) {
      throw new IllegalArgumentException("Membership projection has unknown or missing fields");
    }
    if (fields.contains("sourceEvent") && !fields.equals(ALL_FIELDS)) {
      throw new IllegalArgumentException("Membership projection has unknown or missing fields");
    }
    if (!SCHEMA_VERSION.equals(requireText(object, "schemaVersion"))) {
      throw new IllegalArgumentException("Membership projection schemaVersion is unsupported");
    }

    String tenantId = requireText(object, "tenantId");
    JsonNode versionNode = object.get("membershipVersion");
    if (!(versionNode instanceof ObjectNode versions) || versions.size() != 1) {
      throw new IllegalArgumentException(
          "Membership projection membershipVersion must be a one-tenant object");
    }
    Map<String, String> versionMap = Map.of(tenantId, requireText(versions, tenantId));
    Optional<String> event =
        fields.contains("sourceEvent")
            ? Optional.of(requireText(object, "sourceEvent"))
            : Optional.empty();
    MembershipGenerationProjection projection =
        new MembershipGenerationProjection(
            requireText(object, "accountId"),
            tenantId,
            requireText(object, "membershipAuthorityGeneration"),
            requireText(object, "sourceVersion"),
            versionMap,
            requireText(object, "outboxStreamKey"),
            requireText(object, "outboxSequence"),
            event);
    if (!projection.toJson().equals(json)) {
      throw new IllegalArgumentException("Membership projection JSON is not canonical");
    }
    return projection;
  }

  public String key() {
    return KEY_PREFIX + accountId + ":" + tenantId;
  }

  public String toJson() {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("accountId", accountId);
    object.put("tenantId", tenantId);
    object.put("membershipAuthorityGeneration", membershipAuthorityGeneration);
    object.put("sourceVersion", sourceVersion);
    ObjectNode versions = object.putObject("membershipVersion");
    versions.put(tenantId, membershipVersion.get(tenantId));
    object.put("outboxStreamKey", outboxStreamKey);
    object.put("outboxSequence", outboxSequence);
    sourceEvent.ifPresent(value -> object.put("sourceEvent", value));
    try {
      return JSON.writeValueAsString(object);
    } catch (IOException exception) {
      throw new IllegalStateException("Membership projection cannot be serialized", exception);
    }
  }

  public BigInteger generationValue() {
    return new BigInteger(membershipAuthorityGeneration);
  }

  public BigInteger sourceVersionValue() {
    return new BigInteger(sourceVersion);
  }

  public BigInteger membershipVersionValue() {
    return new BigInteger(membershipVersion.get(tenantId));
  }

  public BigInteger outboxSequenceValue() {
    return new BigInteger(outboxSequence);
  }

  static String keyForPair(UUID accountId, UUID tenantId) {
    requireNonNilUuid(accountId, "Account UUID");
    requireNonNilUuid(tenantId, "tenant UUID");
    return KEY_PREFIX + accountId + ":" + tenantId;
  }

  private static String streamKeyForPair(String accountId, String tenantId) {
    return STREAM_PREFIX + accountId + "/" + tenantId;
  }

  private static void validateSourceEvent(
      String eventJson,
      String accountId,
      String tenantId,
      String generation,
      Map<String, String> membershipVersion,
      String streamKey,
      String sequence) {
    MembershipEvent event = MembershipAuthorityEventV1Codec.verify(eventJson);
    if (!event.canonicalJson().equals(eventJson)
        || !Arrays.equals(event.canonicalJsonUtf8(), eventJson.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Membership source event JSON is not canonical");
    }
    if (!accountId.equals(event.accountId())
        || !tenantId.equals(event.tenantId())
        || !("membership/" + accountId + "/" + tenantId).equals(event.sourceScope())
        || !streamKey.equals(event.outboxStreamKey())
        || !sequence.equals(event.outboxSequence())
        || !generation.equals(event.membershipAuthorityGeneration())
        || !membershipVersion.equals(event.membershipVersion())) {
      throw new IllegalArgumentException(
          "Membership source event does not match its exact projection checkpoint");
    }
  }

  private static String requireText(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Membership projection " + field + " must be a string");
    }
    return value.textValue();
  }

  private static void requireCanonicalUuid(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    try {
      UUID parsed = UUID.fromString(value);
      if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static void requireNonNilUuid(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
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
