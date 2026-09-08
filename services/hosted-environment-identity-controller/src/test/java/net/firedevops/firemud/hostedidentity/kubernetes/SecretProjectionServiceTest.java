package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
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
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.GrpcTransportBundleGenerator;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecretProjectionServiceTest {
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
  void acceptedRevisionRequiresTheCanonicalAlgorithmPrefixAndFullDigest() {
    SecretProjectionService service = new SecretProjectionService();
    assertThrows(
        IllegalStateException.class,
        () -> service.acknowledge(null, null, "ingress", "1".repeat(64), 1, 1, "2".repeat(64)));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.acknowledge(
                null, null, "ingress", "sha256:" + "1".repeat(63), 1, 1, "2".repeat(64)));
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
            "revisionHistoryLimit",
            1,
            "privateKey",
            Map.of("algorithm", "RSA"),
            "dnsNames",
            java.util.List.of("pr-42.example.test"));
    Map<String, Object> defaulted =
        Map.of(
            "secretName",
            "pr-42-tls",
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
    assertEquals(
        true,
        CertificateMaterialService.containsDesiredLabels(
            Map.of("managed", "yes", "cert-manager-default", "present"), Map.of("managed", "yes")));
  }

  @Test
  void serializedRotationContinuesPendingRoleBeforeStartingAnotherChange() {
    var ingress = new CertificateMaterialService.RotationState("ingress", false, true, false);
    var telnet = new CertificateMaterialService.RotationState("telnet", false, true, false);
    var grpc = new CertificateMaterialService.RotationState("grpc", true, true, false);

    assertEquals(
        "grpc",
        CertificateMaterialService.selectSerializedRole(java.util.List.of(ingress, telnet, grpc)));
  }

  @Test
  void serializedRotationStartsOnlyTheFirstChangedRole() {
    var ingress = new CertificateMaterialService.RotationState("ingress", false, false, false);
    var telnet = new CertificateMaterialService.RotationState("telnet", false, true, false);
    var grpc = new CertificateMaterialService.RotationState("grpc", false, true, false);

    assertEquals(
        "telnet",
        CertificateMaterialService.selectSerializedRole(java.util.List.of(ingress, telnet, grpc)));
    assertEquals(
        null,
        CertificateMaterialService.selectSerializedRole(
            java.util.List.of(
                ingress,
                new CertificateMaterialService.RotationState("telnet", false, false, false),
                new CertificateMaterialService.RotationState("grpc", false, false, false))));
  }

  @Test
  void serializedRotationDoesNotGateInitialProjectionUntilEveryRoleIsAccepted() {
    var ingress = new CertificateMaterialService.RotationState("ingress", true, true, false);
    var telnet = new CertificateMaterialService.RotationState("telnet", false, false, true);
    var grpc = new CertificateMaterialService.RotationState("grpc", true, true, false);

    assertEquals(
        null,
        CertificateMaterialService.selectSerializedRole(java.util.List.of(ingress, telnet, grpc)));
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
            "cert-manager");

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
    assertEquals(
        HostedIdentityContract.INGRESS_ROLE,
        CertificateMaterialService.selectSerializedRole(
            List.of(
                new CertificateMaterialService.RotationState(
                    HostedIdentityContract.INGRESS_ROLE, true, false, false),
                new CertificateMaterialService.RotationState(
                    HostedIdentityContract.TELNET_ROLE, false, false, false))));
  }

  @Test
  void grpcSourceOwnershipIsRecheckedAfterRotationSelection() {
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
      when(projectionResource.get()).thenReturn(ownedSecret(plan, role, name, data, annotations));
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
    CertificateMaterialService service =
        new CertificateMaterialService(
            mock(CertificateResourceFactory.class),
            mock(SecretMaterialValidator.class),
            mock(GrpcTransportBundleGenerator.class),
            new HostedIdentityProperties());

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.grpc(client, plan, 1L));

    assertEquals("identity source Secret is not controller-owned", exception.getMessage());
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
    deployment.getStatus().setReplicas(0);
    deployment.getStatus().setReadyReplicas(0);
    deployment.getStatus().setAvailableReplicas(0);
    assertEquals(true, DeploymentRolloutService.retirementScaleDownObserved(deployment));
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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

  private record SecretClient(
      KubernetesClient client,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets,
      NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets) {}

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
