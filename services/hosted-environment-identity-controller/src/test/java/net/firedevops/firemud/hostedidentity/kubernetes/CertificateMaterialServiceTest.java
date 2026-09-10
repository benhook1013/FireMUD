package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ReplaceDeletable;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.GrpcTransportBundleGenerator;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class CertificateMaterialServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void controlledCertificateFieldsAreRepairedButUnknownDriftIsRejected() {
    EnvironmentIdentityPlan plan = plan();
    GenericKubernetesResource desired = new CertificateResourceFactory().ingress(plan);
    GenericKubernetesResource existing = new GenericKubernetesResource();
    existing.setApiVersion("cert-manager.io/v1");
    existing.setKind("Certificate");
    existing.setMetadata(
        new ObjectMetaBuilder()
            .withName(desired.getMetadata().getName())
            .withNamespace(plan.identityNamespace())
            .withLabels(desired.getMetadata().getLabels())
            .withResourceVersion("7")
            .build());
    Map<String, Object> existingSpec =
        new LinkedHashMap<>((Map<String, Object>) desired.getAdditionalProperties().get("spec"));
    existingSpec.put("secretName", "obsolete-secret-name");
    existing.setAdditionalProperties(Map.of("spec", existingSpec));
    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        certificates = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityCertificates = mock(NonNamespaceOperation.class);
    Resource<GenericKubernetesResource> existingResource = mock(Resource.class);
    Resource<GenericKubernetesResource> replacementResource = mock(Resource.class);
    ReplaceDeletable<GenericKubernetesResource> lockedReplacementResource =
        mock(ReplaceDeletable.class);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATES)).thenReturn(certificates);
    when(certificates.inNamespace(plan.identityNamespace())).thenReturn(identityCertificates);
    when(identityCertificates.withName(desired.getMetadata().getName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(identityCertificates.resource(desired)).thenReturn(replacementResource);
    when(replacementResource.lockResourceVersion("7")).thenReturn(lockedReplacementResource);

    CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired);

    assertEquals("7", desired.getMetadata().getResourceVersion());
    verify(replacementResource).lockResourceVersion("7");
    verify(lockedReplacementResource).replace();

    existingSpec.put("isCA", true);
    assertThrows(
        IllegalStateException.class,
        () ->
            CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired));
    existingSpec.remove("isCA");
    existingSpec.put(
        "secretName",
        ((Map<String, Object>) desired.getAdditionalProperties().get("spec")).get("secretName"));
    existing.getMetadata().getLabels().put("tooling.example/managed-by", "cluster-tool");
    assertDoesNotThrow(
        () ->
            CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired));
    existing
        .getMetadata()
        .getLabels()
        .put(HostedIdentityContract.MANAGED_BY_LABEL, "other-controller");
    assertThrows(
        IllegalStateException.class,
        () ->
            CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired));
  }

  @Test
  @SuppressWarnings("unchecked")
  void certificateRepairTreatsConflictAsTransientButPropagatesOtherFailures() {
    EnvironmentIdentityPlan plan = plan();
    GenericKubernetesResource desired = new CertificateResourceFactory().ingress(plan);
    GenericKubernetesResource existing = new GenericKubernetesResource();
    existing.setApiVersion("cert-manager.io/v1");
    existing.setKind("Certificate");
    existing.setMetadata(
        new ObjectMetaBuilder()
            .withName(desired.getMetadata().getName())
            .withNamespace(plan.identityNamespace())
            .withLabels(desired.getMetadata().getLabels())
            .withResourceVersion("7")
            .build());
    Map<String, Object> existingSpec =
        new LinkedHashMap<>((Map<String, Object>) desired.getAdditionalProperties().get("spec"));
    existingSpec.put("secretName", "obsolete-secret-name");
    existing.setAdditionalProperties(Map.of("spec", existingSpec));
    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        certificates = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityCertificates = mock(NonNamespaceOperation.class);
    Resource<GenericKubernetesResource> existingResource = mock(Resource.class);
    Resource<GenericKubernetesResource> replacementResource = mock(Resource.class);
    ReplaceDeletable<GenericKubernetesResource> lockedReplacementResource =
        mock(ReplaceDeletable.class);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATES)).thenReturn(certificates);
    when(certificates.inNamespace(plan.identityNamespace())).thenReturn(identityCertificates);
    when(identityCertificates.withName(desired.getMetadata().getName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(identityCertificates.resource(desired)).thenReturn(replacementResource);
    when(replacementResource.lockResourceVersion("7")).thenReturn(lockedReplacementResource);

    org.mockito.Mockito.doThrow(new KubernetesClientException("conflict", 409, null))
        .when(lockedReplacementResource)
        .replace();

    assertDoesNotThrow(
        () ->
            CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired));

    existingSpec.put("secretName", "another-obsolete-secret-name");
    KubernetesClientException failure = new KubernetesClientException("forbidden", 403, null);
    org.mockito.Mockito.doThrow(failure).when(lockedReplacementResource).replace();

    verify(replacementResource).lockResourceVersion("7");

    KubernetesClientException thrown =
        assertThrows(
            KubernetesClientException.class,
            () ->
                CertificateMaterialService.applyCertificate(
                    client, plan.identityNamespace(), desired));

    assertSame(failure, thrown);
  }

  @Test
  void issuerReferenceRejectsMissingResourceBodyExplicitly() {
    GenericKubernetesResource certificate = new GenericKubernetesResource();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> CertificateMaterialService.issuerReference(certificate));

    assertEquals("certificate issuer reference is unavailable", failure.getMessage());
  }

  @Test
  @SuppressWarnings("unchecked")
  void materializationBatchReusesOneRotationSelectionSnapshot() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    stubCertificate(client, plan, plan.ingressCertificateName(), true);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> runtimeSecret = mock(Resource.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeSecrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    when(runtimeSecrets.withName(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(runtimeSecret);
    when(runtimeSecret.get()).thenReturn(null);

    GrpcTransportBundleGenerator generator = mock(GrpcTransportBundleGenerator.class);
    Secret grpcSource =
        new SecretBuilder()
            .withNewMetadata()
            .withLabels(
                HostedIdentityContract.managedLabels(plan.name(), HostedIdentityContract.GRPC_ROLE))
            .addToAnnotations(HostedIdentityContract.ISSUANCE_GENERATION_ANNOTATION, "1")
            .endMetadata()
            .withType("Opaque")
            .build();
    Secret ingressSource =
        certManagerSource(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key")));
    Resource<Secret> ingressSourceResource = mock(Resource.class);
    when(identitySecrets.withName(plan.ingressSecretName())).thenReturn(ingressSourceResource);
    when(ingressSourceResource.get()).thenReturn(ingressSource);
    when(generator.ensure(
            client,
            plan,
            null,
            properties.getGrpcRenewBefore(),
            properties.getGrpcTrustAnchorSha256()))
        .thenReturn(grpcSource);
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    SecretMaterialValidator.MaterialSummary summary =
        new SecretMaterialValidator.MaterialSummary(
            "1".repeat(64),
            "2".repeat(64),
            java.time.Instant.EPOCH,
            java.time.Instant.MAX,
            "3".repeat(64));
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(summary);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(), validator, generator, properties);

    CertificateMaterialService.MaterializationBatch batch =
        service.beginMaterialization(client, plan);
    CertificateMaterialService.RoleMaterial ingress = batch.ingress();

    assertEquals(HostedIdentityContract.INGRESS_ROLE, ingress.role());
    assertEquals(summary, ingress.summary());
    ArgumentCaptor<String> read = ArgumentCaptor.forClass(String.class);
    verify(runtimeSecrets, org.mockito.Mockito.atLeastOnce()).withName(read.capture());
    assertEquals(
        java.util.Set.of(
            plan.ingressSecretName(),
            plan.telnetSecretName(),
            plan.gatewayInternalWsSecretName(),
            plan.tcpProxyBridgeSecretName(),
            plan.grpcSecretName()),
        java.util.Set.copyOf(read.getAllValues()));

    clearInvocations(runtimeSecrets);
    CertificateMaterialService.RoleMaterial grpc = batch.grpc(null);

    assertEquals(HostedIdentityContract.GRPC_ROLE, grpc.role());
    assertEquals(summary, grpc.summary());
    verifyNoInteractions(runtimeSecrets);
  }

  @Test
  @SuppressWarnings("unchecked")
  void normalGrpcMaterializationRejectsUnownedGeneratorResultBeforeValidation() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> runtimeSecret = mock(Resource.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeSecrets);
    when(runtimeSecrets.withName(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(runtimeSecret);
    when(runtimeSecret.get()).thenReturn(null);
    GrpcTransportBundleGenerator generator = mock(GrpcTransportBundleGenerator.class);
    Secret unowned =
        new SecretBuilder()
            .withNewMetadata()
            .withName(plan.grpcSecretName())
            .withNamespace(plan.identityNamespace())
            .endMetadata()
            .withType("Opaque")
            .build();
    when(generator.ensure(
            client,
            plan,
            1L,
            properties.getGrpcRenewBefore(),
            properties.getGrpcTrustAnchorSha256()))
        .thenReturn(unowned);
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    CertificateMaterialService service =
        new CertificateMaterialService(
            mock(CertificateResourceFactory.class), validator, generator, properties);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> service.beginMaterialization(client, plan).grpc(1L));

    assertEquals("identity source Secret is not controller-owned", failure.getMessage());
    verify(generator)
        .ensure(
            client,
            plan,
            1L,
            properties.getGrpcRenewBefore(),
            properties.getGrpcTrustAnchorSha256());
    verifyNoInteractions(validator);
  }

  @ParameterizedTest(name = "normal cert-manager materialization rejects invalid {0}")
  @EnumSource(CertManagerSourceMutation.class)
  @SuppressWarnings("unchecked")
  void normalCertManagerMaterializationRejectsInvalidIdentityBeforeValidation(
      CertManagerSourceMutation mutation) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    SecretClient secretClient = secretClient(plan);
    Resource<Secret> absentRuntimeSecret = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(absentRuntimeSecret);
    when(absentRuntimeSecret.get()).thenReturn(null);
    stubCertificate(secretClient.client(), plan, plan.ingressCertificateName(), true);
    Secret source =
        certManagerSource(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key")));
    mutation.apply(source);
    Resource<Secret> sourceResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.ingressSecretName()))
        .thenReturn(sourceResource);
    when(sourceResource.get()).thenReturn(source);
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(),
            validator,
            mock(GrpcTransportBundleGenerator.class),
            properties);

    assertThrows(
        IllegalStateException.class,
        () -> service.beginMaterialization(secretClient.client(), plan).ingress());

    verifyNoInteractions(validator);
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void initialMaterializationWaitsWhenOldRevisionEvidencePrecedesNewSecretBytes() {
    Map<String, String> oldData =
        Map.of("tls.crt", encoded("old-certificate"), "tls.key", encoded("old-key"));
    Map<String, String> newData =
        Map.of("tls.crt", encoded("new-certificate"), "tls.key", encoded("new-key"));

    IssuanceObservation observation = materializeIngress(1, oldData, newData);

    assertEquals(false, observation.material().ready());
    assertEquals("materialization-pending", observation.material().state());
    verifyNoInteractions(observation.validator());
    verify(observation.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void initialMaterializationWaitsWhenNewRevisionEvidencePrecedesOldSecretBytes() {
    Map<String, String> newData =
        Map.of("tls.crt", encoded("new-certificate"), "tls.key", encoded("new-key"));
    Map<String, String> oldData =
        Map.of("tls.crt", encoded("old-certificate"), "tls.key", encoded("old-key"));

    IssuanceObservation observation = materializeIngress(2, newData, oldData);

    assertEquals(false, observation.material().ready());
    assertEquals("materialization-pending", observation.material().state());
    verifyNoInteractions(observation.validator());
    verify(observation.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void initialMaterializationAdvancesOnlyWithMaterialBoundRevisionEvidence() {
    Map<String, String> data =
        Map.of("tls.crt", encoded("new-certificate"), "tls.key", encoded("new-key"));

    IssuanceObservation observation = materializeIngress(2, data, data);

    assertEquals(true, observation.material().ready());
    assertEquals("source-ready", observation.material().state());
    assertEquals(2, observation.material().sourceGeneration());
    assertEquals(2, observation.material().sourceObjectGeneration());
    verify(observation.validator())
        .validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void initialMaterializationAllowsBestEffortCaToBeAbsentFromRequestStatus() {
    Map<String, String> requestData =
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    Map<String, String> secretData =
        Map.of(
            "tls.crt",
            encoded("certificate"),
            "tls.key",
            encoded("key"),
            "ca.crt",
            encoded("issuer-chain"));

    IssuanceObservation observation = materializeIngress(2, requestData, secretData);

    assertEquals(true, observation.material().ready());
    assertEquals("source-ready", observation.material().state());
  }

  @Test
  void initialMaterializationWaitsWhenCertificateChangesDuringEvidenceReads() {
    Map<String, String> data =
        Map.of("tls.crt", encoded("new-certificate"), "tls.key", encoded("new-key"));

    IssuanceObservation observation = materializeIngress(2, data, data, 3);

    assertEquals(false, observation.material().ready());
    assertEquals("materialization-pending", observation.material().state());
    verifyNoInteractions(observation.validator());
  }

  @Test
  void pendingIngressStillWaitsBeforeReadingSourceMaterial() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    SecretClient secretClient = secretClient(plan);
    Resource<Secret> absentRuntimeSecret = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(absentRuntimeSecret);
    when(absentRuntimeSecret.get()).thenReturn(null);
    stubCertificate(secretClient.client(), plan, plan.ingressCertificateName(), false);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(),
            mock(SecretMaterialValidator.class),
            mock(GrpcTransportBundleGenerator.class),
            properties);

    CertificateMaterialService.RoleMaterial ingress =
        service.beginMaterialization(secretClient.client(), plan).ingress();

    assertEquals(false, ingress.ready());
    assertEquals("certificate-pending", ingress.state());
    verify(secretClient.runtimeSecrets(), org.mockito.Mockito.atLeastOnce())
        .withName(org.mockito.ArgumentMatchers.anyString());
    verify(secretClient.identitySecrets(), never())
        .withName(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void unacceptedProjectionRemainsPinnedWhenItsCertificateIsNotReady() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Secret projection =
        fixture
            .secretClient()
            .runtimeSecrets()
            .withName(plan.ingressSecretName())
            .get();
    projection
        .getMetadata()
        .getAnnotations()
        .remove(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), false);

    CertificateMaterialService.RoleMaterial material = fixture.batch().ingress();

    assertEquals("serialized-in-flight", material.state());
    assertSame(projection, material.source());
  }

  @Test
  void selectedRotationIsResolvedBeforeAnEarlierNonSelectedRoleAppliesItsCertificate() {
    StableBatchFixture fixture = stableBatchFixture();
    Secret telnetSource =
        fixture.secretClient().identitySecrets().withName(fixture.plan().telnetSecretName()).get();
    telnetSource.setData(
        Map.of("tls.crt", encoded("telnet-replacement"), "tls.key", encoded("telnet-key-2")));

    CertificateMaterialService.RoleMaterial ingress = fixture.batch().ingress();

    assertEquals("serialized-deferred", ingress.state());
    assertEquals(fixture.acceptedData(), ingress.source().getData());
    verify(fixture.secretClient().client(), never())
        .genericKubernetesResources(ResourceContexts.CERTIFICATES);
  }

  @Test
  void certificateReadinessIsBoundToObservedGenerationAndPositiveRevision() {
    GenericKubernetesResource certificate = new GenericKubernetesResource();
    certificate.setMetadata(new ObjectMetaBuilder().withGeneration(7L).build());
    certificate.setAdditionalProperties(
        Map.of(
            "status",
            Map.of(
                "revision",
                3,
                "conditions",
                java.util.List.of(
                    Map.of(
                        "type", "Ready",
                        "status", "True",
                        "observedGeneration", 7)))));
    assertEquals(3, CertificateMaterialService.readyRevision(certificate).revision());
    certificate.setAdditionalProperties(
        Map.of(
            "status",
            Map.of(
                "revision",
                3,
                "conditions",
                java.util.List.of(
                    Map.of(
                        "type", "Ready",
                        "status", "True",
                        "observedGeneration", 6)))));
    assertEquals(null, CertificateMaterialService.readyRevision(certificate));
  }

  @Test
  void certificateComparisonAcceptsOnlyDefaultedFieldsAndRepresentationChanges() {
    Map<String, Object> desired =
        Map.of(
            "secretName",
            "pr-42-tls",
            "encodeUsagesInRequest",
            true,
            "privateKey",
            Map.of("algorithm", "RSA"),
            "dnsNames",
            java.util.List.of("pr-42.example.test"));
    Map<String, Object> defaulted =
        Map.of(
            "secretName",
            "pr-42-tls",
            "encodeUsagesInRequest",
            true,
            "revisionHistoryLimit",
            1L,
            "privateKey",
            Map.of("algorithm", "RSA", "size", 2048),
            "dnsNames",
            new java.util.ArrayList<>(java.util.List.of("pr-42.example.test")),
            "duration",
            "2160h");
    assertEquals(true, CertificateMaterialService.desiredSubsetEquivalent(desired, defaulted));
    Map<String, Object> changed = new java.util.LinkedHashMap<>(defaulted);
    changed.put("secretName", "other");
    assertEquals(false, CertificateMaterialService.desiredSubsetEquivalent(desired, changed));
    changed = new java.util.LinkedHashMap<>(defaulted);
    changed.put("encodeUsagesInRequest", false);
    assertEquals(false, CertificateMaterialService.desiredSubsetEquivalent(desired, changed));
    for (Map.Entry<String, Object> semanticExtra :
        Map.<String, Object>of(
                "isCA", true,
                "commonName", "unexpected.example.test",
                "additionalOutputFormats", List.of(Map.of("type", "CombinedPEM")),
                "keystores", Map.of("pkcs12", Map.of("create", true)),
                "secretTemplate", Map.of("annotations", Map.of("unexpected", "value")))
            .entrySet()) {
      Map<String, Object> unsafe = new LinkedHashMap<>(defaulted);
      unsafe.put(semanticExtra.getKey(), semanticExtra.getValue());
      assertEquals(false, CertificateMaterialService.desiredSubsetEquivalent(desired, unsafe));
    }
    for (Map.Entry<String, Object> invalidDefault :
        Map.<String, Object>of("duration", "1h", "revisionHistoryLimit", 2).entrySet()) {
      Map<String, Object> unsafe = new LinkedHashMap<>(defaulted);
      unsafe.put(invalidDefault.getKey(), invalidDefault.getValue());
      assertEquals(false, CertificateMaterialService.desiredSubsetEquivalent(desired, unsafe));
    }
    Map<String, Object> unsafePrivateKey = new LinkedHashMap<>(defaulted);
    unsafePrivateKey.put("privateKey", Map.of("algorithm", "RSA", "size", 4096));
    assertEquals(
        false, CertificateMaterialService.desiredSubsetEquivalent(desired, unsafePrivateKey));
    Map<String, Object> desiredWithTemplate = new LinkedHashMap<>(desired);
    desiredWithTemplate.put(
        "secretTemplate", Map.of("annotations", Map.of("firemud.dev/managed", "true")));
    Map<String, Object> exactTemplate = new LinkedHashMap<>(defaulted);
    exactTemplate.put(
        "secretTemplate", Map.of("annotations", Map.of("firemud.dev/managed", "true")));
    assertEquals(
        true,
        CertificateMaterialService.desiredSubsetEquivalent(desiredWithTemplate, exactTemplate));
    Map<String, Object> unsafeTemplate = new LinkedHashMap<>(exactTemplate);
    unsafeTemplate.put(
        "secretTemplate",
        Map.of("annotations", Map.of("firemud.dev/managed", "true", "unexpected", "value")));
    assertEquals(
        false,
        CertificateMaterialService.desiredSubsetEquivalent(desiredWithTemplate, unsafeTemplate));
    assertEquals(
        true,
        CertificateMaterialService.containsDesiredLabels(
            Map.of("managed", "yes", "cert-manager-default", "present"), Map.of("managed", "yes")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void certificateComparisonAcceptsRealisticReadbackOfTheFactorySpec() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    GenericKubernetesResource certificate = new CertificateResourceFactory().ingress(plan);
    Map<String, Object> desired =
        (Map<String, Object>) certificate.getAdditionalProperties().get("spec");
    Map<String, Object> readback = new LinkedHashMap<>(desired);
    Map<String, Object> defaultedPrivateKey =
        new LinkedHashMap<>((Map<String, Object>) desired.get("privateKey"));
    defaultedPrivateKey.put("size", 2048L);
    readback.put("privateKey", defaultedPrivateKey);
    readback.put("duration", "2160h");
    readback.put("revisionHistoryLimit", 1);

    assertEquals(true, CertificateMaterialService.desiredSubsetEquivalent(desired, readback));
    assertEquals(true, readback.get("encodeUsagesInRequest"));
    Map<?, ?> secretTemplate = (Map<?, ?>) readback.get("secretTemplate");
    assertEquals(
        HostedIdentityContract.managedLabels(plan.name(), HostedIdentityContract.INGRESS_ROLE),
        secretTemplate.get("labels"));
    assertEquals(
        Map.of(
            HostedIdentityContract.PROVENANCE_ANNOTATION,
            "cert-manager",
            HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
            "source-materialized"),
        secretTemplate.get("annotations"));
  }

  @Test
  void serializedRotationContinuesPendingRoleBeforeStartingAnotherChange() {
    var ingress =
        new CertificateMaterialService.RotationState("ingress", false, true, false, false);
    var telnet = new CertificateMaterialService.RotationState("telnet", false, true, false, false);
    var grpc = new CertificateMaterialService.RotationState("grpc", true, true, false, true);

    assertEquals(
        "grpc",
        CertificateMaterialService.selectSerializedRole(java.util.List.of(ingress, telnet, grpc)));
  }

  @Test
  void serializedRotationStartsOnlyTheFirstChangedRole() {
    var ingress =
        new CertificateMaterialService.RotationState("ingress", false, false, false, false);
    var telnet = new CertificateMaterialService.RotationState("telnet", false, true, false, false);
    var grpc = new CertificateMaterialService.RotationState("grpc", false, true, false, false);

    assertEquals(
        "telnet",
        CertificateMaterialService.selectSerializedRole(java.util.List.of(ingress, telnet, grpc)));
    assertEquals(
        null,
        CertificateMaterialService.selectSerializedRole(
            java.util.List.of(
                ingress,
                new CertificateMaterialService.RotationState("telnet", false, false, false, false),
                new CertificateMaterialService.RotationState("grpc", false, false, false, false))));
  }

  @Test
  void serializedRotationSelectsExistingChangeWhileAnotherRoleInitializes() {
    var ingress = new CertificateMaterialService.RotationState("ingress", true, true, false, false);
    var telnet = new CertificateMaterialService.RotationState("telnet", false, false, true, false);
    var grpc = new CertificateMaterialService.RotationState("grpc", true, true, false, false);

    assertEquals(
        "ingress",
        CertificateMaterialService.selectSerializedRole(java.util.List.of(ingress, telnet, grpc)));
  }

  @Test
  void postSnapshotCertRotationsAdvanceOnlyTheFirstChangedRole() {
    StableBatchFixture fixture = stableBatchFixture();
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().ingressCertificateName(),
        true,
        1,
        fixture.acceptedData());

    assertEquals("source-ready", fixture.batch().ingress().state());

    Map<String, String> telnetReplacement =
        Map.of("tls.crt", encoded("telnet-replacement"), "tls.key", encoded("telnet-key-2"));
    Secret telnetSource =
        certManagerSource(
            fixture.plan(),
            HostedIdentityContract.TELNET_ROLE,
            fixture.plan().telnetSecretName(),
            telnetReplacement);
    when(fixture.secretClient().identitySecrets().withName(fixture.plan().telnetSecretName()).get())
        .thenReturn(telnetSource);
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().telnetCertificateName(),
        true,
        2,
        telnetReplacement);

    CertificateMaterialService.RoleMaterial telnet = fixture.batch().telnet();

    Map<String, String> gatewayReplacement =
        Map.of("tls.crt", encoded("gateway-replacement"), "tls.key", encoded("gateway-key-2"));
    Secret gatewaySource =
        certManagerSource(
            fixture.plan(),
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            fixture.plan().gatewayInternalWsSecretName(),
            gatewayReplacement);
    when(fixture
            .secretClient()
            .identitySecrets()
            .withName(fixture.plan().gatewayInternalWsSecretName())
            .get())
        .thenReturn(gatewaySource);
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().gatewayInternalWsCertificateName(),
        true,
        2,
        gatewayReplacement);

    CertificateMaterialService.RoleMaterial gateway = fixture.batch().gatewayInternalWs();

    assertEquals("source-ready", telnet.state());
    assertEquals(telnetReplacement, telnet.source().getData());
    assertEquals("serialized-deferred", gateway.state());
    assertEquals(fixture.acceptedData(), gateway.source().getData());
  }

  @Test
  void postSnapshotCertificateRotationDefersConcurrentGrpcGeneration() {
    StableBatchFixture fixture = stableBatchFixture();
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().ingressCertificateName(),
        true,
        1,
        fixture.acceptedData());
    assertEquals("source-ready", fixture.batch().ingress().state());

    Map<String, String> telnetReplacement =
        Map.of("tls.crt", encoded("telnet-replacement"), "tls.key", encoded("telnet-key-2"));
    when(fixture.secretClient().identitySecrets().withName(fixture.plan().telnetSecretName()).get())
        .thenReturn(
            certManagerSource(
                fixture.plan(),
                HostedIdentityContract.TELNET_ROLE,
                fixture.plan().telnetSecretName(),
                telnetReplacement));
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().telnetCertificateName(),
        true,
        2,
        telnetReplacement);
    assertEquals("source-ready", fixture.batch().telnet().state());

    Map<String, String> grpcReplacement =
        Map.of(
            "tls.crt",
            encoded("grpc-replacement"),
            "tls.key",
            encoded("grpc-key-2"),
            "ca.crt",
            encoded("grpc-ca"));
    Secret generated =
        ownedSecret(
            fixture.plan(),
            HostedIdentityContract.GRPC_ROLE,
            fixture.plan().grpcSecretName(),
            grpcReplacement,
            Map.of(HostedIdentityContract.ISSUANCE_GENERATION_ANNOTATION, "2"));
    generated.setType("Opaque");
    when(fixture
            .grpcGenerator()
            .ensure(
                fixture.secretClient().client(),
                fixture.plan(),
                1L,
                fixture.properties().getGrpcRenewBefore(),
                fixture.properties().getGrpcTrustAnchorSha256()))
        .thenReturn(generated);

    CertificateMaterialService.RoleMaterial grpc = fixture.batch().grpc(1L);

    assertEquals("serialized-deferred", grpc.state());
    assertEquals(fixture.acceptedData(), grpc.source().getData());
    verifyNoInteractions(fixture.grpcGenerator());
  }

  @Test
  void postSnapshotRotationStillInitializesAnUninitializedRoleBehindTheSelectedChange() {
    StableBatchFixture fixture = stableBatchFixture();
    Resource<Secret> gatewayProjection =
        fixture
            .secretClient()
            .runtimeSecrets()
            .withName(fixture.plan().gatewayInternalWsSecretName());
    when(gatewayProjection.get()).thenReturn(null);

    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().ingressCertificateName(),
        true,
        1,
        fixture.acceptedData());
    assertEquals("source-ready", fixture.batch().ingress().state());

    Map<String, String> telnetReplacement =
        Map.of("tls.crt", encoded("telnet-replacement"), "tls.key", encoded("telnet-key-2"));
    Resource<Secret> telnetSourceResource = mock(Resource.class);
    when(fixture.secretClient().identitySecrets().withName(fixture.plan().telnetSecretName()))
        .thenReturn(telnetSourceResource);
    when(telnetSourceResource.get())
        .thenReturn(
            certManagerSource(
                fixture.plan(),
                HostedIdentityContract.TELNET_ROLE,
                fixture.plan().telnetSecretName(),
                telnetReplacement));
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().telnetCertificateName(),
        true,
        2,
        telnetReplacement);
    assertEquals("source-ready", fixture.batch().telnet().state());

    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().gatewayInternalWsCertificateName(),
        true,
        1,
        fixture.acceptedData());

    CertificateMaterialService.RoleMaterial gateway = fixture.batch().gatewayInternalWs();

    assertEquals("source-ready", gateway.state());
    assertEquals(fixture.acceptedData(), gateway.source().getData());
  }

  @Test
  void uniqueValidCertificateRequestAmongOwnedDuplicatesAllowsMaterialization() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    stubCertificateRequests(
        fixture.secretClient().client(),
        plan,
        List.of(
            certificateRequest(
                plan, plan.ingressCertificateName(), 1, "ingress-request-valid", sourceData, true),
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request-stale",
                Map.of("tls.crt", encoded("stale"), "tls.key", encoded("key-1")),
                true)));

    CertificateMaterialService.RoleMaterial material = fixture.batch().ingress();

    assertEquals("source-ready", material.state());
  }

  @Test
  void singleOwnedCertificateRequestWithIncompleteIssuerKeepsMaterializationPending() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    GenericKubernetesResource request =
        certificateRequest(
            plan, plan.ingressCertificateName(), 1, "ingress-request-incomplete", sourceData, true);
    Map<String, Object> requestProperties = new LinkedHashMap<>(request.getAdditionalProperties());
    requestProperties.put(
        "spec", Map.of("issuerRef", Map.of("name", plan.ingressIssuer(), "kind", "ClusterIssuer")));
    request.setAdditionalProperties(requestProperties);
    stubCertificateRequests(fixture.secretClient().client(), plan, List.of(request));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> fixture.batch().ingress());
    assertEquals("CertificateRequest issuer binding is invalid", failure.getMessage());
  }

  @Test
  void singleOwnedCertificateRequestWithWrongIssuerRemainsRejected() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    GenericKubernetesResource request =
        certificateRequest(
            plan,
            plan.ingressCertificateName(),
            1,
            "ingress-request-wrong-issuer",
            sourceData,
            true);
    Map<String, Object> requestProperties = new LinkedHashMap<>(request.getAdditionalProperties());
    requestProperties.put(
        "spec",
        Map.of(
            "issuerRef",
            Map.of(
                "name", plan.grpcIssuer(),
                "kind", "ClusterIssuer",
                "group", "cert-manager.io")));
    request.setAdditionalProperties(requestProperties);
    stubCertificateRequests(fixture.secretClient().client(), plan, List.of(request));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> fixture.batch().ingress());

    assertEquals("CertificateRequest issuer binding is invalid", failure.getMessage());
  }

  @Test
  void ownedCertificateRequestWithWrongIssuerIsRejectedEvenWhenAnotherRequestIsValid() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    GenericKubernetesResource wrong =
        certificateRequest(plan, plan.ingressCertificateName(), 1, "wrong", sourceData, true);
    Map<String, Object> properties = new LinkedHashMap<>(wrong.getAdditionalProperties());
    properties.put(
        "spec",
        Map.of(
            "issuerRef",
            Map.of(
                "name", plan.grpcIssuer(), "kind", "ClusterIssuer", "group", "cert-manager.io")));
    wrong.setAdditionalProperties(properties);
    stubCertificateRequests(
        fixture.secretClient().client(),
        plan,
        List.of(
            certificateRequest(plan, plan.ingressCertificateName(), 1, "valid", sourceData, true),
            wrong));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> fixture.batch().ingress());
    assertEquals("CertificateRequest issuer binding is invalid", failure.getMessage());
  }

  @Test
  void ownedCertificateRequestWithMalformedIssuerIsRejectedEvenWhenAnotherRequestIsValid() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    GenericKubernetesResource malformed =
        certificateRequest(plan, plan.ingressCertificateName(), 1, "malformed", sourceData, true);
    Map<String, Object> properties = new LinkedHashMap<>(malformed.getAdditionalProperties());
    properties.put("spec", Map.of("issuerRef", Map.of("name", plan.ingressIssuer())));
    malformed.setAdditionalProperties(properties);
    stubCertificateRequests(
        fixture.secretClient().client(),
        plan,
        List.of(
            certificateRequest(plan, plan.ingressCertificateName(), 1, "valid", sourceData, true),
            malformed));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> fixture.batch().ingress());
    assertEquals("CertificateRequest issuer binding is invalid", failure.getMessage());
  }

  @Test
  void zeroValidCertificateRequestsAmongOwnedDuplicatesKeepsMaterializationPending() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    stubCertificate(
        fixture.secretClient().client(),
        plan,
        plan.ingressCertificateName(),
        true,
        1,
        fixture.acceptedData());
    stubCertificateRequests(
        fixture.secretClient().client(),
        plan,
        List.of(
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request-stale-1",
                Map.of("tls.crt", encoded("stale-1"), "tls.key", encoded("key-1")),
                true),
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request-stale-2",
                Map.of("tls.crt", encoded("stale-2"), "tls.key", encoded("key-1")),
                true)));

    CertificateMaterialService.RoleMaterial material = fixture.batch().ingress();

    assertEquals("materialization-pending", material.state());
  }

  @Test
  void multipleValidCertificateRequestsKeepsMaterializationPending() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    stubCertificateRequests(
        fixture.secretClient().client(),
        plan,
        List.of(
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request-valid-1",
                sourceData,
                true),
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request-valid-2",
                sourceData,
                true)));

    CertificateMaterialService.RoleMaterial material = fixture.batch().ingress();

    assertEquals("materialization-pending", material.state());
  }

  @Test
  void unreadyCertificateRequestKeepsMaterializationPending() {
    StableBatchFixture fixture = stableBatchFixture();
    EnvironmentIdentityPlan plan = fixture.plan();
    Map<String, String> sourceData = fixture.acceptedData();
    stubCertificate(
        fixture.secretClient().client(), plan, plan.ingressCertificateName(), true, 1, sourceData);
    stubCertificateRequests(
        fixture.secretClient().client(),
        plan,
        List.of(
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request-unready",
                sourceData,
                false)));

    CertificateMaterialService.RoleMaterial material = fixture.batch().ingress();

    assertEquals("materialization-pending", material.state());
    assertEquals(false, material.ready());
  }

  @Test
  void serializedRotationPrioritizesTheFirstDriftedRole() {
    var pending =
        new CertificateMaterialService.RotationState("ingress", true, false, false, false);
    var firstDrift =
        new CertificateMaterialService.RotationState("telnet", true, false, false, true);
    var secondDrift =
        new CertificateMaterialService.RotationState("grpc", true, false, false, true);

    assertEquals(
        "telnet",
        CertificateMaterialService.selectSerializedRole(
            java.util.List.of(pending, firstDrift, secondDrift)));
  }

  @Test
  void serializedRotationPinsAnInFlightProjectionAheadOfNewerSourceMaterial() {
    assertEquals(
        true,
        CertificateMaterialService.pendingProjectionOwnsRotation(
            true, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64)));
    assertEquals(
        false,
        CertificateMaterialService.pendingProjectionOwnsRotation(
            false, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64)));
    assertEquals(
        false,
        CertificateMaterialService.pendingProjectionOwnsRotation(
            true, "sha256:" + "1".repeat(64), "sha256:" + "1".repeat(64)));
  }

  @Test
  void grpcUsesTheAcceptedProjectionWithoutReadingADeferredSource() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    KubernetesClient client = secretClient.client();
    Map<String, String> data = Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    for (String role :
        List.of(
            HostedIdentityContract.INGRESS_ROLE,
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      String name = secretName(plan, role);
      String revision = SecretProjectionService.revisionForRole(role, data);
      Map<String, String> annotations = acceptedAnnotations(revision, "1".repeat(64));
      if (HostedIdentityContract.INGRESS_ROLE.equals(role)) {
        annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
      }
      Resource<Secret> projectionResource = mock(Resource.class);
      when(secretClient.runtimeSecrets().withName(name)).thenReturn(projectionResource);
      Secret projection = ownedSecret(plan, role, name, data, annotations);
      if (HostedIdentityContract.GRPC_ROLE.equals(role)) {
        projection.setType("Opaque");
      }
      when(projectionResource.get()).thenReturn(projection);
      Resource<Secret> sourceResource = mock(Resource.class);
      when(secretClient.identitySecrets().withName(name)).thenReturn(sourceResource);
    }
    Secret ownedGrpc =
        ownedSecret(plan, HostedIdentityContract.GRPC_ROLE, plan.grpcSecretName(), data, Map.of());
    Resource<Secret> grpcSourceResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.grpcSecretName()))
        .thenReturn(grpcSourceResource);
    when(grpcSourceResource.get()).thenReturn(ownedGrpc);
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            new SecretMaterialValidator.MaterialSummary(
                "1".repeat(64),
                "1".repeat(64),
                java.time.Instant.EPOCH,
                java.time.Instant.MAX,
                "1".repeat(64)));
    CertificateMaterialService service =
        new CertificateMaterialService(
            mock(CertificateResourceFactory.class),
            validator,
            mock(GrpcTransportBundleGenerator.class),
            new HostedIdentityProperties());

    CertificateMaterialService.MaterializationBatch materialization =
        service.beginMaterialization(client, plan);
    CertificateMaterialService.RoleMaterial grpc = materialization.grpc(1L);

    assertEquals("serialized-deferred", grpc.state());
    verify(grpcSourceResource, org.mockito.Mockito.times(1)).get();
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Secret certManagerSource(
      EnvironmentIdentityPlan plan, String role, String name, Map<String, String> data) {
    String issuer =
        switch (role) {
          case HostedIdentityContract.INGRESS_ROLE -> plan.ingressIssuer();
          case HostedIdentityContract.TELNET_ROLE -> plan.telnetIssuer();
          case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
              HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE ->
              plan.grpcIssuer();
          default -> throw new IllegalArgumentException("unsupported cert-manager role: " + role);
        };
    Secret source = ownedSecret(plan, role, name, data, new LinkedHashMap<>());
    source.getMetadata().setNamespace(plan.identityNamespace());
    source
        .getMetadata()
        .setOwnerReferences(
            List.of(
                new OwnerReferenceBuilder()
                    .withApiVersion("cert-manager.io/v1")
                    .withKind("Certificate")
                    .withName(name)
                    .withUid("uid-" + name)
                    .withController(true)
                    .build()));
    source
        .getMetadata()
        .setAnnotations(
            new LinkedHashMap<>(
                Map.of(
                    HostedIdentityContract.PROVENANCE_ANNOTATION,
                    "cert-manager",
                    HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION,
                    "source-materialized",
                    "cert-manager.io/certificate-name",
                    name,
                    "cert-manager.io/issuer-name",
                    issuer,
                    "cert-manager.io/issuer-kind",
                    "ClusterIssuer",
                    "cert-manager.io/issuer-group",
                    "cert-manager.io")));
    return source;
  }

  private static IssuanceObservation materializeIngress(
      long revision, Map<String, String> requestData, Map<String, String> sourceData) {
    return materializeIngress(revision, requestData, sourceData, revision);
  }

  private static IssuanceObservation materializeIngress(
      long revision,
      Map<String, String> requestData,
      Map<String, String> sourceData,
      long finalCertificateRevision) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    SecretClient secretClient = secretClient(plan);
    Resource<Secret> absentRuntimeSecret = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(absentRuntimeSecret);
    when(absentRuntimeSecret.get()).thenReturn(null);
    Resource<GenericKubernetesResource> certificate =
        stubCertificate(
            secretClient.client(),
            plan,
            plan.ingressCertificateName(),
            true,
            revision,
            requestData);
    when(certificate.get())
        .thenReturn(
            null,
            readyCertificate(plan, plan.ingressCertificateName(), revision),
            readyCertificate(plan, plan.ingressCertificateName(), finalCertificateRevision));
    Resource<Secret> sourceResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.ingressSecretName()))
        .thenReturn(sourceResource);
    when(sourceResource.get())
        .thenReturn(
            certManagerSource(
                plan, HostedIdentityContract.INGRESS_ROLE, plan.ingressSecretName(), sourceData));
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            new SecretMaterialValidator.MaterialSummary(
                "1".repeat(64),
                "2".repeat(64),
                java.time.Instant.EPOCH,
                java.time.Instant.MAX,
                "3".repeat(64)));
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(),
            validator,
            mock(GrpcTransportBundleGenerator.class),
            properties);
    return new IssuanceObservation(
        service.beginMaterialization(secretClient.client(), plan).ingress(),
        validator,
        secretClient.runtimeSecrets());
  }

  @SuppressWarnings("unchecked")
  private static Resource<GenericKubernetesResource> stubCertificate(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      boolean readyAfterCreate) {
    return stubCertificate(
        client,
        plan,
        certificateName,
        readyAfterCreate,
        1,
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key")));
  }

  @SuppressWarnings("unchecked")
  private static Resource<GenericKubernetesResource> stubCertificate(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      boolean readyAfterCreate,
      long revision,
      Map<String, String> sourceData) {
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        certificates = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityCertificates = mock(NonNamespaceOperation.class);
    Resource<GenericKubernetesResource> certificate = mock(Resource.class);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATES)).thenReturn(certificates);
    when(certificates.inNamespace(plan.identityNamespace())).thenReturn(identityCertificates);
    when(identityCertificates.withName(certificateName)).thenReturn(certificate);
    when(identityCertificates.resource(
            org.mockito.ArgumentMatchers.any(GenericKubernetesResource.class)))
        .thenReturn(mock(Resource.class));
    if (readyAfterCreate) {
      GenericKubernetesResource ready = readyCertificate(plan, certificateName, revision);
      when(certificate.get()).thenReturn(null, ready);
      stubCertificateRequest(client, plan, certificateName, revision, sourceData);
    } else {
      when(certificate.get()).thenReturn(null);
    }
    return certificate;
  }

  private static GenericKubernetesResource readyCertificate(
      EnvironmentIdentityPlan plan, String certificateName, long revision) {
    GenericKubernetesResource ready = new GenericKubernetesResource();
    ready.setApiVersion("cert-manager.io/v1");
    ready.setKind("Certificate");
    ready.setMetadata(
        new ObjectMetaBuilder()
            .withName(certificateName)
            .withNamespace(plan.identityNamespace())
            .withUid("uid-" + certificateName)
            .withGeneration(revision)
            .build());
    ready.setAdditionalProperties(
        Map.of(
            "spec",
            Map.of(
                "issuerRef",
                Map.of(
                    "name",
                    issuerFor(plan, certificateName),
                    "kind",
                    "ClusterIssuer",
                    "group",
                    "cert-manager.io")),
            "status",
            Map.of(
                "revision",
                revision,
                "conditions",
                List.of(
                    Map.of(
                        "type", "Ready",
                        "status", "True",
                        "observedGeneration", revision)))));
    return ready;
  }

  @SuppressWarnings("unchecked")
  private static void stubCertificateRequest(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String certificateName,
      long revision,
      Map<String, String> sourceData) {
    GenericKubernetesResource request =
        certificateRequest(
            plan,
            certificateName,
            revision,
            certificateName + "-request-" + revision,
            sourceData,
            true);
    GenericKubernetesResourceList requestList = new GenericKubernetesResourceList();
    requestList.setItems(List.of(request));
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        requests = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityRequests = mock(NonNamespaceOperation.class);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATE_REQUESTS))
        .thenReturn(requests);
    when(requests.inNamespace(plan.identityNamespace())).thenReturn(identityRequests);
    when(identityRequests.list()).thenReturn(requestList);
  }

  @SuppressWarnings("unchecked")
  private static void stubCertificateRequests(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      List<GenericKubernetesResource> requestsToReturn) {
    MixedOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        requests = mock(MixedOperation.class);
    NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityRequests = mock(NonNamespaceOperation.class);
    GenericKubernetesResourceList requestList = new GenericKubernetesResourceList();
    requestList.setItems(requestsToReturn);
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATE_REQUESTS))
        .thenReturn(requests);
    when(requests.inNamespace(plan.identityNamespace())).thenReturn(identityRequests);
    when(identityRequests.list()).thenReturn(requestList);
  }

  private static GenericKubernetesResource certificateRequest(
      EnvironmentIdentityPlan plan,
      String certificateName,
      long revision,
      String requestName,
      Map<String, String> sourceData,
      boolean ready) {
    GenericKubernetesResource request = new GenericKubernetesResource();
    request.setApiVersion("cert-manager.io/v1");
    request.setKind("CertificateRequest");
    request.setMetadata(
        new ObjectMetaBuilder()
            .withName(requestName)
            .withNamespace(plan.identityNamespace())
            .withAnnotations(
                Map.of(
                    "cert-manager.io/certificate-name",
                    certificateName,
                    "cert-manager.io/certificate-revision",
                    Long.toString(revision)))
            .withOwnerReferences(
                new OwnerReferenceBuilder()
                    .withApiVersion("cert-manager.io/v1")
                    .withKind("Certificate")
                    .withName(certificateName)
                    .withUid("uid-" + certificateName)
                    .withController(true)
                    .build())
            .build());
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("certificate", sourceData.get("tls.crt"));
    if (sourceData.containsKey("ca.crt")) {
      status.put("ca", sourceData.get("ca.crt"));
    }
    status.put(
        "conditions", ready ? List.of(Map.of("type", "Ready", "status", "True")) : List.of());
    request.setAdditionalProperties(
        Map.of(
            "spec",
            Map.of(
                "issuerRef",
                Map.of(
                    "name", issuerFor(plan, certificateName),
                    "kind", "ClusterIssuer",
                    "group", "cert-manager.io")),
            "status",
            status));
    return request;
  }

  private static String issuerFor(EnvironmentIdentityPlan plan, String certificateName) {
    return certificateName.equals(plan.ingressCertificateName())
        ? plan.ingressIssuer()
        : certificateName.equals(plan.telnetCertificateName())
            ? plan.telnetIssuer()
            : plan.grpcIssuer();
  }

  @SuppressWarnings("unchecked")
  private static SecretClient secretClient(EnvironmentIdentityPlan plan) {
    KubernetesClient client = mock(KubernetesClient.class);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets =
        mock(NonNamespaceOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    when(client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeSecrets);
    when(secrets.inNamespace(plan.identityNamespace())).thenReturn(identitySecrets);
    Resource<Secret> runtimeReplacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> runtimeLockedResource = mock(ReplaceDeletable.class);
    when(runtimeReplacementResource.lockResourceVersion(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(runtimeLockedResource);
    Resource<Secret> identityReplacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> identityLockedResource = mock(ReplaceDeletable.class);
    when(identityReplacementResource.lockResourceVersion(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(identityLockedResource);
    when(runtimeSecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(runtimeReplacementResource);
    when(identitySecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(identityReplacementResource);
    return new SecretClient(client, runtimeSecrets, identitySecrets);
  }

  private static StableBatchFixture stableBatchFixture() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    SecretClient secretClient = secretClient(plan);
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    for (String role :
        List.of(
            HostedIdentityContract.INGRESS_ROLE,
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      stubProjectionAndSource(secretClient, plan, role, acceptedData, acceptedData, acceptedData);
      secretClient
          .runtimeSecrets()
          .withName(secretName(plan, role))
          .get()
          .getMetadata()
          .getAnnotations()
          .put(
              HostedIdentityContract.PROVENANCE_ANNOTATION,
              HostedIdentityContract.GRPC_ROLE.equals(role)
                  ? HostedIdentityContract.TRANSPORT_PROVENANCE
                  : "cert-manager");
    }
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    SecretMaterialValidator.MaterialSummary summary =
        new SecretMaterialValidator.MaterialSummary(
            "1".repeat(64),
            "2".repeat(64),
            java.time.Instant.EPOCH,
            java.time.Instant.MAX,
            "3".repeat(64));
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(summary);
    GrpcTransportBundleGenerator grpcGenerator = mock(GrpcTransportBundleGenerator.class);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(), validator, grpcGenerator, properties);
    return new StableBatchFixture(
        plan,
        properties,
        secretClient,
        acceptedData,
        validator,
        grpcGenerator,
        service.beginMaterialization(secretClient.client(), plan));
  }

  private static Secret stubProjectionAndSource(
      SecretClient secretClient,
      EnvironmentIdentityPlan plan,
      String role,
      Map<String, String> projectionData,
      Map<String, String> recordedData,
      Map<String, String> sourceData) {
    String name = secretName(plan, role);
    String revision = SecretProjectionService.revisionForRole(role, recordedData);
    Secret projection =
        ownedSecret(
            plan, role, name, projectionData, acceptedAnnotations(revision, "2".repeat(64)));
    if (HostedIdentityContract.GRPC_ROLE.equals(role)) {
      projection.setType("Opaque");
    }
    projection.getMetadata().setResourceVersion("7");
    Resource<Secret> projectionResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(name)).thenReturn(projectionResource);
    when(projectionResource.get()).thenReturn(projection);
    Secret source = ownedSecret(plan, role, name, sourceData, Map.of());
    if (!HostedIdentityContract.GRPC_ROLE.equals(role)) {
      source = certManagerSource(plan, role, name, sourceData);
    }
    Resource<Secret> sourceResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(name)).thenReturn(sourceResource);
    when(sourceResource.get()).thenReturn(source);
    Resource<Secret> predecessorResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(name + "-previous"))
        .thenReturn(predecessorResource);
    when(predecessorResource.get()).thenReturn(null);
    return source;
  }

  private record SecretClient(
      KubernetesClient client,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets) {}

  private record StableBatchFixture(
      EnvironmentIdentityPlan plan,
      HostedIdentityProperties properties,
      SecretClient secretClient,
      Map<String, String> acceptedData,
      SecretMaterialValidator validator,
      GrpcTransportBundleGenerator grpcGenerator,
      CertificateMaterialService.MaterializationBatch batch) {}

  private record IssuanceObservation(
      CertificateMaterialService.RoleMaterial material,
      SecretMaterialValidator validator,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets) {}

  private enum CertManagerSourceMutation {
    MANAGED_BY,
    ENVIRONMENT,
    ROLE,
    RETENTION,
    PROVENANCE,
    CONVERGENCE_STATE,
    CERTIFICATE_NAME,
    ISSUER_NAME,
    ISSUER_KIND,
    ISSUER_GROUP,
    OWNER_API_VERSION,
    OWNER_KIND,
    OWNER_NAME,
    OWNER_UID;

    void apply(Secret source) {
      switch (this) {
        case MANAGED_BY ->
            source
                .getMetadata()
                .getLabels()
                .put(HostedIdentityContract.MANAGED_BY_LABEL, "other-controller");
        case ENVIRONMENT ->
            source.getMetadata().getLabels().put(HostedIdentityContract.ENVIRONMENT_LABEL, "pr-99");
        case ROLE ->
            source
                .getMetadata()
                .getLabels()
                .put(HostedIdentityContract.ROLE_LABEL, HostedIdentityContract.TELNET_ROLE);
        case RETENTION ->
            source
                .getMetadata()
                .getLabels()
                .put(HostedIdentityContract.RETENTION_LABEL, "disposable");
        case PROVENANCE ->
            source
                .getMetadata()
                .getAnnotations()
                .put(HostedIdentityContract.PROVENANCE_ANNOTATION, "manual");
        case CONVERGENCE_STATE ->
            source
                .getMetadata()
                .getAnnotations()
                .put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
        case CERTIFICATE_NAME ->
            source
                .getMetadata()
                .getAnnotations()
                .put("cert-manager.io/certificate-name", "other-certificate");
        case ISSUER_NAME ->
            source
                .getMetadata()
                .getAnnotations()
                .put("cert-manager.io/issuer-name", "other-issuer");
        case ISSUER_KIND ->
            source.getMetadata().getAnnotations().put("cert-manager.io/issuer-kind", "Issuer");
        case ISSUER_GROUP ->
            source
                .getMetadata()
                .getAnnotations()
                .put("cert-manager.io/issuer-group", "other.example");
        case OWNER_API_VERSION ->
            source
                .getMetadata()
                .getOwnerReferences()
                .get(0)
                .setApiVersion("cert-manager.io/v1beta1");
        case OWNER_KIND ->
            source.getMetadata().getOwnerReferences().get(0).setKind("CertificateRequest");
        case OWNER_NAME ->
            source.getMetadata().getOwnerReferences().get(0).setName("other-certificate");
        case OWNER_UID -> source.getMetadata().getOwnerReferences().get(0).setUid("stale-uid");
      }
    }
  }

  private static EnvironmentIdentityPlan plan() {
    return new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
  }

  private static Map<String, String> acceptedAnnotations(String revision, String spki) {
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put(HostedIdentityContract.REVISION_ANNOTATION, revision);
    annotations.put(HostedIdentityContract.SOURCE_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.SPKI_SHA256_ANNOTATION, spki);
    annotations.put(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION, revision);
    annotations.put(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION, spki);
    annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "accepted");
    return annotations;
  }

  private static Secret ownedSecret(
      EnvironmentIdentityPlan plan,
      String role,
      String name,
      Map<String, String> data,
      Map<String, String> annotations) {
    return new SecretBuilder()
        .withNewMetadata()
        .withName(name)
        .withNamespace(plan.runtimeNamespace())
        .withLabels(HostedIdentityContract.managedLabels(plan.name(), role))
        .withAnnotations(annotations)
        .endMetadata()
        .withType("kubernetes.io/tls")
        .withData(data)
        .build();
  }

  private static String secretName(EnvironmentIdentityPlan plan, String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> plan.ingressSecretName();
      case HostedIdentityContract.TELNET_ROLE -> plan.telnetSecretName();
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> plan.gatewayInternalWsSecretName();
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeSecretName();
      case HostedIdentityContract.GRPC_ROLE -> plan.grpcSecretName();
      default -> throw new IllegalArgumentException("unsupported role");
    };
  }
}
