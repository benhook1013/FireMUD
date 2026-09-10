package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ReplaceDeletable;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.io.ByteArrayInputStream;
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
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SecretMaterialValidatorTest {
  private static final AtomicLong CA_SERIAL = new AtomicLong(1);
  private static final KeyPair FIXTURE_CA_KEY_PAIR = generateRsaKeyPair();

  @Test
  void generatedGrpcBundleHasTransportUsagesAndNoPerWorkloadIdentityClaim() throws Exception {
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

    X509Certificate leaf = certificate(source.getData().get("tls.crt"));
    var subjectAlternativeNames = leaf.getSubjectAlternativeNames();
    assertTrue(subjectAlternativeNames != null);
    assertTrue(
        subjectAlternativeNames.stream()
            .allMatch(
                subjectAlternativeName ->
                    subjectAlternativeName.size() == 2
                        && Integer.valueOf(2).equals(subjectAlternativeName.get(0))));
    assertEquals(
        GrpcTransportBundleGenerator.grpcDnsNames(plan),
        subjectAlternativeNames.stream()
            .map(subjectAlternativeName -> (String) subjectAlternativeName.get(1))
            .sorted()
            .toList());
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
  void trustAnchorFingerprintRequiresExactlyOneCertificate() throws Exception {
    Secret singleCertificate = generatedCaWithDistinctKeyPair(Instant.now(), Duration.ofDays(60));
    String fingerprint = SecretMaterialValidator.trustAnchorFingerprint(singleCertificate);
    assertEquals(64, fingerprint.length());

    Secret secondCertificate = generatedCaWithDistinctKeyPair(Instant.now(), Duration.ofDays(60));
    Map<String, String> multiCertificateData = new LinkedHashMap<>(singleCertificate.getData());
    multiCertificateData.put(
        "ca.crt",
        encode(
            pemText(singleCertificate.getData().get("ca.crt"))
                + pemText(secondCertificate.getData().get("ca.crt"))));
    Secret multiCertificate =
        new SecretBuilder(singleCertificate).withData(multiCertificateData).build();

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> SecretMaterialValidator.trustAnchorFingerprint(multiCertificate));
    assertEquals("Secret ca.crt must contain exactly one X.509 certificate", failure.getMessage());
  }

  @Test
  void grpcTransportIdentityValidationRequiresExactSanAndEkuProfiles() {
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
    when(certificate.getBasicConstraints()).thenReturn(-1);
    when(certificate.getKeyUsage()).thenReturn(new boolean[] {true, false, true});
    var extraEku =
        assertThrows(
            SecretMaterialValidator.MaterialValidationException.class,
            () -> SecretMaterialValidator.validateUsage(certificate, true, false, true));
    assertEquals(
        "certificate EKUs do not exactly match the identity profile", extraEku.getMessage());

    when(certificate.getKeyUsage())
        .thenReturn(new boolean[] {true, false, true, true, false, false, false, false, false});
    var keyUsage =
        assertThrows(
            SecretMaterialValidator.MaterialValidationException.class,
            () -> SecretMaterialValidator.validateLeafProfile(certificate));
    assertEquals(
        "certificate key usages do not exactly match digitalSignature/keyEncipherment",
        keyUsage.getMessage());

    when(certificate.getBasicConstraints()).thenReturn(0);
    when(certificate.getKeyUsage())
        .thenReturn(new boolean[] {true, false, true, false, false, false, false, false, false});
    var caAuthority =
        assertThrows(
            SecretMaterialValidator.MaterialValidationException.class,
            () -> SecretMaterialValidator.validateLeafProfile(certificate));
    assertEquals("certificate leaf must not be a CA", caAuthority.getMessage());
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
  void grpcBundleRenewalReportsMissingLeafMaterialAgainstTheLeafSecret() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret source = new GrpcTransportBundleGenerator().generate(plan);
    source.getData().remove("tls.crt");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                GrpcTransportBundleGenerator.renewalRequired(
                    source, Duration.ofDays(7), Instant.now()));

    assertEquals("gRPC source has invalid leaf certificate", failure.getMessage());
    assertEquals(
        "gRPC source leaf Secret is missing required material", failure.getCause().getMessage());
  }

  @Test
  void grpcBundleRenewalReportsInvalidLeafPemAgainstTheLeafSecret() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret generated = new GrpcTransportBundleGenerator().generate(plan);
    Map<String, String> invalidLeafData = new LinkedHashMap<>(generated.getData());
    invalidLeafData.put(
        "tls.crt",
        Base64.getEncoder()
            .encodeToString("not a certificate".getBytes(StandardCharsets.US_ASCII)));
    Secret source =
        new SecretBuilder()
            .withType("Opaque")
            .withMetadata(generated.getMetadata())
            .withData(invalidLeafData)
            .build();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                GrpcTransportBundleGenerator.renewalRequired(
                    source, Duration.ofDays(7), Instant.now()));

    assertEquals("gRPC source has invalid leaf certificate", failure.getMessage());
    assertEquals(
        "gRPC source leaf Secret must use CERTIFICATE PEM", failure.getCause().getMessage());
  }

  @Test
  void conflictRereadMustFindTheWinningSecret() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret winner = new GrpcTransportBundleGenerator().generate(plan);
    winner
        .getMetadata()
        .getAnnotations()
        .put(HostedIdentityContract.ISSUANCE_GENERATION_ANNOTATION, "4");
    assertEquals(winner, GrpcTransportBundleGenerator.requireConflictWinner(winner, 4, plan));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.requireConflictWinner(null, 4, plan));
    assertThrows(
        IllegalStateException.class,
        () -> GrpcTransportBundleGenerator.requireConflictWinner(winner, 5, plan));
  }

  @ParameterizedTest(name = "rejects conflict winner with invalid {0}")
  @ValueSource(
      strings = {
        HostedIdentityContract.MANAGED_BY_LABEL,
        HostedIdentityContract.ENVIRONMENT_LABEL,
        HostedIdentityContract.ROLE_LABEL,
        HostedIdentityContract.RETENTION_LABEL
      })
  void conflictRereadRejectsEveryUnownedWinningSecret(String ownershipLabel) {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret winner = new GrpcTransportBundleGenerator().generate(plan);
    winner
        .getMetadata()
        .getAnnotations()
        .put(HostedIdentityContract.ISSUANCE_GENERATION_ANNOTATION, "5");
    winner.getMetadata().getLabels().put(ownershipLabel, "not-controller-owned");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> GrpcTransportBundleGenerator.requireConflictWinner(winner, 4, plan));

    assertEquals("identity source Secret is not controller-owned", failure.getMessage());
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
    assertTrue(
        ca.getSubjectAlternativeNames() == null || ca.getSubjectAlternativeNames().isEmpty());
    assertEquals(
        GrpcTransportBundleGenerator.grpcDnsNames(plan).size(),
        leaf.getSubjectAlternativeNames().size());
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
  @SuppressWarnings("unchecked")
  void ensureRejectsStaleConfiguredTrustAnchorBeforeIdentityWrite() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    GrpcTransportBundleGenerator generator = new GrpcTransportBundleGenerator();
    Secret oldCa = generatedCa(now, Duration.ofDays(60));
    Secret existing = generator.generate(plan, oldCa, 4, renewBefore, now);
    existing.getMetadata().setResourceVersion("7");
    String oldTrustAnchor = SecretMaterialValidator.trustAnchorFingerprint(oldCa);
    Secret rotatedCa = generatedCaWithDistinctKeyPair(now, Duration.ofDays(60));

    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> controlSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> existingResource = mock(Resource.class);
    Resource<Secret> caResource = mock(Resource.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(secrets.inNamespace(plan.controlNamespace())).thenReturn(controlSecrets);
    when(identitySecrets.withName(plan.grpcSecretName())).thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(controlSecrets.withName(plan.caSecretName())).thenReturn(caResource);
    when(caResource.get()).thenReturn(rotatedCa);

    IllegalStateException stalePin =
        assertThrows(
            IllegalStateException.class,
            () -> generator.ensure(client, plan, 4L, renewBefore, oldTrustAnchor));
    assertEquals("configured gRPC CA trust anchor mismatch", stalePin.getMessage());
    verify(identitySecrets, org.mockito.Mockito.never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @SuppressWarnings("unchecked")
  void ensurePreservesCurrentBundleWithoutRepair(String resourceVersion) throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    GrpcTransportBundleGenerator generator = new GrpcTransportBundleGenerator();
    Secret ca = generatedCa(now, Duration.ofDays(60));
    Secret existing = generator.generate(plan, ca, 4, renewBefore, now);
    existing.getMetadata().setResourceVersion(resourceVersion);
    String trustAnchor = SecretMaterialValidator.trustAnchorFingerprint(ca);

    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> controlSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> existingResource = mock(Resource.class);
    Resource<Secret> caResource = mock(Resource.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(secrets.inNamespace(plan.controlNamespace())).thenReturn(controlSecrets);
    when(identitySecrets.withName(plan.grpcSecretName())).thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(controlSecrets.withName(plan.caSecretName())).thenReturn(caResource);
    when(caResource.get()).thenReturn(ca);

    assertSame(existing, generator.ensure(client, plan, 4L, renewBefore, trustAnchor));
    verify(identitySecrets, org.mockito.Mockito.never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void renewalRequiredTreatsTheExpiryThresholdAsInclusive() throws Exception {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    GrpcTransportBundleGenerator generator = new GrpcTransportBundleGenerator();
    Secret generated =
        generator.generate(plan, generatedCa(now, Duration.ofDays(60)), 4, Duration.ofDays(7), now);
    Instant leafNotAfter = certificate(generated.getData().get("tls.crt")).getNotAfter().toInstant();
    Duration exactRenewalThreshold = Duration.between(now, leafNotAfter);

    assertTrue(
        GrpcTransportBundleGenerator.renewalRequired(generated, exactRenewalThreshold, now));
    assertFalse(
        GrpcTransportBundleGenerator.renewalRequired(
            generated, exactRenewalThreshold.minusSeconds(1), now));
  }

  @Test
  @SuppressWarnings("unchecked")
  void ensureRepairsCurrentBundleWhenLeafDnsNamesDoNotMatch() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    EnvironmentIdentityPlan wrongDnsPlan = withGrpcConsumers(plan, "unexpected-service");
    GrpcTransportBundleGenerator generator = new GrpcTransportBundleGenerator();
    Secret ca = generatedCa(now, Duration.ofDays(60));
    Secret existing = generator.generate(wrongDnsPlan, ca, 4, renewBefore, now);
    existing.getMetadata().setResourceVersion("7");
    String trustAnchor = SecretMaterialValidator.trustAnchorFingerprint(ca);
    assertNotEquals(
        GrpcTransportBundleGenerator.grpcDnsNames(plan),
        GrpcTransportBundleGenerator.grpcDnsNames(wrongDnsPlan));

    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> controlSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> existingResource = mock(Resource.class);
    Resource<Secret> caResource = mock(Resource.class);
    Resource<Secret> replacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> lockedReplacementResource = mock(ReplaceDeletable.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(secrets.inNamespace(plan.controlNamespace())).thenReturn(controlSecrets);
    when(identitySecrets.withName(plan.grpcSecretName())).thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(controlSecrets.withName(plan.caSecretName())).thenReturn(caResource);
    when(caResource.get()).thenReturn(ca);
    Secret[] replacementHolder = new Secret[1];
    when(identitySecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenAnswer(
            invocation -> {
              replacementHolder[0] = invocation.getArgument(0, Secret.class);
              return replacementResource;
            });
    when(replacementResource.lockResourceVersion("7")).thenReturn(lockedReplacementResource);
    when(lockedReplacementResource.replace()).thenAnswer(invocation -> replacementHolder[0]);

    Secret repaired = generator.ensure(client, plan, 4L, renewBefore, trustAnchor);

    verify(identitySecrets).resource(org.mockito.ArgumentMatchers.any(Secret.class));
    verify(replacementResource).lockResourceVersion("7");
    verify(lockedReplacementResource).replace();
    assertNotSame(existing, repaired);
    assertEquals(5, GrpcTransportBundleGenerator.issuanceGeneration(repaired));
    assertEquals(
        GrpcTransportBundleGenerator.grpcDnsNames(plan),
        certificate(repaired.getData().get("tls.crt")).getSubjectAlternativeNames().stream()
            .map(subjectAlternativeName -> (String) subjectAlternativeName.get(1))
            .sorted()
            .toList());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @SuppressWarnings("unchecked")
  void ensureRejectsCaRotationWithoutResourceVersionBeforeIdentityWrite(String resourceVersion)
      throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    GrpcTransportBundleGenerator generator = new GrpcTransportBundleGenerator();
    Secret oldCa = generatedCaWithDistinctKeyPair(now, Duration.ofDays(60));
    Secret existing = generator.generate(plan, oldCa, 4, renewBefore, now);
    existing.getMetadata().setResourceVersion(resourceVersion);
    Secret rotatedCa = generatedCaWithDistinctKeyPair(now, Duration.ofDays(60));
    String rotatedTrustAnchor = SecretMaterialValidator.trustAnchorFingerprint(rotatedCa);

    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> controlSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> existingResource = mock(Resource.class);
    Resource<Secret> caResource = mock(Resource.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(secrets.inNamespace(plan.controlNamespace())).thenReturn(controlSecrets);
    when(identitySecrets.withName(plan.grpcSecretName())).thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(controlSecrets.withName(plan.caSecretName())).thenReturn(caResource);
    when(caResource.get()).thenReturn(rotatedCa);

    IllegalStateException missingVersion =
        assertThrows(
            IllegalStateException.class,
            () -> generator.ensure(client, plan, 4L, renewBefore, rotatedTrustAnchor));
    assertEquals(
        "existing gRPC Secret has no resourceVersion for repair", missingVersion.getMessage());
    verify(identitySecrets, org.mockito.Mockito.never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void ensureRegeneratesTimeCurrentBundleWhenTheValidatedCaRotates() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    GrpcTransportBundleGenerator generator = new GrpcTransportBundleGenerator();
    Secret oldCa = generatedCaWithDistinctKeyPair(now, Duration.ofDays(60));
    Secret existing = generator.generate(plan, oldCa, 4, renewBefore, now);
    existing.getMetadata().setResourceVersion("7");
    String oldTrustAnchor = SecretMaterialValidator.trustAnchorFingerprint(oldCa);
    Secret rotatedCa = generatedCaWithDistinctKeyPair(now, Duration.ofDays(60));
    String rotatedTrustAnchor = SecretMaterialValidator.trustAnchorFingerprint(rotatedCa);
    assertFalse(GrpcTransportBundleGenerator.renewalRequired(existing, renewBefore, Instant.now()));
    assertNotEquals(rotatedTrustAnchor, SecretMaterialValidator.trustAnchorFingerprint(existing));

    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> controlSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> existingResource = mock(Resource.class);
    Resource<Secret> caResource = mock(Resource.class);
    Resource<Secret> replacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> lockedReplacementResource = mock(ReplaceDeletable.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(secrets.inNamespace(plan.controlNamespace())).thenReturn(controlSecrets);
    when(identitySecrets.withName(plan.grpcSecretName())).thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(controlSecrets.withName(plan.caSecretName())).thenReturn(caResource);
    when(caResource.get()).thenReturn(rotatedCa);
    Secret[] replacementHolder = new Secret[1];
    when(identitySecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenAnswer(
            invocation -> {
              replacementHolder[0] = invocation.getArgument(0, Secret.class);
              return replacementResource;
            });
    when(replacementResource.lockResourceVersion("7")).thenReturn(lockedReplacementResource);
    when(lockedReplacementResource.replace()).thenAnswer(invocation -> replacementHolder[0]);

    Secret repaired = generator.ensure(client, plan, 4L, renewBefore, rotatedTrustAnchor);

    org.mockito.ArgumentCaptor<Secret> replacement =
        org.mockito.ArgumentCaptor.forClass(Secret.class);
    verify(identitySecrets).resource(replacement.capture());
    verify(replacementResource).lockResourceVersion("7");
    verify(lockedReplacementResource).replace();
    Secret rotated = replacementHolder[0];
    assertSame(rotated, repaired);
    assertEquals(5, GrpcTransportBundleGenerator.issuanceGeneration(rotated));
    assertEquals("7", rotated.getMetadata().getResourceVersion());
    assertEquals(rotatedCa.getData().get("ca.crt"), rotated.getData().get("ca.crt"));
    certificate(rotated.getData().get("tls.crt"))
        .verify(certificate(rotatedCa.getData().get("ca.crt")).getPublicKey());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ensureRejectsUnownedExistingBundleBeforeCaReadOrIdentityWrite() {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret existing = new GrpcTransportBundleGenerator().generate(plan);
    existing
        .getMetadata()
        .getLabels()
        .put(HostedIdentityContract.MANAGED_BY_LABEL, "another-controller");
    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> existingResource = mock(Resource.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(identitySecrets.withName(plan.grpcSecretName())).thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GrpcTransportBundleGenerator()
                    .ensure(client, plan, 1L, Duration.ofDays(7), "0".repeat(64)));

    assertEquals("identity source Secret is not controller-owned", failure.getMessage());
    verify(secrets, org.mockito.Mockito.never()).inNamespace(plan.controlNamespace());
    verify(identitySecrets, org.mockito.Mockito.never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
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
  void productionGenerationAcceptsCaAtTheRenewalSlackBoundaryWhilePreservingTheCap()
      throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    Duration caLifetime =
        renewBefore.plus(HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret caSource = generatedCa(now, caLifetime);

    Secret generated =
        new GrpcTransportBundleGenerator().generate(plan, caSource, 2, renewBefore, now);

    assertEquals(
        certificate(caSource.getData().get("ca.crt")).getNotAfter(),
        certificate(generated.getData().get("tls.crt")).getNotAfter());
  }

  @Test
  void productionGenerationRejectsCaWithoutTheRenewalSlack() throws Exception {
    // X.509 validity timestamps have whole-second precision; truncating now
    // makes this one-nanosecond-under-boundary case deterministic before encoding.
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration renewBefore = Duration.ofDays(7);
    Duration caLifetime =
        renewBefore.plus(HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK).minusNanos(1);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret caSource = generatedCa(now, caLifetime);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new GrpcTransportBundleGenerator().generate(plan, caSource, 2, renewBefore, now));

    assertTrue(failure.getMessage().contains("expires within the gRPC renewal window"));
  }

  @Test
  void productionGenerationRejectsRenewalWindowBelowCertManagerMinimum() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret caSource = generatedCa(now, Duration.ofDays(60));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GrpcTransportBundleGenerator()
                    .generate(plan, caSource, 2, Duration.ofMinutes(5).minusNanos(1), now));

    assertEquals(
        "gRPC renewal window must be at least 5 minutes and leave at least 5 minutes before the 30-day certificate expiry",
        failure.getMessage());
  }

  @Test
  void productionGenerationRejectsRenewalWindowAtThirtyDays() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    Secret caSource = generatedCa(now, Duration.ofDays(60));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GrpcTransportBundleGenerator()
                    .generate(plan, caSource, 2, Duration.ofDays(30), now));

    assertEquals(
        "gRPC renewal window must be at least 5 minutes and leave at least 5 minutes before the 30-day certificate expiry",
        failure.getMessage());
  }

  @Test
  void grpcCaRejectsNonCanonicalKeysAndMismatchedPrivateKey() throws Exception {
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
    Secret matchingCa = generatedCa(Instant.now(), Duration.ofDays(60), new X500Name("CN=fixture"));
    Secret wrongPemLabels =
        new SecretBuilder(matchingCa)
            .withData(
                Map.of(
                    "ca.crt",
                    relabel(matchingCa.getData().get("ca.crt"), "CERTIFICATE", "X509 CERTIFICATE"),
                    "ca.key",
                    matchingCa.getData().get("ca.key")))
            .build();

    IllegalStateException fallbackFailure =
        assertThrows(
            IllegalStateException.class,
            () -> GrpcTransportBundleGenerator.validateCa(fallbackShape, fingerprint));
    assertEquals(
        "configured gRPC CA Secret must be Opaque and contain exactly ca.crt and ca.key",
        fallbackFailure.getMessage());
    IllegalStateException mismatchedKeyFailure =
        assertThrows(
            IllegalStateException.class,
            () -> GrpcTransportBundleGenerator.validateCa(mismatchedKey, fingerprint));
    assertEquals(
        "configured gRPC CA certificate and key do not match", mismatchedKeyFailure.getMessage());
    var wrongPemException =
        assertThrows(
            IllegalStateException.class,
            () ->
                GrpcTransportBundleGenerator.validateCa(
                    wrongPemLabels, SecretMaterialValidator.trustAnchorFingerprint(matchingCa)));
    assertEquals("configured gRPC CA material is invalid", wrongPemException.getMessage());
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

  private static EnvironmentIdentityPlan withGrpcConsumers(
      EnvironmentIdentityPlan plan, String... consumers) {
    return new EnvironmentIdentityPlan(
        plan.name(),
        plan.controlNamespace(),
        plan.identityNamespace(),
        plan.runtimeNamespace(),
        plan.hostname(),
        plan.ingressCertificateName(),
        plan.ingressSecretName(),
        plan.telnetCertificateName(),
        plan.telnetSecretName(),
        plan.gatewayInternalWsCertificateName(),
        plan.gatewayInternalWsSecretName(),
        plan.gatewayInternalWsDnsName(),
        plan.tcpProxyBridgeCertificateName(),
        plan.tcpProxyBridgeSecretName(),
        plan.tcpProxyBridgeUriSan(),
        plan.grpcCertificateName(),
        plan.grpcSecretName(),
        plan.ingressIssuer(),
        plan.telnetIssuer(),
        plan.grpcIssuer(),
        plan.caSecretName(),
        java.util.List.of(consumers));
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
    return generatedCa(now, lifetime, name, FIXTURE_CA_KEY_PAIR);
  }

  private static Secret generatedCaWithDistinctKeyPair(Instant now, Duration lifetime)
      throws Exception {
    return generatedCa(
        now,
        lifetime,
        new X500Name("CN=FireMUD test transport root, O=FireMUD"),
        generateRsaKeyPair());
  }

  private static Secret generatedCa(Instant now, Duration lifetime, X500Name name, KeyPair keyPair)
      throws Exception {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
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
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      return keyPairGenerator.generateKeyPair();
    } catch (Exception exception) {
      throw new AssertionError("unable to create RSA test fixture key pair", exception);
    }
  }

  private static String pem(String label, byte[] der) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return encode("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
  }
}
