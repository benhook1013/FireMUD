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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  public Secret ensure(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      Long acceptedGeneration,
      Duration renewBefore,
      String expectedTrustAnchorSha256) {
    Secret existing =
        client
            .secrets()
            .inNamespace(plan.identityNamespace())
            .withName(plan.grpcSecretName())
            .get();
    Secret caSource =
        client.secrets().inNamespace(plan.controlNamespace()).withName(plan.caSecretName()).get();
    if (caSource == null) {
      throw new IllegalStateException("configured gRPC CA Secret is absent");
    }
    validateCa(caSource, expectedTrustAnchorSha256);
    long accepted = acceptedGeneration == null ? 0 : acceptedGeneration;
    if (existing == null) {
      long attemptedGeneration = nextGeneration(accepted);
      Secret generated = generate(plan, caSource, attemptedGeneration);
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
            attemptedGeneration);
      }
    }
    long currentGeneration = issuanceGeneration(existing);
    validateAcceptedGeneration(currentGeneration, accepted);
    if (renewBefore == null || renewBefore.isNegative() || renewBefore.isZero()) {
      throw new IllegalStateException("gRPC renewal window must be positive");
    }
    if (!renewalRequired(existing, renewBefore, Instant.now())) {
      return existing;
    }
    long attemptedGeneration = nextGeneration(currentGeneration);
    Secret replacement = generate(plan, caSource, attemptedGeneration);
    replacement.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
    try {
      return client.secrets().inNamespace(plan.identityNamespace()).resource(replacement).replace();
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
          attemptedGeneration);
    }
  }

  Secret generate(EnvironmentIdentityPlan plan) {
    try {
      if (Security.getProvider("BC") == null) {
        Security.addProvider(new BouncyCastleProvider());
      }
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      KeyPair root = keyPairGenerator.generateKeyPair();
      KeyPair leaf = keyPairGenerator.generateKeyPair();
      X509Certificate rootCertificate =
          certificate(
              "CN=FireMUD hosted transport root, O=FireMUD",
              "CN=FireMUD hosted transport root, O=FireMUD",
              root,
              root,
              grpcDnsNames(plan),
              true);
      X509Certificate leafCertificate =
          certificate(
              "CN=FireMUD hosted transport, O=FireMUD",
              "CN=FireMUD hosted transport root, O=FireMUD",
              leaf,
              root,
              grpcDnsNames(plan),
              false);
      Map<String, String> data = new LinkedHashMap<>();
      data.put("tls.crt", pem(leafCertificate));
      data.put("tls.key", pem(leaf.getPrivate()));
      data.put("ca.crt", pem(rootCertificate));
      return secret(plan, data, 1);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "unable to generate hosted gRPC transport material", exception);
    }
  }

  private Secret generate(EnvironmentIdentityPlan plan, Secret caSource, long generation) {
    try {
      if (Security.getProvider("BC") == null) {
        Security.addProvider(new BouncyCastleProvider());
      }
      X509Certificate caCertificate = parseCertificate(requiredData(caSource, "ca.crt"));
      KeyPair caKey =
          new KeyPair(
              caCertificate.getPublicKey(), parsePrivateKey(requiredData(caSource, "ca.key")));
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      KeyPair leaf = keyPairGenerator.generateKeyPair();
      X509Certificate leafCertificate =
          certificate(
              "CN=FireMUD hosted transport, O=FireMUD",
              caCertificate.getSubjectX500Principal().getName(),
              leaf,
              caKey,
              grpcDnsNames(plan),
              false);
      Map<String, String> data = new LinkedHashMap<>();
      data.put("tls.crt", pem(leafCertificate));
      data.put("tls.key", pem(leaf.getPrivate()));
      data.put("ca.crt", requiredData(caSource, "ca.crt"));
      return secret(plan, data, generation);
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
      return parseCertificate(requiredData(secret, "tls.crt")).getNotAfter().toInstant();
    } catch (Exception exception) {
      throw new IllegalStateException("gRPC source has invalid leaf certificate", exception);
    }
  }

  static boolean renewalRequired(Secret secret, Duration renewBefore, Instant now) {
    issuanceGeneration(secret);
    return !leafNotAfter(secret).isAfter(now.plus(renewBefore));
  }

  static void validateAcceptedGeneration(long currentGeneration, long acceptedGeneration) {
    if (currentGeneration < acceptedGeneration) {
      throw new IllegalStateException("gRPC source issuance generation rolled back");
    }
  }

  static Secret requireConflictWinner(Secret reread, long minimumGeneration) {
    if (reread == null) {
      throw new IllegalStateException("gRPC source conflict winner is absent after reread");
    }
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
      String expected =
          expectedTrustAnchorSha256 == null
              ? ""
              : expectedTrustAnchorSha256.toLowerCase(Locale.ROOT).replace(":", "").trim();
      if (!expected.matches("[0-9a-f]{64}")
          || !expected.equals(SecretMaterialValidator.trustAnchorFingerprint(caSource))) {
        throw new IllegalStateException("configured gRPC CA trust anchor mismatch");
      }
      X509Certificate certificate = parseCertificate(requiredData(caSource, "ca.crt"));
      certificate.checkValidity();
      if (certificate.getBasicConstraints() < 0) {
        throw new IllegalStateException("configured gRPC CA certificate is not a CA");
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

  private static X509Certificate certificate(
      String subject,
      String issuer,
      KeyPair subjectKey,
      KeyPair issuerKey,
      List<String> dnsNames,
      boolean ca)
      throws Exception {
    Instant now = Instant.now();
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            new X500Name(issuer),
            newCertificateSerial(),
            Date.from(now.minus(Duration.ofMinutes(1))),
            Date.from(now.plus(Duration.ofDays(30))),
            new X500Name(subject),
            subjectKey.getPublic());
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
    String value = source.getData() == null ? null : source.getData().get(key);
    if (value == null) {
      throw new IllegalStateException("configured gRPC CA Secret is missing required material");
    }
    return value;
  }

  private static X509Certificate parseCertificate(String encoded) throws Exception {
    byte[] der = pemBytes(encoded, CERTIFICATE_PEM, "CERTIFICATE");
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
    String content =
        new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.US_ASCII);
    Matcher matcher = expected.matcher(content);
    if (!matcher.matches()) {
      throw new IllegalStateException("configured gRPC CA material must use " + label + " PEM");
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
