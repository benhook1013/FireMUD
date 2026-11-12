package net.firedevops.firemud.accountservice.service.auth;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.common.EmailCanonicalization;

/**
 * Account-global v2 subject mapping. This pure helper neither authenticates its input's source nor
 * grants policy, store or credential authority.
 */
public final class AccountCredentialAbuseSubjectKeys {
  public static final String PREFIX = "ratelimit:account-credential:v1:";
  private static final String SUBJECT_PURPOSE = "FireMUD/accountCredentialSubject/v2";
  private static final String WITNESS_PURPOSE = "FireMUD/accountCredentialWitness/v2";
  private static final long MAX_EXACT_REDIS_INTEGER = 9_007_199_254_740_991L;
  private final String policyScope;
  private final DigestKey key;

  public AccountCredentialAbuseSubjectKeys(String policyScope, DigestKey key) {
    this.policyScope = identifier(policyScope);
    this.key = Objects.requireNonNull(key, "Credential-abuse digest key is required");
  }

  /** The caller must first establish authenticated server-derived source custody. */
  public Subject source(String literalAddress, long window) {
    if (literalAddress == null
        || literalAddress.length() > 64
        || !literalAddress.matches("[0-9a-fA-F:.]+")) {
      throw invalid();
    }
    byte[] network;
    try {
      // Literal-only parsing performs no hostname or DNS lookup.
      network = InetAddress.ofLiteral(literalAddress).getAddress();
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
    if (isIpv4Mapped(network)) {
      byte[] mapped = Arrays.copyOfRange(network, 12, 16);
      Arrays.fill(network, (byte) 0);
      network = mapped;
    }
    try {
      return subject("source", network, window);
    } finally {
      Arrays.fill(network, (byte) 0);
    }
  }

  /** Maps a presented candidate, without looking up whether an Account exists. */
  public Subject candidate(String candidate, long window) {
    if (candidate == null || candidate.length() > 254) throw invalid();
    String canonical;
    try {
      canonical = EmailCanonicalization.normalize(candidate);
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
    if (canonical.chars().anyMatch(Character::isISOControl)) throw invalid();
    byte[] encoded = utf8(canonical);
    try {
      return subject("candidate", encoded, window);
    } finally {
      Arrays.fill(encoded, (byte) 0);
    }
  }

  /** Deliberately shared pressure/admission signal; never an individual subject's identity. */
  public String pressure(long window) {
    requireWindow(window);
    key.requireOpen();
    return PREFIX + "pressure:" + policyScope + ":" + key.id() + ":" + window;
  }

  private Subject subject(String kind, byte[] canonical, long window) {
    requireWindow(window);
    String digest = keyedDigest(SUBJECT_PURPOSE, kind, canonical);
    String witness = keyedDigest(WITNESS_PURPOSE, kind, canonical);
    String subjectHash = "rsh-v2-" + key.id() + "-" + digest;
    return new Subject(
        PREFIX + "subject:" + policyScope + ":" + kind + ":" + subjectHash + ":" + window,
        "wsh-v2-" + key.id() + "-" + witness);
  }

  private String keyedDigest(String purpose, String kind, byte[] canonical) {
    byte[][] segments = {utf8(purpose), utf8(key.id()), utf8(policyScope), utf8(kind), canonical};
    byte[][] lengths = new byte[segments.length][];
    int length = 0;
    for (int index = 0; index < segments.length; index++) {
      lengths[index] = Integer.toString(segments[index].length).getBytes(StandardCharsets.US_ASCII);
      length = Math.addExact(length, lengths[index].length + 1 + segments[index].length);
    }
    byte[] preimage = new byte[length];
    ByteBuffer target = ByteBuffer.wrap(preimage);
    for (int index = 0; index < segments.length; index++) {
      target.put(lengths[index]).put((byte) ':').put(segments[index]);
    }
    try {
      return key.digest(preimage);
    } finally {
      Arrays.fill(preimage, (byte) 0);
    }
  }

  private static boolean isIpv4Mapped(byte[] address) {
    if (address.length != 16 || address[10] != (byte) 0xff || address[11] != (byte) 0xff) {
      return false;
    }
    for (int index = 0; index < 10; index++) {
      if (address[index] != 0) return false;
    }
    return true;
  }

  private static byte[] utf8(String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  private static String identifier(String value) {
    if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw invalid();
    return value;
  }

  private static void requireWindow(long window) {
    if (window < 0 || window > MAX_EXACT_REDIS_INTEGER) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid credential-abuse subject input");
  }

  @Override
  public String toString() {
    return "AccountCredentialAbuseSubjectKeys[redacted]";
  }

  /** Only this helper creates a subject; the value contains no raw subject or key material. */
  public static final class Subject {
    private final String redisKey;
    private final String collisionWitness;

    private Subject(String redisKey, String collisionWitness) {
      this.redisKey = redisKey;
      this.collisionWitness = collisionWitness;
    }

    public String redisKey() {
      return redisKey;
    }

    public String collisionWitness() {
      return collisionWitness;
    }

    @Override
    public String toString() {
      return "AccountCredentialAbuseSubject[redacted]";
    }
  }

  /**
   * A borrowed protected-source key. Constructing it is not proof of key custody or authorization.
   * Its owner closes it after all policy snapshots using it have drained.
   */
  public static final class DigestKey implements AutoCloseable {
    private final String id;
    private final byte[] material;
    private boolean closed;

    public DigestKey(String id, byte[] material) {
      this.id = identifier(id);
      if (material == null || material.length != 32) {
        throw new IllegalArgumentException("A 256-bit credential-abuse digest key is required");
      }
      this.material = material.clone();
    }

    public String id() {
      return id;
    }

    private synchronized String digest(byte[] preimage) {
      requireOpen();
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(material, "HmacSHA256"));
        byte[] result = mac.doFinal(preimage);
        try {
          return HexFormat.of().formatHex(result);
        } finally {
          Arrays.fill(result, (byte) 0);
        }
      } catch (GeneralSecurityException unavailable) {
        throw new IllegalStateException("Credential-abuse digest is unavailable");
      }
    }

    private synchronized void requireOpen() {
      if (closed) throw new IllegalStateException("Credential-abuse key is unavailable");
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        Arrays.fill(material, (byte) 0);
        closed = true;
      }
    }

    @Override
    public String toString() {
      return "AccountCredentialAbuseDigestKey[redacted]";
    }
  }
}
