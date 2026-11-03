package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GrantAuthorityScopeCodecTest {
  private static final String BASE64URL_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID LIFECYCLE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final GrantAuthorityScopeCodec.Scope GOLDEN_SCOPE =
      new GrantAuthorityScopeCodec.Scope(
          ACCOUNT_ID, TENANT_ID, "verdant", "moon-keep", LIFECYCLE_ID);
  private static final byte[] GOLDEN_SCOPE_BYTES =
      ("13:grantScope/v1"
              + "36:11111111-1111-4111-8111-111111111111"
              + "36:22222222-2222-4222-8222-222222222222"
              + "7:verdant"
              + "9:moon-keep"
              + "36:33333333-3333-4333-8333-333333333333")
          .getBytes(StandardCharsets.US_ASCII);

  @Test
  void encodesFixedGoldenBytesAndProjectsIdenticalSourceAndStreamScopes() {
    String expectedScopeId =
        GrantAuthorityScopeCodec.SCOPE_ID_PREFIX
            + Base64.getUrlEncoder().withoutPadding().encodeToString(GOLDEN_SCOPE_BYTES);
    String expectedOutboxStreamKey =
        GrantAuthorityScopeCodec.OUTBOX_STREAM_PREFIX + expectedScopeId;

    assertThat(GrantAuthorityScopeCodec.scopeId(GOLDEN_SCOPE, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(expectedScopeId);
    assertThat(
            GrantAuthorityScopeCodec.sourceScope(GOLDEN_SCOPE, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(expectedScopeId);
    assertThat(
            GrantAuthorityScopeCodec.outboxStreamKey(
                GOLDEN_SCOPE, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(expectedOutboxStreamKey);
    assertThat(
            Base64.getUrlDecoder()
                .decode(
                    expectedScopeId.substring(GrantAuthorityScopeCodec.SCOPE_ID_PREFIX.length())))
        .containsExactly(GOLDEN_SCOPE_BYTES);
    assertThat(
            GrantAuthorityScopeCodec.parseScopeId(
                expectedScopeId, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(GOLDEN_SCOPE);
    assertThat(
            GrantAuthorityScopeCodec.parseOutboxStreamKey(
                expectedOutboxStreamKey, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(GOLDEN_SCOPE);
  }

  @Test
  void acceptsTheCompleteMaximumOwnerVectorAndDerivesBoundedKeys() {
    GrantAuthorityScopeCodec.Scope maximumScope =
        new GrantAuthorityScopeCodec.Scope(
            ACCOUNT_ID, TENANT_ID, "a".repeat(120), "z".repeat(120), LIFECYCLE_ID);

    String scopeId =
        GrantAuthorityScopeCodec.scopeId(maximumScope, GrantAuthorityScopeCodec.V1_BOUNDS);
    String outboxStreamKey =
        GrantAuthorityScopeCodec.outboxStreamKey(maximumScope, GrantAuthorityScopeCodec.V1_BOUNDS);

    assertThat(scopeId.length()).isEqualTo(517);
    assertThat(outboxStreamKey.length()).isEqualTo(543);
    assertThat(GrantAuthorityScopeCodec.parseScopeId(scopeId, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(maximumScope);
    assertThat(
            GrantAuthorityScopeCodec.parseOutboxStreamKey(
                outboxStreamKey, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(maximumScope);
  }

  @Test
  void changingAnyOwnerFieldChangesTheScopeAndStreamIdentity() {
    String original =
        GrantAuthorityScopeCodec.scopeId(GOLDEN_SCOPE, GrantAuthorityScopeCodec.V1_BOUNDS);
    GrantAuthorityScopeCodec.Scope[] changedScopes = {
      new GrantAuthorityScopeCodec.Scope(OTHER_ID, TENANT_ID, "verdant", "moon-keep", LIFECYCLE_ID),
      new GrantAuthorityScopeCodec.Scope(
          ACCOUNT_ID, OTHER_ID, "verdant", "moon-keep", LIFECYCLE_ID),
      new GrantAuthorityScopeCodec.Scope(
          ACCOUNT_ID, TENANT_ID, "verdant-2", "moon-keep", LIFECYCLE_ID),
      new GrantAuthorityScopeCodec.Scope(
          ACCOUNT_ID, TENANT_ID, "verdant", "moon-keep-2", LIFECYCLE_ID),
      new GrantAuthorityScopeCodec.Scope(ACCOUNT_ID, TENANT_ID, "verdant", "moon-keep", OTHER_ID)
    };

    for (GrantAuthorityScopeCodec.Scope changed : changedScopes) {
      String changedId =
          GrantAuthorityScopeCodec.scopeId(changed, GrantAuthorityScopeCodec.V1_BOUNDS);
      assertThat(changedId).isNotEqualTo(original);
      assertThat(
              GrantAuthorityScopeCodec.outboxStreamKey(changed, GrantAuthorityScopeCodec.V1_BOUNDS))
          .isNotEqualTo(
              GrantAuthorityScopeCodec.outboxStreamKey(
                  GOLDEN_SCOPE, GrantAuthorityScopeCodec.V1_BOUNDS));
    }
  }

  @Test
  void rejectsMissingContradictoryAndNonpositiveOwnerBounds() {
    assertThatThrownBy(() -> new GrantAuthorityScopeCodec.OwnerBounds(0, 36, 120, 120, 36))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GrantAuthorityScopeCodec.OwnerBounds(-1, 36, 120, 120, 36))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GrantAuthorityScopeCodec.OwnerBounds(35, 36, 120, 120, 36))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new GrantAuthorityScopeCodec.OwnerBounds(Integer.MAX_VALUE, 36, 120, 120, 36))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GrantAuthorityScopeCodec.scopeId(GOLDEN_SCOPE, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> GrantAuthorityScopeCodec.scopeId(null, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownVersionsPaddingAlternateBase64AndOversizedKeys() {
    GrantAuthorityScopeCodec.Scope shortScope =
        new GrantAuthorityScopeCodec.Scope(ACCOUNT_ID, TENANT_ID, "a", "b", LIFECYCLE_ID);
    String shortId =
        GrantAuthorityScopeCodec.scopeId(shortScope, GrantAuthorityScopeCodec.V1_BOUNDS);
    String shortPayload = shortId.substring(GrantAuthorityScopeCodec.SCOPE_ID_PREFIX.length());
    String padded = shortId + "=";
    String alternate = alterUnusedFinalBase64Bits(shortId);

    assertThat(shortPayload.length() % 4).isEqualTo(2);
    assertRejectedScopeId("grant/v2/" + shortPayload);
    assertRejectedScopeId(padded);
    assertRejectedScopeId(alternate);
    assertRejectedScopeId(GrantAuthorityScopeCodec.SCOPE_ID_PREFIX + "A".repeat(509));
    assertRejectedOutboxKey(GrantAuthorityScopeCodec.OUTBOX_STREAM_PREFIX + shortId + "=");
    assertRejectedOutboxKey(GrantAuthorityScopeCodec.OUTBOX_STREAM_PREFIX + "a".repeat(518));
  }

  @Test
  void rejectsMalformedLengthsMissingFieldsTrailingBytesAndUnknownMarkers() {
    String golden = new String(GOLDEN_SCOPE_BYTES, StandardCharsets.US_ASCII);

    assertRejectedRaw(golden.replace("7:verdant", "07:verdant"));
    assertRejectedRaw(golden.replace("7:verdant", "0:verdant"));
    assertRejectedRaw(golden.replace("7:verdant", "-1:verdant"));
    assertRejectedRaw(golden.replace("7:verdant", "7verdant"));
    assertRejectedRaw(golden.replace("13:grantScope/v1", "13:grantScope/v2"));
    assertRejectedRaw(golden.substring(0, golden.lastIndexOf("36:33333333-")));
    assertRejectedRaw(golden + "x");

    String overLimitBeforeBytes =
        "13:grantScope/v1"
            + "36:11111111-1111-4111-8111-111111111111"
            + "36:22222222-2222-4222-8222-222222222222"
            + "121:";
    assertRejectedRaw(overLimitBeforeBytes);
    assertRejectedRaw(
        "13:grantScope/v1"
            + "36:11111111-1111-4111-8111-111111111111"
            + "36:22222222-2222-4222-8222-222222222222"
            + "999999999999999999999999999999999999:");
  }

  @Test
  void rejectsInvalidUtf8AndLifecycleOmission() {
    byte[][] invalidUtf8Fields = {
      ascii("grantScope/v1"),
      ascii(ACCOUNT_ID.toString()),
      ascii(TENANT_ID.toString()),
      {(byte) 0xc3},
      ascii("moon-keep"),
      ascii(LIFECYCLE_ID.toString())
    };
    byte[][] fourFieldLegacyScope = {
      ascii("grantScope/v1"),
      ascii(ACCOUNT_ID.toString()),
      ascii(TENANT_ID.toString()),
      ascii("verdant"),
      ascii("moon-keep")
    };

    assertRejectedRaw(rawSegments(invalidUtf8Fields));
    assertRejectedRaw(rawSegments(fourFieldLegacyScope));
  }

  @Test
  void requiresCanonicalUuidTextAtExactlyThirtySixBytes() {
    String account = ACCOUNT_ID.toString();
    String tenant = TENANT_ID.toString();
    String lifecycle = LIFECYCLE_ID.toString();
    String[] exactFields = {account, tenant, "verdant", "moon-keep", lifecycle};
    int[] uuidFieldIndexes = {0, 1, 4};

    assertThat(
            GrantAuthorityScopeCodec.parseScopeId(
                scopeIdFromRaw(rawScope(exactFields)), GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(GOLDEN_SCOPE);
    for (int fieldIndex : uuidFieldIndexes) {
      String[][] variants = {
        {account, tenant, "verdant", "moon-keep", lifecycle},
        {account, tenant, "verdant", "moon-keep", lifecycle}
      };
      variants[0][fieldIndex] = variants[0][fieldIndex].substring(1);
      variants[1][fieldIndex] = variants[1][fieldIndex] + "a";
      assertRejectedRaw(rawScope(variants[0]));
      assertRejectedRaw(rawScope(variants[1]));
    }
    assertRejectedRaw(
        rawScope(
            new String[] {
              "00000000-0000-0000-0000-000000000000", tenant, "verdant", "moon-keep", lifecycle
            }));
    assertRejectedRaw(
        rawScope(
            new String[] {
              account, "00000000-0000-0000-0000-000000000000", "verdant", "moon-keep", lifecycle
            }));
    assertRejectedRaw(
        rawScope(
            new String[] {
              account, tenant, "verdant", "moon-keep", "00000000-0000-0000-0000-000000000000"
            }));
    assertRejectedRaw(
        rawScope(
            new String[] {
              "ABCDEF01-1111-4111-8111-111111111111", tenant, "verdant", "moon-keep", lifecycle
            }));
  }

  @Test
  void rejectsSlugNormalizationAndAcceptsOneByteSlugs() {
    GrantAuthorityScopeCodec.Scope minimumScope =
        new GrantAuthorityScopeCodec.Scope(ACCOUNT_ID, TENANT_ID, "a", "z", LIFECYCLE_ID);
    assertThat(
            GrantAuthorityScopeCodec.parseScopeId(
                GrantAuthorityScopeCodec.scopeId(minimumScope, GrantAuthorityScopeCodec.V1_BOUNDS),
                GrantAuthorityScopeCodec.V1_BOUNDS))
        .isEqualTo(minimumScope);

    for (String invalidSlug : new String[] {"", "World", " word", "word ", "é", "e\u0301"}) {
      assertThatThrownBy(
              () ->
                  new GrantAuthorityScopeCodec.Scope(
                      ACCOUNT_ID, TENANT_ID, invalidSlug, "realm", LIFECYCLE_ID))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new GrantAuthorityScopeCodec.Scope(
                    ACCOUNT_ID, TENANT_ID, "a".repeat(121), "realm", LIFECYCLE_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrantAuthorityScopeCodec.Scope(
                    ACCOUNT_ID, TENANT_ID, "world", "z".repeat(121), LIFECYCLE_ID))
        .isInstanceOf(IllegalArgumentException.class);

    assertRejectedRaw(
        rawScope(
            new String[] {
              ACCOUNT_ID.toString(), TENANT_ID.toString(), "World", "realm", LIFECYCLE_ID.toString()
            }));
    assertRejectedRaw(
        rawScope(
            new String[] {
              ACCOUNT_ID.toString(), TENANT_ID.toString(), "world", "Realm", LIFECYCLE_ID.toString()
            }));
    assertRejectedRaw(
        rawScope(
            new String[] {
              ACCOUNT_ID.toString(),
              TENANT_ID.toString(),
              "a".repeat(121),
              "realm",
              LIFECYCLE_ID.toString()
            }));
    assertRejectedRaw(
        rawScope(
            new String[] {
              ACCOUNT_ID.toString(),
              TENANT_ID.toString(),
              "world",
              "z".repeat(121),
              LIFECYCLE_ID.toString()
            }));
  }

  private static String alterUnusedFinalBase64Bits(String scopeId) {
    int payloadStart = GrantAuthorityScopeCodec.SCOPE_ID_PREFIX.length();
    String payload = scopeId.substring(payloadStart);
    int lastIndex = BASE64URL_ALPHABET.indexOf(payload.charAt(payload.length() - 1));
    int upperBits = lastIndex & 0b110000;
    int lowerBits = lastIndex & 0b001111;
    int alternateLowerBits = lowerBits == 0 ? 1 : 0;
    char alternateCharacter = BASE64URL_ALPHABET.charAt(upperBits | alternateLowerBits);
    return scopeId.substring(0, scopeId.length() - 1) + alternateCharacter;
  }

  private static void assertRejectedScopeId(String scopeId) {
    assertThatThrownBy(
            () ->
                GrantAuthorityScopeCodec.parseScopeId(scopeId, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertRejectedOutboxKey(String key) {
    assertThatThrownBy(
            () ->
                GrantAuthorityScopeCodec.parseOutboxStreamKey(
                    key, GrantAuthorityScopeCodec.V1_BOUNDS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertRejectedRaw(String encodedSegments) {
    assertRejectedRaw(encodedSegments.getBytes(StandardCharsets.US_ASCII));
  }

  private static void assertRejectedRaw(byte[] encodedSegments) {
    assertRejectedScopeId(scopeIdFromRaw(encodedSegments));
  }

  private static String scopeIdFromRaw(byte[] encodedSegments) {
    return GrantAuthorityScopeCodec.SCOPE_ID_PREFIX
        + Base64.getUrlEncoder().withoutPadding().encodeToString(encodedSegments);
  }

  private static byte[] rawScope(String[] ownerFields) {
    byte[][] fields = new byte[ownerFields.length + 1][];
    fields[0] = ascii("grantScope/v1");
    for (int index = 0; index < ownerFields.length; index++) {
      fields[index + 1] = ascii(ownerFields[index]);
    }
    return rawSegments(fields);
  }

  private static byte[] rawSegments(byte[][] segments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (byte[] segment : segments) {
      byte[] length = Integer.toString(segment.length).getBytes(StandardCharsets.US_ASCII);
      output.write(length, 0, length.length);
      output.write(':');
      output.write(segment, 0, segment.length);
    }
    return output.toByteArray();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
