package net.firedevops.firemud.hostedidentity.kubernetes;

import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.SERIALIZED_DEFERRED;
import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.SERIALIZED_DEFERRED_DRIFT;
import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.SOURCE_READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Set;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.GrpcTransportBundleGenerator;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecretProjectionServiceTest {
  private static final Runnable ALWAYS_CURRENT = () -> {};

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
  void revisionLengthPrefixesKeysAndValuesToPreventDelimiterCollisions() {
    Map<String, String> embeddedNull = Map.of("a", encoded("x\0b\0y"));
    Map<String, String> splitEntries = Map.of("a", encoded("x"), "b", encoded("y"));

    assertNotEquals(
        SecretProjectionService.revisionForData(embeddedNull),
        SecretProjectionService.revisionForData(splitEntries));
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
                          () -> {}));
      assertEquals("projection provenance is required", exception.getMessage());
    }
  }

  @Test
  void revisionRejectsMissingAndMalformedMaterial() {
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class, () -> SecretProjectionService.revisionForData(null));
    assertEquals("material data is required", missing.getMessage());
    IllegalArgumentException missingRoleData =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SecretProjectionService.revisionForRole(HostedIdentityContract.INGRESS_ROLE, null));
    assertEquals("material data is required", missingRoleData.getMessage());
    IllegalArgumentException malformed =
        assertThrows(
            IllegalArgumentException.class,
            () -> SecretProjectionService.revisionForData(Map.of("tls.crt", "not-base64")));
    assertEquals("unable to calculate material revision", malformed.getMessage());
    assertTrue(malformed.getCause() instanceof IllegalArgumentException);
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
    Map<String, String> annotations = candidate.getValue().getMetadata().getAnnotations();
    assertEquals(
        HostedIdentityContract.TRANSPORT_PROVENANCE,
        annotations.get(HostedIdentityContract.PROVENANCE_ANNOTATION));
    assertEquals(
        Set.of(
            HostedIdentityContract.REVISION_ANNOTATION,
            HostedIdentityContract.SOURCE_GENERATION_ANNOTATION,
            HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION,
            HostedIdentityContract.SPKI_SHA256_ANNOTATION,
            HostedIdentityContract.PROVENANCE_ANNOTATION,
            HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION),
        annotations.keySet());
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

    IllegalStateException failure =
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
                    "1".repeat(64),
                    "cert-manager",
                    runtimeProfileFence(guardCalls, 1)));

    assertEquals("runtime profile changed", failure.getMessage());
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

    IllegalStateException failure =
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
                    "1".repeat(64),
                    "cert-manager",
                    runtimeProfileFence(guardCalls, 1)));

    assertEquals("runtime profile changed", failure.getMessage());
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

    IllegalStateException failure =
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
                    runtimeProfileFence(guardCalls, 2)));

    assertEquals("runtime profile changed", failure.getMessage());
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

    IllegalStateException failure =
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
                    runtimeProfileFence(guardCalls, 3)));

    assertEquals("runtime profile changed", failure.getMessage());
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

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.acknowledge(
                    secretClient.client(),
                    plan,
                    HostedIdentityContract.INGRESS_ROLE,
                    revision,
                    1,
                    1,
                    "1".repeat(64),
                    runtimeProfileFence(guardCalls, 2)));

    assertEquals("runtime profile changed", failure.getMessage());
    verify(currentResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void acknowledgementAwaitsWhenProjectionIsAbsentAfterTheProbe() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    String expectedRevision = "sha256:" + "1".repeat(64);
    Resource<Secret> currentResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.ingressSecretName()))
        .thenReturn(currentResource);
    when(currentResource.get()).thenReturn(null);

    SecretProjectionService.ProjectionResult result =
        new SecretProjectionService()
            .acknowledge(
                secretClient.client(),
                plan,
                HostedIdentityContract.INGRESS_ROLE,
                expectedRevision,
                1,
                1,
                "1".repeat(64),
                ALWAYS_CURRENT);

    assertEquals("projection-absent", result.state());
    assertEquals(expectedRevision, result.revision());
    verify(currentResource).get();
    verify(currentResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));
  }

  @Test
  void grpcAcknowledgementUsesTheRoleCanonicalMaterialRevision() {
    EnvironmentIdentityPlan plan = plan();
    SecretClient secretClient = secretClient(plan);
    Map<String, String> sourceShapedData =
        Map.of(
            "tls.crt", encoded("certificate"), "tls.key", encoded("key"), "ca.crt", encoded("ca"));
    String revision =
        SecretProjectionService.revisionForRole(HostedIdentityContract.GRPC_ROLE, sourceShapedData);
    Map<String, String> pendingAnnotations = new LinkedHashMap<>();
    pendingAnnotations.put(HostedIdentityContract.REVISION_ANNOTATION, revision);
    pendingAnnotations.put(HostedIdentityContract.SOURCE_GENERATION_ANNOTATION, "1");
    pendingAnnotations.put(HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION, "1");
    pendingAnnotations.put(HostedIdentityContract.SPKI_SHA256_ANNOTATION, "1".repeat(64));
    pendingAnnotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
    Secret current =
        ownedSecret(
            plan,
            HostedIdentityContract.GRPC_ROLE,
            plan.grpcSecretName(),
            sourceShapedData,
            pendingAnnotations);
    current.getMetadata().setResourceVersion("7");
    Resource<Secret> currentResource = mock(Resource.class);
    when(secretClient.runtimeSecrets().withName(plan.grpcSecretName())).thenReturn(currentResource);
    when(currentResource.get()).thenReturn(current);
    ReplaceDeletable<Secret> lockedResource = mock(ReplaceDeletable.class);
    when(currentResource.lockResourceVersion("7")).thenReturn(lockedResource);

    SecretProjectionService.ProjectionResult result =
        new SecretProjectionService()
            .acknowledge(
                secretClient.client(),
                plan,
                HostedIdentityContract.GRPC_ROLE,
                revision,
                1,
                1,
                "1".repeat(64),
                ALWAYS_CURRENT);

    assertEquals(true, result.isSynced());
    verify(lockedResource).replace(current);
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
  void unacceptedProjectionAwaitsAcceptanceAndCanBeAcknowledged() {
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
    Resource<Secret> projectionResource =
        fixture.secretClient().runtimeSecrets().withName(fixture.plan().ingressSecretName());
    ReplaceDeletable<Secret> lockedResource = mock(ReplaceDeletable.class);
    when(projectionResource.lockResourceVersion("7")).thenReturn(lockedResource);
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
  }

  @Test
  void replacementProjectionRetainsAcceptedSnapshot() {
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
    Secret prior =
        ownedSecret(
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            plan.ingressSecretName() + "-previous",
            acceptedData,
            acceptedAnnotations(acceptedRevision, acceptedSpki));
    prior.getMetadata().setNamespace(plan.identityNamespace());
    prior.getMetadata().setResourceVersion("6");
    when(predecessorResource.get()).thenReturn(prior);
    Resource<Secret> predecessorReplacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> lockedPredecessorResource = mock(ReplaceDeletable.class);
    when(secretClient.identitySecrets().resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(predecessorReplacementResource);
    when(predecessorReplacementResource.lockResourceVersion("6"))
        .thenReturn(lockedPredecessorResource);
    Resource<Secret> replacementResource = mock(Resource.class);
    ReplaceDeletable<Secret> lockedReplacementResource = mock(ReplaceDeletable.class);
    when(secretClient.runtimeSecrets().resource(org.mockito.ArgumentMatchers.any(Secret.class)))
        .thenReturn(replacementResource);
    when(replacementResource.lockResourceVersion("7")).thenReturn(lockedReplacementResource);

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
    verify(predecessorReplacementResource).lockResourceVersion("6");
    verify(lockedPredecessorResource).replace();
    verify(replacementResource).lockResourceVersion("7");
    verify(lockedReplacementResource).replace();
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
    ReplaceDeletable<Secret> lockedResource = mock(ReplaceDeletable.class);
    when(existingResource.lockResourceVersion("7")).thenReturn(lockedResource);
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

    repaired.setData(Map.of("tls.crt", "not-base64", "tls.key", encoded("key")));
    var malformedAcceptance =
        service.acknowledge(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            revision,
            1,
            1,
            spki,
            ALWAYS_CURRENT);
    assertEquals("projection-material-changed", malformedAcceptance.state());
    verify(existingResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));

    Map<String, String> nullMaterial = new LinkedHashMap<>(desiredData);
    nullMaterial.put("tls.crt", null);
    repaired.setData(nullMaterial);
    var nullAcceptance =
        service.acknowledge(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            revision,
            1,
            1,
            spki,
            ALWAYS_CURRENT);
    assertEquals("projection-material-changed", nullAcceptance.state());
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
    verify(existingResource).lockResourceVersion("7");
    verify(lockedResource).replace(repaired);
  }

  @Test
  void acknowledgementReturnsPendingOnLockedResourceVersionConflict() {
    EnvironmentIdentityPlan plan = plan();
    SecretProjectionService service = new SecretProjectionService();
    SecretClient secretClient = secretClient(plan);
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
    ReplaceDeletable<Secret> lockedResource = mock(ReplaceDeletable.class);
    when(currentResource.lockResourceVersion("7")).thenReturn(lockedResource);
    org.mockito.Mockito.doThrow(new KubernetesClientException("conflict", 409, null))
        .when(lockedResource)
        .replace(current);

    SecretProjectionService.ProjectionResult result =
        service.acknowledge(
            secretClient.client(),
            plan,
            HostedIdentityContract.INGRESS_ROLE,
            revision,
            1,
            1,
            "1".repeat(64),
            ALWAYS_CURRENT);

    assertEquals("acceptance-cas-conflict", result.state());
    assertEquals(revision, result.revision());
    verify(currentResource).lockResourceVersion("7");
    verify(lockedResource).replace(current);
    verify(currentResource, never()).replace(org.mockito.ArgumentMatchers.any(Secret.class));
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
    assertEquals(SOURCE_READY, material.state());
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

    assertEquals(SOURCE_READY, ingress.state());
    assertEquals(SERIALIZED_DEFERRED_DRIFT, telnet.state());
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

    assertEquals(SOURCE_READY, ingress.state());
    assertEquals(SOURCE_READY, telnet.state());
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
    assertEquals(SOURCE_READY, gateway.state());
    assertEquals(SERIALIZED_DEFERRED, bridge.state());
    assertEquals(SERIALIZED_DEFERRED, grpc.state());
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
    Map<String, String> annotations =
        new LinkedHashMap<>(telnetProjection.getMetadata().getAnnotations());
    annotations.remove(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION);
    annotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION);
    annotations.remove(HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION);
    annotations.remove(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);
    telnetProjection.getMetadata().setAnnotations(annotations);

    assertEquals(SOURCE_READY, fixture.materialization().ingress().state());
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
    assertEquals(SOURCE_READY, material.state());
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
    stubCertificateRequests(client, plan, List.of(request));
  }

  @SuppressWarnings("unchecked")
  private static void stubCertificateRequests(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      List<GenericKubernetesResource> requestsToReturn) {
    GenericKubernetesResourceList requestList = new GenericKubernetesResourceList();
    requestList.setItems(requestsToReturn);
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
    stubCertificateRequests(
        secretClient.client(),
        plan,
        List.of(
            certificateRequest(
                plan,
                plan.ingressCertificateName(),
                1,
                "ingress-request",
                ingressSource.getData(),
                true),
            certificateRequest(
                plan, plan.telnetCertificateName(), 1, "telnet-request", acceptedData, true),
            certificateRequest(
                plan,
                plan.gatewayInternalWsCertificateName(),
                1,
                "gateway-request",
                acceptedData,
                true),
            certificateRequest(
                plan,
                plan.tcpProxyBridgeCertificateName(),
                1,
                "tcp-proxy-request",
                acceptedData,
                true)));
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
    Secret source =
        HostedIdentityContract.GRPC_ROLE.equals(role)
            ? ownedSecret(plan, role, name, sourceData, Map.of())
            : certManagerSource(plan, role, name, sourceData);
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

  private static Runnable runtimeProfileFence(
      java.util.concurrent.atomic.AtomicInteger calls, int successfulCalls) {
    return () -> {
      if (calls.getAndIncrement() >= successfulCalls) {
        throw new IllegalStateException("runtime profile changed");
      }
    };
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
