package net.firedevops.firemud.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RateLimitSubjectHashTest {
  private static final RateLimitSubjectHash.HmacKey KEY =
      new RateLimitSubjectHash.HmacKey(
          "test-1",
          new byte[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
          });

  @Test
  void hmacSha256MatchesRfc4231GoldenVector() {
    byte[] key = new byte[20];
    Arrays.fill(key, (byte) 0x0b);

    byte[] result =
        RateLimitSubjectHash.hmacSha256(key, "Hi There".getBytes(StandardCharsets.US_ASCII));

    assertEquals(
        "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", lowerHex(result));
  }

  @Test
  void platformAuthPreimageMatchesTheFramedGoldenVector() {
    byte[] expected =
        concat(
            ascii("27:FireMUD/rateLimitSubject/v1"),
            ascii("6:test-1"),
            ascii("14:client-address"),
            ascii("0:"),
            ascii("28:platform-auth/source-attempt"),
            ascii("4:"),
            new byte[] {(byte) 203, 0, 113, 9});

    byte[] actual =
        RateLimitSubjectHash.framedPreimage(
            "FireMUD/rateLimitSubject/v1",
            "test-1",
            "client-address",
            "",
            "platform-auth/source-attempt",
            new byte[] {(byte) 203, 0, 113, 9});

    assertArrayEquals(expected, actual);
  }

  @Test
  void subjectAndCollisionDigestsAreFullLengthAndDomainSeparated() {
    var source =
        RateLimitSubjectHash.platformAuthSubject(
            KEY,
            RateLimitSubjectHash.PlatformAuthPolicy.SOURCE_ATTEMPT,
            RateLimitSubjectHash.canonicalClientAddressBytes("203.0.113.9"));
    var failure =
        RateLimitSubjectHash.platformAuthSubject(
            KEY,
            RateLimitSubjectHash.PlatformAuthPolicy.SOURCE_FAILURE,
            RateLimitSubjectHash.canonicalClientAddressBytes("203.0.113.9"));

    assertTrue(source.subjectHash().matches("rsh-v1-test-1-[0-9a-f]{64}"));
    assertTrue(source.collisionFingerprint().matches("rsc-v1-test-1-[0-9a-f]{64}"));
    assertNotEquals(source.subjectHash(), source.collisionFingerprint());
    assertNotEquals(source.subjectHash(), failure.subjectHash());
    assertNotEquals(source.collisionFingerprint(), failure.collisionFingerprint());
  }

  @Test
  void activeKeyRotationChangesOpaqueSubjectAndCollisionDigests() {
    var subject = RateLimitSubjectHash.canonicalClientAddressBytes("203.0.113.9");
    var first =
        RateLimitSubjectHash.platformAuthSubject(
            KEY, RateLimitSubjectHash.PlatformAuthPolicy.SOURCE_ATTEMPT, subject);
    var rotated =
        RateLimitSubjectHash.platformAuthSubject(
            new RateLimitSubjectHash.HmacKey(
                "test-2",
                new byte[] {
                  0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                  16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 32
                }),
            RateLimitSubjectHash.PlatformAuthPolicy.SOURCE_ATTEMPT,
            subject);

    assertNotEquals(first.subjectHash(), rotated.subjectHash());
    assertNotEquals(first.collisionFingerprint(), rotated.collisionFingerprint());
    assertTrue(RateLimitSubjectHash.platformAuthBucketKey(first, 17L).contains(":rsh-v1-test-1-"));
    assertTrue(
        RateLimitSubjectHash.platformAuthBucketKey(rotated, 17L).contains(":rsh-v1-test-2-"));
  }

  @Test
  void ipv4MappedIpv6AndIpv4HaveTheSameCanonicalNetworkBytes() {
    assertArrayEquals(
        RateLimitSubjectHash.canonicalClientAddressBytes("192.0.2.44"),
        RateLimitSubjectHash.canonicalClientAddressBytes("0:0:0:0:0:ffff:c000:22c"));
    assertArrayEquals(
        RateLimitSubjectHash.canonicalClientAddressBytes("192.0.2.44"),
        RateLimitSubjectHash.canonicalClientAddressBytes("::ffff:192.0.2.44"));
  }

  @Test
  void literalParserRejectsDnsNamesAndNonCanonicalWireAddressesWithoutResolution() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RateLimitSubjectHash.canonicalClientAddressBytes("example.invalid"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RateLimitSubjectHash.canonicalClientAddressBytes("192.000.2.1"));
    assertTrue(RateLimitSubjectHash.isCanonicalClientAddressLiteral("203.0.113.9"));
  }

  @Test
  void tenantKeyShapeRemainsSeparateFromTypedPlatformAuthNamespace() {
    var tenantDigest =
        RateLimitSubjectHash.tenantSubject(
            KEY,
            "client-address",
            "tenant-7",
            "login",
            RateLimitSubjectHash.canonicalClientAddressBytes("203.0.113.9"));
    var platformDigest =
        RateLimitSubjectHash.platformAuthSubject(
            KEY,
            RateLimitSubjectHash.PlatformAuthPolicy.SOURCE_ATTEMPT,
            RateLimitSubjectHash.canonicalClientAddressBytes("203.0.113.9"));

    assertEquals(
        "ratelimit:tenant-7:" + tenantDigest.subjectHash() + ":42",
        RateLimitSubjectHash.tenantBucketKey("tenant-7", tenantDigest, 42L));
    assertTrue(
        RateLimitSubjectHash.platformAuthBucketKey(platformDigest, 42L)
            .startsWith("ratelimit:platform-auth:v1:rsh-v1-test-1-"));
    assertNotEquals(
        RateLimitSubjectHash.tenantBucketKey("tenant-7", tenantDigest, 42L),
        RateLimitSubjectHash.platformAuthBucketKey(platformDigest, 42L));
  }

  @Test
  void keyMaterialAndScopeInputsAreValidated() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RateLimitSubjectHash.HmacKey("invalid:key", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RateLimitSubjectHash.HmacKey("test-1", new byte[31]));
  }

  private static byte[] concat(byte[]... values) {
    int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] value : values) {
      System.arraycopy(value, 0, result, offset, value.length);
      offset += value.length;
    }
    return result;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String lowerHex(byte[] bytes) {
    StringBuilder output = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      output.append(String.format("%02x", value & 0xff));
    }
    return output.toString();
  }
}
