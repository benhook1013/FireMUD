package net.firedevops.firemud.accountjwtmaterializer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Private-key bundle v1 codec; no signing operation is exposed. */
final class SignerKeyBundle {
  static final int MAX_BUNDLE_BYTES = 64 * 1024;
  private static final int MAX_DER_BYTES = 16 * 1024;
  private static final int MIN_RSA_BITS = 3_072;
  private static final int MAX_RSA_BITS = 16_384;
  private static final String ALGORITHM = "RS256";
  private static final Pattern GENERATION = Pattern.compile("[1-9][0-9]{0,63}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> BUNDLE_FIELDS =
      Set.of(
          "version",
          "environmentId",
          "clusterId",
          "namespace",
          "operationId",
          "generation",
          "kid",
          "algorithm",
          "privateKeyPkcs8",
          "publicKeyFingerprint");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private SignerKeyBundle() {}

  static Generated generate(
      ProtectedBinding.Binding binding, String operationId, String generation, String kid) {
    validateIdentity(binding, operationId, generation, kid);
    byte[] privateDer = null;
    byte[] bundleBytes = null;
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(new RSAKeyGenParameterSpec(3_072, RSAKeyGenParameterSpec.F4));
      KeyPair pair = generator.generateKeyPair();
      RSAPrivateCrtKey privateKey = requireStrongKey(pair.getPrivate());
      RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
      if (publicKey.getModulus().bitLength() != 3_072
          || !BigInteger.valueOf(65_537).equals(publicKey.getPublicExponent())
          || !publicKey.getModulus().equals(privateKey.getModulus())) {
        throw denied();
      }
      privateDer = privateKey.getEncoded();
      if (privateDer == null || privateDer.length == 0 || privateDer.length > MAX_DER_BYTES) {
        throw denied();
      }
      String fingerprint = fingerprint(publicKey);
      String publicJwk = publicJwk(publicKey, kid);
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("version", 1);
      fields.put("environmentId", binding.environmentId());
      fields.put("clusterId", binding.clusterId());
      fields.put("namespace", binding.namespace());
      fields.put("operationId", operationId);
      fields.put("generation", generation);
      fields.put("kid", kid);
      fields.put("algorithm", ALGORITHM);
      fields.put(
          "privateKeyPkcs8", Base64.getUrlEncoder().withoutPadding().encodeToString(privateDer));
      fields.put("publicKeyFingerprint", fingerprint);
      bundleBytes = canonicalJson(fields);
      if (bundleBytes.length > MAX_BUNDLE_BYTES) {
        throw denied();
      }
      return new Generated(
          bundleBytes, publicJwk, new PublicIdentity(generation, kid, fingerprint, operationId));
    } catch (java.security.GeneralSecurityException | RuntimeException ex) {
      wipe(bundleBytes);
      throw denied();
    } finally {
      wipe(privateDer);
    }
  }

  static PublicIdentity verify(
      byte[] bundleBytes,
      ProtectedBinding.Binding binding,
      String expectedOperationId,
      String expectedGeneration,
      String expectedKid,
      String expectedFingerprint) {
    byte[] privateDer = null;
    try {
      if (bundleBytes == null || bundleBytes.length == 0 || bundleBytes.length > MAX_BUNDLE_BYTES) {
        throw denied();
      }
      JsonNode root = JSON.readTree(bundleBytes);
      requireExactObject(root, BUNDLE_FIELDS);
      JsonNode version = root.get("version");
      if (version == null || !version.isIntegralNumber() || version.intValue() != 1) {
        throw denied();
      }
      String environment = text(root, "environmentId", 63);
      String cluster = text(root, "clusterId", 128);
      String namespace = text(root, "namespace", 63);
      String operation = canonicalUuidV4(text(root, "operationId", 36));
      String generation = text(root, "generation", 64);
      String kid = text(root, "kid", 64);
      String algorithm = text(root, "algorithm", 8);
      String declaredFingerprint = text(root, "publicKeyFingerprint", 64);
      String privateKeyEncoded = text(root, "privateKeyPkcs8", 24 * 1024);
      if (!binding.environmentId().equals(environment)
          || !binding.clusterId().equals(cluster)
          || !binding.namespace().equals(namespace)
          || (expectedOperationId != null && !expectedOperationId.equals(operation))
          || (expectedGeneration != null && !expectedGeneration.equals(generation))
          || (expectedKid != null && !expectedKid.equals(kid))
          || (expectedFingerprint != null && !expectedFingerprint.equals(declaredFingerprint))
          || !ALGORITHM.equals(algorithm)
          || !GENERATION.matcher(generation).matches()
          || !KID.matcher(kid).matches()
          || !SHA256.matcher(declaredFingerprint).matches()) {
        throw denied();
      }
      validateIdentity(binding, operation, generation, kid);
      privateDer = decodeCanonicalBase64Url(privateKeyEncoded, MAX_DER_BYTES);
      PrivateKey parsed =
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateDer));
      RSAPrivateCrtKey privateKey = requireStrongKey(parsed);
      byte[] canonicalDer = privateKey.getEncoded();
      try {
        if (canonicalDer == null || !MessageDigest.isEqual(privateDer, canonicalDer)) {
          throw denied();
        }
      } finally {
        wipe(canonicalDer);
      }
      RSAPublicKey publicKey = publicKey(privateKey);
      if (!declaredFingerprint.equals(fingerprint(publicKey))) {
        throw denied();
      }
      return new PublicIdentity(generation, kid, declaredFingerprint, operation);
    } catch (RuntimeException | java.security.GeneralSecurityException ex) {
      throw denied();
    } finally {
      wipe(privateDer);
    }
  }

  static String publicJwkForBundle(
      byte[] bundleBytes,
      ProtectedBinding.Binding binding,
      String expectedOperationId,
      String expectedGeneration,
      String expectedKid,
      String expectedFingerprint) {
    byte[] privateDer = null;
    try {
      PublicIdentity identity =
          verify(
              bundleBytes,
              binding,
              expectedOperationId,
              expectedGeneration,
              expectedKid,
              expectedFingerprint);
      JsonNode root = JSON.readTree(bundleBytes);
      privateDer =
          decodeCanonicalBase64Url(text(root, "privateKeyPkcs8", 24 * 1024), MAX_DER_BYTES);
      PrivateKey parsed =
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateDer));
      return publicJwk(publicKey(requireStrongKey(parsed)), identity.kid());
    } catch (java.security.GeneralSecurityException | RuntimeException ex) {
      throw denied();
    } finally {
      wipe(privateDer);
    }
  }

  static String fingerprint(RSAPublicKey publicKey) {
    Map<String, Object> thumbprint = new LinkedHashMap<>();
    thumbprint.put("e", unsignedBase64Url(publicKey.getPublicExponent()));
    thumbprint.put("kty", "RSA");
    thumbprint.put("n", unsignedBase64Url(publicKey.getModulus()));
    return sha256(canonicalJson(thumbprint));
  }

  private static String publicJwk(RSAPublicKey publicKey, String kid) {
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("kty", "RSA");
    jwk.put("use", "sig");
    jwk.put("alg", ALGORITHM);
    jwk.put("kid", kid);
    jwk.put("key_ops", java.util.List.of("verify"));
    jwk.put("n", unsignedBase64Url(publicKey.getModulus()));
    jwk.put("e", unsignedBase64Url(publicKey.getPublicExponent()));
    return new String(canonicalJson(jwk), StandardCharsets.UTF_8);
  }

  private static String unsignedBase64Url(BigInteger value) {
    byte[] encoded = value.toByteArray();
    try {
      int start = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(Arrays.copyOfRange(encoded, start, encoded.length));
    } finally {
      wipe(encoded);
    }
  }

  private static RSAPublicKey publicKey(RSAPrivateCrtKey privateKey)
      throws java.security.GeneralSecurityException {
    return (RSAPublicKey)
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new java.security.spec.RSAPublicKeySpec(
                    privateKey.getModulus(), privateKey.getPublicExponent()));
  }

  private static RSAPrivateCrtKey requireStrongKey(PrivateKey key) {
    if (!(key instanceof RSAPrivateCrtKey rsa)
        || !"RSA".equalsIgnoreCase(key.getAlgorithm())
        || rsa.getModulus().bitLength() < MIN_RSA_BITS
        || rsa.getModulus().bitLength() > MAX_RSA_BITS
        || !BigInteger.valueOf(65_537).equals(rsa.getPublicExponent())
        || !positive(rsa.getPrivateExponent())
        || !positive(rsa.getPrimeP())
        || !positive(rsa.getPrimeQ())
        || rsa.getPrimeP().equals(rsa.getPrimeQ())
        || !positive(rsa.getPrimeExponentP())
        || !positive(rsa.getPrimeExponentQ())
        || !positive(rsa.getCrtCoefficient())) {
      throw denied();
    }
    BigInteger pMinusOne = rsa.getPrimeP().subtract(BigInteger.ONE);
    BigInteger qMinusOne = rsa.getPrimeQ().subtract(BigInteger.ONE);
    BigInteger lambda = pMinusOne.divide(pMinusOne.gcd(qMinusOne)).multiply(qMinusOne);
    if (!rsa.getPrimeP().multiply(rsa.getPrimeQ()).equals(rsa.getModulus())
        || !rsa.getPrivateExponent()
            .multiply(rsa.getPublicExponent())
            .mod(lambda)
            .equals(BigInteger.ONE)
        || !rsa.getPrivateExponent().mod(pMinusOne).equals(rsa.getPrimeExponentP())
        || !rsa.getPrivateExponent().mod(qMinusOne).equals(rsa.getPrimeExponentQ())
        || !rsa.getPrimeQ().modInverse(rsa.getPrimeP()).equals(rsa.getCrtCoefficient())) {
      throw denied();
    }
    return rsa;
  }

  private static boolean positive(BigInteger value) {
    return value != null && value.signum() > 0;
  }

  private static byte[] decodeCanonicalBase64Url(String encoded, int maximumBytes) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encoded);
    } catch (RuntimeException ex) {
      throw denied();
    }
    byte[] canonical = Base64.getUrlEncoder().withoutPadding().encode(decoded);
    boolean canonicalEncoding = canonical.length == encoded.length();
    for (int index = 0; canonicalEncoding && index < canonical.length; index++) {
      canonicalEncoding = Byte.toUnsignedInt(canonical[index]) == encoded.charAt(index);
    }
    wipe(canonical);
    if (decoded.length == 0 || decoded.length > maximumBytes || !canonicalEncoding) {
      wipe(decoded);
      throw denied();
    }
    return decoded;
  }

  private static void validateIdentity(
      ProtectedBinding.Binding binding, String operationId, String generation, String kid) {
    canonicalUuidV4(operationId);
    if (generation == null
        || !GENERATION.matcher(generation).matches()
        || kid == null
        || !KID.matcher(kid).matches()
        || binding == null) {
      throw denied();
    }
  }

  private static String canonicalUuidV4(String value) {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value) || uuid.version() != 4 || uuid.variant() != 2) {
      throw denied();
    }
    return value;
  }

  private static void requireExactObject(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || node.size() != fields.size()) {
      throw denied();
    }
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      if (!fields.contains(entry.getKey())) {
        throw denied();
      }
    }
  }

  private static String text(JsonNode node, String name, int maxLength) {
    JsonNode value = node == null ? null : node.get(name);
    if (value == null || !value.isTextual()) {
      throw denied();
    }
    String text = value.textValue();
    if (text.isEmpty()
        || text.length() > maxLength
        || text.chars().anyMatch(Character::isISOControl)) {
      throw denied();
    }
    return text;
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) Arrays.fill(bytes, (byte) 0);
  }

  private static IllegalArgumentException denied() {
    return new IllegalArgumentException("JWT signing Secret state is malformed or unauthorized");
  }

  static final class Generated implements AutoCloseable {
    private byte[] bundleBytes;
    private final String publicJwkJson;
    private final PublicIdentity identity;

    Generated(byte[] bundleBytes, String publicJwkJson, PublicIdentity identity) {
      this.bundleBytes = bundleBytes;
      this.publicJwkJson = publicJwkJson;
      this.identity = identity;
    }

    byte[] bundleBytes() {
      if (bundleBytes == null) throw denied();
      return bundleBytes;
    }

    String publicJwkJson() {
      return publicJwkJson;
    }

    PublicIdentity identity() {
      return identity;
    }

    @Override
    public void close() {
      wipe(bundleBytes);
      bundleBytes = null;
    }
  }

  record PublicIdentity(String generation, String kid, String fingerprint, String operationId) {}
}
