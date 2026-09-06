package net.firedevops.firemud.hostedidentity.reconcile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.admission.AdmissionValidator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService;
import net.firedevops.firemud.hostedidentity.kubernetes.DeploymentRolloutService;
import net.firedevops.firemud.hostedidentity.kubernetes.HostedIdentityScopeService;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.kubernetes.SecretProjectionService;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;

class HostedIdentityReconcilerSafetyTest {
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
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    Context<HostedEnvironmentIdentity> context = mock(Context.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            mock(KubernetesClient.class),
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            certificates,
            mock(SecretProjectionService.class),
            scope,
            runtime,
            mock(DeploymentRolloutService.class),
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);

    UpdateControl<HostedEnvironmentIdentity> result = reconciler.reconcile(resource(), context);

    verifyNoInteractions(certificates, scope, context);
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

    assertThrows(
        IllegalStateException.class,
        () -> HostedIdentityReconciler.validateSourceProgress(rollback, previous));
    assertThrows(
        IllegalStateException.class,
        () -> HostedIdentityReconciler.validateSourceProgress(substitution, previous));
    assertThrows(
        IllegalStateException.class,
        () -> HostedIdentityReconciler.validateSourceProgress(objectRollback, previous));
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
