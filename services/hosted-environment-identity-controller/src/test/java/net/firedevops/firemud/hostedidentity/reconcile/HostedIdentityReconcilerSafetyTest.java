package net.firedevops.firemud.hostedidentity.reconcile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingList;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ResourceOperations;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.admission.AdmissionValidator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService;
import net.firedevops.firemud.hostedidentity.kubernetes.DeploymentRolloutService;
import net.firedevops.firemud.hostedidentity.kubernetes.HostedIdentityScopeService;
import net.firedevops.firemud.hostedidentity.kubernetes.ResourceContexts;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.kubernetes.SecretProjectionService;
import net.firedevops.firemud.hostedidentity.model.HostedCondition;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
  void retirementRetainsIdentityNamespaceWhenCanonicalSecretOwnershipMismatches() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Resource<Secret> ownedIngressSecret = fixture.secret("pr-42-tls", "ingress");
    Resource<Secret> mismatchedSecret = mock(Resource.class);
    when(fixture.identitySecrets.withName("firemud-grpc-tls")).thenReturn(mismatchedSecret);
    when(mismatchedSecret.get())
        .thenReturn(
            new SecretBuilder()
                .withNewMetadata()
                .withName("firemud-grpc-tls")
                .withNamespace("pr-42-identity")
                .withLabels(
                    Map.of(
                        HostedIdentityContract.MANAGED_BY_LABEL,
                        "another-controller",
                        HostedIdentityContract.ENVIRONMENT_LABEL,
                        "pr-42",
                        HostedIdentityContract.RETENTION_LABEL,
                        HostedIdentityContract.RETAINED))
                .endMetadata()
                .build());

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    assertOwnershipUncertain(result);
    verify(ownedIngressSecret, never()).delete();
    verify(mismatchedSecret, never()).delete();
    verify(fixture.identityNamespace, never()).delete();
  }

  @Test
  void retirementRetainsIdentityNamespaceWhenCanonicalCertificateOwnershipMismatches() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Resource<Secret> ownedIngressSecret = fixture.secret("pr-42-tls", "ingress");
    Resource<GenericKubernetesResource> mismatchedCertificate = mock(Resource.class);
    when(fixture.identityCertificates.withName("pr-42-tls")).thenReturn(mismatchedCertificate);
    GenericKubernetesResource certificate = new GenericKubernetesResource();
    certificate.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42-tls")
            .withNamespace("pr-42-identity")
            .withLabels(
                Map.of(
                    HostedIdentityContract.MANAGED_BY_LABEL,
                    HostedIdentityContract.CONTROLLER_NAME,
                    HostedIdentityContract.ENVIRONMENT_LABEL,
                    "another-environment"))
            .build());
    when(mismatchedCertificate.get()).thenReturn(certificate);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    assertOwnershipUncertain(result);
    verify(ownedIngressSecret, never()).delete();
    verify(mismatchedCertificate, never()).delete();
    verify(fixture.identityNamespace, never()).delete();
  }

  @Test
  void retirementObservesNamespaceTerminationBeforeDeletingScopeObjects() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Namespace terminating = fixture.identityNamespace(true);
    when(fixture.identityNamespace.get())
        .thenReturn(fixture.identityNamespace(false), terminating, terminating);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    InOrder deletionOrder =
        inOrder(fixture.identityNamespace, fixture.identityRole, fixture.identityBinding);
    deletionOrder.verify(fixture.identityNamespace).delete();
    deletionOrder.verify(fixture.identityRole).delete();
    deletionOrder.verify(fixture.identityBinding).delete();
    assertIdentityCleanupPending(result);
  }

  @Test
  void retirementDoesNotDeleteScopeWhenNamespaceDeleteFails() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    doThrow(new KubernetesClientException("timeout", 504, null))
        .when(fixture.identityNamespace)
        .delete();

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    verify(fixture.identityRole, never()).delete();
    verify(fixture.identityBinding, never()).delete();
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
  }

  @Test
  void terminatingNamespaceResumesCleanupWhenOneScopeObjectIsAlreadyMissing() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Namespace terminating = fixture.identityNamespace(true);
    when(fixture.identityNamespace.get()).thenReturn(terminating);
    when(fixture.identityRole.get()).thenReturn(null);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    verify(fixture.identityNamespace, never()).delete();
    verify(fixture.identityRole, never()).delete();
    verify(fixture.identityBinding).delete();
    assertIdentityCleanupPending(result);
  }

  @Test
  void terminatingNamespaceResumesCleanupWhenBothScopeObjectsAreAlreadyMissing() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Namespace terminating = fixture.identityNamespace(true);
    when(fixture.identityNamespace.get()).thenReturn(terminating);
    when(fixture.identityRole.get()).thenReturn(null);
    when(fixture.identityBinding.get()).thenReturn(null);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    verify(fixture.identityNamespace, never()).delete();
    verify(fixture.identityRole, never()).delete();
    verify(fixture.identityBinding, never()).delete();
    assertIdentityCleanupPending(result);
  }

  @Test
  void terminatingNamespaceRejectsMismatchedRemainingScopeOwnership() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    when(fixture.identityNamespace.get()).thenReturn(fixture.identityNamespace(true));
    when(fixture.identityRole.get()).thenReturn(null);
    when(fixture.identityBinding.get())
        .thenReturn(
            new RoleBindingBuilder()
                .withNewMetadata()
                .withLabels(
                    Map.of(
                        HostedIdentityContract.MANAGED_BY_LABEL,
                        "another-controller",
                        HostedIdentityContract.ENVIRONMENT_LABEL,
                        "pr-42"))
                .endMetadata()
                .build());

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    assertOwnershipUncertain(result);
    verify(fixture.identityNamespace, never()).delete();
    verify(fixture.identityRole, never()).delete();
    verify(fixture.identityBinding, never()).delete();
  }

  @Test
  void nonTerminatingNamespaceStillRequiresBothScopeObjects() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    when(fixture.identityRole.get()).thenReturn(null);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.retire();

    assertOwnershipUncertain(result);
    verify(fixture.identityNamespace, never()).delete();
    verify(fixture.identityBinding, never()).delete();
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
            sha256(certificate), spki, Instant.EPOCH, Instant.MAX, "4".repeat(64)),
        generation,
        objectGeneration,
        "cert-manager",
        "source-ready");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
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

  private static void assertOwnershipUncertain(UpdateControl<HostedEnvironmentIdentity> result) {
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retiring,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "IdentityOwnershipUncertain",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
  }

  private static void assertIdentityCleanupPending(
      UpdateControl<HostedEnvironmentIdentity> result) {
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retiring,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "IdentityCleanupPending",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
  }

  private static final class RetirementDeletionFixture {
    private final KubernetesClient client = mock(KubernetesClient.class);
    private final Resource<Namespace> identityNamespace = mock(Resource.class);
    private final Resource<Role> identityRole = mock(Resource.class);
    private final Resource<RoleBinding> identityBinding = mock(Resource.class);
    private final NonNamespaceOperation<Secret, SecretList, Resource<Secret>> identitySecrets =
        mock(NonNamespaceOperation.class);
    private final NonNamespaceOperation<
            GenericKubernetesResource,
            GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>>
        identityCertificates = mock(NonNamespaceOperation.class);
    private final HostedIdentityReconciler reconciler;

    private RetirementDeletionFixture() {
      HostedIdentityProperties properties = new HostedIdentityProperties();
      properties.setActivationMode("active");

      NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces =
          mock(NonNamespaceOperation.class);
      Resource<Namespace> runtimeNamespace = mock(Resource.class);
      when(client.namespaces()).thenReturn(namespaces);
      when(namespaces.withName("pr-42")).thenReturn(runtimeNamespace);
      when(runtimeNamespace.get()).thenReturn(null);
      when(namespaces.withName("pr-42-identity")).thenReturn(identityNamespace);
      when(identityNamespace.get()).thenReturn(identityNamespace(false));

      stubOwnedScope();
      stubMaterialLookups();
      reconciler =
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
    }

    private void stubOwnedScope() {
      Map<String, String> labels =
          Map.of(
              HostedIdentityContract.MANAGED_BY_LABEL,
              HostedIdentityContract.CONTROLLER_NAME,
              HostedIdentityContract.ENVIRONMENT_LABEL,
              "pr-42");
      RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
      MixedOperation<Role, RoleList, Resource<Role>> roles = mock(MixedOperation.class);
      NonNamespaceOperation<Role, RoleList, Resource<Role>> identityRoles =
          mock(NonNamespaceOperation.class);
      MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> bindings =
          mock(MixedOperation.class);
      NonNamespaceOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> identityBindings =
          mock(NonNamespaceOperation.class);
      when(client.rbac()).thenReturn(rbac);
      when(rbac.roles()).thenReturn(roles);
      when(roles.inNamespace("pr-42-identity")).thenReturn(identityRoles);
      when(identityRoles.withName("firemud-hosted-identity-scope")).thenReturn(identityRole);
      when(identityRole.get())
          .thenReturn(new RoleBuilder().withNewMetadata().withLabels(labels).endMetadata().build());
      when(rbac.roleBindings()).thenReturn(bindings);
      when(bindings.inNamespace("pr-42-identity")).thenReturn(identityBindings);
      when(identityBindings.withName("firemud-hosted-identity-scope")).thenReturn(identityBinding);
      when(identityBinding.get())
          .thenReturn(
              new RoleBindingBuilder().withNewMetadata().withLabels(labels).endMetadata().build());
    }

    private Namespace identityNamespace(boolean terminating) {
      Namespace namespace =
          new NamespaceBuilder()
              .withNewMetadata()
              .withName("pr-42-identity")
              .withLabels(
                  Map.of(
                      HostedIdentityContract.MANAGED_BY_LABEL,
                      HostedIdentityContract.CONTROLLER_NAME,
                      HostedIdentityContract.ENVIRONMENT_LABEL,
                      "pr-42",
                      HostedIdentityContract.RETENTION_LABEL,
                      HostedIdentityContract.RETAINED,
                      "firemud.dev/environment-class",
                      "pr-preview"))
              .endMetadata()
              .build();
      if (terminating) {
        namespace.getMetadata().setDeletionTimestamp(Instant.now().toString());
      }
      return namespace;
    }

    private void stubMaterialLookups() {
      MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
      Resource<Secret> absentSecret = mock(Resource.class);
      when(client.secrets()).thenReturn(secrets);
      when(secrets.inNamespace("pr-42-identity")).thenReturn(identitySecrets);
      when(identitySecrets.withName(anyString())).thenReturn(absentSecret);
      when(absentSecret.get()).thenReturn(null);

      MixedOperation<
              GenericKubernetesResource,
              GenericKubernetesResourceList,
              Resource<GenericKubernetesResource>>
          certificates = mock(MixedOperation.class);
      Resource<GenericKubernetesResource> absentCertificate = mock(Resource.class);
      when(client.genericKubernetesResources(ResourceContexts.CERTIFICATES))
          .thenReturn(certificates);
      when(certificates.inNamespace("pr-42-identity")).thenReturn(identityCertificates);
      when(identityCertificates.withName(anyString())).thenReturn(absentCertificate);
      when(absentCertificate.get()).thenReturn(null);
    }

    private Resource<Secret> secret(String name, String role) {
      Resource<Secret> secret = mock(Resource.class);
      when(identitySecrets.withName(name)).thenReturn(secret);
      when(secret.get())
          .thenReturn(
              new SecretBuilder()
                  .withNewMetadata()
                  .withName(name)
                  .withNamespace("pr-42-identity")
                  .withLabels(HostedIdentityContract.managedLabels("pr-42", role))
                  .endMetadata()
                  .build());
      return secret;
    }

    private UpdateControl<HostedEnvironmentIdentity> retire() {
      HostedEnvironmentIdentity resource = resource();
      resource
          .getSpec()
          .setDesiredState(
              net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec.DesiredState
                  .Retired);
      return reconciler.reconcile(resource, mock(Context.class));
    }
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
