package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.RealmGrantAuthorityEventV1Codec.Event;
import net.firedevops.firemud.common.account.authority.RealmGrantAuthorityEventV1Codec.GrantPreimage;
import org.junit.jupiter.api.Test;

class RealmGrantAuthorityEventV1CodecTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID LIFECYCLE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID GENERATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID ACTOR_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID OTHER_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final GrantAuthorityScopeCodec.Scope GRANT =
      new GrantAuthorityScopeCodec.Scope(
          ACCOUNT_ID, TENANT_ID, "verdant", "moon-keep", LIFECYCLE_ID);
  private static final Instant EXPIRED_AT = Instant.parse("1970-01-01T00:00:00Z");
  private static final String MUTATION_DIGEST =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String AUDIT_REASON = "approved for private testing";
  private static final GrantPreimage PREIMAGE =
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7);

  @Test
  void sealsAndVerifiesIndependentCanonicalGoldenStructure() throws Exception {
    Event sealed = RealmGrantAuthorityEventV1Codec.seal(PREIMAGE);
    String expectedPreimage = expectedPreimageJson(GRANT);
    String expectedDigest = independentDigest(expectedPreimage);
    String expectedPayload = wireJson(expectedPreimage, expectedDigest);

    assertThat(sealed.preimage()).isEqualTo(PREIMAGE);
    assertThat(sealed.eventId()).isEqualTo("realm-grant-event:v1:" + REQUEST_ID.toString());
    assertThat(sealed.eventDigest()).isEqualTo(expectedDigest);
    assertThat(new String(sealed.canonicalPreimage(), StandardCharsets.UTF_8))
        .isEqualTo(expectedPreimage);
    assertThat(new String(sealed.payload(), StandardCharsets.UTF_8)).isEqualTo(expectedPayload);

    Event verified =
        RealmGrantAuthorityEventV1Codec.verify(expectedPayload.getBytes(StandardCharsets.UTF_8));
    assertThat(verified.preimage()).isEqualTo(PREIMAGE);
    assertThat(verified.eventId()).isEqualTo(sealed.eventId());
    assertThat(verified.eventDigest()).isEqualTo(expectedDigest);
    assertThat(verified.canonicalPreimage()).containsExactly(sealed.canonicalPreimage());
    assertThat(verified.payload()).containsExactly(sealed.payload());
  }

  @Test
  void preservesIndependentPositiveRevisionAndSequenceValues() {
    Event event = RealmGrantAuthorityEventV1Codec.seal(PREIMAGE);

    assertThat(event.preimage().grantRevision()).isEqualTo(2);
    assertThat(event.preimage().outboxSequence()).isEqualTo(7);
    assertThat(event.preimage().grantRevision()).isNotEqualTo(event.preimage().outboxSequence());
    assertThat(RealmGrantAuthorityEventV1Codec.verify(event.payload()).preimage())
        .isEqualTo(PREIMAGE);
  }

  @Test
  void changesToIndependentOwnerFieldsChangeTheEventDigest() {
    String originalDigest = RealmGrantAuthorityEventV1Codec.seal(PREIMAGE).eventDigest();
    GrantPreimage[] changed = {
      preimage(
          OTHER_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          new GrantAuthorityScopeCodec.Scope(
              OTHER_ID, TENANT_ID, "verdant", "moon-keep", LIFECYCLE_ID),
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          new GrantAuthorityScopeCodec.Scope(
              ACCOUNT_ID, OTHER_ID, "verdant", "moon-keep", LIFECYCLE_ID),
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          new GrantAuthorityScopeCodec.Scope(
              ACCOUNT_ID, TENANT_ID, "verdant-2", "moon-keep", LIFECYCLE_ID),
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          new GrantAuthorityScopeCodec.Scope(
              ACCOUNT_ID, TENANT_ID, "verdant", "moon-keep-2", LIFECYCLE_ID),
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          new GrantAuthorityScopeCodec.Scope(
              ACCOUNT_ID, TENANT_ID, "verdant", "moon-keep", OTHER_ID),
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          Instant.parse("1971-01-01T00:00:00Z"),
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          3,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          OTHER_ID,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          OTHER_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "EXTEND",
          MUTATION_DIGEST,
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          "f".repeat(64),
          AUDIT_REASON,
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          "approved for another tester",
          7),
      preimage(
          REQUEST_ID,
          GRANT,
          2,
          GENERATION,
          "ACTIVE",
          EXPIRED_AT,
          ACTOR_ID,
          "CREATE",
          MUTATION_DIGEST,
          AUDIT_REASON,
          8)
    };

    for (GrantPreimage changedPreimage : changed) {
      assertThat(RealmGrantAuthorityEventV1Codec.seal(changedPreimage).eventDigest())
          .isNotEqualTo(originalDigest);
    }

    GrantPreimage revoked =
        preimage(
            REQUEST_ID,
            GRANT,
            2,
            GENERATION,
            "REVOKED",
            EXPIRED_AT,
            ACTOR_ID,
            "REVOKE",
            MUTATION_DIGEST,
            AUDIT_REASON,
            7);
    assertThat(RealmGrantAuthorityEventV1Codec.seal(revoked).eventDigest())
        .isNotEqualTo(originalDigest);
  }

  @Test
  void usesSharedScopeCodecAndRejectsContradictoryIdentityProjections() {
    Event event = RealmGrantAuthorityEventV1Codec.seal(PREIMAGE);
    String wire = new String(event.payload(), StandardCharsets.UTF_8);
    String expectedSource =
        GrantAuthorityScopeCodec.sourceScope(GRANT, GrantAuthorityScopeCodec.V1_BOUNDS);
    String expectedStream =
        GrantAuthorityScopeCodec.outboxStreamKey(GRANT, GrantAuthorityScopeCodec.V1_BOUNDS);

    assertRejected(
        wire.replace(
                "\"sourceScope\":\"" + expectedSource + "\"",
                "\"sourceScope\":\"grant/v1/contradiction\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        wire.replace(
                "\"outboxStreamKey\":\"" + expectedStream + "\"",
                "\"outboxStreamKey\":\"account:auth-authority:v1:wrong\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        wire.replace("\"accountId\":\"" + ACCOUNT_ID + "\"", "\"accountId\":\"" + OTHER_ID + "\"")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsMismatchedEventIdAndDigest() {
    String wire =
        new String(
            RealmGrantAuthorityEventV1Codec.seal(PREIMAGE).payload(), StandardCharsets.UTF_8);

    assertRejected(replaceField(wire, "eventId", "wrong-event-id"));
    assertRejected(replaceField(wire, "requestId", OTHER_ID.toString()));
    assertRejected(replaceField(wire, "eventDigest", "sha256:" + "0".repeat(64)));
  }

  @Test
  void rejectsUnknownDuplicateTrailingOversizedAndMalformedWireBytes() {
    String wire =
        new String(
            RealmGrantAuthorityEventV1Codec.seal(PREIMAGE).payload(), StandardCharsets.UTF_8);
    String unknown = wire.substring(0, wire.length() - 1) + ",\"future\":true}";
    String duplicate = wire.replaceFirst("\\{", "{\"accountId\":\"" + ACCOUNT_ID + "\",");
    String missing = wire.replace("\"auditReason\":\"" + AUDIT_REASON + "\",", "");
    byte[] malformedUtf8 = new byte[] {'{', (byte) 0xff};

    assertRejected(unknown.getBytes(StandardCharsets.UTF_8));
    assertRejected(duplicate.getBytes(StandardCharsets.UTF_8));
    assertRejected(missing.getBytes(StandardCharsets.UTF_8));
    assertRejected((wire + "{}").getBytes(StandardCharsets.UTF_8));
    assertRejected((wire + " ").getBytes(StandardCharsets.UTF_8));
    assertRejected(malformedUtf8);
    assertRejected(new byte[RealmGrantAuthorityEventV1Codec.MAX_EVENT_PAYLOAD_BYTES + 1]);
  }

  @Test
  void rejectsWrongValueTypesAndContradictoryStateAndMutationKind() {
    String wire =
        new String(
            RealmGrantAuthorityEventV1Codec.seal(PREIMAGE).payload(), StandardCharsets.UTF_8);

    assertRejected(
        wire.replace("\"outboxSequence\":\"7\"", "\"outboxSequence\":7")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        wire.replace("\"grantRevision\":\"2\"", "\"grantRevision\":\"9223372036854775808\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        wire.replace(
                "\"schemaVersion\":\"account-auth-authority-event/v1\"",
                "\"schemaVersion\":\"account-auth-authority-event/v2\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(replaceField(wire, "grantState", "REVOKED"));
  }

  @Test
  void rejectsNoncanonicalInstantTextEvenWhenItsDigestMatches() throws Exception {
    String noncanonicalPreimage =
        expectedPreimageJson(GRANT)
            .replace(
                "\"grantExpiresAt\":\"1970-01-01T00:00:00Z\"",
                "\"grantExpiresAt\":\"1970-01-01T00:00:00.000Z\"");
    String wire = wireJson(noncanonicalPreimage, independentDigest(noncanonicalPreimage));

    assertRejected(wire.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void acceptsNfcReasonAtItsUtf8LimitAndRejectsOverLimitOrNonNfcReason() {
    String maximumReason = String.valueOf((char) 0).repeat(4096);
    GrantPreimage atLimit =
        preimage(
            REQUEST_ID,
            GRANT,
            2,
            GENERATION,
            "ACTIVE",
            EXPIRED_AT,
            ACTOR_ID,
            "CREATE",
            MUTATION_DIGEST,
            maximumReason,
            7);
    assertThat(
            RealmGrantAuthorityEventV1Codec.verify(
                    RealmGrantAuthorityEventV1Codec.seal(atLimit).payload())
                .preimage()
                .auditReason())
        .isEqualTo(maximumReason);

    String multibyteAtLimit = "é".repeat(2048);
    GrantPreimage multibyteReason =
        preimage(
            REQUEST_ID,
            GRANT,
            2,
            GENERATION,
            "ACTIVE",
            EXPIRED_AT,
            ACTOR_ID,
            "CREATE",
            MUTATION_DIGEST,
            multibyteAtLimit,
            7);
    assertThat(
            RealmGrantAuthorityEventV1Codec.verify(
                    RealmGrantAuthorityEventV1Codec.seal(multibyteReason).payload())
                .preimage()
                .auditReason())
        .isEqualTo(multibyteAtLimit);

    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    String.valueOf((char) 0).repeat(4097),
                    7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4096 UTF-8 bytes");
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    "é".repeat(2049),
                    7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4096 UTF-8 bytes");
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    "cafe" + (char) 0x0301,
                    7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NFC");
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    String.valueOf((char) 0x0085),
                    7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void rejectsInvalidCountersStatesMutationsDigestsAndNilIdentities() {
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    0,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "UNKNOWN",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "DELETE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    "sha256:" + MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    new UUID(0L, 0L),
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "ACTIVE",
                    EXPIRED_AT,
                    new UUID(0L, 0L),
                    "CREATE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                preimage(
                    REQUEST_ID,
                    GRANT,
                    2,
                    GENERATION,
                    "REVOKED",
                    EXPIRED_AT,
                    ACTOR_ID,
                    "CREATE",
                    MUTATION_DIGEST,
                    AUDIT_REASON,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void returnsDefensiveCopiesOfPreimageAndWireBytes() {
    byte[] input = RealmGrantAuthorityEventV1Codec.seal(PREIMAGE).payload();
    Event event = RealmGrantAuthorityEventV1Codec.verify(input);
    byte[] firstPayload = event.payload();
    byte[] firstPreimage = event.canonicalPreimage();
    firstPayload[0] = (byte) 'x';
    firstPreimage[0] = (byte) 'x';
    input[0] = (byte) 'x';

    assertThat(event.payload()[0]).isNotEqualTo((byte) 'x');
    assertThat(event.canonicalPreimage()[0]).isNotEqualTo((byte) 'x');
    assertThat(event.preimage()).isEqualTo(PREIMAGE);
  }

  private static GrantPreimage preimage(
      UUID requestId,
      GrantAuthorityScopeCodec.Scope grant,
      long revision,
      UUID generation,
      String state,
      Instant expiry,
      UUID actor,
      String mutation,
      String digest,
      String reason,
      long sequence) {
    return new GrantPreimage(
        requestId,
        grant,
        revision,
        generation,
        state,
        expiry,
        actor,
        mutation,
        digest,
        reason,
        sequence);
  }

  private static String expectedPreimageJson(GrantAuthorityScopeCodec.Scope grant) {
    String sourceScope =
        GrantAuthorityScopeCodec.sourceScope(grant, GrantAuthorityScopeCodec.V1_BOUNDS);
    String outboxStreamKey =
        GrantAuthorityScopeCodec.outboxStreamKey(grant, GrantAuthorityScopeCodec.V1_BOUNDS);
    return "{\"accountId\":\""
        + grant.accountId()
        + "\",\"actorAccountId\":\""
        + ACTOR_ID
        + "\",\"auditReason\":\""
        + AUDIT_REASON
        + "\",\"eventId\":\"realm-grant-event:v1:"
        + REQUEST_ID
        + "\",\"eventType\":\"REALM_GRANT_CHANGED\",\"grantAuthorityGeneration\":\""
        + GENERATION
        + "\",\"grantExpiresAt\":\"1970-01-01T00:00:00Z\",\"grantRevision\":\"2\","
        + "\"grantState\":\"ACTIVE\",\"mutationDigest\":\""
        + MUTATION_DIGEST
        + "\",\"mutationKind\":\"CREATE\",\"outboxSequence\":\"7\","
        + "\"outboxStreamKey\":\""
        + outboxStreamKey
        + "\",\"playtestLifecycleId\":\""
        + grant.playtestLifecycleId()
        + "\",\"realmSlug\":\""
        + grant.realmSlug()
        + "\",\"requestId\":\""
        + REQUEST_ID
        + "\",\"schemaVersion\":\"account-auth-authority-event/v1\",\"sourceScope\":\""
        + sourceScope
        + "\",\"tenantId\":\""
        + grant.tenantId()
        + "\",\"worldSlug\":\""
        + grant.worldSlug()
        + "\"}";
  }

  private static String wireJson(String canonicalPreimage, String eventDigest) {
    return canonicalPreimage.replace(
        "\"eventId\":\"", "\"eventDigest\":\"" + eventDigest + "\",\"eventId\":\"");
  }

  private static String independentDigest(String canonicalPreimage) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalPreimage.getBytes(StandardCharsets.UTF_8));
    return "sha256:" + HexFormat.of().formatHex(digest);
  }

  private static byte[] replaceField(String wire, String field, String value) {
    String marker = "\"" + field + "\":\"";
    int start = wire.indexOf(marker);
    if (start < 0) {
      throw new IllegalArgumentException("Missing expected fixture field " + field);
    }
    int valueStart = start + marker.length();
    int end = wire.indexOf('"', valueStart);
    String changed = wire.substring(0, valueStart) + value + wire.substring(end);
    return changed.getBytes(StandardCharsets.UTF_8);
  }

  private static void assertRejected(byte[] payload) {
    assertThatThrownBy(() -> RealmGrantAuthorityEventV1Codec.verify(payload))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
