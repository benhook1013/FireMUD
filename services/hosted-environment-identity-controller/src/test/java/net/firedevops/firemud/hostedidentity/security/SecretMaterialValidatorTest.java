package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    Secret winner = new SecretBuilder().withType("Opaque").build();
    assertEquals(winner, GrpcTransportBundleGenerator.requireConflictWinner(winner));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.requireConflictWinner(null));
  }

  @Test
  void generatedCaCanSignAndHasCaOnlyKeyUsages() throws Exception {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    X509Certificate ca = certificate(source.getData().get("ca.crt"));
    X509Certificate leaf = certificate(source.getData().get("tls.crt"));

    leaf.verify(ca.getPublicKey());
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

    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.validateCa(fallbackShape, fingerprint));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.validateCa(mismatchedKey, fingerprint));
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

  private static X509Certificate certificate(String encoded) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
  }
}
