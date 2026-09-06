package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
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
    assertEquals(44, GrpcTransportBundleGenerator.grpcDnsNames(plan).size());
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
  void missingOrWrongMaterialFailsClosed() {
    Secret secret = new io.fabric8.kubernetes.api.model.SecretBuilder().withType("Opaque").build();
    assertThrows(
        SecretMaterialValidator.MaterialValidationException.class,
        () -> new SecretMaterialValidator().validate(secret, "host", "Opaque", false, ""));
  }

  @Test
  void grpcBundleRenewsBeforeExpiryOnlyAfterTheCurrentGenerationIsAccepted() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    assertEquals(
        false,
        GrpcTransportBundleGenerator.renewalRequired(source, 1, Duration.ofDays(7), Instant.now()));
    assertEquals(
        true,
        GrpcTransportBundleGenerator.renewalRequired(
            source, 1, Duration.ofDays(31), Instant.now()));
    assertEquals(
        false,
        GrpcTransportBundleGenerator.renewalRequired(
            source, 0, Duration.ofDays(31), Instant.now()));
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
}
