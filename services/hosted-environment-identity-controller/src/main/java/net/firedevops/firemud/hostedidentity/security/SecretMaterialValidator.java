package net.firedevops.firemud.hostedidentity.security;

import io.fabric8.kubernetes.api.model.Secret;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates public certificate material while deliberately never returning secret bytes. */
@Component
public class SecretMaterialValidator {
  private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
  private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
  private static final Pattern CERTIFICATE_BLOCK =
      Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);
  private static final Pattern PRIVATE_KEY_BLOCK =
      Pattern.compile(
          "\\A\\s*-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----\\s*\\z",
          Pattern.DOTALL);

  /**
   * Validates transport material using subset EKU matching. Use {@link #validateIdentity} when the
   * identity profile requires an exact EKU set.
   */
  public MaterialSummary validate(
      Secret secret,
      String expectedHostname,
      String expectedType,
      boolean requireClientAuth,
      String expectedTrustAnchorSha256) {
    if (expectedHostname == null) {
      throw new MaterialValidationException("expected hostname is required");
    }
    return validate(
        secret,
        Set.of(expectedHostname),
        expectedType,
        requireClientAuth,
        expectedTrustAnchorSha256);
  }

  /**
   * Validates transport material using subset EKU matching. Use {@link #validateIdentity} when the
   * identity profile requires an exact EKU set.
   */
  public MaterialSummary validate(
      Secret secret,
      Collection<String> expectedDnsNames,
      String expectedType,
      boolean requireClientAuth,
      String expectedTrustAnchorSha256) {
    return validateIdentity(
        secret,
        expectedDnsNames,
        List.of(),
        expectedType,
        true,
        requireClientAuth,
        expectedTrustAnchorSha256,
        false);
  }

  public MaterialSummary validateIdentity(
      Secret secret,
      Collection<String> expectedDnsNames,
      Collection<String> expectedUriSans,
      String expectedType,
      boolean requireServerAuth,
      boolean requireClientAuth,
      String expectedTrustAnchorSha256) {
    return validateIdentity(
        secret,
        expectedDnsNames,
        expectedUriSans,
        expectedType,
        requireServerAuth,
        requireClientAuth,
        expectedTrustAnchorSha256,
        true);
  }

  private MaterialSummary validateIdentity(
      Secret secret,
      Collection<String> expectedDnsNames,
      Collection<String> expectedUriSans,
      String expectedType,
      boolean requireServerAuth,
      boolean requireClientAuth,
      String expectedTrustAnchorSha256,
      boolean exactUsages) {
    if (secret == null || secret.getData() == null) {
      throw new MaterialValidationException("owned Secret is missing data");
    }
    // A bound cert-manager predecessor may retain its native TLS type while an Opaque expectation
    // keeps fixed trust-anchor validation mandatory below.
    if (!expectedType.equals(secret.getType())
        && !("Opaque".equals(expectedType) && "kubernetes.io/tls".equals(secret.getType()))) {
      throw new MaterialValidationException("owned Secret has an unexpected type");
    }
    Map<String, String> data = secret.getData();
    String certificatePem = data.get("tls.crt");
    if (certificatePem == null) {
      certificatePem = data.get("client.crt");
    }
    String privateKeyPem = data.get("tls.key");
    if (privateKeyPem == null) {
      privateKeyPem = data.get("client.key");
    }
    if (certificatePem == null || privateKeyPem == null) {
      throw new MaterialValidationException("owned Secret is missing certificate or key");
    }
    try {
      List<X509Certificate> presentedChain = parseCertificates(certificatePem);
      X509Certificate certificate = presentedChain.get(0);
      presentedChain.forEach(SecretMaterialValidator::checkValidity);
      validateSans(certificate, expectedDnsNames, expectedUriSans);
      validateLeafProfile(certificate);
      validateUsage(certificate, requireServerAuth, requireClientAuth, exactUsages);
      PrivateKey privateKey = parsePrivateKey(privateKeyPem);
      if (!(privateKey instanceof RSAPrivateCrtKey rsaKey)
          || !certificate
              .getPublicKey()
              .equals(
                  KeyFactory.getInstance("RSA")
                      .generatePublic(
                          new RSAPublicKeySpec(rsaKey.getModulus(), rsaKey.getPublicExponent())))) {
        throw new MaterialValidationException("certificate and private key do not match");
      }
      boolean requireConfiguredAnchor = "Opaque".equals(expectedType);
      if (requireConfiguredAnchor
          && (expectedTrustAnchorSha256 == null || expectedTrustAnchorSha256.isBlank())) {
        throw new MaterialValidationException("configured certificate trust anchor is required");
      }
      String expectedAnchor =
          normalize(expectedTrustAnchorSha256 == null ? "" : expectedTrustAnchorSha256);
      if (!expectedAnchor.isBlank() && !expectedAnchor.matches("[0-9a-f]{64}")) {
        throw new MaterialValidationException("configured certificate trust anchor is invalid");
      }
      String chainFingerprint = validateChain(data.get("ca.crt"), presentedChain, expectedAnchor);
      return new MaterialSummary(
          sha256(certificate.getEncoded()),
          sha256(certificate.getPublicKey().getEncoded()),
          certificate.getNotBefore().toInstant(),
          certificate.getNotAfter().toInstant(),
          chainFingerprint);
    } catch (MaterialValidationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new MaterialValidationException(
          "owned Secret certificate material is invalid", exception);
    }
  }

  static String trustAnchorFingerprint(Secret secret) {
    try {
      if (secret == null || secret.getData() == null || secret.getData().get("ca.crt") == null) {
        throw new IllegalArgumentException("Secret has no ca.crt");
      }
      List<X509Certificate> certificates = parseCertificates(secret.getData().get("ca.crt"));
      if (certificates.size() != 1) {
        throw new IllegalArgumentException(
            "Secret ca.crt must contain exactly one X.509 certificate");
      }
      return sha256(certificates.get(0).getEncoded());
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Secret has invalid ca.crt", exception);
    }
  }

  private static List<X509Certificate> parseCertificates(String encodedPem) throws Exception {
    String pem = new String(Base64.getDecoder().decode(encodedPem), StandardCharsets.US_ASCII);
    Matcher matcher = CERTIFICATE_BLOCK.matcher(pem);
    List<X509Certificate> certificates = new ArrayList<>();
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    int consumed = 0;
    while (matcher.find()) {
      if (!pem.substring(consumed, matcher.start()).isBlank()) {
        throw new MaterialValidationException("certificate PEM contains an unexpected label");
      }
      String body = matcher.group(1).replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(body);
      certificates.add(
          (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
      consumed = matcher.end();
    }
    if (certificates.isEmpty()) {
      throw new MaterialValidationException("certificate PEM contains no X.509 certificate");
    }
    if (!pem.substring(consumed).isBlank()) {
      throw new MaterialValidationException("certificate PEM contains unexpected trailing content");
    }
    return certificates;
  }

  private static PrivateKey parsePrivateKey(String pem) throws Exception {
    byte[] der = pemBytes(pem);
    return KeyFactory.getInstance("RSA")
        .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
  }

  private static byte[] pemBytes(String pem) {
    String decodedPem = new String(Base64.getDecoder().decode(pem), StandardCharsets.US_ASCII);
    Matcher matcher = PRIVATE_KEY_BLOCK.matcher(decodedPem);
    if (!matcher.matches()) {
      throw new MaterialValidationException("private key PEM must use the PRIVATE KEY label");
    }
    return Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
  }

  private static void validateSans(
      X509Certificate certificate,
      Collection<String> expectedDnsNames,
      Collection<String> expectedUriSans)
      throws Exception {
    Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
    Set<String> expected =
        expectedDnsNames.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<String> expectedUris = Set.copyOf(expectedUriSans);
    Set<String> actual = new HashSet<>();
    Set<String> actualUris = new HashSet<>();
    if (sans != null) {
      for (List<?> entry : sans) {
        if (entry.size() < 2) {
          throw new MaterialValidationException("certificate contains a malformed SAN entry");
        }
        if (Integer.valueOf(2).equals(entry.get(0))) {
          actual.add(String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT));
        } else if (Integer.valueOf(6).equals(entry.get(0))) {
          actualUris.add(String.valueOf(entry.get(1)));
        } else {
          throw new MaterialValidationException("certificate contains an unsupported SAN");
        }
      }
    }
    if (!actual.equals(expected) || !actualUris.equals(expectedUris)) {
      throw new MaterialValidationException(
          "certificate SANs do not exactly match the derived names");
    }
  }

  static void validateUsage(
      X509Certificate certificate,
      boolean requireServerAuth,
      boolean requireClientAuth,
      boolean exactUsages)
      throws Exception {
    List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
    Set<String> actual = extendedKeyUsage == null ? Set.of() : Set.copyOf(extendedKeyUsage);
    Set<String> expected = new HashSet<>();
    if (requireServerAuth) {
      expected.add(SERVER_AUTH);
    }
    if (requireClientAuth) {
      expected.add(CLIENT_AUTH);
    }
    if (exactUsages && !actual.equals(expected)) {
      throw new MaterialValidationException(
          "certificate EKUs do not exactly match the identity profile");
    }
    if (!exactUsages && !actual.containsAll(expected)) {
      throw new MaterialValidationException("certificate is missing a required transport EKU");
    }
  }

  static void validateLeafProfile(X509Certificate certificate) {
    if (certificate.getBasicConstraints() >= 0) {
      throw new MaterialValidationException("certificate leaf must not be a CA");
    }
    boolean[] usage = certificate.getKeyUsage();
    if (usage == null || usage.length < 3 || !usage[0] || !usage[2]) {
      throw new MaterialValidationException(
          "certificate is missing digitalSignature/keyEncipherment key usages");
    }
    for (int index = 0; index < usage.length; index++) {
      if (usage[index] && index != 0 && index != 2) {
        throw new MaterialValidationException(
            "certificate key usages do not exactly match digitalSignature/keyEncipherment");
      }
    }
  }

  static boolean caKeyUsageAllowsSigning(X509Certificate certificate) {
    boolean[] usage = certificate.getKeyUsage();
    // An omitted extension is accepted; when present, it must allow certificate signing.
    return usage == null || (usage.length > 5 && usage[5]);
  }

  private static String validateChain(
      String caPem, List<X509Certificate> presentedChain, String expectedAnchor) throws Exception {
    List<X509Certificate> anchors =
        caPem == null || caPem.isBlank() ? List.of() : parseCertificates(caPem);
    anchors.forEach(SecretMaterialValidator::checkValidity);
    Set<String> used = new HashSet<>();
    X509Certificate current = presentedChain.get(0);
    used.add(sha256(current.getEncoded()));
    X509Certificate anchor = null;
    int subordinateCaDepth = 0;
    List<X509Certificate> candidates = new ArrayList<>(presentedChain);
    candidates.addAll(anchors);
    for (int depth = 0; depth <= candidates.size(); depth++) {
      X509Certificate next = verifiedIssuer(current, candidates, used);
      if (next == null) {
        break;
      }
      String fingerprint = sha256(next.getEncoded());
      used.add(fingerprint);
      boolean isConfiguredAnchor =
          anchors.stream()
              .anyMatch(certificate -> fingerprint.equals(sha256Unchecked(certificate)));
      if (isConfiguredAnchor
          && ((!expectedAnchor.isBlank() && expectedAnchor.equals(fingerprint))
              || (expectedAnchor.isBlank() && verifiedIssuer(next, candidates, used) == null))) {
        anchor = next;
        break;
      }
      if (next.getBasicConstraints() < 0) {
        throw new MaterialValidationException("certificate chain contains a non-CA issuer");
      }
      if (!caKeyUsageAllowsSigning(next)) {
        throw new MaterialValidationException("certificate CA key usage must include keyCertSign");
      }
      if (next.getBasicConstraints() < subordinateCaDepth) {
        throw new MaterialValidationException(
            "certificate chain exceeds CA path length constraint");
      }
      if (!isSelfIssued(next)) {
        subordinateCaDepth++;
      }
      current = next;
    }
    if (anchor == null) {
      if (!anchors.isEmpty() || presentedChain.size() < 2 || current.getBasicConstraints() < 0) {
        throw new MaterialValidationException(
            "certificate chain does not terminate at its CA anchor");
      }
      // ACME TLS Secrets commonly omit ca.crt. The complete presented chain is
      // still checked here; the served system-trust probe supplies the public
      // trust-anchor proof before Ready is reported.
      if (!caKeyUsageAllowsSigning(current)) {
        throw new MaterialValidationException("certificate CA key usage must include keyCertSign");
      }
      String fingerprint = sha256(current.getEncoded());
      if (!expectedAnchor.isBlank() && !expectedAnchor.equals(normalize(fingerprint))) {
        throw new MaterialValidationException(
            "certificate chain trust anchor fingerprint mismatch");
      }
      return fingerprint;
    }
    if (anchor.getBasicConstraints() < 0) {
      throw new MaterialValidationException("certificate chain anchor is not a CA");
    }
    if (anchor.getBasicConstraints() < subordinateCaDepth) {
      throw new MaterialValidationException("certificate chain exceeds CA path length constraint");
    }
    if (!caKeyUsageAllowsSigning(anchor)) {
      throw new MaterialValidationException("certificate CA key usage must include keyCertSign");
    }
    String fingerprint = sha256(anchor.getEncoded());
    if (!expectedAnchor.isBlank() && !expectedAnchor.equals(normalize(fingerprint))) {
      throw new MaterialValidationException("certificate chain trust anchor fingerprint mismatch");
    }
    return fingerprint;
  }

  private static boolean isSelfIssued(X509Certificate certificate) {
    return certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal());
  }

  private static X509Certificate verifiedIssuer(
      X509Certificate certificate, List<X509Certificate> candidates, Set<String> used)
      throws Exception {
    for (X509Certificate candidate : candidates) {
      String fingerprint = sha256(candidate.getEncoded());
      if (used.contains(fingerprint)
          || !certificate.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
        continue;
      }
      try {
        certificate.verify(candidate.getPublicKey());
        return candidate;
      } catch (Exception ignored) {
        // A matching issuer name is not sufficient; the signature must verify.
      }
    }
    return null;
  }

  private static String sha256Unchecked(X509Certificate certificate) {
    try {
      return sha256(certificate.getEncoded());
    } catch (Exception exception) {
      throw new MaterialValidationException("unable to fingerprint certificate", exception);
    }
  }

  private static void checkValidity(X509Certificate certificate) {
    try {
      certificate.checkValidity();
    } catch (Exception exception) {
      throw new MaterialValidationException(
          "certificate chain contains expired material", exception);
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder result = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      result.append(String.format(Locale.ROOT, "%02x", value));
    }
    return result.toString();
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replace(":", "").trim();
  }

  /**
   * Summary of validated identity material.
   *
   * <p>{@code chainRootFingerprint} intentionally has two related meanings: for retained {@code
   * Opaque} transport material it is the fingerprint of the exactly-one {@code ca.crt} certificate;
   * for cert-manager TLS material that omits {@code ca.crt}, it is the fingerprint of the terminal
   * certificate in the presented chain, whose external trust is proved separately by the
   * served-system probe.
   */
  public record MaterialSummary(
      String certificateFingerprint,
      String spkiSha256,
      Instant notBefore,
      Instant notAfter,
      String chainRootFingerprint) {
    public boolean isCurrent(Instant now) {
      return !notBefore().isAfter(now) && !notAfter().isBefore(now);
    }
  }

  public static class MaterialValidationException extends IllegalArgumentException {
    public MaterialValidationException(String message) {
      super(message);
    }

    public MaterialValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
