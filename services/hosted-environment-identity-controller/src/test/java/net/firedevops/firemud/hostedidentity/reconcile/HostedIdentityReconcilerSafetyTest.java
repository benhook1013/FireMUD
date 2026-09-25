package net.firedevops.firemud.hostedidentity.reconcile;

import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.CERTIFICATE_PENDING;
import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.SERIALIZED_DEFERRED;
import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.SERIALIZED_DEFERRED_DRIFT;
import static net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService.RoleMaterialState.SOURCE_READY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.firedevops.firemud.hostedidentity.admission.AdmissionValidator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.CertificateMaterialService;
import net.firedevops.firemud.hostedidentity.kubernetes.DeploymentRolloutService;
import net.firedevops.firemud.hostedidentity.kubernetes.HostedIdentityScopeService;
import net.firedevops.firemud.hostedidentity.kubernetes.ResourceContexts;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.kubernetes.SecretProjectionService;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.model.HostedCondition;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentitySpec;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.probe.ServedEnvironmentProbe;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import net.firedevops.firemud.hostedidentity.security.SecretMaterialValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.yaml.snakeyaml.Yaml;

@ExtendWith(OutputCaptureExtension.class)
class HostedIdentityReconcilerSafetyTest {
  @Test
  void deferredDriftReturnsCanonicalSecretRevisionWithoutWritingAProjection() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    EnvironmentIdentityPlanner planner = new EnvironmentIdentityPlanner(properties);
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    SecretProjectionService projectionService = mock(SecretProjectionService.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            client,
            mock(AdmissionValidator.class),
            planner,
            mock(CertificateMaterialService.class),
            projectionService,
            mock(HostedIdentityScopeService.class),
            mock(RuntimeProfileService.class),
            mock(DeploymentRolloutService.class),
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(planner),
            properties);
    Secret accepted =
        new SecretBuilder()
            .withNewMetadata()
            .withName(plan.telnetSecretName())
            .withNamespace(plan.identityNamespace())
            .withLabels(
                HostedIdentityContract.managedLabels(
                    plan.name(), HostedIdentityContract.TELNET_ROLE))
            .endMetadata()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", "accepted", "tls.key", "accepted"))
            .build();
    String certificateFingerprint = "1".repeat(64);
    var material =
        new CertificateMaterialService.RoleMaterial(
            HostedIdentityContract.TELNET_ROLE,
            accepted,
            new SecretMaterialValidator.MaterialSummary(
                certificateFingerprint, "2".repeat(64), Instant.EPOCH, Instant.MAX, "3".repeat(64)),
            1,
            1,
            "cert-manager",
            SERIALIZED_DEFERRED_DRIFT);

    SecretProjectionService.ProjectionResult result =
        reconciler.project(
            plan,
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true),
            material,
            HostedIdentityContract.TELNET_ROLE);

    assertEquals("serialized-deferred-drift", result.state());
    assertEquals(
        SecretProjectionService.revisionForRole(
            HostedIdentityContract.TELNET_ROLE, accepted.getData()),
        result.revision());
    assertEquals(false, result.isSynced());
    verifyNoInteractions(projectionService);
  }

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
  void protectedPublicationProjectionMustBeAcceptedBeforeReadiness() {
    DeploymentHeadGateFixture fixture =
        new DeploymentHeadGateFixture(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true));
    when(fixture.rollout.sync(
            org.mockito.ArgumentMatchers.eq(fixture.client),
            org.mockito.ArgumentMatchers.eq(fixture.plan),
            anyString(),
            anyString(),
            anyString(),
            anyMap(),
            any()))
        .thenReturn(new DeploymentRolloutService.RolloutResult(true, true, true));
    when(fixture.probes.probe(
            any(),
            anyString(),
            anyInt(),
            anyString(),
            anyString(),
            any(Secret.class),
            anyString(),
            any(Secret.class),
            anyString()))
        .thenReturn(new ServedEnvironmentProbe.ProbeResult(true, "served"));
    Map<String, SecretProjectionService.ProjectionResult> acknowledgedProjections =
        new java.util.LinkedHashMap<>();
    when(fixture.projections.acknowledge(
            any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String role = invocation.getArgument(2, String.class);
              String revision = invocation.getArgument(3, String.class);
              SecretProjectionService.ProjectionResult result =
                  HostedIdentityContract.grpcPublicationRole("game-logic-service").equals(role)
                      ? SecretProjectionService.ProjectionResult.awaiting(
                          "awaiting-acceptance", revision)
                      : SecretProjectionService.ProjectionResult.synced(revision);
              acknowledgedProjections.put(role, result);
              return result;
            });

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedEnvironmentIdentityStatus status = result.getResource().orElseThrow().getStatus();
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Syncing, status.getPhase());
    assertEquals("AwaitingAcceptance", status.getConditions().get(0).getReason());
    assertEquals("awaiting-acceptance", status.getConditions().get(0).getMessage());
    assertEquals(
        HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS.stream()
            .map(HostedIdentityContract::grpcPublicationRole)
            .collect(java.util.stream.Collectors.toSet()),
        status.getGrpcPublication().keySet());
    assertTrue(status.getGrpcPublication().values().stream().allMatch(java.util.Objects::nonNull));
    assertEquals(
        HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS.size() + 5,
        acknowledgedProjections.size());
    assertEquals(
        "awaiting-acceptance",
        acknowledgedProjections
            .get(HostedIdentityContract.grpcPublicationRole("game-logic-service"))
            .state());
    assertTrue(
        acknowledgedProjections.entrySet().stream()
            .filter(
                entry ->
                    !entry
                        .getKey()
                        .equals(HostedIdentityContract.grpcPublicationRole("game-logic-service")))
            .allMatch(entry -> entry.getValue().isSynced()));
    verify(fixture.rollout)
        .sync(
            org.mockito.ArgumentMatchers.eq(fixture.client),
            org.mockito.ArgumentMatchers.eq(fixture.plan),
            anyString(),
            anyString(),
            anyString(),
            anyMap(),
            any());
    verify(fixture.probes)
        .probe(
            any(),
            anyString(),
            anyInt(),
            anyString(),
            anyString(),
            any(Secret.class),
            anyString(),
            any(Secret.class),
            anyString());
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
  void rolloutAndServedProofStayBlockedUntilSuccessfulDeploymentMatchesTheRequest() {
    var beforeHelm =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            null,
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    var staleDeployment =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "b".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    var deployed =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);

    assertReadinessStatus(
        HostedIdentityReconciler.deploymentHeadStatus(beforeHelm),
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RuntimeDeploymentPending",
        "waiting for successful Helm deployment evidence",
        false);
    assertReadinessStatus(
        HostedIdentityReconciler.deploymentHeadStatus(staleDeployment),
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RuntimeDeploymentPending",
        "deployed runtime head does not match the requested head",
        false);
    assertReadinessStatus(
        HostedIdentityReconciler.deploymentHeadStatus(deployed),
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        "RuntimeDeploymentCurrent",
        "deployed runtime head matches the requested head",
        true);
  }

  @Test
  void reconcileBlocksRolloutAndServedProofUntilTheExactDeploymentHeadIsRecorded() {
    for (var profile :
        java.util.List.of(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                null,
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true),
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "b".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true))) {
      DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(profile);

      UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

      assertEquals(
          HostedEnvironmentIdentityStatus.Phase.Verifying,
          result.getResource().orElseThrow().getStatus().getPhase());
      assertEquals(
          "RuntimeDeploymentPending",
          result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
      verifyNoInteractions(fixture.rollout, fixture.probes);
    }

    DeploymentHeadGateFixture aligned =
        new DeploymentHeadGateFixture(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true));
    when(aligned.rollout.sync(
            org.mockito.ArgumentMatchers.eq(aligned.client),
            org.mockito.ArgumentMatchers.eq(aligned.plan),
            anyString(),
            anyString(),
            anyString(),
            anyMap(),
            any()))
        .thenThrow(new IllegalStateException("downstream-rollout-boundary"));

    UpdateControl<HostedEnvironmentIdentity> result = aligned.reconcile();

    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "downstream-rollout-boundary",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getMessage());
    verify(aligned.rollout)
        .sync(
            org.mockito.ArgumentMatchers.eq(aligned.client),
            org.mockito.ArgumentMatchers.eq(aligned.plan),
            anyString(),
            anyString(),
            anyString(),
            anyMap(),
            any());
    verifyNoInteractions(aligned.probes);
  }

  @Test
  void materializationPipelineStopsAtFirstPendingRoleAndRetainsReadyPredecessors() {
    DeploymentHeadGateFixture fixture =
        new DeploymentHeadGateFixture(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true));
    when(fixture.batch.telnet())
        .thenReturn(
            new CertificateMaterialService.RoleMaterial(
                HostedIdentityContract.TELNET_ROLE, null, null, 0, 0, "", CERTIFICATE_PENDING));

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedEnvironmentIdentityStatus status = result.getResource().orElseThrow().getStatus();
    assertEquals(HostedEnvironmentIdentityStatus.Phase.WaitingForCertificate, status.getPhase());
    assertEquals("certificate-pending", status.getConditions().get(0).getReason());
    assertEquals("1".repeat(64), status.getIngress().getSpkiSha256());
    assertNull(status.getTelnet());
    var orderedMaterialization = inOrder(fixture.batch);
    orderedMaterialization.verify(fixture.batch).ingress();
    orderedMaterialization.verify(fixture.batch).telnet();
    verify(fixture.batch, never()).gatewayInternalWs();
    verify(fixture.batch, never()).tcpProxyBridge();
    verify(fixture.batch, never()).grpc(any());
    verifyNoInteractions(fixture.projections, fixture.rollout, fixture.probes);
  }

  @Test
  void readinessBoundaryRejectsAChangedRuntimeTupleBeforeAcknowledgement() {
    var initial =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    var changed =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32015,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(initial);
    when(fixture.runtime.read(fixture.client, fixture.plan)).thenReturn(initial, initial, changed);
    when(fixture.rollout.sync(
            org.mockito.ArgumentMatchers.eq(fixture.client),
            org.mockito.ArgumentMatchers.eq(fixture.plan),
            anyString(),
            anyString(),
            anyString(),
            anyMap(),
            any()))
        .thenReturn(new DeploymentRolloutService.RolloutResult(true, true, true));
    when(fixture.probes.probe(
            any(),
            anyString(),
            anyInt(),
            anyString(),
            anyString(),
            any(Secret.class),
            anyString(),
            any(Secret.class),
            anyString()))
        .thenReturn(new ServedEnvironmentProbe.ProbeResult(true, "served"));

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "RuntimeIdentityChanged",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
    assertTrue(
        result
            .getResource()
            .orElseThrow()
            .getStatus()
            .getConditions()
            .get(0)
            .getMessage()
            .contains("Telnet port"));
    verify(fixture.projections, never())
        .acknowledge(
            any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
  }

  @Test
  void malformedRuntimeProfileAtReadinessBoundaryFailsClosedWithoutAcknowledgement() {
    var initial =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(initial);
    when(fixture.runtime.read(fixture.client, fixture.plan))
        .thenReturn(initial, initial)
        .thenThrow(new IllegalStateException("invalid runtime profile"));
    when(fixture.rollout.sync(
            org.mockito.ArgumentMatchers.eq(fixture.client),
            org.mockito.ArgumentMatchers.eq(fixture.plan),
            anyString(),
            anyString(),
            anyString(),
            anyMap(),
            any()))
        .thenReturn(new DeploymentRolloutService.RolloutResult(true, true, true));
    when(fixture.probes.probe(
            any(),
            anyString(),
            anyInt(),
            anyString(),
            anyString(),
            any(Secret.class),
            anyString(),
            any(Secret.class),
            anyString()))
        .thenReturn(new ServedEnvironmentProbe.ProbeResult(true, "served"));

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "RuntimeProfileInvalid",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
    verify(fixture.projections, never())
        .acknowledge(
            any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
  }

  @Test
  void malformedTcpProxyServiceAtRolloutBoundaryFailsClosedBeforeRollout() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    doThrow(new IllegalStateException("invalid TCP Proxy Service"))
        .when(fixture.runtime)
        .validateTcpProxyService(fixture.client, fixture.plan, expected);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "RuntimeProfileInvalid",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
    assertTrue(
        result
            .getResource()
            .orElseThrow()
            .getStatus()
            .getConditions()
            .get(0)
            .getMessage()
            .contains("invalid TCP Proxy Service"));
    verifyNoInteractions(fixture.rollout, fixture.probes);
  }

  @Test
  void identityProjectionFenceNamesTheProtectedAction(CapturedOutput output) {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    when(fixture.runtime.read(fixture.client, fixture.plan))
        .thenReturn(expected)
        .thenThrow(new IllegalStateException("invalid runtime profile"));
    when(fixture.projections.project(
            any(),
            any(),
            anyString(),
            any(Secret.class),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any()))
        .thenAnswer(
            invocation -> {
              Runnable guard = invocation.getArgument(8);
              guard.run();
              throw new AssertionError("runtime-profile guard unexpectedly passed");
            });

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedCondition condition =
        result.getResource().orElseThrow().getStatus().getConditions().get(0);
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals("RuntimeProfileInvalid", condition.getReason());
    assertEquals(
        "runtime profile became malformed before identity projection; identity projection is withheld: invalid runtime profile",
        condition.getMessage());
    assertTrue(
        output
            .getOut()
            .contains(
                "Hosted identity reconciliation fenced for environment 'dev-demo' and runtime Namespace 'dev'"));
    assertTrue(output.getOut().contains("RuntimeProfileFenceException:"));
    assertTrue(output.getOut().contains("IllegalStateException: invalid runtime profile"));
  }

  @Test
  void unexpectedReconcileFailureLogsContextAndStackBeforeReturningBlockedStatus(
      CapturedOutput output) {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
    EnvironmentIdentityPlanner planner = new EnvironmentIdentityPlanner(properties);
    EnvironmentIdentityPlan plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    when(runtime.read(client, plan))
        .thenReturn(
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32042,
                true));
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    doThrow(new IllegalStateException("unexpected scope failure")).when(scope).ensure(client, plan);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            client,
            mock(AdmissionValidator.class),
            planner,
            mock(CertificateMaterialService.class),
            mock(SecretProjectionService.class),
            scope,
            runtime,
            mock(DeploymentRolloutService.class),
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(planner),
            properties);
    HostedEnvironmentIdentity resource = resource();
    resource.getMetadata().setFinalizers(java.util.List.of(HostedIdentityContract.FINALIZER));

    UpdateControl<HostedEnvironmentIdentity> result =
        reconciler.reconcile(resource, mock(Context.class));

    HostedEnvironmentIdentityStatus status = result.getResource().orElseThrow().getStatus();
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, status.getPhase());
    assertEquals("ReconciliationBlocked", status.getConditions().get(0).getReason());
    assertEquals("unexpected scope failure", status.getConditions().get(0).getMessage());
    assertTrue(
        output
            .getOut()
            .contains(
                "Hosted identity reconciliation failed for environment 'pr-42' and runtime Namespace 'pr-42'"));
    assertTrue(output.getOut().contains("IllegalStateException: unexpected scope failure"));
  }

  @Test
  void missingResourceMetadataReturnsReconciliationBlockedStatus() {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
    EnvironmentIdentityPlanner planner = new EnvironmentIdentityPlanner(properties);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    CertificateMaterialService certificates = mock(CertificateMaterialService.class);
    SecretProjectionService projections = mock(SecretProjectionService.class);
    HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    ServedEnvironmentProbe probes = mock(ServedEnvironmentProbe.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            mock(KubernetesClient.class),
            new AdmissionValidator(planner),
            planner,
            certificates,
            projections,
            scope,
            runtime,
            rollout,
            probes,
            new HostedStatusService(planner),
            properties);
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(null);

    UpdateControl<HostedEnvironmentIdentity> result =
        reconciler.reconcile(resource, mock(Context.class));

    HostedEnvironmentIdentityStatus status = result.getResource().orElseThrow().getStatus();
    assertTrue(result.isPatchStatus());
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, status.getPhase());
    assertEquals("ReconciliationBlocked", status.getConditions().get(0).getReason());
    assertEquals(
        "HostedEnvironmentIdentity metadata is required",
        status.getConditions().get(0).getMessage());
    assertNull(status.getObservedGeneration());
    assertNull(status.getConditions().get(0).getObservedGeneration());
    verifyNoInteractions(runtime, certificates, projections, scope, rollout, probes);
  }

  @Test
  void projectionAcknowledgementFenceNamesTheProtectedAction() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    when(fixture.runtime.read(fixture.client, fixture.plan))
        .thenReturn(expected, expected, expected)
        .thenThrow(new IllegalStateException("invalid runtime profile"));
    when(fixture.rollout.sync(any(), any(), anyString(), anyString(), anyString(), anyMap(), any()))
        .thenReturn(new DeploymentRolloutService.RolloutResult(true, true, true));
    when(fixture.probes.probe(
            any(),
            anyString(),
            anyInt(),
            anyString(),
            anyString(),
            any(Secret.class),
            anyString(),
            any(Secret.class),
            anyString()))
        .thenReturn(new ServedEnvironmentProbe.ProbeResult(true, "served"));
    when(fixture.projections.acknowledge(
            any(), any(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              Runnable guard = invocation.getArgument(7);
              guard.run();
              throw new AssertionError("runtime-profile guard unexpectedly passed");
            });

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedCondition condition =
        result.getResource().orElseThrow().getStatus().getConditions().get(0);
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals("RuntimeProfileInvalid", condition.getReason());
    assertEquals(
        "runtime profile became malformed before projection acknowledgement; projection acknowledgement is withheld: invalid runtime profile",
        condition.getMessage());
  }

  @Test
  void informerAndConfiguredControlNamespaceMustStayCanonical() {
    ControllerConfiguration configuration =
        HostedIdentityReconciler.class.getAnnotation(ControllerConfiguration.class);
    assertEquals(
        java.util.List.of(HostedIdentityContract.CONTROL_NAMESPACE),
        java.util.Arrays.asList(configuration.informer().namespaces()));
    assertEquals(true, configuration.triggerReconcilerOnAllEvents());

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
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
    KubernetesClient client = mock(KubernetesClient.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    when(runtime.read(any(), any())).thenReturn(RuntimeProfileService.RuntimeProfile.absent());
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
            runtime,
            mock(DeploymentRolloutService.class),
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);
    HostedEnvironmentIdentity resource = resource();
    resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);

    UpdateControl<HostedEnvironmentIdentity> published = reconciler.reconcile(resource, context);

    assertEquals(true, published.isPatchStatus());
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retired,
        published.getResource().orElseThrow().getStatus().getPhase());
    verify(operations, never()).removeFinalizer(HostedIdentityContract.FINALIZER);

    UpdateControl<HostedEnvironmentIdentity> waitingForDeletion =
        reconciler.reconcile(resource, context);

    assertEquals(true, waitingForDeletion.isNoUpdate());
    assertEquals(
        java.util.Optional.of(properties.getReconcileInterval().toMillis()),
        waitingForDeletion.getScheduleDelay());
    verify(operations, never()).removeFinalizer(HostedIdentityContract.FINALIZER);

    resource.getMetadata().setDeletionTimestamp(Instant.now().toString());
    UpdateControl<HostedEnvironmentIdentity> deleted = reconciler.reconcile(resource, context);

    assertEquals(true, deleted.isNoUpdate());
    assertEquals(false, deleted.isPatchStatus());
    verify(operations).removeFinalizer(HostedIdentityContract.FINALIZER);
  }

  @Test
  void retiredIntentTerminatesLiveBridgeEndpointsWithLegacyPublicProfile() {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
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
    var runtimeProfile =
        new RuntimeProfileService.RuntimeProfile(
            "runtime-uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32000,
            true);
    when(runtime.read(any(), any())).thenReturn(runtimeProfile);
    when(runtime.isValidTelnetPort(any(), anyInt())).thenReturn(true);
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    when(rollout.stopBridges(
            org.mockito.ArgumentMatchers.eq(client),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
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
    resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);
    HostedEnvironmentIdentityStatus priorStatus = new HostedEnvironmentIdentityStatus();
    HostedEnvironmentIdentityStatus.RuntimeProfile priorProfile =
        new HostedEnvironmentIdentityStatus.RuntimeProfile();
    priorProfile.setRuntimeNamespaceUid(runtimeProfile.runtimeNamespaceUid());
    priorProfile.setRequestedHeadSha(runtimeProfile.requestedHeadSha());
    priorProfile.setDeployedHeadSha(runtimeProfile.deployedHeadSha());
    priorProfile.setTelnetPort(runtimeProfile.telnetPort());
    priorStatus.setProfile(priorProfile);
    resource.setStatus(priorStatus);

    UpdateControl<HostedEnvironmentIdentity> result = reconciler.reconcile(resource, context);

    verify(rollout)
        .stopBridges(
            org.mockito.ArgumentMatchers.eq(client),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(operations, never()).removeFinalizer(HostedIdentityContract.FINALIZER);
    verifyNoInteractions(certificates, projections, scope, probes);
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retiring,
        result.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "BridgeShutdownPending",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
  }

  @Test
  void retirementWithLiveRuntimeWithoutPriorIdentityProofWithholdsBridgeShutdown() {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
    KubernetesClient client = mock(KubernetesClient.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    when(runtime.read(any(), any()))
        .thenReturn(
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32000,
                true));
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            client,
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            mock(CertificateMaterialService.class),
            mock(SecretProjectionService.class),
            mock(HostedIdentityScopeService.class),
            runtime,
            rollout,
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);
    HostedEnvironmentIdentity resource = resource();
    resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);

    UpdateControl<HostedEnvironmentIdentity> result =
        reconciler.reconcile(resource, mock(Context.class));

    verifyNoInteractions(rollout);
    assertEquals(
        "RuntimeIdentityUnproven",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
  }

  @Test
  void retirementWithMalformedLiveRuntimeProfileWithholdsBridgeShutdown() {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
    KubernetesClient client = mock(KubernetesClient.class);
    RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    when(runtime.read(any(), any()))
        .thenThrow(new IllegalStateException("invalid runtime profile"));
    DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    HostedIdentityReconciler reconciler =
        new HostedIdentityReconciler(
            client,
            mock(AdmissionValidator.class),
            new EnvironmentIdentityPlanner(properties),
            mock(CertificateMaterialService.class),
            mock(SecretProjectionService.class),
            mock(HostedIdentityScopeService.class),
            runtime,
            rollout,
            mock(ServedEnvironmentProbe.class),
            new HostedStatusService(new EnvironmentIdentityPlanner(properties)),
            properties);
    HostedEnvironmentIdentity resource = resource();
    resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);

    UpdateControl<HostedEnvironmentIdentity> result =
        reconciler.reconcile(resource, mock(Context.class));

    verifyNoInteractions(rollout);
    assertEquals(
        "RuntimeProfileInvalid",
        result.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
  }

  @Test
  void retirementWithReplacedLiveRuntimeProfileWithholdsBridgeShutdown() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    when(fixture.runtime.read(any(), any()))
        .thenReturn(
            new RuntimeProfileService.RuntimeProfile(
                "replacement-uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32000,
                true),
            RuntimeProfileService.RuntimeProfile.absent());
    when(fixture.identityNamespace.get())
        .thenReturn(fixture.buildIdentityNamespace(false), null, null);
    HostedEnvironmentIdentity resource = resource();
    resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);
    HostedEnvironmentIdentityStatus priorStatus = new HostedEnvironmentIdentityStatus();
    HostedEnvironmentIdentityStatus.RuntimeProfile priorProfile =
        new HostedEnvironmentIdentityStatus.RuntimeProfile();
    priorProfile.setRuntimeNamespaceUid("original-uid");
    priorProfile.setRequestedHeadSha("a".repeat(40));
    priorProfile.setDeployedHeadSha("a".repeat(40));
    priorProfile.setExposureMode(HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE);
    priorProfile.setTelnetPort(32000);
    priorStatus.setProfile(priorProfile);
    resource.setStatus(priorStatus);

    UpdateControl<HostedEnvironmentIdentity> mismatch =
        fixture.reconciler.reconcile(resource, mock(Context.class));

    verifyNoInteractions(fixture.rollout);
    assertEquals(
        "RuntimeIdentityChanged",
        mismatch.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
    HostedEnvironmentIdentityStatus.RuntimeProfile preserved =
        mismatch.getResource().orElseThrow().getStatus().getProfile();
    assertEquals("original-uid", preserved.getRuntimeNamespaceUid());
    assertEquals("a".repeat(40), preserved.getRequestedHeadSha());
    assertEquals("a".repeat(40), preserved.getDeployedHeadSha());
    assertEquals(32000, preserved.getTelnetPort());

    UpdateControl<HostedEnvironmentIdentity> runtimeAbsent =
        fixture.reconciler.reconcile(resource, mock(Context.class));

    verify(fixture.identityNamespace).delete();
    verifyNoInteractions(fixture.rollout);
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Retired,
        runtimeAbsent.getResource().orElseThrow().getStatus().getPhase());
    assertEquals(
        "Retired",
        runtimeAbsent.getResource().orElseThrow().getStatus().getConditions().get(0).getReason());
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
  void retirementCleansGrpcSecretWithoutReadingSyntheticGrpcCertificate() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Resource<Secret> grpcSecret =
        fixture.secret("firemud-grpc-tls", HostedIdentityContract.GRPC_ROLE);

    fixture.retire();

    verify(grpcSecret).delete();
    verify(fixture.identityCertificates, never()).withName("firemud-grpc-tls");
  }

  @Test
  void retirementDeletesEverySecretAndCertificateFromCanonicalNameLists() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    List<String> secretNames = HostedIdentityScopeService.identitySecretNames(fixture.plan);
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      String publicationSecretName = fixture.plan.grpcPublicationSourceSecretName(workload);
      assertTrue(secretNames.contains(publicationSecretName));
      assertTrue(secretNames.contains(publicationSecretName + "-previous"));
    }
    List<Resource<Secret>> secrets =
        secretNames.stream()
            .map(name -> fixture.secret(name, HostedIdentityContract.GRPC_ROLE))
            .toList();
    List<Resource<GenericKubernetesResource>> certificates =
        HostedIdentityScopeService.requiredCertificateNames(fixture.plan).stream()
            .map(fixture::certificate)
            .toList();

    fixture.retire();

    secrets.forEach(secret -> verify(secret).delete());
    certificates.forEach(certificate -> verify(certificate).delete());
  }

  @Test
  void retirementObservesNamespaceTerminationBeforeDeletingScopeObjects() {
    RetirementDeletionFixture fixture = new RetirementDeletionFixture();
    Namespace terminating = fixture.buildIdentityNamespace(true);
    when(fixture.identityNamespace.get())
        .thenReturn(fixture.buildIdentityNamespace(false), terminating, terminating);

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
    Namespace terminating = fixture.buildIdentityNamespace(true);
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
    Namespace terminating = fixture.buildIdentityNamespace(true);
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
    when(fixture.identityNamespace.get()).thenReturn(fixture.buildIdentityNamespace(true));
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
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.PAUSED);
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
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        result.getResource().orElseThrow().getStatus().getPhase());
  }

  @Test
  void observeModeIsAlsoNonMaterializing() {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.OBSERVE);
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
    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Pending,
        result.getResource().orElseThrow().getStatus().getPhase());
  }

  @Test
  void activeModeReportsRuntimeAbsenceBeforeIssuingMaterial() {
    HostedIdentityProperties properties =
        initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
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
    assertEquals(
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
    var deferredAccepted = material(3, 2, "2".repeat(64), "old", SERIALIZED_DEFERRED);
    var deferredDrift = material(3, 2, "2".repeat(64), "old", SERIALIZED_DEFERRED_DRIFT);

    assertDoesNotThrow(() -> HostedIdentityReconciler.validateSourceProgress(unchanged, previous));
    assertDoesNotThrow(() -> HostedIdentityReconciler.validateSourceProgress(advanced, previous));
    assertDoesNotThrow(
        () -> HostedIdentityReconciler.validateSourceProgress(deferredAccepted, previous));
    assertDoesNotThrow(
        () -> HostedIdentityReconciler.validateSourceProgress(deferredDrift, previous));
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
  void grpcPublicationRollbackIsRejectedBeforeRecreatingMissingRuntimeProjection() {
    String role = HostedIdentityContract.grpcPublicationRole("game-design-service");
    DeploymentHeadGateFixture fixture =
        new DeploymentHeadGateFixture(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true));
    Map<String, HostedEnvironmentIdentityStatus.RoleStatus> publicationHistory =
        new java.util.LinkedHashMap<>();
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      String publicationRole = HostedIdentityContract.grpcPublicationRole(workload);
      var persistedRole = new HostedEnvironmentIdentityStatus.RoleStatus();
      persistedRole.setRevision("sha256:" + "a".repeat(64));
      boolean rollbackTarget = publicationRole.equals(role);
      String fixtureFingerprintDigit = grpcPublicationFixtureFingerprintDigit(workload);
      persistedRole.setSourceGeneration(rollbackTarget ? 4L : 1L);
      persistedRole.setSourceObjectGeneration(rollbackTarget ? 2L : 1L);
      persistedRole.setSpkiSha256((rollbackTarget ? "b" : fixtureFingerprintDigit).repeat(64));
      persistedRole.setProvenance("cert-manager");
      persistedRole.setState("source-ready");
      publicationHistory.put(publicationRole, persistedRole);
    }
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    status.setGrpcPublication(publicationHistory);
    fixture.resource.setStatus(status);

    Secret olderSourceSecret =
        new SecretBuilder()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", encoded("older")))
            .build();
    CertificateMaterialService.RoleMaterial olderSource =
        new CertificateMaterialService.RoleMaterial(
            role,
            olderSourceSecret,
            new SecretMaterialValidator.MaterialSummary(
                "c".repeat(64), "d".repeat(64), Instant.EPOCH, Instant.MAX, "e".repeat(64)),
            3,
            2,
            "cert-manager",
            SOURCE_READY);
    when(fixture.batch.grpcPublication("game-design-service")).thenReturn(olderSource);

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedEnvironmentIdentityStatus blocked = result.getResource().orElseThrow().getStatus();
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, blocked.getPhase());
    assertEquals("ReconciliationBlocked", blocked.getConditions().get(0).getReason());
    assertEquals(
        "certificate source generation rolled back for role "
            + role
            + " (source generation 3 < prior generation 4)",
        blocked.getConditions().get(0).getMessage());
    assertEquals(4L, blocked.getGrpcPublication().get(role).getSourceGeneration());
    verifyNoInteractions(fixture.projections);
  }

  @Test
  void grpcPublicationHistoryAllowsLegacyAbsenceAndRejectsMalformedPartialMap() {
    String role = HostedIdentityContract.grpcPublicationRole("game-design-service");
    HostedEnvironmentIdentity resource = resource();
    HostedEnvironmentIdentityStatus legacyStatus = new HostedEnvironmentIdentityStatus();
    resource.setStatus(legacyStatus);
    assertNull(HostedIdentityReconciler.previousRole(resource, role));

    HostedEnvironmentIdentityStatus malformedStatus = new HostedEnvironmentIdentityStatus();
    malformedStatus.setGrpcPublication(
        Map.of(role, new HostedEnvironmentIdentityStatus.RoleStatus()));
    resource.setStatus(malformedStatus);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> HostedIdentityReconciler.previousRole(resource, role));
    assertEquals(
        "grpc publication status must contain exactly five role entries", failure.getMessage());
  }

  @Test
  void grpcPublicationHistoryAcceptsSchemaValidEmptyRoleStatus() {
    String role = HostedIdentityContract.grpcPublicationRole("game-design-service");
    HostedEnvironmentIdentity resource = resource();
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    Map<String, HostedEnvironmentIdentityStatus.RoleStatus> publicationHistory =
        new java.util.LinkedHashMap<>();
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      publicationHistory.put(
          HostedIdentityContract.grpcPublicationRole(workload),
          new HostedEnvironmentIdentityStatus.RoleStatus());
    }
    status.setGrpcPublication(publicationHistory);
    resource.setStatus(status);

    assertNotNull(HostedIdentityReconciler.previousRole(resource, role));
  }

  @Test
  void publicationRoleStatusValidationMatchesTheCrdConsumerSchema() throws IOException {
    Map<?, ?> roleProperties = publicationRoleStatusProperties();
    assertEquals(
        Set.of(
            "revision",
            "sourceGeneration",
            "sourceObjectGeneration",
            "spkiSha256",
            "provenance",
            "state"),
        roleProperties.keySet());

    HostedEnvironmentIdentityStatus.RoleStatus empty =
        new HostedEnvironmentIdentityStatus.RoleStatus();
    assertTrue(empty.isSchemaValid(), "all consumer status fields are optional in the CRD");

    assertRoleStatusExamples(
        roleProperties,
        "revision",
        List.of("A", "a".repeat(128), "A._:+/@=-"),
        List.of("", "-invalid", "a".repeat(129)));
    assertRoleStatusExamples(
        roleProperties, "sourceGeneration", List.of(1L, Long.MAX_VALUE), List.of(0L, -1L));
    assertRoleStatusExamples(
        roleProperties, "sourceObjectGeneration", List.of(1L, Long.MAX_VALUE), List.of(0L, -1L));
    assertRoleStatusExamples(
        roleProperties,
        "spkiSha256",
        List.of("a".repeat(64)),
        List.of("A".repeat(64), "a".repeat(63)));
    assertRoleStatusExamples(
        roleProperties,
        "provenance",
        List.of("A", "A".repeat(128), "Cert_manager-v2"),
        List.of("", "9source", "_source", "A".repeat(129)));
    assertRoleStatusExamples(
        roleProperties,
        "state",
        List.of("A", "A".repeat(64), "Ready_v2.-"),
        List.of("", "9state", "_state", "A".repeat(65)));

    HostedEnvironmentIdentityStatus.RoleStatus source =
        new HostedEnvironmentIdentityStatus.RoleStatus();
    source.setRevision("revision-1");
    source.setSourceGeneration(1L);
    source.setSourceObjectGeneration(2L);
    source.setSpkiSha256("a".repeat(64));
    source.setProvenance("cert-manager");
    source.setState("source-ready");
    HostedEnvironmentIdentityStatus.RoleStatus copy = source.copy();
    assertNotSame(source, copy);
    assertEquals(source.getRevision(), copy.getRevision());
    assertEquals(source.getSourceGeneration(), copy.getSourceGeneration());
    assertEquals(source.getSourceObjectGeneration(), copy.getSourceObjectGeneration());
    assertEquals(source.getSpkiSha256(), copy.getSpkiSha256());
    assertEquals(source.getProvenance(), copy.getProvenance());
    assertEquals(source.getState(), copy.getState());
    source.setRevision("changed");
    assertEquals("revision-1", copy.getRevision());
  }

  @Test
  void crdSchemaParityUsesPatternSearchAndRejectsUnsupportedConstraints() {
    Map<String, Object> unanchoredPattern =
        Map.of("type", "string", "maxLength", 32, "pattern", "status");
    assertTrue(crdSchemaAccepts(unanchoredPattern, "review-status-ready"));

    Map<String, Object> unsupportedStringConstraint =
        Map.of("type", "string", "maxLength", 32, "pattern", "status", "enum", List.of("status"));
    assertThrows(AssertionError.class, () -> crdSchemaAccepts(unsupportedStringConstraint, null));

    Map<String, Object> unsupportedIntegerConstraint =
        Map.of("type", "integer", "format", "int64", "minimum", 1L, "maximum", 2L);
    assertThrows(AssertionError.class, () -> crdSchemaAccepts(unsupportedIntegerConstraint, 1L));
  }

  private static void assertRoleStatusExamples(
      Map<?, ?> roleProperties, String field, List<?> accepted, List<?> rejected) {
    assertRoleStatusValueParity(roleProperties, field, null, true);
    for (Object value : accepted) {
      assertRoleStatusValueParity(roleProperties, field, value, true);
    }
    for (Object value : rejected) {
      assertRoleStatusValueParity(roleProperties, field, value, false);
    }
  }

  private static void assertRoleStatusValueParity(
      Map<?, ?> roleProperties, String field, Object value, boolean expected) {
    Map<?, ?> propertySchema = schemaMap(roleProperties.get(field));
    boolean schemaValid = crdSchemaAccepts(propertySchema, value);
    assertEquals(expected, schemaValid, "the CRD example expectation for " + field);

    HostedEnvironmentIdentityStatus.RoleStatus roleStatus =
        new HostedEnvironmentIdentityStatus.RoleStatus();
    switch (field) {
      case "revision" -> roleStatus.setRevision((String) value);
      case "sourceGeneration" -> roleStatus.setSourceGeneration((Long) value);
      case "sourceObjectGeneration" -> roleStatus.setSourceObjectGeneration((Long) value);
      case "spkiSha256" -> roleStatus.setSpkiSha256((String) value);
      case "provenance" -> roleStatus.setProvenance((String) value);
      case "state" -> roleStatus.setState((String) value);
      default -> throw new IllegalArgumentException("unsupported role status field: " + field);
    }
    assertEquals(
        schemaValid,
        roleStatus.isSchemaValid(),
        "RoleStatus model validation must match the CRD for " + field + " value " + value);
  }

  private static boolean crdSchemaAccepts(Map<?, ?> propertySchema, Object value) {
    Object type = propertySchema.get("type");
    if ("string".equals(type)) {
      if (!propertySchema.keySet().equals(Set.of("type", "maxLength", "pattern"))) {
        throw new AssertionError(
            "unsupported CRD consumer string property schema: " + propertySchema);
      }
      if (value == null) {
        return true;
      }
      if (!(value instanceof String stringValue)) {
        return false;
      }
      int maxLength = ((Number) propertySchema.get("maxLength")).intValue();
      String pattern = Objects.toString(propertySchema.get("pattern"));
      return stringValue.length() <= maxLength
          && Pattern.compile(pattern).matcher(stringValue).find();
    }
    if ("integer".equals(type)) {
      if (!propertySchema.keySet().equals(Set.of("type", "format", "minimum"))
          || !"int64".equals(propertySchema.get("format"))) {
        throw new AssertionError(
            "unsupported CRD consumer integer property schema: " + propertySchema);
      }
      if (value == null) {
        return true;
      }
      if (!(value instanceof Long || value instanceof Integer)) {
        return false;
      }
      long minimum = ((Number) propertySchema.get("minimum")).longValue();
      return ((Number) value).longValue() >= minimum;
    }
    throw new AssertionError("unsupported CRD consumer status property schema: " + propertySchema);
  }

  private static Map<?, ?> publicationRoleStatusProperties() throws IOException {
    Map<?, ?> root;
    try (var reader =
        Files.newBufferedReader(findRepositoryFile("k8s/hosted-identity-controller/crd.yaml"))) {
      root = schemaMap(new Yaml().load(reader));
    }
    Map<?, ?> spec = schemaMap(root.get("spec"));
    List<?> versions = (List<?>) spec.get("versions");
    Map<?, ?> version = schemaMap(versions.get(0));
    Map<?, ?> openApiSchema = schemaMap(schemaMap(version.get("schema")).get("openAPIV3Schema"));
    Map<?, ?> statusProperties =
        schemaMap(
            schemaMap(schemaMap(openApiSchema.get("properties")).get("status")).get("properties"));
    Map<?, ?> consumerSchema = schemaMap(statusProperties.get("ingress"));
    Map<?, ?> publicationSchema = schemaMap(statusProperties.get("grpcPublication"));
    assertEquals(
        consumerSchema,
        publicationSchema.get("additionalProperties"),
        "grpcPublication entries must use the shared consumer status schema");
    return schemaMap(consumerSchema.get("properties"));
  }

  private static Map<?, ?> schemaMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    throw new AssertionError("expected CRD schema mapping but found " + value);
  }

  private static Path findRepositoryFile(String relativePath) {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve(relativePath);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
        break;
      }
      directory = directory.getParent();
    }
    throw new AssertionError("could not locate repository file " + relativePath);
  }

  @Test
  void grpcPublicationHistoryRejectsNullRoleStatusWithExactCanonicalRoleSet() {
    String nullRole = HostedIdentityContract.grpcPublicationRole("entity-management-service");
    Map<String, HostedEnvironmentIdentityStatus.RoleStatus> publicationHistory =
        new java.util.LinkedHashMap<>();
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      String role = HostedIdentityContract.grpcPublicationRole(workload);
      publicationHistory.put(
          role, role.equals(nullRole) ? null : new HostedEnvironmentIdentityStatus.RoleStatus());
    }
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    status.setGrpcPublication(publicationHistory);
    HostedEnvironmentIdentity resource = resource();
    resource.setStatus(status);

    assertEquals(5, publicationHistory.size());
    assertEquals(
        HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS.stream()
            .map(HostedIdentityContract::grpcPublicationRole)
            .collect(java.util.stream.Collectors.toSet()),
        publicationHistory.keySet());
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> HostedIdentityReconciler.previousRole(resource, nullRole));
    assertEquals(
        "grpc publication status contains an invalid entry for role " + nullRole,
        failure.getMessage());
  }

  @Test
  void grpcPublicationHistoryRejectsUnexpectedKeyReplacingCanonicalRole() {
    String replacedRole = HostedIdentityContract.grpcPublicationRole("entity-management-service");
    Map<String, HostedEnvironmentIdentityStatus.RoleStatus> publicationHistory =
        new java.util.LinkedHashMap<>();
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      String role = HostedIdentityContract.grpcPublicationRole(workload);
      publicationHistory.put(role, new HostedEnvironmentIdentityStatus.RoleStatus());
    }
    publicationHistory.remove(replacedRole);
    publicationHistory.put("unexpected-role", new HostedEnvironmentIdentityStatus.RoleStatus());
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    status.setGrpcPublication(publicationHistory);
    HostedEnvironmentIdentity resource = resource();
    resource.setStatus(status);

    assertEquals(5, publicationHistory.size());
    assertFalse(publicationHistory.containsKey(replacedRole));
    assertTrue(publicationHistory.containsKey("unexpected-role"));
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> HostedIdentityReconciler.previousRole(resource, replacedRole));
    assertEquals(
        "grpc publication status must contain exactly five role entries", failure.getMessage());
  }

  @Test
  void malformedGrpcPublicationHistoryIsNormalizedWithoutBecomingHighWaterEvidence() {
    String role = HostedIdentityContract.grpcPublicationRole("game-design-service");
    String invalidRole = HostedIdentityContract.grpcPublicationRole("entity-management-service");
    String nullRole = HostedIdentityContract.grpcPublicationRole("world-management-service");
    DeploymentHeadGateFixture fixture =
        new DeploymentHeadGateFixture(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true));
    HostedEnvironmentIdentityStatus.RoleStatus validHistory =
        HostedStatusService.role(
            "sha256:" + "a".repeat(64), 4L, 2L, "b".repeat(64), "cert-manager", "source-ready");
    HostedEnvironmentIdentityStatus.RoleStatus invalidHistory =
        HostedStatusService.role(
            "invalid revision", 99L, 2L, "c".repeat(64), "cert-manager", "source-ready");
    Map<String, HostedEnvironmentIdentityStatus.RoleStatus> malformedPublication =
        new java.util.LinkedHashMap<>();
    malformedPublication.put(role, validHistory);
    malformedPublication.put(invalidRole, invalidHistory);
    malformedPublication.put(nullRole, null);
    malformedPublication.put("unexpected-role", new HostedEnvironmentIdentityStatus.RoleStatus());
    HostedEnvironmentIdentityStatus priorStatus = new HostedEnvironmentIdentityStatus();
    priorStatus.setGrpcPublication(malformedPublication);
    fixture.resource.setStatus(priorStatus);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> HostedIdentityReconciler.previousRole(fixture.resource, role));
    assertEquals(
        "grpc publication status must contain exactly five role entries", failure.getMessage());

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedEnvironmentIdentityStatus blocked = result.getResource().orElseThrow().getStatus();
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, blocked.getPhase());
    assertEquals("ReconciliationBlocked", blocked.getConditions().get(0).getReason());
    assertEquals("False", blocked.getConditions().get(0).getStatus());
    assertEquals(
        HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS.stream()
            .map(HostedIdentityContract::grpcPublicationRole)
            .collect(java.util.stream.Collectors.toSet()),
        blocked.getGrpcPublication().keySet());
    assertEquals(4L, blocked.getGrpcPublication().get(role).getSourceGeneration());
    assertNull(blocked.getGrpcPublication().get(invalidRole).getSourceGeneration());
    assertNull(blocked.getGrpcPublication().get(invalidRole).getRevision());
    assertNull(blocked.getGrpcPublication().get(nullRole).getSourceGeneration());
    assertNull(
        blocked
            .getGrpcPublication()
            .get(HostedIdentityContract.grpcPublicationRole("game-logic-service"))
            .getSourceGeneration());
    assertNull(
        blocked
            .getGrpcPublication()
            .get(HostedIdentityContract.grpcPublicationRole("automation-scripting-service"))
            .getSourceGeneration());
    assertTrue(blocked.getGrpcPublication().values().stream().allMatch(java.util.Objects::nonNull));
    verifyNoInteractions(fixture.projections);
  }

  @Test
  void invalidRevisionWithHighGenerationIsRejectedAndCannotBecomePublicationBaseline() {
    String role = HostedIdentityContract.grpcPublicationRole("game-design-service");
    DeploymentHeadGateFixture fixture =
        new DeploymentHeadGateFixture(
            new RuntimeProfileService.RuntimeProfile(
                "uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32016,
                true));
    Map<String, HostedEnvironmentIdentityStatus.RoleStatus> publicationHistory =
        new java.util.LinkedHashMap<>();
    for (String workload : HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS) {
      String publicationRole = HostedIdentityContract.grpcPublicationRole(workload);
      HostedEnvironmentIdentityStatus.RoleStatus history =
          HostedStatusService.role(
              "sha256:" + "a".repeat(64), 1L, 1L, "b".repeat(64), "cert-manager", "source-ready");
      publicationHistory.put(publicationRole, history);
    }
    publicationHistory.get(role).setRevision("invalid revision");
    publicationHistory.get(role).setSourceGeneration(99L);
    HostedEnvironmentIdentityStatus priorStatus = new HostedEnvironmentIdentityStatus();
    priorStatus.setGrpcPublication(publicationHistory);
    fixture.resource.setStatus(priorStatus);

    assertEquals(
        HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS.stream()
            .map(HostedIdentityContract::grpcPublicationRole)
            .collect(java.util.stream.Collectors.toSet()),
        publicationHistory.keySet());
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> HostedIdentityReconciler.previousRole(fixture.resource, role));
    assertEquals(
        "grpc publication status contains an invalid entry for role " + role, failure.getMessage());

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    HostedEnvironmentIdentityStatus blocked = result.getResource().orElseThrow().getStatus();
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, blocked.getPhase());
    assertEquals("ReconciliationBlocked", blocked.getConditions().get(0).getReason());
    assertNull(blocked.getGrpcPublication().get(role).getSourceGeneration());
    assertNull(blocked.getGrpcPublication().get(role).getRevision());
    verifyNoInteractions(fixture.projections);
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
    assertEquals(
        "controller-managed certificate identity has no SPKI digest",
        assertThrows(
                IllegalStateException.class,
                () ->
                    HostedIdentityReconciler.validateDistinctIdentities(
                        ingress, telnet, gateway, bridge, material(2, 2, null, "missing-spki")))
            .getMessage());
  }

  @Test
  void deferredBridgeDriftUsesAcceptedMaterialWithoutRereadingRuntimeSecret() {
    CertificateMaterialService.RoleMaterial deferred =
        material(3, 2, "2".repeat(64), "accepted", SERIALIZED_DEFERRED_DRIFT);
    AtomicBoolean runtimeRead = new AtomicBoolean();

    Secret selected =
        HostedIdentityReconciler.bridgeProbeMaterial(
            deferred,
            () -> {
              runtimeRead.set(true);
              return null;
            });

    assertEquals(deferred.source(), selected);
    assertEquals(false, runtimeRead.get());
  }

  @Test
  void sourceReadyBridgeUsesRuntimeProjectionMaterial() {
    CertificateMaterialService.RoleMaterial sourceReady =
        material(3, 2, "2".repeat(64), "source-ready");
    Secret runtime =
        new SecretBuilder()
            .withType("kubernetes.io/tls")
            .withData(Map.of("tls.crt", encoded("runtime")))
            .build();
    AtomicBoolean runtimeRead = new AtomicBoolean();

    Secret selected =
        HostedIdentityReconciler.bridgeProbeMaterial(
            sourceReady,
            () -> {
              runtimeRead.set(true);
              return runtime;
            });

    assertSame(runtime, selected);
    assertEquals(true, runtimeRead.get());
  }

  @Test
  void runtimeProjectionGuardRejectsBothBridgeReadsBeforeSecretAccess() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    when(fixture.runtime.read(fixture.client, fixture.plan))
        .thenReturn(RuntimeProfileService.RuntimeProfile.absent());

    for (String role :
        java.util.List.of(
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE)) {
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> fixture.reconciler.runtimeProjection(fixture.plan, expected, role));
      assertEquals(
          "runtime Namespace disappeared before runtime projection read; runtime projection read is withheld",
          failure.getMessage());
    }
    verify(fixture.client, never()).secrets();
  }

  @Test
  void absentRuntimeProjectionRemainsProbeLevelMissingMaterial() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> absent = mock(Resource.class);
    when(fixture.client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(fixture.plan.runtimeNamespace())).thenReturn(runtimeSecrets);
    when(runtimeSecrets.withName(anyString())).thenReturn(absent);
    when(absent.get()).thenReturn(null);

    for (String role :
        java.util.List.of(
            HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE,
            HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE)) {
      assertEquals(null, fixture.reconciler.runtimeProjection(fixture.plan, expected, role));
    }
  }

  @Test
  void missingBridgeProjectionMapsReconciliationToVerifyingProbeStatus() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    when(fixture.batch.tcpProxyBridge())
        .thenReturn(
            DeploymentHeadGateFixture.material(
                fixture.plan, HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE, "4"));
    when(fixture.rollout.sync(any(), any(), anyString(), anyString(), anyString(), anyMap(), any()))
        .thenReturn(new DeploymentRolloutService.RolloutResult(true, true, true));
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> runtimeSecrets =
        mock(NonNamespaceOperation.class);
    Resource<Secret> absent = mock(Resource.class);
    when(fixture.client.secrets()).thenReturn(secrets);
    when(secrets.inNamespace(fixture.plan.runtimeNamespace())).thenReturn(runtimeSecrets);
    when(runtimeSecrets.withName(fixture.plan.tcpProxyBridgeSecretName())).thenReturn(absent);
    when(absent.get()).thenReturn(null);
    when(fixture.probes.probe(
            any(),
            anyString(),
            anyInt(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.isNull(),
            anyString(),
            any(Secret.class),
            anyString()))
        .thenReturn(
            new ServedEnvironmentProbe.ProbeResult(false, "material-or-leaf-fingerprint-missing"));

    UpdateControl<HostedEnvironmentIdentity> result = fixture.reconcile();

    assertEquals(
        HostedEnvironmentIdentityStatus.Phase.Verifying,
        result.getResource().orElseThrow().getStatus().getPhase());
    HostedCondition condition =
        result.getResource().orElseThrow().getStatus().getConditions().get(0);
    assertEquals("ServedProbePending", condition.getReason());
    assertEquals("material-or-leaf-fingerprint-missing", condition.getMessage());
  }

  @Test
  void statusMaterialIsMappedByRoleRatherThanCallPosition() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    when(fixture.rollout.sync(any(), any(), anyString(), anyString(), anyString(), anyMap(), any()))
        .thenReturn(new DeploymentRolloutService.RolloutResult(false, false, false));

    HostedEnvironmentIdentityStatus status =
        fixture.reconcile().getResource().orElseThrow().getStatus();

    assertEquals("1".repeat(64), status.getIngress().getSpkiSha256());
    assertEquals("2".repeat(64), status.getTelnet().getSpkiSha256());
    assertEquals("3".repeat(64), status.getGatewayInternalWs().getSpkiSha256());
    assertEquals("4".repeat(64), status.getTcpProxyBridge().getSpkiSha256());
    assertEquals("5".repeat(64), status.getGrpc().getSpkiSha256());
    ArgumentCaptor<String> gatewayRevision = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, String>> publicationRevisions = ArgumentCaptor.forClass(Map.class);
    verify(fixture.rollout)
        .sync(
            any(),
            any(),
            anyString(),
            gatewayRevision.capture(),
            anyString(),
            publicationRevisions.capture(),
            any());
    assertEquals(
        "revision-" + HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE, gatewayRevision.getValue());
    assertEquals(
        Map.of(
            "grpc-publication-game-design-service",
            "revision-grpc-publication-game-design-service",
            "grpc-publication-world-management-service",
            "revision-grpc-publication-world-management-service",
            "grpc-publication-entity-management-service",
            "revision-grpc-publication-entity-management-service",
            "grpc-publication-game-logic-service",
            "revision-grpc-publication-game-logic-service",
            "grpc-publication-automation-scripting-service",
            "revision-grpc-publication-automation-scripting-service"),
        publicationRevisions.getValue());
    verify(fixture.runtime, times(2))
        .validateTcpProxyService(fixture.client, fixture.plan, expected);
  }

  @Test
  void runtimeProfileFencePreservesMalformedProfileCause() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32016,
            true);
    DeploymentHeadGateFixture fixture = new DeploymentHeadGateFixture(expected);
    IllegalStateException cause = new IllegalStateException("invalid runtime profile");
    when(fixture.runtime.read(fixture.client, fixture.plan)).thenThrow(cause);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.reconciler.runtimeProjection(
                    fixture.plan, expected, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE));

    assertEquals(
        "runtime profile became malformed before runtime projection read; runtime projection read is withheld: invalid runtime profile",
        failure.getMessage());
    assertSame(cause, failure.getCause());
    verify(fixture.client, never()).secrets();
  }

  private static CertificateMaterialService.RoleMaterial material(
      long generation, long objectGeneration, String spki, String certificate) {
    return material(generation, objectGeneration, spki, certificate, SOURCE_READY);
  }

  private static CertificateMaterialService.RoleMaterial material(
      long generation,
      long objectGeneration,
      String spki,
      String certificate,
      CertificateMaterialService.RoleMaterialState state) {
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
        state);
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

  private static final class DeploymentHeadGateFixture {
    private final KubernetesClient client = mock(KubernetesClient.class);
    private final HostedIdentityProperties properties;
    private final EnvironmentIdentityPlanner planner;
    private final EnvironmentIdentityPlan plan;
    private final CertificateMaterialService certificates = mock(CertificateMaterialService.class);
    private final CertificateMaterialService.MaterializationBatch batch =
        mock(CertificateMaterialService.MaterializationBatch.class);
    private final SecretProjectionService projections = mock(SecretProjectionService.class);
    private final HostedIdentityScopeService scope = mock(HostedIdentityScopeService.class);
    private final RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    private final DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
    private final ServedEnvironmentProbe probes = mock(ServedEnvironmentProbe.class);
    private final HostedIdentityReconciler reconciler;
    private final HostedEnvironmentIdentity resource;

    private DeploymentHeadGateFixture(RuntimeProfileService.RuntimeProfile runtimeProfile) {
      properties = initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);
      planner = new EnvironmentIdentityPlanner(properties);
      plan = planner.plan("dev-demo");
      when(runtime.read(client, plan)).thenReturn(runtimeProfile);

      when(certificates.beginMaterialization(client, plan)).thenReturn(batch);
      when(batch.ingress()).thenReturn(material(plan, HostedIdentityContract.INGRESS_ROLE, "1"));
      when(batch.telnet()).thenReturn(material(plan, HostedIdentityContract.TELNET_ROLE, "2"));
      when(batch.gatewayInternalWs())
          .thenReturn(material(plan, HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE, "3"));
      when(batch.tcpProxyBridge())
          .thenReturn(
              material(
                  plan,
                  HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE,
                  "4",
                  SERIALIZED_DEFERRED_DRIFT));
      when(batch.grpc(any())).thenReturn(material(plan, HostedIdentityContract.GRPC_ROLE, "5"));
      when(batch.grpcPublication(anyString()))
          .thenAnswer(
              invocation -> {
                String workload = invocation.getArgument(0, String.class);
                String fingerprintDigit = grpcPublicationFixtureFingerprintDigit(workload);
                return material(
                    plan, HostedIdentityContract.grpcPublicationRole(workload), fingerprintDigit);
              });
      when(projections.project(
              org.mockito.ArgumentMatchers.eq(client),
              org.mockito.ArgumentMatchers.eq(plan),
              anyString(),
              any(Secret.class),
              anyLong(),
              anyLong(),
              anyString(),
              anyString(),
              any()))
          .thenAnswer(
              invocation ->
                  SecretProjectionService.ProjectionResult.synced(
                      "revision-" + invocation.getArgument(2, String.class)));

      reconciler =
          new HostedIdentityReconciler(
              client,
              mock(AdmissionValidator.class),
              planner,
              certificates,
              projections,
              scope,
              runtime,
              rollout,
              probes,
              new HostedStatusService(planner),
              properties);
      resource = new HostedEnvironmentIdentity();
      resource.setMetadata(
          new ObjectMetaBuilder()
              .withName(plan.name())
              .withNamespace(HostedIdentityContract.CONTROL_NAMESPACE)
              .withGeneration(1L)
              .withFinalizers(HostedIdentityContract.FINALIZER)
              .build());
      resource.setSpec(new HostedEnvironmentIdentitySpec());
      resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Active);
    }

    private UpdateControl<HostedEnvironmentIdentity> reconcile() {
      return reconciler.reconcile(resource, mock(Context.class));
    }

    private static CertificateMaterialService.RoleMaterial material(
        EnvironmentIdentityPlan plan, String role, String fingerprintDigit) {
      return material(plan, role, fingerprintDigit, SOURCE_READY);
    }

    private static CertificateMaterialService.RoleMaterial material(
        EnvironmentIdentityPlan plan,
        String role,
        String fingerprintDigit,
        CertificateMaterialService.RoleMaterialState state) {
      Secret source =
          new SecretBuilder()
              .withNewMetadata()
              .withName(role)
              .withNamespace(plan.identityNamespace())
              .withLabels(HostedIdentityContract.managedLabels(plan.name(), role))
              .endMetadata()
              .withType("Opaque")
              .withData(Map.of("tls.crt", encoded(role)))
              .build();
      return new CertificateMaterialService.RoleMaterial(
          role,
          source,
          new SecretMaterialValidator.MaterialSummary(
              fingerprintDigit.repeat(64),
              fingerprintDigit.repeat(64),
              Instant.EPOCH,
              Instant.MAX,
              "a".repeat(64)),
          1,
          1,
          "fixture",
          state);
    }
  }

  private static final class RetirementDeletionFixture {
    private final EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(
                initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE))
            .plan("pr-42");
    private final KubernetesClient client = mock(KubernetesClient.class);
    private final Resource<Namespace> identityNamespace = mock(Resource.class);
    private final RuntimeProfileService runtime = mock(RuntimeProfileService.class);
    private final DeploymentRolloutService rollout = mock(DeploymentRolloutService.class);
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
      HostedIdentityProperties properties =
          initializedProperties(HostedIdentityProperties.ActivationMode.ACTIVE);

      NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces =
          mock(NonNamespaceOperation.class);
      Resource<Namespace> runtimeNamespace = mock(Resource.class);
      when(client.namespaces()).thenReturn(namespaces);
      when(namespaces.withName("pr-42")).thenReturn(runtimeNamespace);
      when(runtimeNamespace.get()).thenReturn(null);
      when(namespaces.withName("pr-42-identity")).thenReturn(identityNamespace);
      when(identityNamespace.get()).thenReturn(buildIdentityNamespace(false));
      when(runtime.read(any(), any())).thenReturn(RuntimeProfileService.RuntimeProfile.absent());
      when(runtime.isValidTelnetPort(any(), anyInt())).thenReturn(true);

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
              runtime,
              rollout,
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

    private Namespace buildIdentityNamespace(boolean terminating) {
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

    private Resource<GenericKubernetesResource> certificate(String name) {
      Resource<GenericKubernetesResource> certificate = mock(Resource.class);
      when(identityCertificates.withName(name)).thenReturn(certificate);
      GenericKubernetesResource resource = new GenericKubernetesResource();
      resource.setMetadata(
          new ObjectMetaBuilder()
              .withName(name)
              .withNamespace("pr-42-identity")
              .withLabels(
                  Map.of(
                      HostedIdentityContract.MANAGED_BY_LABEL,
                      HostedIdentityContract.CONTROLLER_NAME,
                      HostedIdentityContract.ENVIRONMENT_LABEL,
                      "pr-42"))
              .build());
      when(certificate.get()).thenReturn(resource);
      return certificate;
    }

    private UpdateControl<HostedEnvironmentIdentity> retire() {
      HostedEnvironmentIdentity resource = resource();
      resource.getSpec().setDesiredState(HostedEnvironmentIdentitySpec.DesiredState.Retired);
      return reconciler.reconcile(resource, mock(Context.class));
    }
  }

  private static String grpcPublicationFixtureFingerprintDigit(String workload) {
    return switch (workload) {
      case "game-design-service" -> "6";
      case "world-management-service" -> "7";
      case "entity-management-service" -> "8";
      case "game-logic-service" -> "9";
      case "automation-scripting-service" -> "a";
      default -> throw new IllegalArgumentException(workload);
    };
  }

  private static HostedIdentityProperties initializedProperties(
      HostedIdentityProperties.ActivationMode activationMode) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode(activationMode.name());
    if (activationMode == HostedIdentityProperties.ActivationMode.ACTIVE) {
      properties.setGrpcTrustAnchorSha256("a".repeat(64));
    }
    properties.afterPropertiesSet();
    return properties;
  }

  private static HostedEnvironmentIdentity resource() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(1L)
            .build());
    resource.setSpec(new HostedEnvironmentIdentitySpec());
    return resource;
  }
}
