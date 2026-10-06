package net.firedevops.firemud.accountservice.service.session;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded Account-local parser and correspondence proof for the interim mounted JWT signer.
 *
 * <p>This class exposes no general private-key operation or caller-facing token response. Its
 * package-private signing operations are closed readiness probes and the exact Account-owned
 * initial Game Session delegation candidate; compact bytes are delivered only to an owner callback
 * and wiped after it returns. Local correspondence alone proves no lifecycle or authorization.
 */
public final class AccountMountedJwtSignerBundle {
  public static final int MAX_PRIVATE_BUNDLE_BYTES = 64 * 1024;
  public static final int MAX_PUBLIC_JWKS_BYTES = 256 * 1024;
  private static final int MAX_READINESS_COMPACT_JWT_BYTES = 16 * 1024;
  private static final int MAX_DELEGATION_COMPACT_JWT_BYTES =
      GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES;
  private static final int MAX_RSA_BITS = 16_384;
  private static final int MIN_RSA_BITS = 3_072;
  private static final int MAX_JWKS_KEYS = 64;
  private static final int MAX_PRIVATE_KEY_DER_BYTES = 16 * 1024;
  private static final int CHALLENGE_NONCE_BYTES = 32;
  private static final SecureRandom CHALLENGE_RANDOM = new SecureRandom();
  private static final String ALGORITHM = "RS256";
  static final String READINESS_CANARY_PROFILE = "account-jwt-readiness-canary";
  static final String READINESS_CANARY_TYPE = "account_jwt_readiness_canary";
  static final String READINESS_CANARY_AUDIENCE = "firemud-account-jwt-readiness";
  static final String READINESS_VALIDATOR_ID = "account-service";
  static final String REPRESENTATIVE_PROFILE = GameSessionAccountDelegationProfile.PROFILE;
  static final String REPRESENTATIVE_AUDIENCE = GameSessionAccountDelegationProfile.AUDIENCE;
  private static final String ISSUER = "firemud-account-service";
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern GENERATION = Pattern.compile("[1-9][0-9]{0,63}");
  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> PRIVATE_BUNDLE_FIELDS =
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
  private static final Set<String> JWKS_FIELDS = Set.of("keys");
  private static final Set<String> JWK_FIELDS =
      Set.of("kty", "use", "alg", "kid", "key_ops", "n", "e");
  private static final byte[] CHALLENGE_DOMAIN =
      new byte[] {
        0, 'F', 'i', 'r', 'e', 'M', 'U', 'D', '/', 'A', 'c', 'c', 'o', 'u', 'n', 't', '/', 'm', 'o',
        'u', 'n', 't', 'e', 'd', '-', 's', 'i', 'g', 'n', 'e', 'r', '-', 'c', 'o', 'r', 'r', 'e',
        's', 'p', 'o', 'n', 'd', 'e', 'n', 'c', 'e', '/', 'v', '1', 0
      };
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private AccountMountedJwtSignerBundle() {}

  /**
   * Reads the explicitly configured Account-only private projection and public JWKS projection.
   * Relative file paths must remain beneath their respective configured mount roots. Kubernetes
   * projected-volume symlinks are accepted only when their resolved regular files stay beneath
   * those roots.
   */
  public static LocalObservation observeMounted(
      Path privateMountRoot,
      Path privateBundlePath,
      Path publicMountRoot,
      Path publicJwksPath,
      ExpectedIdentity expectedIdentity) {
    return readAndVerifyMounted(
            privateMountRoot, privateBundlePath, publicMountRoot, publicJwksPath, expectedIdentity)
        .observation();
  }

  /**
   * Signs only one fixed non-authorizing canary or one fixed-shape representative profile, passes
   * the exact compact bytes transiently to the Account-owned callback, then wipes them and returns
   * only their digest plus immutable planned identity. This method is package-private so no route,
   * ordinary issuer, or other service can request arbitrary JWT signing from the mounted pending
   * key.
   */
  static SignedProbeDigest signReadinessProbeDigest(
      Path privateMountRoot,
      Path privateBundlePath,
      Path publicMountRoot,
      Path publicJwksPath,
      ExpectedIdentity expectedIdentity,
      ReadinessProbeSigningSpec spec,
      ProbeDeliveryConsumer deliveryConsumer) {
    Objects.requireNonNull(spec, "Readiness probe signing specification is required");
    Objects.requireNonNull(deliveryConsumer, "Readiness probe delivery consumer is required");
    VerifiedMountedSigner mounted =
        readAndVerifyMounted(
            privateMountRoot, privateBundlePath, publicMountRoot, publicJwksPath, expectedIdentity);
    if (!mounted.observation().expectedIdentity().equals(expectedIdentity)
        || !spec.targetGeneration().equals(expectedIdentity.generation())
        || !spec.targetKid().equals(expectedIdentity.kid())) {
      throw invalid();
    }

    byte[] header = null;
    byte[] claims = null;
    byte[] encodedHeader = null;
    byte[] encodedClaims = null;
    byte[] signingInput = null;
    byte[] signatureBytes = null;
    byte[] encodedSignature = null;
    byte[] compactBytes = null;
    byte[] tokenDigest = null;
    byte[] deliveryBytes = null;
    try {
      SignedProbeDigest result;
      try {
        Map<String, Object> headerFields = new LinkedHashMap<>();
        headerFields.put("alg", ALGORITHM);
        headerFields.put("kid", expectedIdentity.kid());
        headerFields.put("typ", "JWT");
        header = canonicalJsonBytes(headerFields);
        claims = canonicalJsonBytes(readinessClaims(spec));
        encodedHeader = Base64.getUrlEncoder().withoutPadding().encode(header);
        encodedClaims = Base64.getUrlEncoder().withoutPadding().encode(claims);
        signingInput = joinCompactParts(encodedHeader, encodedClaims, null);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(mounted.privateKey());
        signer.update(signingInput);
        signatureBytes = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(mounted.publicKey());
        verifier.update(signingInput);
        if (!verifier.verify(signatureBytes)) {
          throw invalid();
        }
        encodedSignature = Base64.getUrlEncoder().withoutPadding().encode(signatureBytes);
        compactBytes = joinCompactParts(encodedHeader, encodedClaims, encodedSignature);
        if (compactBytes.length > MAX_READINESS_COMPACT_JWT_BYTES) {
          throw invalid();
        }
        tokenDigest = MessageDigest.getInstance("SHA-256").digest(compactBytes);
        result =
            new SignedProbeDigest(
                expectedIdentity.operationId(),
                spec.validatorId(),
                spec.probeKind(),
                spec.tokenProfile(),
                spec.audience(),
                spec.jti(),
                expectedIdentity.generation(),
                expectedIdentity.kid(),
                expectedIdentity.publicKeyFingerprint(),
                spec.issuedAtEpochSecond(),
                spec.expiresAtEpochSecond(),
                java.util.HexFormat.of().formatHex(tokenDigest));
      } catch (InvalidMountedSignerBundleException ex) {
        throw ex;
      } catch (Exception ex) {
        throw invalid();
      }
      deliveryBytes = compactBytes.clone();
      deliveryConsumer.persistAttemptAndDeliver(result, deliveryBytes);
      if (!sha256(deliveryBytes).equals(result.compactTokenSha256())) {
        throw invalid();
      }
      return result;
    } finally {
      wipe(header);
      wipe(claims);
      wipe(encodedHeader);
      wipe(encodedClaims);
      wipe(signingInput);
      wipe(signatureBytes);
      wipe(encodedSignature);
      wipe(compactBytes);
      wipe(tokenDigest);
      wipe(deliveryBytes);
    }
  }

  /**
   * Signs only the exact Account-owned, initial Game Session delegation claim shape with the
   * already committed signer identity supplied by the owner service. The compact credential is
   * passed once to the Account persistence callback and wiped before this method returns.
   */
  static SignedDelegationDigest signCommittedGameplayDelegationDigest(
      Path privateMountRoot,
      Path privateBundlePath,
      Path publicMountRoot,
      Path publicJwksPath,
      ExpectedIdentity expectedIdentity,
      DelegationSigningSpec spec,
      DelegationCandidateConsumer candidateConsumer) {
    Objects.requireNonNull(spec, "Gameplay delegation signing specification is required");
    Objects.requireNonNull(candidateConsumer, "Gameplay delegation owner callback is required");
    VerifiedMountedSigner mounted =
        readAndVerifyMounted(
            privateMountRoot, privateBundlePath, publicMountRoot, publicJwksPath, expectedIdentity);
    AccountGameplayDelegationPendingIdentity identity = spec.identity();
    AccountAuthoritySnapshot authority = spec.authoritySnapshot();
    if (!mounted.observation().expectedIdentity().equals(expectedIdentity)
        || !authority.accountId().equals(identity.accountId())) {
      throw invalid();
    }

    byte[] header = null;
    byte[] claims = null;
    byte[] encodedHeader = null;
    byte[] encodedClaims = null;
    byte[] signingInput = null;
    byte[] signatureBytes = null;
    byte[] encodedSignature = null;
    byte[] compactBytes = null;
    byte[] tokenDigest = null;
    byte[] callbackBytes = null;
    try {
      Map<String, Object> headerFields = new LinkedHashMap<>();
      headerFields.put("alg", ALGORITHM);
      headerFields.put("kid", expectedIdentity.kid());
      headerFields.put("typ", "JWT");
      header = canonicalJsonBytes(headerFields);
      Map<String, Object> delegation = delegationClaims(identity, authority);
      if (spec.bound().isPresent()) {
        var bound = spec.bound().orElseThrow();
        delegation.put("tenantId", bound.tenantId());
        delegation.put("authorityTuple", bound.authorityTuple());
        delegation.put("membershipVersion", bound.membershipVersion());
      }
      net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator
          .validateClaims(delegation);
      claims = canonicalJsonBytes(delegation);
      encodedHeader = Base64.getUrlEncoder().withoutPadding().encode(header);
      encodedClaims = Base64.getUrlEncoder().withoutPadding().encode(claims);
      signingInput = joinCompactParts(encodedHeader, encodedClaims, null);

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(mounted.privateKey());
      signer.update(signingInput);
      signatureBytes = signer.sign();

      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(mounted.publicKey());
      verifier.update(signingInput);
      if (!verifier.verify(signatureBytes)) {
        throw invalid();
      }
      encodedSignature = Base64.getUrlEncoder().withoutPadding().encode(signatureBytes);
      compactBytes = joinCompactParts(encodedHeader, encodedClaims, encodedSignature);
      if (compactBytes.length > MAX_DELEGATION_COMPACT_JWT_BYTES) {
        throw invalid();
      }
      tokenDigest = MessageDigest.getInstance("SHA-256").digest(compactBytes);
      SignedDelegationDigest digest =
          new SignedDelegationDigest(
              identity.operationId(),
              identity.requestId(),
              identity.tokenJti(),
              expectedIdentity.operationId(),
              expectedIdentity.generation(),
              expectedIdentity.kid(),
              expectedIdentity.publicKeyFingerprint(),
              identity.issuedAtEpochSecond(),
              identity.notBeforeEpochSecond(),
              identity.expiresAtEpochSecond(),
              java.util.HexFormat.of().formatHex(tokenDigest));
      callbackBytes = compactBytes.clone();
      try {
        candidateConsumer.bindExactCandidate(digest, callbackBytes);
      } catch (RuntimeException failure) {
        // Callback exception chains must never retain the transient compact credential.
        throw invalid();
      }
      if (!sha256(callbackBytes).equals(digest.compactTokenSha256())) {
        throw invalid();
      }
      return digest;
    } catch (InvalidMountedSignerBundleException ex) {
      throw ex;
    } catch (Exception ex) {
      throw invalid();
    } finally {
      wipe(header);
      wipe(claims);
      wipe(encodedHeader);
      wipe(encodedClaims);
      wipe(signingInput);
      wipe(signatureBytes);
      wipe(encodedSignature);
      wipe(compactBytes);
      wipe(tokenDigest);
      wipe(callbackBytes);
    }
  }

  private static Map<String, Object> delegationClaims(
      AccountGameplayDelegationPendingIdentity identity, AccountAuthoritySnapshot authority) {
    if (!authority.accountId().equals(identity.accountId())) {
      throw invalid();
    }
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("sub", authority.accountId().toString());
    claims.put("jti", identity.tokenJti().toString());
    claims.put("accountId", authority.accountId().toString());
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
    claims.put("iat", identity.issuedAtEpochSecond());
    claims.put("nbf", identity.notBeforeEpochSecond());
    claims.put("exp", identity.expiresAtEpochSecond());
    claims.put("tokenGeneration", "1");
    claims.put(
        "authorityTuple",
        GameSessionAccountDelegationProfile.authorityTuple(
            authority.issuerGeneration(),
            authority.accountGeneration(),
            authority.accountSecurityCutoff()));
    claims.put("membershipVersion", Map.of());
    claims.put("issuanceFence", Long.toString(authority.issuanceFence()));
    return claims;
  }

  private static VerifiedMountedSigner readAndVerifyMounted(
      Path privateMountRoot,
      Path privateBundlePath,
      Path publicMountRoot,
      Path publicJwksPath,
      ExpectedIdentity expectedIdentity) {
    byte[] privateBytes = null;
    try {
      if (expectedIdentity == null) {
        throw invalid();
      }
      validateIdentity(expectedIdentity);
      Path privateFile = resolveMountedFile(privateMountRoot, privateBundlePath);
      Path publicFile = resolveMountedFile(publicMountRoot, publicJwksPath);
      privateBytes = readBounded(privateFile, MAX_PRIVATE_BUNDLE_BYTES);
      ParsedPrivateBundle privateBundle = parsePrivateBundle(privateBytes);
      Arrays.fill(privateBytes, (byte) 0);
      privateBytes = null;
      requireExpectedIdentity(privateBundle.identity(), expectedIdentity);

      byte[] publicBytes = readBounded(publicFile, MAX_PUBLIC_JWKS_BYTES);
      Map<String, RSAPublicKey> publicKeys;
      try {
        publicKeys = parsePublicJwks(publicBytes);
      } finally {
        Arrays.fill(publicBytes, (byte) 0);
      }
      RSAPublicKey publicKey = publicKeys.get(expectedIdentity.kid());
      if (publicKey == null
          || !publicKey.getModulus().equals(privateBundle.publicModulus())
          || !publicKey.getPublicExponent().equals(privateBundle.publicExponent())) {
        throw invalid();
      }
      String fingerprint = fingerprint(publicKey.getModulus(), publicKey.getPublicExponent());
      if (!fingerprint.equals(expectedIdentity.publicKeyFingerprint())
          || !fingerprint.equals(privateBundle.identity().publicKeyFingerprint())) {
        throw invalid();
      }
      proveCorrespondence(privateBundle.privateKey(), publicKey);
      return new VerifiedMountedSigner(
          new LocalObservation(expectedIdentity, fingerprint),
          privateBundle.privateKey(),
          publicKey);
    } catch (InvalidMountedSignerBundleException ex) {
      throw ex;
    } catch (Exception ex) {
      // Do not retain path, parser, key, or mounted-file details in a caller-visible failure.
      throw invalid();
    } finally {
      if (privateBytes != null) {
        Arrays.fill(privateBytes, (byte) 0);
      }
    }
  }

  private static Map<String, Object> readinessClaims(ReadinessProbeSigningSpec spec) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("sub", RESERVED_SUBJECT);
    claims.put("jti", spec.jti().toString());
    claims.put("aud", spec.audience());
    claims.put("iat", spec.issuedAtEpochSecond());
    claims.put("nbf", spec.issuedAtEpochSecond());
    claims.put("exp", spec.expiresAtEpochSecond());
    if (spec.probeKind() == ProbeKind.CANARY) {
      claims.put("tokenProfile", READINESS_CANARY_PROFILE);
      claims.put("tokenType", READINESS_CANARY_TYPE);
      return claims;
    }
    if (ControlUiJwtProfileValidator.PROFILE.equals(spec.tokenProfile())
        || PlayerBootstrapJwtProfileValidator.PROFILE.equals(spec.tokenProfile())) {
      claims.put("scopedRoles", Map.of());
    }
    boolean decimalCounters =
        GameSessionAccountDelegationProfile.PROFILE.equals(spec.tokenProfile());
    Object initialCounter = decimalCounters ? "1" : 1L;
    claims.put("accountId", RESERVED_SUBJECT);
    claims.put("tokenGeneration", initialCounter);
    Map<String, Object> authorityTuple = new LinkedHashMap<>();
    authorityTuple.put("issuerAuthGeneration", initialCounter);
    authorityTuple.put("accountAuthorityGeneration", initialCounter);
    authorityTuple.put("tenantAuthorityGeneration", Map.of());
    authorityTuple.put("membershipAuthorityGeneration", Map.of());
    authorityTuple.put("privateRealmGrantVersions", List.of());
    claims.put("authorityTuple", authorityTuple);
    claims.put("membershipVersion", Map.of());
    claims.put("issuanceFence", initialCounter);
    return claims;
  }

  private static byte[] canonicalJsonBytes(Map<String, Object> fields) throws Exception {
    String json = STRICT_JSON.writeValueAsString(fields);
    return Rfc8785CanonicalJson.canonicalizeUtf8(json);
  }

  private static byte[] joinCompactParts(byte[] header, byte[] claims, byte[] signature) {
    int signatureLength = signature == null ? 0 : signature.length;
    int separatorCount = signature == null ? 1 : 2;
    byte[] compact = new byte[header.length + claims.length + signatureLength + separatorCount];
    int offset = 0;
    System.arraycopy(header, 0, compact, offset, header.length);
    offset += header.length;
    compact[offset++] = (byte) '.';
    System.arraycopy(claims, 0, compact, offset, claims.length);
    offset += claims.length;
    if (signature != null) {
      compact[offset++] = (byte) '.';
      System.arraycopy(signature, 0, compact, offset, signature.length);
    }
    return compact;
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (GeneralSecurityException ex) {
      throw invalid();
    }
  }

  /**
   * Builds the binary, domain-separated challenge used only by the local correspondence proof. Its
   * leading NUL makes it structurally distinct from an ASCII compact JWT serialization.
   */
  static byte[] correspondenceChallenge(byte[] nonce) {
    if (nonce == null || nonce.length != CHALLENGE_NONCE_BYTES) {
      throw invalid();
    }
    byte[] challenge = Arrays.copyOf(CHALLENGE_DOMAIN, CHALLENGE_DOMAIN.length + nonce.length);
    System.arraycopy(nonce, 0, challenge, CHALLENGE_DOMAIN.length, nonce.length);
    return challenge;
  }

  static void validateStrongRsaCrtPrivateKey(PrivateKey privateKey) {
    if (!(privateKey instanceof RSAPrivateCrtKey rsaPrivateKey)
        || !"RSA".equalsIgnoreCase(privateKey.getAlgorithm())) {
      throw invalid();
    }
    BigInteger modulus = rsaPrivateKey.getModulus();
    BigInteger publicExponent = rsaPrivateKey.getPublicExponent();
    BigInteger privateExponent = rsaPrivateKey.getPrivateExponent();
    BigInteger primeP = rsaPrivateKey.getPrimeP();
    BigInteger primeQ = rsaPrivateKey.getPrimeQ();
    BigInteger primeExponentP = rsaPrivateKey.getPrimeExponentP();
    BigInteger primeExponentQ = rsaPrivateKey.getPrimeExponentQ();
    BigInteger crtCoefficient = rsaPrivateKey.getCrtCoefficient();
    if (!positive(modulus)
        || modulus.bitLength() < MIN_RSA_BITS
        || modulus.bitLength() > MAX_RSA_BITS
        || !BigInteger.valueOf(65_537).equals(publicExponent)
        || !positive(privateExponent)
        || !positive(primeP)
        || !positive(primeQ)
        || primeP.equals(primeQ)
        || !positive(primeExponentP)
        || !positive(primeExponentQ)
        || !positive(crtCoefficient)) {
      throw invalid();
    }
    BigInteger pMinusOne = primeP.subtract(BigInteger.ONE);
    BigInteger qMinusOne = primeQ.subtract(BigInteger.ONE);
    BigInteger lambda = pMinusOne.divide(pMinusOne.gcd(qMinusOne)).multiply(qMinusOne);
    if (!primeP.multiply(primeQ).equals(modulus)
        || !privateExponent.multiply(publicExponent).mod(lambda).equals(BigInteger.ONE)
        || !privateExponent.mod(pMinusOne).equals(primeExponentP)
        || !privateExponent.mod(qMinusOne).equals(primeExponentQ)
        || !primeQ.modInverse(primeP).equals(crtCoefficient)) {
      throw invalid();
    }
  }

  private static void validateIdentity(ExpectedIdentity identity) {
    if (!validIdentityValue(identity.environmentId())
        || !validIdentityValue(identity.clusterId())
        || !validIdentityValue(identity.namespace())
        || !validUuidV4(identity.operationId())
        || identity.generation() == null
        || !GENERATION.matcher(identity.generation()).matches()
        || identity.kid() == null
        || !KID.matcher(identity.kid()).matches()
        || identity.publicKeyFingerprint() == null
        || !FINGERPRINT.matcher(identity.publicKeyFingerprint()).matches()) {
      throw invalid();
    }
  }

  private static boolean validIdentityValue(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean validUuidV4(String value) {
    if (value == null) {
      return false;
    }
    try {
      UUID uuid = UUID.fromString(value);
      return uuid.toString().equals(value) && uuid.version() == 4 && uuid.variant() == 2;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static void requireExpectedIdentity(ExpectedIdentity actual, ExpectedIdentity expected) {
    if (!actual.equals(expected)) {
      throw invalid();
    }
  }

  private static ParsedPrivateBundle parsePrivateBundle(byte[] contents) {
    if (contents == null || contents.length == 0 || contents.length > MAX_PRIVATE_BUNDLE_BYTES) {
      throw invalid();
    }
    try {
      JsonNode root = strictJson(contents);
      requireOnlyFields(root, PRIVATE_BUNDLE_FIELDS);
      if (root.size() != PRIVATE_BUNDLE_FIELDS.size()) {
        throw invalid();
      }
      JsonNode version = root.get("version");
      if (version == null
          || version.isNull()
          || !version.isIntegralNumber()
          || !version.canConvertToInt()
          || version.intValue() != 1) {
        throw invalid();
      }
      ExpectedIdentity identity =
          new ExpectedIdentity(
              requiredText(root, "environmentId", 128),
              requiredText(root, "clusterId", 128),
              requiredText(root, "namespace", 128),
              requiredText(root, "operationId", 36),
              requiredText(root, "generation", 64),
              requiredText(root, "kid", 64),
              requiredText(root, "publicKeyFingerprint", 64));
      validateIdentity(identity);
      if (!ALGORITHM.equals(requiredText(root, "algorithm", 8))) {
        throw invalid();
      }

      String encodedPrivateKey = requiredText(root, "privateKeyPkcs8", 24 * 1024);
      byte[] privateDer = decodeCanonicalBase64Url(encodedPrivateKey, MAX_PRIVATE_KEY_DER_BYTES);
      try {
        PrivateKey privateKey =
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateDer));
        validateStrongRsaCrtPrivateKey(privateKey);
        RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
        byte[] canonicalEncoding = privateKey.getEncoded();
        try {
          if (canonicalEncoding == null || !Arrays.equals(privateDer, canonicalEncoding)) {
            throw invalid();
          }
        } finally {
          if (canonicalEncoding != null) {
            Arrays.fill(canonicalEncoding, (byte) 0);
          }
        }
        return new ParsedPrivateBundle(
            privateKey, identity, rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent());
      } finally {
        Arrays.fill(privateDer, (byte) 0);
      }
    } catch (InvalidMountedSignerBundleException ex) {
      throw ex;
    } catch (CharacterCodingException | GeneralSecurityException | RuntimeException ex) {
      throw invalid();
    }
  }

  private static Map<String, RSAPublicKey> parsePublicJwks(byte[] contents) {
    if (contents == null || contents.length == 0 || contents.length > MAX_PUBLIC_JWKS_BYTES) {
      throw invalid();
    }
    try {
      JsonNode root = strictJson(contents);
      requireOnlyFields(root, JWKS_FIELDS);
      if (root.size() != 1) {
        throw invalid();
      }
      JsonNode keysNode = root.get("keys");
      if (keysNode == null
          || !keysNode.isArray()
          || keysNode.isEmpty()
          || keysNode.size() > MAX_JWKS_KEYS) {
        throw invalid();
      }
      Map<String, RSAPublicKey> keys = new HashMap<>();
      Set<String> seenKids = new HashSet<>();
      for (JsonNode jwk : keysNode) {
        requireOnlyFields(jwk, JWK_FIELDS);
        String keyType = requiredText(jwk, "kty", 8);
        String use = requiredText(jwk, "use", 8);
        String algorithm = requiredText(jwk, "alg", 8);
        String kid = requiredText(jwk, "kid", 64);
        String encodedModulus = requiredText(jwk, "n", 4_096);
        String encodedExponent = requiredText(jwk, "e", 16);
        if (!"RSA".equals(keyType)
            || !"sig".equals(use)
            || !ALGORITHM.equals(algorithm)
            || !KID.matcher(kid).matches()
            || !seenKids.add(kid)) {
          throw invalid();
        }
        JsonNode keyOps = jwk.get("key_ops");
        if (keyOps != null) {
          if (!keyOps.isArray()
              || keyOps.size() != 1
              || !keyOps.get(0).isTextual()
              || !"verify".equals(keyOps.get(0).textValue())) {
            throw invalid();
          }
        }
        byte[] modulusBytes = decodeCanonicalBase64Url(encodedModulus, 2_048);
        byte[] exponentBytes = decodeCanonicalBase64Url(encodedExponent, 8);
        try {
          if (modulusBytes.length == 0
              || modulusBytes[0] == 0
              || exponentBytes.length == 0
              || exponentBytes[0] == 0) {
            throw invalid();
          }
          BigInteger modulus = new BigInteger(1, modulusBytes);
          BigInteger exponent = new BigInteger(1, exponentBytes);
          if (modulus.bitLength() < MIN_RSA_BITS
              || modulus.bitLength() > MAX_RSA_BITS
              || !modulus.testBit(0)
              || !BigInteger.valueOf(65_537).equals(exponent)) {
            throw invalid();
          }
          RSAPublicKey publicKey =
              (RSAPublicKey)
                  KeyFactory.getInstance("RSA")
                      .generatePublic(new RSAPublicKeySpec(modulus, exponent));
          keys.put(kid, publicKey);
        } finally {
          Arrays.fill(modulusBytes, (byte) 0);
          Arrays.fill(exponentBytes, (byte) 0);
        }
      }
      return Map.copyOf(keys);
    } catch (InvalidMountedSignerBundleException ex) {
      throw ex;
    } catch (CharacterCodingException | GeneralSecurityException | RuntimeException ex) {
      throw invalid();
    }
  }

  private static JsonNode strictJson(byte[] contents) throws CharacterCodingException {
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(contents))
            .toString();
    if (text.startsWith("\uFEFF")) {
      throw invalid();
    }
    JsonNode root = STRICT_JSON.readTree(text);
    if (root == null || root.isNull()) {
      throw invalid();
    }
    return root;
  }

  private static void requireOnlyFields(JsonNode node, Set<String> allowedFields) {
    if (node == null || !node.isObject()) {
      throw invalid();
    }
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if (!allowedFields.contains(field.getKey())) {
        throw invalid();
      }
    }
  }

  private static String requiredText(JsonNode object, String name, int maxLength) {
    JsonNode value = object.get(name);
    if (value == null || value.isNull() || !value.isTextual()) {
      throw invalid();
    }
    String text = value.textValue();
    if (text == null || text.isEmpty() || text.length() > maxLength) {
      throw invalid();
    }
    return text;
  }

  private static byte[] decodeCanonicalBase64Url(String encoded, int maxBytes) {
    if (encoded == null || encoded.isEmpty()) {
      throw invalid();
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
    byte[] canonical = Base64.getUrlEncoder().withoutPadding().encode(decoded);
    boolean canonicalEncoding = canonical.length == encoded.length();
    for (int index = 0; canonicalEncoding && index < canonical.length; index++) {
      canonicalEncoding = (char) Byte.toUnsignedInt(canonical[index]) == encoded.charAt(index);
    }
    Arrays.fill(canonical, (byte) 0);
    if (decoded.length == 0 || decoded.length > maxBytes || !canonicalEncoding) {
      Arrays.fill(decoded, (byte) 0);
      throw invalid();
    }
    return decoded;
  }

  private static Path resolveMountedFile(Path configuredRoot, Path configuredRelativeFile)
      throws IOException {
    if (configuredRoot == null
        || configuredRelativeFile == null
        || !configuredRoot.isAbsolute()
        || configuredRelativeFile.isAbsolute()
        || configuredRelativeFile.toString().isBlank()) {
      throw invalid();
    }
    for (Path segment : configuredRelativeFile) {
      if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
        throw invalid();
      }
    }
    Path mountRoot = configuredRoot.toRealPath();
    if (!Files.isDirectory(mountRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw invalid();
    }
    Path resolvedFile = mountRoot.resolve(configuredRelativeFile).toRealPath();
    if (!resolvedFile.startsWith(mountRoot)
        || !Files.isRegularFile(resolvedFile, LinkOption.NOFOLLOW_LINKS)) {
      throw invalid();
    }
    return resolvedFile;
  }

  private static byte[] readBounded(Path path, int maxBytes) throws IOException {
    byte[] scratch = new byte[maxBytes + 1];
    try {
      try (SeekableByteChannel channel =
          Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        ByteBuffer buffer = ByteBuffer.wrap(scratch);
        int total = 0;
        while (true) {
          int count = channel.read(buffer);
          if (count < 0) {
            break;
          }
          if (count == 0) {
            continue;
          }
          total += count;
          if (total > maxBytes) {
            throw invalid();
          }
        }
        if (channel.position() != channel.size() || total != channel.size()) {
          throw invalid();
        }
        return Arrays.copyOf(scratch, total);
      }
    } finally {
      Arrays.fill(scratch, (byte) 0);
    }
  }

  private static void proveCorrespondence(PrivateKey privateKey, RSAPublicKey publicKey)
      throws GeneralSecurityException {
    byte[] nonce = new byte[CHALLENGE_NONCE_BYTES];
    byte[] challenge = null;
    byte[] signatureBytes = null;
    try {
      CHALLENGE_RANDOM.nextBytes(nonce);
      challenge = correspondenceChallenge(nonce);
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(challenge);
      signatureBytes = signer.sign();

      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(challenge);
      if (!verifier.verify(signatureBytes)) {
        throw invalid();
      }
    } finally {
      Arrays.fill(nonce, (byte) 0);
      if (challenge != null) {
        Arrays.fill(challenge, (byte) 0);
      }
      if (signatureBytes != null) {
        Arrays.fill(signatureBytes, (byte) 0);
      }
    }
  }

  private static String fingerprint(BigInteger modulus, BigInteger exponent) {
    String canonicalJwk =
        "{\"e\":\""
            + base64Url(unsignedBytes(exponent))
            + "\",\"kty\":\"RSA\",\"n\":\""
            + base64Url(unsignedBytes(modulus))
            + "\"}";
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalJwk.getBytes(StandardCharsets.US_ASCII));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException ex) {
      throw invalid();
    }
  }

  private static byte[] unsignedBytes(BigInteger value) {
    byte[] encoded = value.toByteArray();
    if (encoded.length > 1 && encoded[0] == 0) {
      return Arrays.copyOfRange(encoded, 1, encoded.length);
    }
    return encoded;
  }

  private static String base64Url(byte[] bytes) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static boolean positive(BigInteger value) {
    return value != null && value.signum() > 0;
  }

  private static InvalidMountedSignerBundleException invalid() {
    return new InvalidMountedSignerBundleException();
  }

  /** Caller-supplied durable request identity; it is not authorization or proof of provisioning. */
  public record ExpectedIdentity(
      String environmentId,
      String clusterId,
      String namespace,
      String operationId,
      String generation,
      String kid,
      String publicKeyFingerprint) {}

  /** Closed signing input built only from the issuance repository's locked owner readback. */
  record DelegationSigningSpec(
      AccountGameplayDelegationPendingIdentity identity,
      AccountAuthoritySnapshot authoritySnapshot,
      Optional<BoundDelegationClaims> bound) {
    DelegationSigningSpec(
        AccountGameplayDelegationPendingIdentity identity,
        AccountAuthoritySnapshot authoritySnapshot) {
      this(identity, authoritySnapshot, Optional.empty());
    }

    DelegationSigningSpec {
      Objects.requireNonNull(bound);
      Objects.requireNonNull(identity, "Persisted pending identity is required");
      Objects.requireNonNull(authoritySnapshot, "Persisted Account authority is required");
      if (!authoritySnapshot.accountId().equals(identity.accountId())
          || identity.issuedAtEpochSecond() <= 0L
          || identity.notBeforeEpochSecond() <= 0L
          || identity.notBeforeEpochSecond() > identity.issuedAtEpochSecond()
          || identity.expiresAtEpochSecond() <= identity.issuedAtEpochSecond()
          || identity.expiresAtEpochSecond() - identity.issuedAtEpochSecond()
              > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
        throw invalid();
      }
    }
  }

  record BoundDelegationClaims(
      String tenantId, Map<String, Object> authorityTuple, Map<String, String> membershipVersion) {
    BoundDelegationClaims {
      net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile
          .requirePublicTenantBoundAuthority(tenantId, authorityTuple, membershipVersion);
      authorityTuple = Map.copyOf(authorityTuple);
      membershipVersion = Map.copyOf(membershipVersion);
    }
  }

  @FunctionalInterface
  interface DelegationCandidateConsumer {
    /** The caller persists the exact candidate before any internal transient handoff. */
    void bindExactCandidate(SignedDelegationDigest digest, byte[] compactJwt);
  }

  /** Non-secret operation, signer and token-hash identity; never includes compact JWT bytes. */
  static final class SignedDelegationDigest {
    private final UUID issuanceOperationId;
    private final UUID requestId;
    private final UUID jti;
    private final String signerOperationId;
    private final String signerGeneration;
    private final String kid;
    private final String publicKeyFingerprint;
    private final long issuedAtEpochSecond;
    private final long notBeforeEpochSecond;
    private final long expiresAtEpochSecond;
    private final String compactTokenSha256;

    private SignedDelegationDigest(
        UUID issuanceOperationId,
        UUID requestId,
        UUID jti,
        String signerOperationId,
        String signerGeneration,
        String kid,
        String publicKeyFingerprint,
        long issuedAtEpochSecond,
        long notBeforeEpochSecond,
        long expiresAtEpochSecond,
        String compactTokenSha256) {
      this.issuanceOperationId = Objects.requireNonNull(issuanceOperationId);
      this.requestId = Objects.requireNonNull(requestId);
      this.jti = Objects.requireNonNull(jti);
      this.signerOperationId = Objects.requireNonNull(signerOperationId);
      this.signerGeneration = Objects.requireNonNull(signerGeneration);
      this.kid = Objects.requireNonNull(kid);
      this.publicKeyFingerprint = Objects.requireNonNull(publicKeyFingerprint);
      this.issuedAtEpochSecond = issuedAtEpochSecond;
      this.notBeforeEpochSecond = notBeforeEpochSecond;
      this.expiresAtEpochSecond = expiresAtEpochSecond;
      if (compactTokenSha256 == null || !FINGERPRINT.matcher(compactTokenSha256).matches()) {
        throw invalid();
      }
      this.compactTokenSha256 = compactTokenSha256;
    }

    UUID issuanceOperationId() {
      return issuanceOperationId;
    }

    UUID requestId() {
      return requestId;
    }

    UUID jti() {
      return jti;
    }

    String signerOperationId() {
      return signerOperationId;
    }

    String signerGeneration() {
      return signerGeneration;
    }

    String kid() {
      return kid;
    }

    String publicKeyFingerprint() {
      return publicKeyFingerprint;
    }

    long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    long notBeforeEpochSecond() {
      return notBeforeEpochSecond;
    }

    long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    String compactTokenSha256() {
      return compactTokenSha256;
    }

    @Override
    public String toString() {
      return "SignedDelegationDigest[redacted]";
    }
  }

  public enum ProbeKind {
    CANARY,
    REPRESENTATIVE
  }

  @FunctionalInterface
  interface ProbeDeliveryConsumer {
    /** The caller must durably pin {@code digest} before passing the transient bytes onward. */
    void persistAttemptAndDeliver(SignedProbeDigest digest, byte[] compactJwt);
  }

  /** A closed claim-shape input; its constructor permits only the two declared readiness forms. */
  record ReadinessProbeSigningSpec(
      String validatorId,
      ProbeKind probeKind,
      String tokenProfile,
      String audience,
      UUID jti,
      String targetGeneration,
      String targetKid,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond) {
    ReadinessProbeSigningSpec {
      if (validatorId == null
          || !READINESS_VALIDATOR_ID.equals(validatorId)
          || probeKind == null
          || jti == null
          || jti.version() != 4
          || jti.variant() != 2
          || targetGeneration == null
          || !GENERATION.matcher(targetGeneration).matches()
          || targetKid == null
          || !KID.matcher(targetKid).matches()
          || issuedAtEpochSecond <= 0L
          || expiresAtEpochSecond <= issuedAtEpochSecond
          || expiresAtEpochSecond - issuedAtEpochSecond > 300L) {
        throw invalid();
      }
      if (probeKind == ProbeKind.CANARY) {
        if (!READINESS_CANARY_PROFILE.equals(tokenProfile)
            || !READINESS_CANARY_AUDIENCE.equals(audience)) {
          throw invalid();
        }
      } else {
        boolean exactProfileAudience =
            (ControlUiJwtProfileValidator.PROFILE.equals(tokenProfile)
                    && ControlUiJwtProfileValidator.AUDIENCE.equals(audience))
                || (PlayerBootstrapJwtProfileValidator.PROFILE.equals(tokenProfile)
                    && PlayerBootstrapJwtProfileValidator.AUDIENCE.equals(audience))
                || (REPRESENTATIVE_PROFILE.equals(tokenProfile)
                    && REPRESENTATIVE_AUDIENCE.equals(audience));
        if (!exactProfileAudience) {
          throw invalid();
        }
      }
    }
  }

  /** Non-secret output from the one constrained signing operation; it never contains JWT bytes. */
  public static final class SignedProbeDigest {
    private final String operationId;
    private final String validatorId;
    private final ProbeKind probeKind;
    private final String tokenProfile;
    private final String audience;
    private final UUID jti;
    private final String targetGeneration;
    private final String targetKid;
    private final String publicKeyFingerprint;
    private final long issuedAtEpochSecond;
    private final long expiresAtEpochSecond;
    private final String compactTokenSha256;

    private SignedProbeDigest(
        String operationId,
        String validatorId,
        ProbeKind probeKind,
        String tokenProfile,
        String audience,
        UUID jti,
        String targetGeneration,
        String targetKid,
        String publicKeyFingerprint,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond,
        String compactTokenSha256) {
      this.operationId = operationId;
      this.validatorId = validatorId;
      this.probeKind = probeKind;
      this.tokenProfile = tokenProfile;
      this.audience = audience;
      this.jti = jti;
      this.targetGeneration = targetGeneration;
      this.targetKid = targetKid;
      this.publicKeyFingerprint = publicKeyFingerprint;
      this.issuedAtEpochSecond = issuedAtEpochSecond;
      this.expiresAtEpochSecond = expiresAtEpochSecond;
      this.compactTokenSha256 = compactTokenSha256;
    }

    public String operationId() {
      return operationId;
    }

    public String validatorId() {
      return validatorId;
    }

    public ProbeKind probeKind() {
      return probeKind;
    }

    public String tokenProfile() {
      return tokenProfile;
    }

    public String audience() {
      return audience;
    }

    public UUID jti() {
      return jti;
    }

    public String targetGeneration() {
      return targetGeneration;
    }

    public String targetKid() {
      return targetKid;
    }

    public String publicKeyFingerprint() {
      return publicKeyFingerprint;
    }

    public long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public String compactTokenSha256() {
      return compactTokenSha256;
    }

    @Override
    public String toString() {
      return "AccountMountedJwtSignerBundle.SignedProbeDigest[operation="
          + operationId
          + ", probeKind="
          + probeKind
          + ", tokenBytes=discarded, authorization=none, verification=unproved]";
    }
  }

  /** Non-secret, process-local observation with no token-signing or private-key accessor. */
  public static final class LocalObservation {
    private final ExpectedIdentity expectedIdentity;
    private final String publicKeyFingerprint;

    private LocalObservation(ExpectedIdentity expectedIdentity, String publicKeyFingerprint) {
      this.expectedIdentity = expectedIdentity;
      this.publicKeyFingerprint = publicKeyFingerprint;
    }

    public ExpectedIdentity expectedIdentity() {
      return expectedIdentity;
    }

    public String publicKeyFingerprint() {
      return publicKeyFingerprint;
    }

    public boolean localPrivatePublicCorrespondenceObserved() {
      return true;
    }

    @Override
    public String toString() {
      return "AccountMountedJwtSignerBundle.LocalObservation[local correspondence observed; "
          + "authorization=none, provisioning=unproved, readiness=unproved]";
    }
  }

  /** Safe generic failure for malformed, unavailable, or mismatched mounted projections. */
  public static final class InvalidMountedSignerBundleException extends IllegalArgumentException {
    private InvalidMountedSignerBundleException() {
      super("Mounted signer correspondence is unavailable or invalid");
    }
  }

  private record ParsedPrivateBundle(
      PrivateKey privateKey,
      ExpectedIdentity identity,
      BigInteger publicModulus,
      BigInteger publicExponent) {}

  private record VerifiedMountedSigner(
      LocalObservation observation, PrivateKey privateKey, RSAPublicKey publicKey) {}
}
