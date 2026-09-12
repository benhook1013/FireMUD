package net.firedevops.firemud.hostedidentity.security;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

/** Creates a retained transport-only bundle only when cert-manager has not materialized one. */
@Component
public class GrpcTransportBundleGenerator {
  private static final String TYPE = "Opaque";
  private static final SecureRandom SERIAL_RANDOM = new SecureRandom();
  private static final Pattern CERTIFICATE_PEM =
      Pattern.compile(
          "\\A\\s*-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----\\s*\\z",
          Pattern.DOTALL);
  private static final Pattern PRIVATE_KEY_PEM =
      Pattern.compile(
          "\\A\\s*-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----\\s*\\z",
          Pattern.DOTALL);

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  public Secret ensure(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      Long acceptedGeneration,
      Duration renewBefore,
      String expectedTrustAnchorSha256) {
    requirePositiveRenewalWindow(renewBefore);
    Instant now = Instant.now();
    Secret existing =
        client
            .secrets()
            .inNamespace(plan.identityNamespace())
            .withName(plan.grpcSecretName())
            .get();
    if (existing != null) {
      requireOwned(existing, plan);
    }
    Secret caSource =
        client.secrets().inNamespace(plan.controlNamespace()).withName(plan.caSecretName()).get();
    if (caSource == null) {
      throw new IllegalStateException("configured gRPC CA Secret is absent");
    }
    validateCa(caSource, expectedTrustAnchorSha256);
    long accepted = acceptedGeneration == null ? 0 : acceptedGeneration;
    if (existing == null) {
      long attemptedGeneration = nextGeneration(accepted);
      Secret generated = generate(plan, caSource, attemptedGeneration, renewBefore, now);
      try {
        return client.secrets().inNamespace(plan.identityNamespace()).resource(generated).create();
      } catch (KubernetesClientException exception) {
        if (exception.getCode() != 409) {
          throw exception;
        }
        return requireConflictWinner(
            client
                .secrets()
                .inNamespace(plan.identityNamespace())
                .withName(plan.grpcSecretName())
                .get(),
            attemptedGeneration,
            plan);
      }
    }
    long currentGeneration = issuanceGeneration(existing);
    validateAcceptedGeneration(currentGeneration, accepted);
    boolean trustAnchorChanged = trustAnchorChanged(existing, expectedTrustAnchorSha256);
    if (!trustAnchorChanged && leafIsReusable(existing, plan, renewBefore, now)) {
      return existing;
    }
    String existingResourceVersion = existing.getMetadata().getResourceVersion();
    if (existingResourceVersion == null || existingResourceVersion.isBlank()) {
      throw new IllegalStateException("existing gRPC Secret has no resourceVersion for repair");
    }
    long attemptedGeneration = nextGeneration(currentGeneration);
    Secret replacement = generate(plan, caSource, attemptedGeneration, renewBefore, now);
    replacement.getMetadata().setResourceVersion(existingResourceVersion);
    try {
      return client
          .secrets()
          .inNamespace(plan.identityNamespace())
          .resource(replacement)
          .lockResourceVersion(existingResourceVersion)
          .replace();
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 409) {
        throw exception;
      }
      return requireConflictWinner(
          client
              .secrets()
              .inNamespace(plan.identityNamespace())
              .withName(plan.grpcSecretName())
              .get(),
          attemptedGeneration,
          plan);
    }
  }

  Secret generate(
      EnvironmentIdentityPlan plan,
      Secret caSource,
      long generation,
      Duration renewBefore,
      Instant now) {
    try {
      requirePositiveRenewalWindow(renewBefore);
      X509Certificate caCertificate = parseCertificate(requiredData(caSource, "ca.crt"));
      Instant caNotAfter = caCertificate.getNotAfter().toInstant();
      Instant renewalHorizon = renewalHorizon(now, renewBefore);
      Instant intendedNotAfter =
          plus(
              now,
              HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION,
              "gRPC certificate validity window is out of range");
      Instant effectiveNotAfter =
          caNotAfter.isBefore(intendedNotAfter) ? caNotAfter : intendedNotAfter;
      if (effectiveNotAfter.isBefore(renewalHorizon)) {
        throw new IllegalStateException(
            "configured gRPC CA expires within the gRPC renewal window plus renewal slack");
      }
      KeyPair caKey =
          new KeyPair(
              caCertificate.getPublicKey(), parsePrivateKey(requiredData(caSource, "ca.key")));
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(HostedIdentityProperties.CERTIFICATE_RSA_KEY_SIZE_BITS);
      KeyPair leaf = keyPairGenerator.generateKeyPair();
      X509Certificate leafCertificate =
          certificate(
              new X500Name("CN=FireMUD hosted transport, O=FireMUD"),
              X500Name.getInstance(caCertificate.getSubjectX500Principal().getEncoded()),
              leaf,
              caKey,
              grpcDnsNames(plan),
              false,
              now,
              effectiveNotAfter);
      Map<String, String> data = new LinkedHashMap<>();
      // ca.crt is the directly pinned issuing trust anchor, so tls.crt intentionally contains
      // only the leaf certificate rather than duplicating that anchor in the presented chain.
      data.put("tls.crt", pem(leafCertificate));
      data.put("tls.key", pem(leaf.getPrivate()));
      data.put("ca.crt", requiredData(caSource, "ca.crt"));
      return secret(plan, data, generation);
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException(
          "unable to generate hosted gRPC transport material", exception);
    }
  }

  public static List<String> grpcDnsNames(EnvironmentIdentityPlan plan) {
    return plan.grpcConsumers().stream()
        .flatMap(
            service ->
                java.util.stream.Stream.of(
                    service,
                    service + "." + plan.runtimeNamespace(),
                    service + "." + plan.runtimeNamespace() + ".svc",
                    service + "." + plan.runtimeNamespace() + ".svc.cluster.local"))
        .distinct()
        .sorted()
        .toList();
  }

  public static long issuanceGeneration(Secret secret) {
    String value =
        secret.getMetadata() == null || secret.getMetadata().getAnnotations() == null
            ? null
            : secret
                .getMetadata()
                .getAnnotations()
                .get(HostedIdentityContract.ISSUANCE_GENERATION_ANNOTATION);
    try {
      long generation = Long.parseLong(value);
      if (generation < 1) {
        throw new NumberFormatException();
      }
      return generation;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("gRPC source has no valid issuance generation", exception);
    }
  }

  private static Secret secret(
      EnvironmentIdentityPlan plan, Map<String, String> data, long generation) {
    return new SecretBuilder()
        .withMetadata(
            new ObjectMetaBuilder()
                .withName(plan.grpcSecretName())
                .withNamespace(plan.identityNamespace())
                .withLabels(
                    HostedIdentityContract.managedLabels(
                        plan.name(), HostedIdentityContract.GRPC_ROLE))
                .withAnnotations(
                    Map.of(
                        HostedIdentityContract.PROVENANCE_ANNOTATION,
                        HostedIdentityContract.TRANSPORT_PROVENANCE,
                        HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
                        "generated-retained",
                        HostedIdentityContract.ISSUANCE_GENERATION_ANNOTATION,
                        Long.toString(generation)))
                .build())
        .withType(TYPE)
        .withData(data)
        .build();
  }

  private static Instant leafNotAfter(Secret secret) {
    try {
      return parseCertificate(
              requiredData(secret, "tls.crt", "gRPC source leaf Secret"), "gRPC source leaf Secret")
          .getNotAfter()
          .toInstant();
    } catch (Exception exception) {
      throw new InvalidLeafCertificateException(exception);
    }
  }

  static boolean renewalRequired(Secret secret, Duration renewBefore, Instant now) {
    // Validate the persisted issuance marker before evaluating certificate lifetime.
    issuanceGeneration(secret);
    return !leafNotAfter(secret)
        .isAfter(plus(now, renewBefore, "gRPC renewal threshold is out of range"));
  }

  private static boolean leafIsReusable(
      Secret secret, EnvironmentIdentityPlan plan, Duration renewBefore, Instant now) {
    try {
      return leafDnsNamesMatch(secret, plan) && !renewalRequired(secret, renewBefore, now);
    } catch (InvalidLeafCertificateException exception) {
      return false;
    }
  }

  private static boolean leafDnsNamesMatch(Secret secret, EnvironmentIdentityPlan plan) {
    try {
      X509Certificate certificate =
          parseCertificate(
              requiredData(secret, "tls.crt", "gRPC source leaf Secret"),
              "gRPC source leaf Secret");
      Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
      if (subjectAlternativeNames == null) {
        return false;
      }
      List<String> actualDnsNames = new java.util.ArrayList<>();
      for (List<?> subjectAlternativeName : subjectAlternativeNames) {
        if (subjectAlternativeName == null
            || subjectAlternativeName.size() != 2
            || !(subjectAlternativeName.get(0) instanceof Integer nameType)) {
          return false;
        }
        if (!Integer.valueOf(2).equals(nameType)) {
          continue;
        }
        if (!(subjectAlternativeName.get(1) instanceof String name)) {
          return false;
        }
        actualDnsNames.add(name.toLowerCase(Locale.ROOT));
      }
      return actualDnsNames.stream().distinct().sorted().toList().equals(grpcDnsNames(plan));
    } catch (Exception exception) {
      throw new InvalidLeafCertificateException(exception);
    }
  }

  private static final class InvalidLeafCertificateException extends IllegalStateException {
    private InvalidLeafCertificateException(Exception cause) {
      super("gRPC source has invalid leaf certificate", cause);
    }
  }

  private static boolean trustAnchorChanged(Secret existing, String expectedTrustAnchorSha256) {
    String existingTrustAnchorSha256;
    try {
      existingTrustAnchorSha256 = SecretMaterialValidator.trustAnchorFingerprint(existing);
    } catch (IllegalArgumentException exception) {
      return true;
    }
    return !normalizeFingerprint(expectedTrustAnchorSha256).equals(existingTrustAnchorSha256);
  }

  static void validateAcceptedGeneration(long currentGeneration, long acceptedGeneration) {
    if (currentGeneration < acceptedGeneration) {
      throw new IllegalStateException("gRPC source issuance generation rolled back");
    }
  }

  private static void requireOwned(Secret secret, EnvironmentIdentityPlan plan) {
    Map<String, String> labels =
        secret.getMetadata() == null ? null : secret.getMetadata().getLabels();
    if (labels == null
        || !HostedIdentityContract.CONTROLLER_NAME.equals(
            labels.get(HostedIdentityContract.MANAGED_BY_LABEL))
        || !plan.name().equals(labels.get(HostedIdentityContract.ENVIRONMENT_LABEL))
        || !HostedIdentityContract.GRPC_ROLE.equals(labels.get(HostedIdentityContract.ROLE_LABEL))
        || !HostedIdentityContract.RETAINED.equals(
            labels.get(HostedIdentityContract.RETENTION_LABEL))) {
      throw new IllegalStateException("identity source Secret is not controller-owned");
    }
  }

  static Secret requireConflictWinner(
      Secret reread, long minimumGeneration, EnvironmentIdentityPlan plan) {
    if (reread == null) {
      throw new IllegalStateException("gRPC source conflict winner is absent after reread");
    }
    requireOwned(reread, plan);
    if (issuanceGeneration(reread) < minimumGeneration) {
      throw new IllegalStateException(
          "gRPC source conflict winner did not reach the attempted issuance generation");
    }
    return reread;
  }

  static void validateCa(Secret caSource, String expectedTrustAnchorSha256) {
    try {
      if (!TYPE.equals(caSource.getType())
          || caSource.getData() == null
          || !caSource.getData().keySet().equals(java.util.Set.of("ca.crt", "ca.key"))) {
        throw new IllegalStateException(
            "configured gRPC CA Secret must be Opaque and contain exactly ca.crt and ca.key");
      }
      String expected = normalizeFingerprint(expectedTrustAnchorSha256);
      if (!expected.matches("[0-9a-f]{64}")
          || !expected.equals(SecretMaterialValidator.trustAnchorFingerprint(caSource))) {
        throw new IllegalStateException("configured gRPC CA trust anchor mismatch");
      }
      X509Certificate certificate = parseCertificate(requiredData(caSource, "ca.crt"));
      certificate.checkValidity();
      if (certificate.getBasicConstraints() < 0) {
        throw new IllegalStateException("configured gRPC CA certificate is not a CA");
      }
      if (!SecretMaterialValidator.caKeyUsageAllowsSigning(certificate)) {
        throw new IllegalStateException(
            "configured gRPC CA certificate key usage must include keyCertSign");
      }
      var privateKey = parsePrivateKey(requiredData(caSource, "ca.key"));
      if (!(privateKey instanceof RSAPrivateCrtKey rsaKey)
          || !certificate
              .getPublicKey()
              .equals(
                  KeyFactory.getInstance("RSA")
                      .generatePublic(
                          new RSAPublicKeySpec(rsaKey.getModulus(), rsaKey.getPublicExponent())))) {
        throw new IllegalStateException("configured gRPC CA certificate and key do not match");
      }
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("configured gRPC CA material is invalid", exception);
    }
  }

  private static String normalizeFingerprint(String fingerprint) {
    return fingerprint == null ? "" : fingerprint.toLowerCase(Locale.ROOT).replace(":", "").trim();
  }

  private static X509Certificate certificate(
      X500Name subject,
      X500Name issuer,
      KeyPair subjectKey,
      KeyPair issuerKey,
      List<String> dnsNames,
      boolean ca,
      Instant now,
      Instant notAfter)
      throws Exception {
    if (!notAfter.isAfter(now)) {
      throw new IllegalStateException("certificate validity window is exhausted");
    }
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            newCertificateSerial(),
            Date.from(
                minus(now, Duration.ofMinutes(1), "certificate validity start is out of range")),
            Date.from(notAfter),
            subject,
            subjectKey.getPublic());
    JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
    builder.addExtension(
        Extension.subjectKeyIdentifier,
        false,
        extensionUtils.createSubjectKeyIdentifier(subjectKey.getPublic()));
    builder.addExtension(
        Extension.authorityKeyIdentifier,
        false,
        extensionUtils.createAuthorityKeyIdentifier(issuerKey.getPublic()));
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(
            ca
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    if (!ca) {
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(
              dnsNames.stream()
                  .map(name -> new GeneralName(GeneralName.dNSName, name))
                  .toArray(GeneralName[]::new)));
    }
    ContentSigner signer =
        new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
  }

  static BigInteger newCertificateSerial() {
    BigInteger serial;
    do {
      serial = new BigInteger(159, SERIAL_RANDOM);
    } while (serial.signum() <= 0);
    return serial;
  }

  private static Instant renewalHorizon(Instant now, Duration renewBefore) {
    Instant renewalThreshold = plus(now, renewBefore, "gRPC renewal threshold is out of range");
    return plus(
        renewalThreshold,
        HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK,
        "gRPC renewal horizon is out of range");
  }

  private static Instant plus(Instant instant, Duration duration, String failureMessage) {
    try {
      return instant.plus(duration);
    } catch (ArithmeticException | DateTimeException exception) {
      throw new IllegalStateException(failureMessage, exception);
    }
  }

  private static Instant minus(Instant instant, Duration duration, String failureMessage) {
    try {
      return instant.minus(duration);
    } catch (ArithmeticException | DateTimeException exception) {
      throw new IllegalStateException(failureMessage, exception);
    }
  }

  private static void requirePositiveRenewalWindow(Duration renewBefore) {
    HostedIdentityProperties.requireValidGrpcRenewBefore(renewBefore);
  }

  static long nextGeneration(long current) {
    if (current < 0) {
      throw new IllegalStateException("gRPC source issuance generation is invalid");
    }
    try {
      return Math.addExact(current, 1);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("gRPC source issuance generation is exhausted", exception);
    }
  }

  private static String requiredData(Secret source, String key) {
    return requiredData(source, key, "configured gRPC CA Secret");
  }

  private static String requiredData(Secret source, String key, String subject) {
    String value = source.getData() == null ? null : source.getData().get(key);
    if (value == null) {
      throw new IllegalStateException(subject + " is missing required material");
    }
    return value;
  }

  private static X509Certificate parseCertificate(String encoded) throws Exception {
    return parseCertificate(encoded, "configured gRPC CA Secret");
  }

  private static X509Certificate parseCertificate(String encoded, String subject) throws Exception {
    byte[] der = pemBytes(encoded, CERTIFICATE_PEM, "CERTIFICATE", subject);
    return (X509Certificate)
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(der));
  }

  private static java.security.PrivateKey parsePrivateKey(String encoded) throws Exception {
    return KeyFactory.getInstance("RSA")
        .generatePrivate(
            new PKCS8EncodedKeySpec(pemBytes(encoded, PRIVATE_KEY_PEM, "PRIVATE KEY")));
  }

  private static byte[] pemBytes(String encoded, Pattern expected, String label) {
    return pemBytes(encoded, expected, label, "configured gRPC CA Secret");
  }

  private static byte[] pemBytes(String encoded, Pattern expected, String label, String subject) {
    String content =
        new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.US_ASCII);
    Matcher matcher = expected.matcher(content);
    if (!matcher.matches()) {
      throw new IllegalStateException(subject + " must use " + label + " PEM");
    }
    return Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
  }

  private static String pem(Object object) throws Exception {
    if (object instanceof java.security.PrivateKey privateKey) {
      String body =
          Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey.getEncoded());
      String value = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
      return Base64.getEncoder()
          .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    StringWriter output = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(output)) {
      writer.writeObject(object);
    }
    return Base64.getEncoder()
        .encodeToString(output.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }
}
