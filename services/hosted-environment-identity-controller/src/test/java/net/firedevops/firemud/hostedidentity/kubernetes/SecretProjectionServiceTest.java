package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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

class SecretProjectionServiceTest {
  private static final Supplier<Boolean> ALWAYS_CURRENT = () -> true;

  @Test
  void revisionIsStableAndIndependentOfMapOrder() {
    SecretProjectionService service = new SecretProjectionService();
    String first =
        service.revisionFor(
            Map.of(
                "tls.key", encoded("key"),
                "tls.crt", encoded("certificate")));
    String second =
        service.revisionFor(
            Map.of(
                "tls.crt", encoded("certificate"),
                "tls.key", encoded("key")));

    assertEquals(first, second);
    assertEquals(71, first.length());
    assertTrue(first.matches("sha256:[0-9a-f]{64}"));
    assertNotEquals(first, service.revisionFor(Map.of("tls.key", encoded("other"))));
  }

  @Test
  void projectRejectsMissingProvenanceBeforeWriting() {
    EnvironmentIdentityPlan plan = plan();
    Secret source =
        new SecretBuilder()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key")))
            .build();
    for (String provenance : new String[] {null, "", "   "}) {
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new SecretProjectionService()
                      .project(
                          mock(KubernetesClient.class),
                          plan,
                          HostedIdentityContract.INGRESS_ROLE,
                          source,
                          1L,
                          1L,
                          "a".repeat(64),
                          provenance,
                          () -> true));
      assertEquals("projection provenance is required", exception.getMessage());
    }
  }

  @Test
  void revisionRejectsMissingAndMalformedMaterialWithoutMaskingTheCause() {
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class, () -> SecretProjectionService.revisionForData(null));
    assertEquals("material data is required", missing.getMessage());
    IllegalArgumentException malformed =
        assertThrows(
            IllegalArgumentException.class,
            () -> SecretProjectionService.revisionForData(Map.of("tls.crt", "not-base64")));
    assertEquals("Illegal base64 character 2d", malformed.getMessage());
  }

  @Test
  void grpcProjectionCapturesTransportProvenance() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    Resource<Secret> absent = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.grpcSecretName())).thenReturn(absent);
    when(absent.get()).thenReturn(null);
    Secret source =
        new SecretBuilder()
            .withType("Opaque")
            .withData(
                Map.of(
                    "tls.crt",
                    encoded("certificate"),
                    "tls.key",
                    encoded("key"),
                    "ca.crt",
                    encoded("ca")))
            .build();

    new SecretProjectionService()
        .project(
            secretClient.client(),
            plan,
            HostedIdentityContract.GRPC_ROLE,
            source,
            1,
            1,
            "1".repeat(64),
            HostedIdentityContract.TRANSPORT_PROVENANCE,
            ALWAYS_CURRENT);

    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(secretClient.runtimeSecrets()).resource(candidate.capture());
    assertEquals(
        HostedIdentityContract.TRANSPORT_PROVENANCE,
        candidate
            .getValue()
            .getMetadata()
            .getAnnotations()
            .get(HostedIdentityContract.PROVENANCE_ANNOTATION));
  }

  @Test
  void runtimeProfileFenceStopsProjectionBeforeTargetReplacement() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    SecretProjectionService service = new SecretProjectionService();
    Map<String, String> desiredData =
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, desiredData);
    Secret existing =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key")),
            acceptedAnnotations(revision, "1".repeat(64)));
    existing.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    Secret source = new SecretBuilder().withType("kubernetes.io/tls").withData(desiredData).build();
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    SecretProjectionService.ProjectionResult result =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            1,
            1,
            "1".repeat(64),
            "cert-manager",
            () -> guardCalls.getAndIncrement() == 0);

    assertEquals("runtime-profile-changed", result.state());
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
    verify(existingResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void runtimeProfileFenceStopsTargetCreationAfterTargetRead() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    SecretProjectionService service = new SecretProjectionService();
    Resource<Secret> targetResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(targetResource);
    when(targetResource.get()).thenReturn(null);
    Secret source =
        new SecretBuilder()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key")))
            .build();
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    SecretProjectionService.ProjectionResult result =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            1,
            1,
            "1".repeat(64),
            "cert-manager",
            () -> guardCalls.getAndIncrement() == 0);

    assertEquals("runtime-profile-changed", result.state());
    verify(targetResource).get();
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void runtimeProfileFenceStopsPredecessorReadBeforePreservation() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    SecretProjectionService service = new SecretProjectionService();
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    Map<String, String> replacementData =
        Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2"));
    String acceptedRevision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, acceptedData);
    Secret existing =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            acceptedData,
            acceptedAnnotations(acceptedRevision, "1".repeat(64)));
    existing.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    Resource<Secret> predecessorResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.ingressSecretName() + "-previous"))
        .thenReturn(predecessorResource);
    when(predecessorResource.get()).thenReturn(null);
    Secret source =
        new SecretBuilder().withType("kubernetes.io/tls").withData(replacementData).build();
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    SecretProjectionService.ProjectionResult result =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            2,
            2,
            "2".repeat(64),
            "cert-manager",
            () -> guardCalls.getAndIncrement() < 2);

    assertEquals("runtime-profile-changed", result.state());
    verify(predecessorResource, never()).get();
    verify(secretClient.identitySecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void runtimeProfileFenceStopsPredecessorReplacementAfterRead() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    SecretProjectionService service = new SecretProjectionService();
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    Map<String, String> replacementData =
        Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2"));
    String acceptedRevision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, acceptedData);
    Secret existing =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            acceptedData,
            acceptedAnnotations(acceptedRevision, "1".repeat(64)));
    existing.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    Resource<Secret> predecessorResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.ingressSecretName() + "-previous"))
        .thenReturn(predecessorResource);
    Secret prior =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName() + "-previous",
            acceptedData,
            acceptedAnnotations(acceptedRevision, "1".repeat(64)));
    prior.getMetadata().setNamespace(plan.identityNamespace());
    prior.getMetadata().setResourceVersion("8");
    when(predecessorResource.get()).thenReturn(prior);
    Secret source =
        new SecretBuilder().withType("kubernetes.io/tls").withData(replacementData).build();
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    SecretProjectionService.ProjectionResult result =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            2,
            2,
            "2".repeat(64),
            "cert-manager",
            () -> guardCalls.getAndIncrement() < 3);

    assertEquals("runtime-profile-changed", result.state());
    verify(predecessorResource).get();
    verify(secretClient.identitySecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void runtimeProfileFenceStopsAcknowledgementBeforeReplacement() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    SecretProjectionService service = new SecretProjectionService();
    Map<String, String> data = Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, data);
    Map<String, String> pendingAnnotations = acceptedAnnotations(revision, "1".repeat(64));
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION);
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION);
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);
    pendingAnnotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
    Secret current =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            data,
            pendingAnnotations);
    current.getMetadata().setResourceVersion("7");
    Resource<Secret> currentResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(currentResource);
    when(currentResource.get()).thenReturn(current);
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    SecretProjectionService.ProjectionResult result =
        service.acknowledge(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            revision,
            1,
            1,
            "1".repeat(64),
            () -> guardCalls.getAndIncrement() < 2);

    assertEquals("runtime-profile-changed", result.state());
    verify(currentResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));
  }

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
    when(client.genericKubernetesResources(ResourceContexts.CERTIFICATES)).thenReturn(certificates);
    when(certificates.inNamespace(plan.identityNamespace())).thenReturn(identityCertificates);
    when(identityCertificates.withName(desired.getMetadata().getName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    when(identityCertificates.resource(desired)).thenReturn(replacementResource);

    CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired);

    assertEquals("7", desired.getMetadata().getResourceVersion());
    verify(replacementResource).replace();

    existingSpec.put("isCA", true);
    assertThrows(
        IllegalStateException.class,
        () ->
            CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired));
    existingSpec.remove("isCA");
    existing.getMetadata().getLabels().put("unexpected", "metadata-drift");
    assertThrows(
        IllegalStateException.class,
        () ->
            CertificateMaterialService.applyCertificate(client, plan.identityNamespace(), desired));
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
  void pendingIngressDoesNotReadAnyRotationProjectionOrSource() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    stubCertificate(client, plan, plan.ingressCertificateName(), false);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(),
            mock(SecretMaterialValidator.class),
            mock(GrpcTransportBundleGenerator.class),
            properties);

    CertificateMaterialService.RoleMaterial ingress =
        service.beginMaterialization(client, plan).ingress();

    assertEquals(false, ingress.ready());
    assertEquals("certificate-pending", ingress.state());
    verify(client, never()).secrets();
  }

  @Test
  void acceptedRevisionRequiresTheCanonicalAlgorithmPrefixAndFullDigest() {
    SecretProjectionService service = new SecretProjectionService();
    assertThrows(
        IllegalStateException.class,
        () ->
            service.acknowledge(
                null, null, "ingress", "1".repeat(64), 1, 1, "2".repeat(64), ALWAYS_CURRENT));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.acknowledge(
                null,
                null,
                "ingress",
                "sha256:" + "1".repeat(63),
                1,
                1,
                "2".repeat(64),
                ALWAYS_CURRENT));
  }

  @Test
  void replacementRequiresMonotonicGenerationAndFreshKey() {
    String first = "1".repeat(64);
    String second = "2".repeat(64);
    SecretProjectionService.validateAdvancement(2, 5, second, 1, 5, first);
    assertThrows(
        IllegalStateException.class,
        () -> SecretProjectionService.validateAdvancement(1, 5, second, 1, 5, first));
    assertThrows(
        IllegalStateException.class,
        () -> SecretProjectionService.validateAdvancement(2, 5, first, 1, 5, first));
    assertThrows(
        IllegalStateException.class,
        () -> SecretProjectionService.validateAdvancement(2, 4, second, 1, 5, first));
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
  void secretOwnershipRequiresTheRetainedBoundary() {
    var secret =
        new SecretBuilder()
            .withNewMetadata()
            .withLabels(
                Map.of(
                    HostedIdentityContract.MANAGED_BY_LABEL,
                    HostedIdentityContract.CONTROLLER_NAME,
                    HostedIdentityContract.ENVIRONMENT_LABEL,
                    "pr-42",
                    HostedIdentityContract.ROLE_LABEL,
                    HostedIdentityContract.INGRESS_ROLE))
            .endMetadata()
            .build();
    assertEquals(
        false, SecretProjectionService.owned(secret, "pr-42", HostedIdentityContract.INGRESS_ROLE));
    secret.getMetadata().setLabels(new java.util.LinkedHashMap<>(secret.getMetadata().getLabels()));
    secret
        .getMetadata()
        .getLabels()
        .put(HostedIdentityContract.RETENTION_LABEL, HostedIdentityContract.RETAINED);
    assertEquals(
        true, SecretProjectionService.owned(secret, "pr-42", HostedIdentityContract.INGRESS_ROLE));
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
  void unacceptedProjectionPinsItsMaterialUntilAcceptanceThenAdvancesOnRestart() {
    StableBatchFixture fixture = stableBatchFixture();
    Secret ingressProjection =
        fixture.secretClient().runtimeSecrets().withName(fixture.plan().ingressSecretName()).get();
    Map<String, String> pendingAnnotations =
        new LinkedHashMap<>(ingressProjection.getMetadata().getAnnotations());
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION);
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION);
    pendingAnnotations.remove(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);
    pendingAnnotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
    ingressProjection.getMetadata().setAnnotations(pendingAnnotations);

    Map<String, String> replacement =
        Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2"));
    when(fixture
            .secretClient()
            .identitySecrets()
            .withName(fixture.plan().ingressSecretName())
            .get())
        .thenReturn(
            certManagerSource(
                fixture.plan(),
                HostedIdentityContract.INGRESS_ROLE,
                fixture.plan().ingressSecretName(),
                replacement));
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().ingressCertificateName(),
        true,
        2,
        replacement);

    CertificateMaterialService.RoleMaterial pinned = fixture.batch().ingress();

    assertEquals("serialized-in-flight", pinned.state());
    assertEquals(fixture.acceptedData(), pinned.source().getData());
    SecretProjectionService projections = new SecretProjectionService();
    SecretProjectionService.ProjectionResult pending =
        projections.project(
            fixture.secretClient().client(),
            fixture.plan(),
            pinned.role(),
            pinned.source(),
            pinned.sourceGeneration(),
            pinned.sourceObjectGeneration(),
            pinned.summary().spkiSha256(),
            pinned.provenance(),
            ALWAYS_CURRENT);
    assertEquals("awaiting-acceptance", pending.state());
    assertEquals(
        true,
        projections
            .acknowledge(
                fixture.secretClient().client(),
                fixture.plan(),
                pinned.role(),
                pending.revision(),
                pinned.sourceGeneration(),
                pinned.sourceObjectGeneration(),
                pinned.summary().spkiSha256(),
                ALWAYS_CURRENT)
            .isSynced());

    CertificateMaterialService restarted =
        new CertificateMaterialService(
            new CertificateResourceFactory(),
            fixture.validator(),
            fixture.grpcGenerator(),
            fixture.properties());
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().ingressCertificateName(),
        true,
        2,
        replacement);

    CertificateMaterialService.RoleMaterial advanced =
        restarted.beginMaterialization(fixture.secretClient().client(), fixture.plan()).ingress();

    assertEquals("source-ready", advanced.state());
    assertEquals(replacement, advanced.source().getData());
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
  void replacementProjectionRetainsAcceptedSnapshotAndKeepsRotationSerialized() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    KubernetesClient client = secretClient.client();
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    String acceptedRevision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, acceptedData);
    String acceptedSpki = "1".repeat(64);
    Map<String, String> acceptedAnnotations = acceptedAnnotations(acceptedRevision, acceptedSpki);
    Secret existing =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            acceptedData,
            acceptedAnnotations);
    existing.getMetadata().setResourceVersion("7");
    Map<String, String> replacementData =
        Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2"));
    Secret replacement =
        new SecretBuilder().withType("kubernetes.io/tls").withData(replacementData).build();
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(existing);
    Resource<Secret> predecessorResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.ingressSecretName() + "-previous"))
        .thenReturn(predecessorResource);

    var result =
        service.project(
            client,
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            replacement,
            2,
            2,
            "2".repeat(64),
            "cert-manager",
            ALWAYS_CURRENT);

    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(secretClient.runtimeSecrets()).resource(candidate.capture());
    Map<String, String> annotations = candidate.getValue().getMetadata().getAnnotations();
    assertEquals(
        acceptedRevision, annotations.get(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION));
    assertEquals(
        "1", annotations.get(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION));
    assertEquals(
        "1", annotations.get(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION));
    assertEquals(
        acceptedSpki, annotations.get(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION));
    assertEquals("pending", annotations.get(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION));
    assertEquals("projected", result.state());

    Secret capturedReplacement = candidate.getValue();
    when(existingResource.get()).thenReturn(capturedReplacement);
    Map<String, String> newerIngressData =
        Map.of("tls.crt", encoded("newer-ingress"), "tls.key", encoded("key-3"));
    Resource<Secret> ingressSource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.ingressSecretName()))
        .thenReturn(ingressSource);
    when(ingressSource.get())
        .thenReturn(
            certManagerSource(
                plan,
                HostedIdentityContract.INGRESS_ROLE,
                plan.ingressSecretName(),
                newerIngressData));
    for (String role :
        List.of(
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      String name = secretName(plan, role);
      String revision = SecretProjectionService.revisionForRole(role, acceptedData);
      Resource<Secret> projection = mock(Resource.class);
      when(secretClient.runtimeSecrets().withName(name)).thenReturn(projection);
      when(projection.get())
          .thenReturn(
              ownedSecret(
                  plan, role, name, acceptedData, acceptedAnnotations(revision, acceptedSpki)));
      Map<String, String> sourceData =
          HostedIdentityContract.TELNET_ROLE.equals(role) ? replacementData : acceptedData;
      Resource<Secret> source = mock(Resource.class);
      when(secretClient.identitySecrets().withName(name)).thenReturn(source);
      when(source.get())
          .thenReturn(
              HostedIdentityContract.GRPC_ROLE.equals(role)
                  ? ownedSecret(plan, role, name, sourceData, Map.of())
                  : certManagerSource(plan, role, name, sourceData));
    }
    stubCertificate(client, plan, plan.ingressCertificateName(), true, 1, newerIngressData);
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    SecretMaterialValidator.MaterialSummary summary =
        new SecretMaterialValidator.MaterialSummary(
            "3".repeat(64),
            "4".repeat(64),
            java.time.Instant.EPOCH,
            java.time.Instant.MAX,
            "5".repeat(64));
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(summary);
    CertificateMaterialService materialService =
        new CertificateMaterialService(
            new CertificateResourceFactory(),
            validator,
            mock(GrpcTransportBundleGenerator.class),
            new HostedIdentityProperties());

    CertificateMaterialService.RoleMaterial continued =
        materialService.beginMaterialization(client, plan).ingress();

    assertEquals(HostedIdentityContract.INGRESS_ROLE, continued.role());
    assertEquals("serialized-in-flight", continued.state());
    assertEquals(capturedReplacement, continued.source());
    verify(secretClient.runtimeSecrets(), org.mockito.Mockito.times(1))
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void equalRevisionDataDriftIsRepairedAndMustBeAcceptedAgain() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> desiredData =
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, desiredData);
    String spki = "1".repeat(64);
    Secret drifted =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key")),
            acceptedAnnotations(revision, spki));
    drifted.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(drifted);
    Secret source = new SecretBuilder().withType("kubernetes.io/tls").withData(desiredData).build();

    var repair =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            1,
            1,
            spki,
            "cert-manager",
            ALWAYS_CURRENT);

    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(secretClient.runtimeSecrets()).resource(candidate.capture());
    Secret repaired = candidate.getValue();
    assertEquals("projected", repair.state());
    assertEquals(desiredData, repaired.getData());
    assertEquals("7", repaired.getMetadata().getResourceVersion());
    assertEquals(
        revision,
        repaired
            .getMetadata()
            .getAnnotations()
            .get(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION));
    assertEquals(
        "pending",
        repaired
            .getMetadata()
            .getAnnotations()
            .get(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION));

    when(existingResource.get()).thenReturn(repaired);
    var pendingAcceptance =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            1,
            1,
            spki,
            "cert-manager",
            ALWAYS_CURRENT);
    assertEquals("awaiting-acceptance", pendingAcceptance.state());
    verify(secretClient.runtimeSecrets(), org.mockito.Mockito.times(1))
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));

    repaired.setData(Map.of("tls.crt", encoded("tampered-again"), "tls.key", encoded("key")));
    var rejectedAcceptance =
        service.acknowledge(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            revision,
            1,
            1,
            spki,
            ALWAYS_CURRENT);
    assertEquals("projection-material-changed", rejectedAcceptance.state());
    verify(existingResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));

    repaired.setData(desiredData);
    var accepted =
        service.acknowledge(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            revision,
            1,
            1,
            spki,
            ALWAYS_CURRENT);
    assertEquals(true, accepted.isSynced());
    assertEquals(
        revision,
        repaired
            .getMetadata()
            .getAnnotations()
            .get(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION));
    assertEquals(
        "accepted",
        repaired
            .getMetadata()
            .getAnnotations()
            .get(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION));
    verify(existingResource).replace(repaired);
  }

  @Test
  void equalRevisionSecretTypeDriftIsRepairedAndRemainsPending() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> desiredData =
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, desiredData);
    String spki = "1".repeat(64);
    Secret drifted =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            desiredData,
            acceptedAnnotations(revision, spki));
    drifted.setType("Opaque");
    drifted.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(drifted);
    Secret source = new SecretBuilder().withType("kubernetes.io/tls").withData(desiredData).build();

    var repair =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            1,
            1,
            spki,
            "cert-manager",
            ALWAYS_CURRENT);

    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(secretClient.runtimeSecrets()).resource(candidate.capture());
    Secret repaired = candidate.getValue();
    assertEquals("projected", repair.state());
    assertEquals(desiredData, repaired.getData());
    assertEquals(source.getType(), repaired.getType());
    assertEquals(
        "pending",
        repaired
            .getMetadata()
            .getAnnotations()
            .get(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION));
  }

  @Test
  void sameRevisionDriftRejectsAnIncompleteAcceptedSnapshotBeforeWriting() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> desiredData =
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, desiredData);
    String spki = "1".repeat(64);
    Map<String, String> incomplete = acceptedAnnotations(revision, spki);
    incomplete.remove(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);
    Secret drifted =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key")),
            incomplete);
    drifted.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(drifted);
    Secret source = new SecretBuilder().withType("kubernetes.io/tls").withData(desiredData).build();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.project(
                    secretClient.client(),
                    plan,
                    HostedIdentityContract.INGRESS_ROLE,
                    source,
                    1,
                    1,
                    spki,
                    "cert-manager",
                    ALWAYS_CURRENT));

    assertEquals("accepted projection snapshot is incomplete", exception.getMessage());
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void sameRevisionDriftWithoutAnAcceptedSnapshotIsRepairedPendingAcceptance() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> desiredData =
        Map.of("tls.crt", encoded("certificate"), "tls.key", encoded("key"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, desiredData);
    String spki = "1".repeat(64);
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put(HostedIdentityContract.REVISION_ANNOTATION, revision);
    annotations.put(HostedIdentityContract.SOURCE_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION, "1");
    annotations.put(HostedIdentityContract.SPKI_SHA256_ANNOTATION, spki);
    annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
    Secret drifted =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key")),
            annotations);
    drifted.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(drifted);
    Secret source = new SecretBuilder().withType("kubernetes.io/tls").withData(desiredData).build();

    var repair =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            1,
            1,
            spki,
            "cert-manager",
            ALWAYS_CURRENT);

    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(secretClient.runtimeSecrets()).resource(candidate.capture());
    Map<String, String> repairedAnnotations = candidate.getValue().getMetadata().getAnnotations();
    assertEquals("projected", repair.state());
    assertEquals(desiredData, candidate.getValue().getData());
    assertEquals(
        "pending", repairedAnnotations.get(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION));
    assertEquals(
        false,
        repairedAnnotations.containsKey(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION));
  }

  @Test
  void inFlightRotationDriftRepairPreservesTheOlderAcceptedTuple() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    Map<String, String> desiredData =
        Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2"));
    String acceptedRevision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, acceptedData);
    String desiredRevision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, desiredData);
    Map<String, String> annotations = acceptedAnnotations(acceptedRevision, "1".repeat(64));
    annotations.put(HostedIdentityContract.REVISION_ANNOTATION, desiredRevision);
    annotations.put(HostedIdentityContract.SOURCE_GENERATION_ANNOTATION, "2");
    annotations.put(HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION, "2");
    annotations.put(HostedIdentityContract.SPKI_SHA256_ANNOTATION, "2".repeat(64));
    annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
    Secret drifted =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key-2")),
            annotations);
    drifted.getMetadata().setResourceVersion("8");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(drifted);
    Secret source = new SecretBuilder().withType("kubernetes.io/tls").withData(desiredData).build();

    var repair =
        service.project(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            source,
            2,
            2,
            "2".repeat(64),
            "cert-manager",
            ALWAYS_CURRENT);

    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(secretClient.runtimeSecrets()).resource(candidate.capture());
    Map<String, String> repairedAnnotations = candidate.getValue().getMetadata().getAnnotations();
    assertEquals("projected", repair.state());
    assertEquals(desiredData, candidate.getValue().getData());
    assertEquals(
        acceptedRevision,
        repairedAnnotations.get(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION));
    assertEquals(
        "1", repairedAnnotations.get(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION));
    assertEquals(
        "1",
        repairedAnnotations.get(
            HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION));
    assertEquals(
        "1".repeat(64),
        repairedAnnotations.get(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION));
    assertEquals(
        "pending", repairedAnnotations.get(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION));
  }

  @Test
  void sourceAdvancementCannotSnapshotDriftedPredecessorMaterial() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    String acceptedRevision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, acceptedData);
    Secret drifted =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName(),
            Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key-1")),
            acceptedAnnotations(acceptedRevision, "1".repeat(64)));
    drifted.getMetadata().setResourceVersion("7");
    Resource<Secret> existingResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(existingResource);
    when(existingResource.get()).thenReturn(drifted);
    Secret source =
        new SecretBuilder()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2")))
            .build();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.project(
                    secretClient.client(),
                    plan,
                    HostedIdentityContract.INGRESS_ROLE,
                    source,
                    2,
                    2,
                    "2".repeat(64),
                    "cert-manager",
                    ALWAYS_CURRENT));

    assertEquals("runtime projection revision does not match its material", exception.getMessage());
    verify(secretClient.runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
    verify(secretClient.identitySecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void materializationSelectsSameRevisionIdentitySourceToRepairProjectionDrift() {
    DriftSelectionFixture fixture = driftSelectionFixture(false);

    CertificateMaterialService.RoleMaterial material = fixture.materialization().ingress();

    assertEquals(fixture.ingressSource(), material.source());
    assertEquals("source-ready", material.state());
    SecretProjectionService projectionService = new SecretProjectionService();
    var repair =
        projectionService.project(
            fixture.secretClient().client(),
            fixture.plan(),
            material.role(),
            material.source(),
            material.sourceGeneration(),
            material.sourceObjectGeneration(),
            material.summary().spkiSha256(),
            material.provenance(),
            ALWAYS_CURRENT);
    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(fixture.secretClient().runtimeSecrets()).resource(candidate.capture());
    assertEquals("projected", repair.state());
    assertEquals(fixture.ingressSource().getData(), candidate.getValue().getData());
  }

  @Test
  void materializationDefersASecondDriftWhileTheFirstProjectionRepairs() {
    DriftSelectionFixture fixture = driftSelectionFixture(false);
    Secret telnetProjection =
        fixture.secretClient().runtimeSecrets().withName(fixture.plan().telnetSecretName()).get();
    telnetProjection.setData(
        Map.of("tls.crt", encoded("tampered-telnet"), "tls.key", encoded("key-1")));

    CertificateMaterialService.RoleMaterial ingress = fixture.materialization().ingress();
    CertificateMaterialService.RoleMaterial telnet = fixture.materialization().telnet();

    assertEquals("source-ready", ingress.state());
    assertEquals("serialized-deferred-drift", telnet.state());
    assertEquals(true, telnet.projectionDeferred());
    var repair =
        new SecretProjectionService()
            .project(
                fixture.secretClient().client(),
                fixture.plan(),
                ingress.role(),
                ingress.source(),
                ingress.sourceGeneration(),
                ingress.sourceObjectGeneration(),
                ingress.summary().spkiSha256(),
                ingress.provenance(),
                ALWAYS_CURRENT);
    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(fixture.secretClient().runtimeSecrets()).resource(candidate.capture());
    assertEquals("projected", repair.state());
    assertEquals(fixture.plan().ingressSecretName(), candidate.getValue().getMetadata().getName());
  }

  @Test
  void materializationRepairsDriftWhileInitializingAnAbsentProjection() {
    DriftSelectionFixture fixture = driftSelectionFixture(false);
    Resource<Secret> telnetProjection =
        fixture.secretClient().runtimeSecrets().withName(fixture.plan().telnetSecretName());
    when(telnetProjection.get()).thenReturn(null);

    CertificateMaterialService.RoleMaterial ingress = fixture.materialization().ingress();
    stubCertificate(
        fixture.secretClient().client(),
        fixture.plan(),
        fixture.plan().telnetCertificateName(),
        true,
        1,
        fixture
            .secretClient()
            .identitySecrets()
            .withName(fixture.plan().telnetSecretName())
            .get()
            .getData());
    CertificateMaterialService.RoleMaterial telnet = fixture.materialization().telnet();

    assertEquals("source-ready", ingress.state());
    assertEquals("source-ready", telnet.state());
    SecretProjectionService projectionService = new SecretProjectionService();
    assertEquals(
        "projected",
        projectionService
            .project(
                fixture.secretClient().client(),
                fixture.plan(),
                ingress.role(),
                ingress.source(),
                ingress.sourceGeneration(),
                ingress.sourceObjectGeneration(),
                ingress.summary().spkiSha256(),
                ingress.provenance(),
                ALWAYS_CURRENT)
            .state());
    assertEquals(
        "projected",
        projectionService
            .project(
                fixture.secretClient().client(),
                fixture.plan(),
                telnet.role(),
                telnet.source(),
                telnet.sourceGeneration(),
                telnet.sourceObjectGeneration(),
                telnet.summary().spkiSha256(),
                telnet.provenance(),
                ALWAYS_CURRENT)
            .state());
    ArgumentCaptor<Secret> candidates = ArgumentCaptor.forClass(Secret.class);
    verify(fixture.secretClient().runtimeSecrets(), org.mockito.Mockito.times(2))
        .resource(candidates.capture());
    assertEquals(
        List.of(fixture.plan().ingressSecretName(), fixture.plan().telnetSecretName()),
        candidates.getAllValues().stream()
            .map(candidate -> candidate.getMetadata().getName())
            .toList());
  }

  @Test
  void caRotationValidatesOnlyTheSelectedNewSourceAndPinsOtherSharedRoles() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setGrpcTrustAnchorSha256("9".repeat(64));
    EnvironmentIdentityPlan plan = new EnvironmentIdentityPlanner(properties).plan("pr-42");
    SecretClient secretClient = secretClient(plan);
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    Map<String, String> replacementData =
        Map.of("tls.crt", encoded("replacement"), "tls.key", encoded("key-2"));
    Secret gatewaySource = null;
    for (String role :
        List.of(
            HostedIdentityContract.INGRESS_ROLE,
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      Map<String, String> sourceData =
          HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE.equals(role)
              ? replacementData
              : acceptedData;
      Secret source =
          stubProjectionAndSource(secretClient, plan, role, acceptedData, acceptedData, sourceData);
      if (HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE.equals(role)) {
        gatewaySource = source;
      }
    }
    stubCertificate(
        secretClient.client(),
        plan,
        plan.gatewayInternalWsCertificateName(),
        true,
        2,
        replacementData);
    Secret selectedGatewaySource = gatewaySource;
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    when(validator.validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              Secret source = invocation.getArgument(0);
              String spki = source == selectedGatewaySource ? "3".repeat(64) : "2".repeat(64);
              return new SecretMaterialValidator.MaterialSummary(
                  "1".repeat(64),
                  spki,
                  java.time.Instant.EPOCH,
                  java.time.Instant.MAX,
                  "4".repeat(64));
            });
    GrpcTransportBundleGenerator generator = mock(GrpcTransportBundleGenerator.class);
    CertificateMaterialService service =
        new CertificateMaterialService(
            new CertificateResourceFactory(), validator, generator, properties);
    var materialization = service.beginMaterialization(secretClient.client(), plan);

    CertificateMaterialService.RoleMaterial gateway = materialization.gatewayInternalWs();
    CertificateMaterialService.RoleMaterial bridge = materialization.tcpProxyBridge();
    CertificateMaterialService.RoleMaterial grpc = materialization.grpc(1L);

    assertEquals(selectedGatewaySource, gateway.source());
    assertEquals("source-ready", gateway.state());
    assertEquals("serialized-deferred", bridge.state());
    assertEquals("serialized-deferred", grpc.state());
    verifyNoInteractions(generator);
    var gatewayProjection =
        new SecretProjectionService()
            .project(
                secretClient.client(),
                plan,
                gateway.role(),
                gateway.source(),
                gateway.sourceGeneration(),
                gateway.sourceObjectGeneration(),
                gateway.summary().spkiSha256(),
                gateway.provenance(),
                ALWAYS_CURRENT);
    assertEquals("projected", gatewayProjection.state());
    ArgumentCaptor<String> anchors = ArgumentCaptor.forClass(String.class);
    verify(validator, org.mockito.Mockito.times(3))
        .validateIdentity(
            org.mockito.ArgumentMatchers.any(Secret.class),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            anchors.capture());
    assertEquals(List.of("9".repeat(64), "", ""), anchors.getAllValues());
  }

  @Test
  void deferredDriftFailsClosedWhenNoAcceptedTupleExists() {
    DriftSelectionFixture fixture = driftSelectionFixture(false);
    Secret telnetProjection =
        fixture.secretClient().runtimeSecrets().withName(fixture.plan().telnetSecretName()).get();
    telnetProjection.setData(
        Map.of("tls.crt", encoded("tampered-telnet"), "tls.key", encoded("key-1")));
    Map<String, String> annotations = telnetProjection.getMetadata().getAnnotations();
    annotations.remove(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    annotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION);
    annotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION);
    annotations.remove(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);

    assertEquals("source-ready", fixture.materialization().ingress().state());
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> fixture.materialization().telnet());

    assertEquals("accepted projection snapshot is unavailable", failure.getMessage());
  }

  @Test
  void materializationFailsClosedWhenProjectionDriftCoincidesWithSourceAdvancement() {
    DriftSelectionFixture fixture = driftSelectionFixture(true);

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> fixture.materialization().ingress());

    assertEquals(
        "runtime projection material drifted while its identity source advanced",
        exception.getMessage());
    verify(fixture.secretClient().runtimeSecrets(), never())
        .resource(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void materializationSelectsValidatedSourceWhenRuntimeProjectionDataIsMalformed() {
    DriftSelectionFixture fixture = driftSelectionFixture(false);
    Secret malformed =
        fixture.secretClient().runtimeSecrets().withName(fixture.plan().ingressSecretName()).get();
    malformed.setData(Map.of("tls.crt", "not-base64", "tls.key", encoded("key-1")));

    CertificateMaterialService.RoleMaterial material = fixture.materialization().ingress();

    assertEquals(fixture.ingressSource(), material.source());
    assertEquals("source-ready", material.state());
    var repair =
        new SecretProjectionService()
            .project(
                fixture.secretClient().client(),
                fixture.plan(),
                material.role(),
                material.source(),
                material.sourceGeneration(),
                material.sourceObjectGeneration(),
                material.summary().spkiSha256(),
                material.provenance(),
                ALWAYS_CURRENT);
    ArgumentCaptor<Secret> candidate = ArgumentCaptor.forClass(Secret.class);
    verify(fixture.secretClient().runtimeSecrets()).resource(candidate.capture());
    assertEquals("projected", repair.state());
    assertEquals(fixture.ingressSource().getData(), candidate.getValue().getData());
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
    Secret unownedGrpc =
        new SecretBuilder(ownedGrpc).editMetadata().withLabels(Map.of()).endMetadata().build();
    Resource<Secret> grpcSourceResource = mock(Resource.class);
    when(secretClient.identitySecrets().withName(plan.grpcSecretName()))
        .thenReturn(grpcSourceResource);
    when(grpcSourceResource.get()).thenReturn(ownedGrpc, unownedGrpc);
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

  @Test
  void rolloutEditPreservesEveryFieldExceptTheSelectedTemplateAnnotation() {
    var deployment =
        new DeploymentBuilder()
            .withNewMetadata()
            .withName("account-service")
            .addToLabels("owner", "runtime")
            .endMetadata()
            .withNewSpec()
            .withReplicas(3)
            .withNewSelector()
            .addToMatchLabels("app", "account-service")
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app", "account-service")
            .addToAnnotations("other", "keep")
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("app")
            .withImage("image@sha256:test")
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build();

    DeploymentRolloutService.applyRevision(deployment, "firemud.dev/grpc-revision", "sha256:new");

    assertEquals(3, deployment.getSpec().getReplicas());
    assertEquals("runtime", deployment.getMetadata().getLabels().get("owner"));
    assertEquals(
        "image@sha256:test",
        deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    assertEquals(
        "keep", deployment.getSpec().getTemplate().getMetadata().getAnnotations().get("other"));
    assertEquals(
        "sha256:new",
        deployment
            .getSpec()
            .getTemplate()
            .getMetadata()
            .getAnnotations()
            .get("firemud.dev/grpc-revision"));
  }

  @Test
  void activeRolloutRequiresAtLeastOneDesiredReplica() {
    var deployment =
        new DeploymentBuilder()
            .withNewMetadata()
            .withName("account-service")
            .withGeneration(3L)
            .endMetadata()
            .withNewSpec()
            .withReplicas(0)
            .endSpec()
            .withNewStatus()
            .withObservedGeneration(3L)
            .withUpdatedReplicas(0)
            .withAvailableReplicas(0)
            .endStatus()
            .build();

    assertEquals(false, DeploymentRolloutService.activeRolloutObserved(deployment));

    deployment.getSpec().setReplicas(1);
    deployment.getStatus().setUpdatedReplicas(1);
    deployment.getStatus().setAvailableReplicas(2);
    deployment.getStatus().setReplicas(2);
    assertEquals(false, DeploymentRolloutService.activeRolloutObserved(deployment));

    deployment.getStatus().setAvailableReplicas(1);
    deployment.getStatus().setReplicas(null);
    assertEquals(false, DeploymentRolloutService.activeRolloutObserved(deployment));

    deployment.getStatus().setReplicas(1);
    assertEquals(true, DeploymentRolloutService.activeRolloutObserved(deployment));
  }

  @Test
  void explicitRetirementScalesBothBridgeEndpointsToZeroAndWaitsForObservedShutdown() {
    assertEquals(
        java.util.List.of("spring-cloud-gateway", "tcp-proxy-service"),
        DeploymentRolloutService.BRIDGE_DEPLOYMENTS);
    var deployment =
        new DeploymentBuilder()
            .withNewMetadata()
            .withName("spring-cloud-gateway")
            .addToLabels("owner", "runtime")
            .endMetadata()
            .withNewSpec()
            .withReplicas(2)
            .withNewSelector()
            .addToMatchLabels("app", "spring-cloud-gateway")
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app", "spring-cloud-gateway")
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("gateway")
            .withImage("image@sha256:test")
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .withNewStatus()
            .withReplicas(2)
            .withReadyReplicas(2)
            .withAvailableReplicas(2)
            .endStatus()
            .build();

    DeploymentRolloutService.applyRetirementScaleDown(deployment);

    assertEquals(0, deployment.getSpec().getReplicas());
    assertEquals("runtime", deployment.getMetadata().getLabels().get("owner"));
    assertEquals(
        "image@sha256:test",
        deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    assertEquals(false, DeploymentRolloutService.retirementScaleDownObserved(deployment));
    deployment.getStatus().setReadyReplicas(0);
    deployment.getStatus().setAvailableReplicas(0);
    assertEquals(false, DeploymentRolloutService.retirementScaleDownObserved(deployment));
    deployment.getStatus().setReplicas(0);
    assertEquals(true, DeploymentRolloutService.retirementScaleDownObserved(deployment));
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
                    certificateName.equals(plan.ingressCertificateName())
                        ? plan.ingressIssuer()
                        : certificateName.equals(plan.telnetCertificateName())
                            ? plan.telnetIssuer()
                            : plan.grpcIssuer(),
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
    String issuer =
        certificateName.equals(plan.ingressCertificateName())
            ? plan.ingressIssuer()
            : certificateName.equals(plan.telnetCertificateName())
                ? plan.telnetIssuer()
                : plan.grpcIssuer();
    GenericKubernetesResource request = new GenericKubernetesResource();
    request.setApiVersion("cert-manager.io/v1");
    request.setKind("CertificateRequest");
    request.setMetadata(
        new ObjectMetaBuilder()
            .withName(certificateName + "-request-" + revision)
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
    status.put("conditions", List.of(Map.of("type", "Ready", "status", "True")));
    request.setAdditionalProperties(
        Map.of(
            "spec",
            Map.of(
                "issuerRef",
                Map.of(
                    "name", issuer,
                    "kind", "ClusterIssuer",
                    "group", "cert-manager.io")),
            "status",
            status));
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
    when(runtimeSecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(mock(Resource.class));
    when(identitySecrets.resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(mock(Resource.class));
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

  private static DriftSelectionFixture driftSelectionFixture(boolean sourceAdvanced) {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> acceptedData =
        Map.of("tls.crt", encoded("accepted"), "tls.key", encoded("key-1"));
    Map<String, String> driftedData =
        Map.of("tls.crt", encoded("tampered"), "tls.key", encoded("key-1"));
    Map<String, String> advancedData =
        Map.of("tls.crt", encoded("advanced"), "tls.key", encoded("key-2"));
    Secret ingressSource = null;
    for (String role :
        List.of(
            HostedIdentityContract.INGRESS_ROLE,
            HostedIdentityContract.TELNET_ROLE,
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
            HostedIdentityContract.GRPC_ROLE)) {
      Map<String, String> projectionData =
          HostedIdentityContract.INGRESS_ROLE.equals(role) ? driftedData : acceptedData;
      Map<String, String> sourceData =
          HostedIdentityContract.INGRESS_ROLE.equals(role) && sourceAdvanced
              ? advancedData
              : acceptedData;
      Secret source =
          stubProjectionAndSource(
              secretClient, plan, role, projectionData, acceptedData, sourceData);
      if (HostedIdentityContract.INGRESS_ROLE.equals(role)) {
        ingressSource = source;
      }
    }
    stubCertificate(
        secretClient.client(),
        plan,
        plan.ingressCertificateName(),
        true,
        1,
        ingressSource.getData());
    SecretMaterialValidator validator = mock(SecretMaterialValidator.class);
    SecretMaterialValidator.MaterialSummary summary =
        new SecretMaterialValidator.MaterialSummary(
            "1".repeat(64),
            "2".repeat(64),
            java.time.Instant.EPOCH,
            java.time.Instant.MAX,
            "1".repeat(64));
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
            new CertificateResourceFactory(),
            validator,
            mock(GrpcTransportBundleGenerator.class),
            new HostedIdentityProperties());
    return new DriftSelectionFixture(
        plan,
        secretClient,
        ingressSource,
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

  private record DriftSelectionFixture(
      EnvironmentIdentityPlan plan,
      SecretClient secretClient,
      Secret ingressSource,
      CertificateMaterialService.MaterializationBatch materialization) {}

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
