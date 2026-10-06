package net.firedevops.firemud.common.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Privacy-preserving subject hashing and key naming for isolated Redis rate-limit buckets. */
public final class RateLimitSubjectHash {
  private static final String SUBJECT_DOMAIN = "FireMUD/rateLimitSubject/v1";
  private static final String COLLISION_DOMAIN = "FireMUD/rateLimitSubjectCollisionCheck/v1";
  private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
  private static final Pattern IPV4_CHARACTERS = Pattern.compile("[0-9.]+");
  private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]+");

  private RateLimitSubjectHash() {}

  /** Explicit platform-auth dimensions; the empty tenant segment means no tenant is involved. */
  public enum PlatformAuthPolicy {
    GLOBAL_PRESSURE("global-pressure", "global-pressure"),
    SOURCE_ATTEMPT("client-address", "source-attempt"),
    CANDIDATE_ATTEMPT("account-candidate", "candidate-attempt"),
    SOURCE_FAILURE("client-address", "source-failure"),
    CANDIDATE_FAILURE("account-candidate", "candidate-failure");

    private final String subjectKind;
    private final String policyScope;

    PlatformAuthPolicy(String subjectKind, String policyScope) {
      this.subjectKind = subjectKind;
      this.policyScope = "platform-auth/" + policyScope;
    }
  }

  /** Immutable active key material. Its printable form intentionally omits the secret bytes. */
  public static final class HmacKey {
    private final String keyId;
    private final byte[] material;

    public HmacKey(String keyId, byte[] material) {
      if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
        throw new IllegalArgumentException("rate-limit HMAC key ID is invalid");
      }
      if (material == null || material.length != 32) {
        throw new IllegalArgumentException("rate-limit HMAC key must contain exactly 32 bytes");
      }
      this.keyId = keyId;
      this.material = material.clone();
    }

    public String keyId() {
      return keyId;
    }

    private byte[] material() {
      return material.clone();
    }

    @Override
    public String toString() {
      return "HmacKey[keyId=" + keyId + ", material=redacted]";
    }
  }

  /** Two full-length keyed digests: one names the bucket and one detects a detected collision. */
  public record Digest(String subjectHash, String collisionFingerprint) {
    public Digest {
      Objects.requireNonNull(subjectHash, "subjectHash must not be null");
      Objects.requireNonNull(collisionFingerprint, "collisionFingerprint must not be null");
    }
  }

  /** Hashes an explicitly typed platform-auth subject using the canonical framed HMAC preimage. */
  public static Digest platformAuthSubject(
      HmacKey key, PlatformAuthPolicy policy, byte[] canonicalSubjectBytes) {
    Objects.requireNonNull(policy, "policy must not be null");
    return hash(key, policy.subjectKind, "", policy.policyScope, canonicalSubjectBytes);
  }

  /** Hashes a tenant subject without changing the established tenant key dimensions. */
  public static Digest tenantSubject(
      HmacKey key,
      String subjectKind,
      String tenantId,
      String policyScope,
      byte[] canonicalSubjectBytes) {
    if (tenantId == null || tenantId.isBlank() || tenantId.indexOf(':') >= 0) {
      throw new IllegalArgumentException("tenant ID must be non-blank and key-safe");
    }
    return hash(key, subjectKind, tenantId, policyScope, canonicalSubjectBytes);
  }

  /** Canonical tenant-family key shape: ratelimit:<tenantId>:<subjectHash>:<timeWindow>. */
  public static String tenantBucketKey(String tenantId, Digest digest, long timeWindow) {
    if (tenantId == null || tenantId.isBlank() || tenantId.indexOf(':') >= 0 || timeWindow < 0) {
      throw new IllegalArgumentException("tenant bucket dimensions are invalid");
    }
    Objects.requireNonNull(digest, "digest must not be null");
    return "ratelimit:" + tenantId + ":" + digest.subjectHash() + ":" + timeWindow;
  }

  /** Distinct, versioned namespace for Account's pre-tenant platform-auth windows. */
  public static String platformAuthBucketKey(Digest digest, long timeWindow) {
    if (timeWindow < 0) {
      throw new IllegalArgumentException("time window must not be negative");
    }
    Objects.requireNonNull(digest, "digest must not be null");
    return "ratelimit:platform-auth:v1:" + digest.subjectHash() + ":" + timeWindow;
  }

  /** Returns canonical network bytes without performing DNS lookup. Mapped IPv6 becomes IPv4. */
  public static byte[] canonicalClientAddressBytes(String address) {
    byte[] parsed = parseLiteralAddress(address);
    if (parsed.length == 16 && isIpv4MappedIpv6(parsed)) {
      return Arrays.copyOfRange(parsed, 12, 16);
    }
    return parsed;
  }

  /** Checks the wire-format canonical address using only numeric parsing, never name resolution. */
  public static boolean isCanonicalClientAddressLiteral(String address) {
    try {
      byte[] parsed = parseLiteralAddress(address);
      return address.equals(InetAddress.getByAddress(parsed).getHostAddress());
    } catch (IllegalArgumentException | UnknownHostException ignored) {
      return false;
    }
  }

  private static Digest hash(
      HmacKey key,
      String subjectKind,
      String tenantId,
      String policyScope,
      byte[] canonicalSubjectBytes) {
    Objects.requireNonNull(key, "key must not be null");
    requireAsciiToken(subjectKind, "subject kind");
    requireAsciiToken(policyScope, "policy scope");
    Objects.requireNonNull(tenantId, "tenant ID must not be null");
    Objects.requireNonNull(canonicalSubjectBytes, "canonical subject bytes must not be null");

    byte[] framedSubject =
        framedPreimage(
            SUBJECT_DOMAIN, key.keyId(), subjectKind, tenantId, policyScope, canonicalSubjectBytes);
    byte[] framedCollision =
        framedPreimage(
            COLLISION_DOMAIN,
            key.keyId(),
            subjectKind,
            tenantId,
            policyScope,
            canonicalSubjectBytes);
    String keyPrefix = "rsh-v1-" + key.keyId() + "-";
    String collisionPrefix = "rsc-v1-" + key.keyId() + "-";
    return new Digest(
        keyPrefix + lowerHex(hmacSha256(key.material(), framedSubject)),
        collisionPrefix + lowerHex(hmacSha256(key.material(), framedCollision)));
  }

  static byte[] framedPreimage(
      String domain,
      String keyId,
      String subjectKind,
      String tenantId,
      String policyScope,
      byte[] canonicalSubjectBytes) {
    List<byte[]> segments =
        List.of(
            utf8(domain),
            utf8(keyId),
            utf8(subjectKind),
            utf8(tenantId),
            utf8(policyScope),
            canonicalSubjectBytes.clone());
    int length =
        segments.stream().mapToInt(bytes -> decimalLength(bytes.length) + 1 + bytes.length).sum();
    ByteBuffer preimage = ByteBuffer.allocate(length);
    for (byte[] segment : segments) {
      preimage.put(utf8(Integer.toString(segment.length)));
      preimage.put((byte) ':');
      preimage.put(segment);
    }
    return preimage.array();
  }

  private static int decimalLength(int value) {
    return Integer.toString(value).length();
  }

  static byte[] hmacSha256(byte[] key, byte[] value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
    }
  }

  private static void requireAsciiToken(String value, String label) {
    if (value == null
        || value.isBlank()
        || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)
        || value.indexOf(':') >= 0) {
      throw new IllegalArgumentException(label + " must be a non-blank printable ASCII token");
    }
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String lowerHex(byte[] bytes) {
    char[] encoded = new char[bytes.length * 2];
    final char[] digits = "0123456789abcdef".toCharArray();
    for (int index = 0; index < bytes.length; index++) {
      int value = bytes[index] & 0xff;
      encoded[index * 2] = digits[value >>> 4];
      encoded[index * 2 + 1] = digits[value & 0x0f];
    }
    return new String(encoded);
  }

  private static byte[] parseLiteralAddress(String address) {
    if (address == null
        || address.isEmpty()
        || !address.equals(address.trim())
        || address.indexOf('%') >= 0
        || address.indexOf('[') >= 0
        || address.indexOf(']') >= 0) {
      throw new IllegalArgumentException("client address must be a literal IP address");
    }
    if (address.indexOf(':') < 0) {
      if (!IPV4_CHARACTERS.matcher(address).matches()) {
        throw new IllegalArgumentException("client address must be a literal IP address");
      }
      return parseIpv4(address);
    }
    if (!IPV6_CHARACTERS.matcher(address).matches()) {
      throw new IllegalArgumentException("client address must be a literal IP address");
    }
    return parseIpv6(address);
  }

  private static byte[] parseIpv4(String address) {
    String[] octets = address.split("\\.", -1);
    if (octets.length != 4) {
      throw new IllegalArgumentException("client IPv4 address is invalid");
    }
    byte[] bytes = new byte[4];
    for (int index = 0; index < octets.length; index++) {
      String octet = octets[index];
      if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.startsWith("0"))) {
        throw new IllegalArgumentException("client IPv4 address is invalid");
      }
      int value = 0;
      for (int characterIndex = 0; characterIndex < octet.length(); characterIndex++) {
        char character = octet.charAt(characterIndex);
        if (character < '0' || character > '9') {
          throw new IllegalArgumentException("client IPv4 address is invalid");
        }
        value = value * 10 + character - '0';
      }
      if (value > 255) {
        throw new IllegalArgumentException("client IPv4 address is invalid");
      }
      bytes[index] = (byte) value;
    }
    return bytes;
  }

  private static byte[] parseIpv6(String address) {
    int compression = address.indexOf("::");
    if (compression >= 0 && address.indexOf("::", compression + 2) >= 0) {
      throw new IllegalArgumentException("client IPv6 address is invalid");
    }
    int finalColon = address.lastIndexOf(':');
    if (address.indexOf('.') >= 0
        && (address.lastIndexOf('.') < finalColon
            || (compression >= 0 && address.lastIndexOf('.') < compression))) {
      throw new IllegalArgumentException("client IPv6 address is invalid");
    }
    String leftText = compression < 0 ? address : address.substring(0, compression);
    String rightText = compression < 0 ? "" : address.substring(compression + 2);
    List<Integer> left = parseIpv6Groups(leftText);
    List<Integer> right = parseIpv6Groups(rightText);
    boolean hasCompression = compression >= 0;
    int groupCount = left.size() + right.size();
    int zeroGroups = hasCompression ? 8 - groupCount : 0;
    if ((hasCompression && zeroGroups < 1) || (!hasCompression && groupCount != 8)) {
      throw new IllegalArgumentException("client IPv6 address is invalid");
    }
    List<Integer> groups = new ArrayList<>(8);
    groups.addAll(left);
    for (int index = 0; index < zeroGroups; index++) {
      groups.add(0);
    }
    groups.addAll(right);
    if (groups.size() != 8) {
      throw new IllegalArgumentException("client IPv6 address is invalid");
    }
    byte[] bytes = new byte[16];
    for (int index = 0; index < groups.size(); index++) {
      int group = groups.get(index);
      bytes[index * 2] = (byte) (group >>> 8);
      bytes[index * 2 + 1] = (byte) group;
    }
    return bytes;
  }

  private static List<Integer> parseIpv6Groups(String text) {
    if (text.isEmpty()) {
      return List.of();
    }
    String[] tokens = text.split(":", -1);
    List<Integer> groups = new ArrayList<>(tokens.length + 1);
    for (int index = 0; index < tokens.length; index++) {
      String token = tokens[index];
      if (token.isEmpty()) {
        throw new IllegalArgumentException("client IPv6 address is invalid");
      }
      if (token.indexOf('.') >= 0) {
        if (index != tokens.length - 1) {
          throw new IllegalArgumentException("client IPv6 address is invalid");
        }
        byte[] ipv4 = parseIpv4(token);
        groups.add(((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff));
        groups.add(((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff));
        continue;
      }
      if (token.length() > 4) {
        throw new IllegalArgumentException("client IPv6 address is invalid");
      }
      int group = 0;
      for (int characterIndex = 0; characterIndex < token.length(); characterIndex++) {
        int digit = Character.digit(token.charAt(characterIndex), 16);
        if (digit < 0) {
          throw new IllegalArgumentException("client IPv6 address is invalid");
        }
        group = (group << 4) | digit;
      }
      groups.add(group);
    }
    return groups;
  }

  private static boolean isIpv4MappedIpv6(byte[] bytes) {
    for (int index = 0; index < 10; index++) {
      if (bytes[index] != 0) {
        return false;
      }
    }
    return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
  }
}
