package net.firedevops.firemud.hostedidentity.security;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Compile-time test seam for cross-package probe coverage of generated transport material. */
public final class GrpcMaterialFixture {
  private static final AtomicLong CA_SERIAL = new AtomicLong(1);

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private GrpcMaterialFixture() {}

  public static Secret generate(EnvironmentIdentityPlan plan) {
    try {
      Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      Secret caSource = generatedCa(now, Duration.ofDays(60));
      String trustAnchor = SecretMaterialValidator.trustAnchorFingerprint(caSource);
      GrpcTransportBundleGenerator.validateCa(caSource, trustAnchor);
      return new GrpcTransportBundleGenerator()
          .generate(plan, caSource, 1, Duration.ofDays(7), now);
    } catch (Exception exception) {
      throw new AssertionError("unable to create configured-CA gRPC test fixture", exception);
    }
  }

  private static Secret generatedCa(Instant now, Duration lifetime) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    X500Name name = new X500Name("CN=FireMUD test transport root, O=FireMUD");
    var builder =
        new JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(CA_SERIAL.getAndIncrement()),
            Date.from(now.minus(Duration.ofMinutes(1))),
            Date.from(now.plus(lifetime)),
            name,
            keyPair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
    X509Certificate ca =
        new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
    return new SecretBuilder()
        .withType("Opaque")
        .withData(
            Map.of(
                "ca.crt", pem("CERTIFICATE", ca.getEncoded()),
                "ca.key", pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())))
        .build();
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(HostedIdentityProperties.CERTIFICATE_RSA_KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (Exception exception) {
      throw new AssertionError("unable to create RSA test fixture key pair", exception);
    }
  }

  private static String pem(String label, byte[] der) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    String pem = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
  }
}
