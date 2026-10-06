package net.firedevops.firemud.accountservice.service.session;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed non-authorizing projections for the selected public-gameplay Account owner path. */
public final class AccountSelectedGameplayAuthorityProjection {
  public static final String TENANT_SCHEMA = "account-auth-tenant-generation-projection/v1";
  public static final String MEMBERSHIP_SCHEMA = "account-auth-membership-generation-projection/v1";
  public static final String TENANT_PREFIX = "session:auth:generation:tenant:";
  public static final String MEMBERSHIP_PREFIX = "session:auth:generation:membership:";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Map<String, Object> fields;
  private final byte[] bytes;

  private AccountSelectedGameplayAuthorityProjection(Map<String, Object> fields) {
    this.fields = Map.copyOf(fields);
    this.bytes = canonical(fields);
    if (bytes.length > AccountGameplayDelegationAuthorityProjection.MAX_PROJECTION_BYTES) {
      throw new IllegalArgumentException("Selected Account authority projection exceeds its bound");
    }
  }

  static AccountSelectedGameplayAuthorityProjection tenant(
      TenantAuthorityEventV1Codec.Event event) {
    Map<String, Object> value =
        base(
            TENANT_SCHEMA,
            event.tenantId().toString(),
            Long.toString(event.tenantAuthoritySourceVersion()),
            event.outboxStreamKey(),
            Long.toString(event.outboxSequence()),
            new String(event.payload(), StandardCharsets.UTF_8));
    value.put("tenantAuthorityGeneration", Long.toString(event.tenantAuthorityGeneration()));
    return decode(canonical(value), null, event.tenantId());
  }

  static AccountSelectedGameplayAuthorityProjection membership(
      MembershipAuthorityEventV1Codec.MembershipEvent event, long sourceVersion) {
    Map<String, Object> value =
        base(
            MEMBERSHIP_SCHEMA,
            event.tenantId(),
            Long.toString(sourceVersion),
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.canonicalJson());
    value.put("accountId", event.accountId());
    value.put("membershipAuthorityGeneration", event.membershipAuthorityGeneration());
    value.put("membershipVersion", event.membershipVersion().get(event.tenantId()));
    return decode(
        canonical(value), UUID.fromString(event.accountId()), UUID.fromString(event.tenantId()));
  }

  /** Strict, no-alias parsing; the complete independently verified event is retained unchanged. */
  public static AccountSelectedGameplayAuthorityProjection decode(
      byte[] bytes, UUID accountId, UUID tenantId) {
    if (bytes == null
        || bytes.length == 0
        || bytes.length > AccountGameplayDelegationAuthorityProjection.MAX_PROJECTION_BYTES)
      throw invalid();
    try {
      String json =
          StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      Map<String, Object> fields = JSON.readValue(json, new TypeReference<>() {});
      boolean member = accountId != null;
      Set<String> expected =
          member
              ? Set.of(
                  "schemaVersion",
                  "accountId",
                  "tenantId",
                  "membershipAuthorityGeneration",
                  "membershipVersion",
                  "sourceVersion",
                  "outboxStreamKey",
                  "outboxSequence",
                  "sourceEvent")
              : Set.of(
                  "schemaVersion",
                  "tenantId",
                  "tenantAuthorityGeneration",
                  "sourceVersion",
                  "outboxStreamKey",
                  "outboxSequence",
                  "sourceEvent");
      if (!fields.keySet().equals(expected)
          || !Arrays.equals(bytes, canonical(fields))
          || !(member ? MEMBERSHIP_SCHEMA : TENANT_SCHEMA).equals(fields.get("schemaVersion"))
          || !tenantId.toString().equals(fields.get("tenantId"))) throw invalid();
      positive(fields, "sourceVersion");
      positive(fields, "outboxSequence");
      if (member) {
        positive(fields, "membershipAuthorityGeneration");
        positive(fields, "membershipVersion");
        var event = MembershipAuthorityEventV1Codec.verify(text(fields, "sourceEvent"));
        if (!accountId.toString().equals(fields.get("accountId"))
            || !event.canonicalJson().equals(fields.get("sourceEvent"))
            || !event.accountId().equals(fields.get("accountId"))
            || !event.tenantId().equals(fields.get("tenantId"))
            || !event
                .membershipAuthorityGeneration()
                .equals(fields.get("membershipAuthorityGeneration"))
            || !event
                .membershipVersion()
                .get(event.tenantId())
                .equals(fields.get("membershipVersion"))
            || !event.outboxStreamKey().equals(fields.get("outboxStreamKey"))
            || !event.outboxSequence().equals(fields.get("outboxSequence"))) throw invalid();
      } else {
        positive(fields, "tenantAuthorityGeneration");
        var event =
            TenantAuthorityEventV1Codec.verify(
                text(fields, "sourceEvent").getBytes(StandardCharsets.UTF_8));
        if (!event.tenantId().equals(tenantId)
            || !Long.toString(event.tenantAuthorityGeneration())
                .equals(fields.get("tenantAuthorityGeneration"))
            || !Long.toString(event.tenantAuthoritySourceVersion())
                .equals(fields.get("sourceVersion"))
            || !event.outboxStreamKey().equals(fields.get("outboxStreamKey"))
            || !Long.toString(event.outboxSequence()).equals(fields.get("outboxSequence")))
          throw invalid();
      }
      return new AccountSelectedGameplayAuthorityProjection(fields);
    } catch (Exception failure) {
      throw invalid();
    }
  }

  public String key() {
    return fields.containsKey("accountId")
        ? MEMBERSHIP_PREFIX + fields.get("accountId") + ":" + fields.get("tenantId")
        : TENANT_PREFIX + fields.get("tenantId");
  }

  public byte[] canonicalBytes() {
    return bytes.clone();
  }

  public BigInteger sequence() {
    return positive(fields, "outboxSequence");
  }

  public BigInteger sourceVersion() {
    return positive(fields, "sourceVersion");
  }

  public BigInteger generation() {
    return positive(
        fields,
        fields.containsKey("accountId")
            ? "membershipAuthorityGeneration"
            : "tenantAuthorityGeneration");
  }

  public BigInteger membershipVersion() {
    return positive(fields, "membershipVersion");
  }

  /** A source gap is not healed by installing an arbitrary latest-head value. */
  public void requireSuccessorOf(AccountSelectedGameplayAuthorityProjection prior) {
    if (prior == null) {
      if (!sequence().equals(BigInteger.ONE)) throw invalid();
      return;
    }
    if (!key().equals(prior.key())) throw invalid();
    if (Arrays.equals(bytes, prior.bytes)) return;
    if (!sequence().equals(prior.sequence().add(BigInteger.ONE))
        || generation().compareTo(prior.generation()) < 0
        || sourceVersion().compareTo(prior.sourceVersion()) < 0
        || (fields.containsKey("accountId")
            && membershipVersion().compareTo(prior.membershipVersion()) <= 0)) throw invalid();
  }

  private static Map<String, Object> base(
      String schema, String tenant, String source, String stream, String sequence, String event) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schemaVersion", schema);
    value.put("tenantId", tenant);
    value.put("sourceVersion", source);
    value.put("outboxStreamKey", stream);
    value.put("outboxSequence", sequence);
    value.put("sourceEvent", event);
    return value;
  }

  private static BigInteger positive(Map<String, Object> fields, String key) {
    String value = text(fields, key);
    if (!value.matches("[1-9][0-9]*")) throw invalid();
    return new BigInteger(value);
  }

  private static String text(Map<String, Object> fields, String key) {
    if (!(fields.get(key) instanceof String value)) throw invalid();
    return value;
  }

  private static byte[] canonical(Map<String, Object> fields) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Selected Account authority projection is unavailable or contradictory");
  }
}
