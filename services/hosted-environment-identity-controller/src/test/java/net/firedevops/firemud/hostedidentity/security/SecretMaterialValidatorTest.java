package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

class SecretMaterialValidatorTest {
  @Test
  void generatedGrpcBundleHasTransportUsagesAndNoPerWorkloadIdentityClaim() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    String trustAnchor = SecretMaterialValidator.trustAnchorFingerprint(source);
    var summary =
        new SecretMaterialValidator()
            .validate(
                source,
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                "Opaque",
                true,
                trustAnchor);

    assertEquals(64, summary.certificateFingerprint().length());
    assertEquals(64, summary.spkiSha256().length());
    assertEquals(1, GrpcTransportBundleGenerator.issuanceGeneration(source));
    assertEquals(
        plan.grpcConsumers().size() * 4, GrpcTransportBundleGenerator.grpcDnsNames(plan).size());
    assertEquals(
        true,
        GrpcTransportBundleGenerator.grpcDnsNames(plan)
            .contains("account-service.pr-42.svc.cluster.local"));
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            new SecretMaterialValidator()
                .validate(source, "pr-42.svc.cluster.local", "Opaque", true, trustAnchor));
  }

  @Test
  void websocketIdentityValidationRequiresExactSanAndEkuProfiles() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret grpc = new GrpcTransportBundleGenerator().generate(plan);
    String trustAnchor = SecretMaterialValidator.trustAnchorFingerprint(grpc);
    var validator = new SecretMaterialValidator();

    validator.validateIdentity(
        grpc,
        GrpcTransportBundleGenerator.grpcDnsNames(plan),
        java.util.List.of(),
        "Opaque",
        true,
        true,
        trustAnchor);
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            validator.validateIdentity(
                grpc,
                java.util.List.of(),
                java.util.List.of(plan.tcpProxyBridgeUriSan()),
                "Opaque",
                false,
                true,
                trustAnchor));
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            validator.validateIdentity(
                grpc,
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                java.util.List.of(),
                "Opaque",
                false,
                true,
                trustAnchor));
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            validator.validateIdentity(
                grpc,
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                java.util.List.of(),
                "Opaque",
                true,
                false,
                trustAnchor));
  }

  @Test
  void exactLeafProfilesRejectExtraEkuKeyUsageAndCaAuthority() throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getExtendedKeyUsage())
        .thenReturn(java.util.List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.3"));
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () -> SecretMaterialValidator.validateUsage(certificate, true, false, true));

    when(certificate.getBasicConstraints()).thenReturn(-1);
    when(certificate.getKeyUsage())
        .thenReturn(new boolean[] {true, false, true, true, false, false, false, false, false});
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () -> SecretMaterialValidator.validateLeafProfile(certificate));

    when(certificate.getBasicConstraints()).thenReturn(0);
    when(certificate.getKeyUsage())
        .thenReturn(new boolean[] {true, false, true, false, false, false, false, false, false});
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () -> SecretMaterialValidator.validateLeafProfile(certificate));
  }

  @Test
  void missingOrWrongMaterialFailsClosed() {
    Secret secret = new io.fabric8.kubernetes.api.model.SecretBuilder().withType("Opaque").build();
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () -> new SecretMaterialValidator().validate(secret, "host", "Opaque", false, ""));
  }

  @Test
  void grpcBundleRenewalIsExpiryDrivenWhileAcceptedGenerationRemainsAnAntiRollbackFloor() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    assertEquals(
        false,
        GrpcTransportBundleGenerator.renewalRequired(source, Duration.ofDays(7), Instant.now()));
    GrpcTransportBundleGenerator.validateAcceptedGeneration(2, 1);
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.validateAcceptedGeneration(1, 2));
    assertEquals(
        true,
        GrpcTransportBundleGenerator.renewalRequired(source, Duration.ofDays(31), Instant.now()));
  }

  @Test
  void conflictRereadMustFindTheWinningSecret() {
    Secret winner =
        new SecretBuilder()
            .withNewMetadata()
            .addToAnnotations("firemud.dev/issuance-generation", "4")
            .endMetadata()
            .withType("Opaque")
            .build();
    assertEquals(winner, GrpcTransportBundleGenerator.requireConflictWinner(winner, 4));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.requireConflictWinner(null, 4));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.requireConflictWinner(winner, 5));
  }

  @Test
  void certificateSerialsAreStrongPositiveAndUniqueWithinAnIssuer() {
    var serials = new HashSet<java.math.BigInteger>();
    for (int index = 0; index < 128; index++) {
      var serial = GrpcTransportBundleGenerator.newCertificateSerial();
      assertTrue(serial.signum() > 0);
      assertTrue(serial.bitLength() <= 159);
      assertTrue(serials.add(serial));
    }
  }

  @Test
  void issuanceGenerationCannotWrapOrStartBelowZero() {
    assertEquals(1, GrpcTransportBundleGenerator.nextGeneration(0));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.nextGeneration(Long.MAX_VALUE));
    assertThrows(
        IllegalStateException.class, () -> GrpcTransportBundleGenerator.nextGeneration(-1));
  }

  @Test
  void generatedCaCanSignAndHasCaOnlyKeyUsages() throws Exception {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    X509Certificate ca = certificate(source.getData().get("ca.crt"));
    X509Certificate leaf = certificate(source.getData().get("tls.crt"));

    leaf.verify(ca.getPublicKey());
    assertTrue(ca.getSerialNumber().signum() > 0);
    assertTrue(leaf.getSerialNumber().signum() > 0);
    assertNotEquals(ca.getSerialNumber(), leaf.getSerialNumber());
    assertTrue(ca.getKeyUsage()[5]);
    assertTrue(ca.getKeyUsage()[6]);
    assertFalse(ca.getKeyUsage()[0]);
    assertFalse(ca.getKeyUsage()[2]);
    assertTrue(leaf.getKeyUsage()[0]);
    assertTrue(leaf.getKeyUsage()[2]);
    assertFalse(leaf.getKeyUsage()[5]);
    assertFalse(leaf.getKeyUsage()[6]);
  }

  @Test
  void productionGenerationUsesTheConfiguredCaAndExactIssuanceGeneration() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    X500Name derSensitiveSubject =
        new X500Name(
            new RDN[] {
              new RDN(BCStyle.CN, new DERPrintableString("FireMUD test transport root")),
              new RDN(BCStyle.O, new DERPrintableString("FireMUD"))
            });
    Secret caSource = generatedCa(now, Duration.ofDays(60), derSensitiveSubject);
    String trustAnchor = SecretMaterialValidator.trustAnchorFingerprint(caSource);
    GrpcTransportBundleGenerator.validateCa(caSource, trustAnchor);

    Secret generated =
        new GrpcTransportBundleGenerator().generate(plan, caSource, 17, Duration.ofDays(7), now);
    X509Certificate ca = certificate(caSource.getData().get("ca.crt"));
    X509Certificate leaf = certificate(generated.getData().get("tls.crt"));

    assertEquals(caSource.getData().get("ca.crt"), generated.getData().get("ca.crt"));
    assertEquals(17, GrpcTransportBundleGenerator.issuanceGeneration(generated));
    assertFalse(
        java.util.Arrays.equals(
            ca.getSubjectX500Principal().getEncoded(),
            new X500Name(ca.getSubjectX500Principal().getName()).getEncoded()),
        "fixture must detect a normalized issuer string round-trip");
    assertArrayEquals(
        ca.getSubjectX500Principal().getEncoded(), leaf.getIssuerX500Principal().getEncoded());
    assertEquals(Date.from(now.plus(Duration.ofDays(30))), leaf.getNotAfter());
    leaf.verify(ca.getPublicKey());
    assertEquals(
        trustAnchor,
        new SecretMaterialValidator()
            .validateIdentity(
                generated,
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                java.util.List.of(),
                "Opaque",
                true,
                true,
                trustAnchor)
            .trustAnchorFingerprint());
  }

  @Test
  void productionGenerationCapsLeafExpiryAtTheSigningCaExpiry() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret caSource = generatedCa(now, Duration.ofDays(10));

    Secret generated =
        new GrpcTransportBundleGenerator().generate(plan, caSource, 2, Duration.ofDays(7), now);

    assertEquals(
        certificate(caSource.getData().get("ca.crt")).getNotAfter(),
        certificate(generated.getData().get("tls.crt")).getNotAfter());
  }

  @Test
  void productionGenerationRejectsCaWithinTheRenewalWindowBeforeSigning() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret caSource = generatedCa(now, Duration.ofDays(7));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GrpcTransportBundleGenerator()
                    .generate(plan, caSource, 2, Duration.ofDays(7), now));

    assertTrue(failure.getMessage().contains("expires within the gRPC renewal window"));
  }

  @Test
  void servedProbeClosesSocketWhenSetupFailsBeforeOwnershipTransfer() throws Exception {
    SSLSocket socket = mock(SSLSocket.class);
    doThrow(new IOException("connect failed"))
        .when(socket)
        .connect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    var method =
        ServedEnvironmentProbe.class.getDeclaredMethod(
            "openTlsSocket", String.class, int.class, String.class, SSLSocket.class);
    method.setAccessible(true);

    assertThrows(
        InvocationTargetException.class,
        () -> method.invoke(null, "pr-42.example.test", 443, "1".repeat(64), socket));
    verify(socket).close();
  }

  @Test
  void grpcCaRejectsNonCanonicalKeysAndMismatchedPrivateKey() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret generated = new GrpcTransportBundleGenerator().generate(plan);
    String fingerprint = SecretMaterialValidator.trustAnchorFingerprint(generated);
    Secret fallbackShape =
        new SecretBuilder(generated)
            .withData(
                Map.of(
                    "tls.crt", generated.getData().get("ca.crt"),
                    "tls.key", generated.getData().get("tls.key")))
            .build();
    Secret mismatchedKey =
        new SecretBuilder(generated)
            .withData(
                Map.of(
                    "ca.crt", generated.getData().get("ca.crt"),
                    "ca.key", generated.getData().get("tls.key")))
            .build();
    Secret wrongPemLabels =
        new SecretBuilder(generated)
            .withData(
                Map.of(
                    "ca.crt",
                    relabel(generated.getData().get("ca.crt"), "CERTIFICATE", "X509 CERTIFICATE"),
                    "ca.key",
                    generated.getData().get("tls.key")))
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.validateCa(fallbackShape, fingerprint));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.validateCa(mismatchedKey, fingerprint));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.validateCa(wrongPemLabels, fingerprint));
  }

  @Test
  void materialValidationRequiresCanonicalCertificateAndPrivateKeyPemLabels() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret generated = new GrpcTransportBundleGenerator().generate(plan);
    String fingerprint = SecretMaterialValidator.trustAnchorFingerprint(generated);
    Map<String, String> wrongCertificate = new LinkedHashMap<>(generated.getData());
    wrongCertificate.put(
        "tls.crt", relabel(wrongCertificate.get("tls.crt"), "CERTIFICATE", "X509 CERTIFICATE"));
    Map<String, String> wrongKey = new LinkedHashMap<>(generated.getData());
    wrongKey.put("tls.key", relabel(wrongKey.get("tls.key"), "PRIVATE KEY", "RSA PRIVATE KEY"));

    var validator = new SecretMaterialValidator();
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            validator.validate(
                new SecretBuilder(generated).withData(wrongCertificate).build(),
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                "Opaque",
                true,
                fingerprint));
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            validator.validate(
                new SecretBuilder(generated).withData(wrongKey).build(),
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                "Opaque",
                true,
                fingerprint));
  }

  @Test
  void validatesTheCompletePresentedChainAndRejectsMissingTrustConfiguration() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    String leaf = pemText(source.getData().get("tls.crt"));
    String anchor = pemText(source.getData().get("ca.crt"));
    Map<String, String> data = new LinkedHashMap<>(source.getData());
    data.put("tls.crt", encode(leaf + anchor));
    Secret chain = new SecretBuilder(source).withData(data).build();

    var validator = new SecretMaterialValidator();
    assertEquals(
        SecretMaterialValidator.trustAnchorFingerprint(source),
        validator
            .validate(
                chain,
                GrpcTransportBundleGenerator.grpcDnsNames(plan),
                "Opaque",
                true,
                SecretMaterialValidator.trustAnchorFingerprint(source))
            .trustAnchorFingerprint());
    data.remove("ca.crt");
    Secret publicChain =
        new SecretBuilder(source).withType("kubernetes.io/tls").withData(data).build();
    validator.validate(
        publicChain,
        GrpcTransportBundleGenerator.grpcDnsNames(plan),
        "kubernetes.io/tls",
        false,
        "");
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () ->
            validator.validate(
                source, GrpcTransportBundleGenerator.grpcDnsNames(plan), "Opaque", true, ""));
  }

  private static String pemText(String encoded) {
    return new String(Base64.getDecoder().decode(encoded), StandardCharsets.US_ASCII);
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static String relabel(String encoded, String oldLabel, String newLabel) {
    return encode(pemText(encoded).replace(oldLabel, newLabel));
  }

  private static X509Certificate certificate(String encoded) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
  }

  private static Secret generatedCa(Instant now, Duration lifetime) throws Exception {
    return generatedCa(now, lifetime, new X500Name("CN=FireMUD test transport root, O=FireMUD"));
  }

  private static Secret generatedCa(Instant now, Duration lifetime, X500Name name)
      throws Exception {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);
    KeyPair keyPair = keyPairGenerator.generateKeyPair();
    var builder =
        new JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(42),
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

  private static String pem(String label, byte[] der) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return encode("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
  }
}
