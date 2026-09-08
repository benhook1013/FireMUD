package net.firedevops.firemud.hostedidentity.reconcile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ResourceOperations;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.admission.AdmissionValidator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService;
import net.firedevops.firemud.hostedidentity.kubernetes.DeploymentRolloutService;
import net.firedevops.firemud.hostedidentity.kubernetes.HostedIdentityScopeService;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.kubernetes.SecretProjectionService;
import net.firedevops.firemud.hostedidentity.model.HostedCondition;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;

class HostedIdentityReconcilerSafetyTest {
  @Test
  void projectionAcceptanceStatusUsesTheFirstUnsyncedProjection() {
    var status =
        HostedIdentityReconciler.readinessStatus(
            java.util.List.of(
                SecretProjectionService.ProjectionResult.synced("ingress"),
                SecretProjectionService.ProjectionResult.awaiting(
                    "predecessor-not-accepted", "telnet"),
                SecretProjectionService.ProjectionResult.awaiting(
                    "awaiting-acceptance", "gateway")),
            new DeploymentRolloutService.RolloutResult(false, false, false),
            new ServedEnvironmentProbe.ProbeResult(false, "https-connection-failed"));

    assertReadinessStatus(
        status,
        HostedEnvironmentIdentityStatus.Phase.Syncing,
        "AwaitingAcceptance",
        "predecessor-not-accepted",
        false);
  }

  @Test
  void rolloutStatusPrecedesProbeStatusAndIdentifiesTheFirstPendingRollout() {
    var synced = SecretProjectionService.ProjectionResult.synced("revision");
    var probeFailure = new ServedEnvironmentProbe.ProbeResult(false, "https-connection-failed");

    assertReadinessStatus(
        HostedIdentityReconciler.readinessStatus(
            java.util.List.of(synced),
            new DeploymentRolloutService.RolloutResult(false, false, false),
            probeFailure),
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RolloutPending",
        "telnet-rollout-pending",
        false);
    assertReadinessStatus(
        HostedIdentityReconciler.readinessStatus(
            java.util.List.of(synced),
            new DeploymentRolloutService.RolloutResult(false, true, false),
            probeFailure),
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RolloutPending",
        "grpc-rollout-pending",
        false);
  }

  @Test
  void probeStatusOwnsReasonAndMessageAfterEarlierGatesPass() {
    var status =
        HostedIdentityReconciler.readinessStatus(
            java.util.List.of(SecretProjectionService.ProjectionResult.synced("revision")),
            new DeploymentRolloutService.RolloutResult(true, true, true),
            new ServedEnvironmentProbe.ProbeResult(false, "bridge-connection-failed"));

    assertReadinessStatus(
        status,
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "ServedProbePending",
        "bridge-connection-failed",
        false);
  }

  @Test
  void readyStatusPreservesTheSuccessfulProbeMessage() {
    var status =
        HostedIdentityReconciler.readinessStatus(
            java.util.List.of(SecretProjectionService.ProjectionResult.synced("revision")),
            new DeploymentRolloutService.RolloutResult(true, true, true),
            new ServedEnvironmentProbe.ProbeResult(true, "served-bridge-and-grpc-accepted"));

    assertReadinessStatus(
        status,
        HostedEnvironmentIdentityStatus.Phase.Ready,
        "Reconciled",
        "served-bridge-and-grpc-accepted",
        true);
  }

  @Test
  void informerAndConfiguredControlNamespaceMustStayCanonical() {
    ControllerConfiguration configuration =
        HostedIdentityReconciler.class.getAnnotation(ControllerConfiguration.class);
    assertEquals(
        java.util.List.of(HostedIdentityContract.CONTROL_NAMESPACE),
        java.util.Arrays.asList(configuration.informer().namespaces()));

    HostedIdentityProperties canonical = new HostedIdentityProperties();
    assertEquals(HostedIdentityContract.CONTROL_NAMESPACE, canonical.getControlNamespace());
    assertDoesNotThrow(canonical::afterPropertiesSet);

    HostedIdentityProperties mismatched = new HostedIdentityProperties();
    mismatched.setControlNamespace("other-system");
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, mismatched::afterPropertiesSet);
    assertEquals("hosted identity control namespace must be firemud-system", failure.getMessage());
  }

  @Test
  void retirementPublishesTerminalStatusBeforeDeletionRemovesFinalizer() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("active");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(anyString())).thenReturn(namespace);
    when(namespace.get()).thenReturn(null);
    Context<HostedEnvironmentIdentity> context = mock(Context.class);
    ResourceOperations<HostedEnvironmentIdentity> operations = mock(ResourceOperations.class);
    when(context.resourceOperations()).thenReturn(operations);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            client,
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            mock(CertificateMaterialService.class),
            mock(SecretProjectionService.class),
            mock(HostedIdentityScopeService.class),
            mock(RuntimeProfileService.class),
            mock(DeploymentRolloutService.class),
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);
    HostedEnvironmentIdentity resource = resource();
    resource
        .getSpec()
        .setDesiredState(
            net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec.DesiredState
                .Retired);

    UpdateControl<HostedEnvironmentIdentity> published = reconciler.reconcile(resource, context);

    assertEquals(true, published.isPatchStatus());
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retired,
        published.getResource().orElseThrow().getStatus().getPhase());
    verify(operations, never()).removeFinalizer(HostedIdentityContract.FINALIZER);

    resource.getMetadata().setDeletionTimestamp(Instant.now().toString());
    UpdateControl<HostedEnvironmentIdentity> deleted = reconciler.reconcile(resource, context);

    assertEquals(true, deleted.isNoUpdate());
    assertEquals(false, deleted.isPatchStatus());
    verify(operations).removeFinalizer(HostedIdentityContract.FINALIZER);
  }

  @Test
  void retiredIntentTerminatesLiveBridgeEndpointsBeforeMaterialRemoval() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("active");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(anyString())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            new NamespaceBuilder().withNewMetadata().withName("pr-42").endMetadata().build());
    CertificateMaterialService certificates = mock(CertificateMaterialService.class);
    SecretProjectionService projections = mock(SecretProjectionService.class);
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    when(rollout.stopBridges(
            org.mockito.ArgumentMatchers.eq(client), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new DeploymentRolloutService.RetirementResult(false, false, false));
    ServedEnvironmentProbe probes = mock(ServedEnvironmentProbe.class);
    Context<HostedEnvironmentIdentity> context = mock(Context.class);
    ResourceOperations<HostedEnvironmentIdentity> operations = mock(ResourceOperations.class);
    when(context.resourceOperations()).thenReturn(operations);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            client,
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            certificates,
            projections,
            scope,
            runtime,
            rollout,
            probes,
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);
    HostedEnvironmentIdentity resource = resource();
    resource
        .getSpec()
        .setDesiredState(
            net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec.DesiredState
                .Retired);

    UpdateControl<HostedEnvironmentIdentity> result = reconciler.reconcile(resource, context);

    verify(rollout)
        .stopBridges(org.mockito.ArgumentMatchers.eq(client), org.mockito.ArgumentMatchers.any());
    verify(operations, never()).removeFinalizer(HostedIdentityContract.FINALIZER);
    verifyNoInteractions(certificates, projections, scope, runtime, probes);
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retiring,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "BridgeShutdownPending",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
  }

  @Test
  void finalizerRemovalRequiresObservableGenerationBoundRetiredStatus() {
    HostedEnvironmentIdentity resource = resource();
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    status.setPhase(HostedEnvironmentIdentityStatus.Phase.Retired);
    status.setObservedGeneration(1L);
    HostedCondition ready =
        new HostedCondition("Ready", "False", "Retired", "identity material removed");
    ready.setObservedGeneration(1L);
    status.setConditions(java.util.List.of(ready));
    resource.setStatus(status);

    assertEquals(true, HostedIdentityReconciler.retiredStatusIsCurrent(resource));

    status.setObservedGeneration(0L);
    assertEquals(false, HostedIdentityReconciler.retiredStatusIsCurrent(resource));
    status.setObservedGeneration(1L);
    ready.setStatus("True");
    status.setConditions(java.util.List.of(ready));
    assertEquals(false, HostedIdentityReconciler.retiredStatusIsCurrent(resource));
  }

  @Test
  void retirementNeverRequestsStatusPatchAfterFinalizerRemovalOrDeletionRace() {
    Context<HostedEnvironmentIdentity> context = mock(Context.class);
    ResourceOperations<HostedEnvironmentIdentity> operations = mock(ResourceOperations.class);
    when(context.resourceOperations()).thenReturn(operations);

    UpdateControl<HostedEnvironmentIdentity> removed =
        HostedIdentityReconciler.finishRetirement(context);
    assertEquals(true, removed.isNoUpdate());
    assertEquals(false, removed.isPatchStatus());
    verify(operations).removeFinalizer(HostedIdentityContract.FINALIZER);

    doThrow(new KubernetesClientException("deleted", 404, null))
        .when(operations)
        .removeFinalizer(HostedIdentityContract.FINALIZER);
    UpdateControl<HostedEnvironmentIdentity> alreadyDeleted =
        HostedIdentityReconciler.finishRetirement(context);
    assertEquals(true, alreadyDeleted.isNoUpdate());
    assertEquals(false, alreadyDeleted.isPatchStatus());

    doThrow(new KubernetesClientException("conflict", 409, null))
        .when(operations)
        .removeFinalizer(HostedIdentityContract.FINALIZER);
    assertThrows(
        KubernetesClientException.class, () -> HostedIdentityReconciler.finishRetirement(context));
  }

  @Test
  void pausedModeCannotMaterializeOrChangeFinalizers() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("paused");
    CertificateMaterialService certificates = mock(CertificateMaterialService.class);
    SecretProjectionService projections = mock(SecretProjectionService.class);
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    ServedEnvironmentProbe probes = mock(ServedEnvironmentProbe.class);
    Context<HostedEnvironmentIdentity> context = mock(Context.class);

    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            mock(KubernetesClient.class),
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            certificates,
            projections,
            scope,
            runtime,
            rollout,
            probes,
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);

    UpdateControl<HostedEnvironmentIdentity> result = reconciler.reconcile(resource(), context);

    verifyNoInteractions(certificates, projections, scope, runtime, rollout, probes, context);
    org.junit.jupiter.api.Assertions.assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
  }

  @Test
  void observeModeIsAlsoNonMaterializing() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("observe");
    CertificateMaterialService certificates = mock(CertificateMaterialService.class);
    SecretProjectionService projections = mock(SecretProjectionService.class);
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    ServedEnvironmentProbe probes = mock(ServedEnvironmentProbe.class);
    Context<HostedEnvironmentIdentity> context = mock(Context.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            mock(KubernetesClient.class),
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            certificates,
            projections,
            scope,
            runtime,
            rollout,
            probes,
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);

    UpdateControl<HostedEnvironmentIdentity> result = reconciler.reconcile(resource(), context);

    verifyNoInteractions(certificates, projections, scope, runtime, rollout, probes, context);
    org.junit.jupiter.api.Assertions.assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Pending,
        result.getResource().orElseThrow().getStatus().getPhase());
  }

  @Test
  void activeModeReportsRuntimeAbsenceBeforeIssuingMaterial() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("active");
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    when(runtime.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(RuntimeProfileService.RuntimeProfile.absent());
    CertificateMaterialService certificates = mock(CertificateMaterialService.class);
    SecretProjectionService projections = mock(SecretProjectionService.class);
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    ServedEnvironmentProbe probes = mock(ServedEnvironmentProbe.class);
    Context<HostedEnvironmentIdentity> context = mock(Context.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            mock(KubernetesClient.class),
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            certificates,
            projections,
            scope,
            runtime,
            rollout,
            probes,
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);

    UpdateControl<HostedEnvironmentIdentity> result = reconciler.reconcile(resource(), context);

    verifyNoInteractions(certificates, projections, scope, rollout, probes, context);
    org.junit.jupiter.api.Assertions.assertEquals(
        HostedEnvironmentIdentityStatus.Phase.RuntimeAbsent,
        result.getResource().orElseThrow().getStatus().getPhase());
  }

  @Test
  void statusHighWaterMarkRejectsRollbackAndSameGenerationSubstitution() {
    String priorSpki = "1".repeat(64);
    var previous = new HostedEnvironmentIdentityStatus.RoleStatus();
    previous.setSourceGeneration(4L);
    previous.setSourceObjectGeneration(2L);
    previous.setSpkiSha256(priorSpki);
    previous.setRevision(
        SecretProjectionService.revisionForRole("ingress", Map.of("tls.crt", encoded("old"))));
    var rollback = material(3, 2, "2".repeat(64), "new");
    var objectRollback = material(5, 1, "2".repeat(64), "new");
    var substitution = material(4, 2, "2".repeat(64), "new");
    var unchanged = material(4, 2, priorSpki, "old");
    var advanced = material(5, 3, "2".repeat(64), "new");
    var reusedKey = material(5, 3, priorSpki, "new");

    assertDoesNotThrow(() -> HostedIdentityReconciler.validateSourceProgress(unchanged, previous));
    assertDoesNotThrow(() -> HostedIdentityReconciler.validateSourceProgress(advanced, previous));
    assertThrows(
        IllegalStateException.class,
        () -> HostedIdentityReconciler.validateSourceProgress(rollback, previous));
    assertThrows(
        IllegalStateException.class,
        () -> HostedIdentityReconciler.validateSourceProgress(substitution, previous));
    assertThrows(
        IllegalStateException.class,
        () -> HostedIdentityReconciler.validateSourceProgress(objectRollback, previous));
    assertEquals(
        "replacement certificate reused the prior public key",
        assertThrows(
                IllegalStateException.class,
                () -> HostedIdentityReconciler.validateSourceProgress(reusedKey, previous))
            .getMessage());
  }

  @Test
  void everyManagedTransportRoleRequiresIndependentLeafKeyMaterial() {
    var ingress = material(1, 1, "1".repeat(64), "ingress");
    var telnet = material(1, 1, "2".repeat(64), "telnet");
    var gateway = material(1, 1, "3".repeat(64), "gateway");
    var bridge = material(1, 1, "4".repeat(64), "bridge");
    var grpc = material(1, 1, "5".repeat(64), "grpc");

    HostedIdentityReconciler.validateDistinctIdentities(ingress, telnet, gateway, bridge, grpc);
    assertThrows(
        IllegalStateException.class,
        () ->
            HostedIdentityReconciler.validateDistinctIdentities(
                ingress, telnet, gateway, bridge, material(2, 2, "4".repeat(64), "reused")));
  }

  private static CertificateMaterialService.RoleMaterial material(
      long generation, long objectGeneration, String spki, String certificate) {
    return new CertificateMaterialService.RoleMaterial(
        "ingress",
        new SecretBuilder()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", encoded(certificate)))
            .build(),
        new SecretMaterialValidator.MaterialSummary(
            "3".repeat(64), spki, Instant.EPOCH, Instant.MAX, "4".repeat(64)),
        generation,
        objectGeneration,
        "cert-manager",
        "source-ready");
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertReadinessStatus(
      HostedIdentityReconciler.ReadinessStatus status,
      HostedEnvironmentIdentityStatus.Phase phase,
      String reason,
      String message,
      boolean ready) {
    assertEquals(phase, status.phase());
    assertEquals(reason, status.reason());
    assertEquals(message, status.message());
    assertEquals(ready, status.ready());
  }

  private static HostedEnvironmentIdentity resource() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(1L)
            .build());
    resource.setSpec(
        new net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec());
    return resource;
  }
}
