package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ReplaceDeletable;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DeploymentRolloutServiceTest {
  @Test
  void mixedRevisionEditPreservesCurrentRoleAndUpdatesOnlyStaleRole() {
    Deployment deployment =
        readyDeployment(
            "tcp-proxy-service",
            Map.of(
                HostedIdentityContract.TELNET_REVISION_ANNOTATION,
                "telnet-old",
                HostedIdentityContract.GRPC_REVISION_ANNOTATION,
                "grpc-current"),
            3L);

    Deployment edited =
        DeploymentRolloutService.applyRevisions(
            deployment, Map.of(HostedIdentityContract.TELNET_REVISION_ANNOTATION, "telnet-new"));

    Map<String, String> annotations = edited.getSpec().getTemplate().getMetadata().getAnnotations();
    assertEquals("telnet-new", annotations.get(HostedIdentityContract.TELNET_REVISION_ANNOTATION));
    assertEquals("grpc-current", annotations.get(HostedIdentityContract.GRPC_REVISION_ANNOTATION));
  }

  @Test
  void syncRejectsNullRevisionsAtTheBoundary() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    DeploymentRolloutService service = new DeploymentRolloutService();

    IllegalArgumentException telnetFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.sync(client, plan, null, "grpc-revision", () -> true));
    IllegalArgumentException grpcFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.sync(client, plan, "telnet-revision", null, () -> true));

    assertEquals("telnet revision is required", telnetFailure.getMessage());
    assertEquals("gRPC revision is required", grpcFailure.getMessage());
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void tcpProxyTelnetAndGrpcChangesUseOneCasReplaceAndConvergeTogether() {
    EnvironmentIdentityPlan plan = planWithConsumers("tcp-proxy-service", "account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> account = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(runtimeDeployments.withName("account-service")).thenReturn(account);
    Deployment oldProxy = readyDeployment("tcp-proxy-service", Map.of("other", "keep"), 3L);
    Deployment readyAccount =
        readyDeployment(
            "account-service",
            Map.of(HostedIdentityContract.GRPC_REVISION_ANNOTATION, "grpc-new"),
            3L);
    when(proxy.get()).thenReturn(oldProxy);
    when(account.get()).thenReturn(readyAccount);
    ReplaceDeletable<Deployment> lockedProxy = mock(ReplaceDeletable.class);
    when(proxy.lockResourceVersion("rv-3")).thenReturn(lockedProxy);
    DeploymentRolloutService service = new DeploymentRolloutService();

    DeploymentRolloutService.RolloutResult first =
        service.sync(client, plan, "telnet-new", "grpc-new", () -> true);

    assertEquals(false, first.ready());
    assertEquals(false, first.telnetReady());
    assertEquals(false, first.grpcReady());
    ArgumentCaptor<Deployment> replacement = ArgumentCaptor.forClass(Deployment.class);
    verify(lockedProxy, times(1)).replace(replacement.capture());
    Deployment converged = replacement.getValue();
    Map<String, String> annotations =
        converged.getSpec().getTemplate().getMetadata().getAnnotations();
    assertEquals("keep", annotations.get("other"));
    assertEquals("telnet-new", annotations.get(HostedIdentityContract.TELNET_REVISION_ANNOTATION));
    assertEquals("grpc-new", annotations.get(HostedIdentityContract.GRPC_REVISION_ANNOTATION));
    assertEquals(
        oldProxy.getMetadata().getResourceVersion(), converged.getMetadata().getResourceVersion());
    verify(proxy).lockResourceVersion("rv-3");

    converged.getMetadata().setGeneration(4L);
    converged.getStatus().setObservedGeneration(3L);
    when(proxy.get()).thenReturn(converged);
    DeploymentRolloutService.RolloutResult staleObservedGeneration =
        service.sync(client, plan, "telnet-new", "grpc-new", () -> true);

    assertEquals(false, staleObservedGeneration.ready());
    assertEquals(false, staleObservedGeneration.telnetReady());
    assertEquals(false, staleObservedGeneration.grpcReady());

    converged.getStatus().setObservedGeneration(4L);
    DeploymentRolloutService.RolloutResult second =
        service.sync(client, plan, "telnet-new", "grpc-new", () -> true);

    assertEquals(true, second.ready());
    assertEquals(true, second.telnetReady());
    assertEquals(true, second.grpcReady());
    verify(lockedProxy, times(1)).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(account, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void revisionCasConflictIsRetryableAndKeepsGuardPassed() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> account = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(runtimeDeployments.withName("account-service")).thenReturn(account);
    Deployment observed = readyDeployment("tcp-proxy-service", Map.of(), 3L);
    Deployment readyAccount =
        readyDeployment(
            "account-service",
            Map.of(HostedIdentityContract.GRPC_REVISION_ANNOTATION, "grpc-new"),
            3L);
    when(proxy.get()).thenReturn(observed);
    when(account.get()).thenReturn(readyAccount);
    ReplaceDeletable<Deployment> lockedProxy = mock(ReplaceDeletable.class);
    when(proxy.lockResourceVersion("rv-3")).thenReturn(lockedProxy);
    doThrow(new KubernetesClientException("conflict", 409, null))
        .when(lockedProxy)
        .replace(org.mockito.ArgumentMatchers.any(Deployment.class));

    DeploymentRolloutService.RolloutResult result =
        new DeploymentRolloutService().sync(client, plan, "telnet-new", "grpc-new", () -> true);

    assertEquals(false, result.ready());
    assertEquals(false, result.telnetReady());
    assertEquals(true, result.grpcReady());
    verify(proxy).lockResourceVersion("rv-3");
    verify(lockedProxy).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(account).get();
    verify(account, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    InOrder order = inOrder(proxy, lockedProxy, account);
    order.verify(proxy).get();
    order.verify(proxy).lockResourceVersion("rv-3");
    order.verify(lockedProxy).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    order.verify(account).get();
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void revisionCasRequiresObservedResourceVersion() {
    EnvironmentIdentityPlan plan = planWithConsumers("tcp-proxy-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    Deployment observed = readyDeployment("tcp-proxy-service", Map.of(), 3L);
    observed.getMetadata().setResourceVersion(null);
    when(proxy.get()).thenReturn(observed);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new DeploymentRolloutService()
                    .sync(client, plan, "telnet-new", "grpc-new", () -> true));

    assertEquals("Deployment has no resourceVersion for CAS", failure.getMessage());
    verify(proxy, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void readinessRemainsIndependentAcrossTelnetAndGrpcConsumers() {
    EnvironmentIdentityPlan plan = planWithConsumers("tcp-proxy-service", "account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> account = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(runtimeDeployments.withName("account-service")).thenReturn(account);
    when(proxy.get())
        .thenReturn(
            readyDeployment(
                "tcp-proxy-service",
                Map.of(
                    HostedIdentityContract.TELNET_REVISION_ANNOTATION,
                    "telnet-current",
                    HostedIdentityContract.GRPC_REVISION_ANNOTATION,
                    "grpc-current"),
                3L));
    when(account.get()).thenReturn(null);

    DeploymentRolloutService.RolloutResult result =
        new DeploymentRolloutService()
            .sync(client, plan, "telnet-current", "grpc-current", () -> true);

    assertEquals(false, result.ready());
    assertEquals(true, result.telnetReady());
    assertEquals(false, result.grpcReady());
    verify(proxy, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(account, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void readinessRequiresTelnetEvenWhenGrpcConsumerIsReady() {
    EnvironmentIdentityPlan plan = planWithConsumers("tcp-proxy-service", "account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> account = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(runtimeDeployments.withName("account-service")).thenReturn(account);
    when(proxy.get()).thenReturn(null);
    when(account.get())
        .thenReturn(
            readyDeployment(
                "account-service",
                Map.of(HostedIdentityContract.GRPC_REVISION_ANNOTATION, "grpc-current"),
                3L));

    DeploymentRolloutService.RolloutResult result =
        new DeploymentRolloutService()
            .sync(client, plan, "telnet-current", "grpc-current", () -> true);

    assertEquals(false, result.ready());
    assertEquals(false, result.telnetReady());
    assertEquals(false, result.grpcReady());
    verify(proxy, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(account, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void runtimeProfileFenceStopsSyncBeforeEditAndLaterDeploymentReads() {
    EnvironmentIdentityPlan plan = planWithConsumers("tcp-proxy-service", "account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> account = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(runtimeDeployments.withName("account-service")).thenReturn(account);
    when(proxy.get()).thenReturn(readyDeployment("tcp-proxy-service", Map.of("other", "keep"), 3L));
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    DeploymentRolloutService.RolloutResult result =
        new DeploymentRolloutService()
            .sync(client, plan, "telnet-new", "grpc-new", () -> guardCalls.getAndIncrement() == 0);

    assertEquals(false, result.ready());
    assertEquals(false, result.telnetReady());
    assertEquals(false, result.grpcReady());
    verify(proxy).get();
    verify(proxy, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(account, never()).get();
    verify(account, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void runtimeProfileFenceExceptionStopsSyncBeforeEditAndLaterDeploymentReads() {
    EnvironmentIdentityPlan plan = planWithConsumers("tcp-proxy-service", "account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> account = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(runtimeDeployments.withName("account-service")).thenReturn(account);
    when(proxy.get()).thenReturn(readyDeployment("tcp-proxy-service", Map.of("other", "keep"), 3L));
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new DeploymentRolloutService()
                    .sync(
                        client,
                        plan,
                        "telnet-new",
                        "grpc-new",
                        () -> {
                          if (guardCalls.getAndIncrement() == 0) {
                            return true;
                          }
                          throw new IllegalStateException("runtime profile fence");
                        }));

    assertEquals("runtime profile fence", failure.getMessage());
    assertEquals(2, guardCalls.get());
    verify(proxy).get();
    verify(proxy, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(account, never()).get();
    verify(account, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  void retirementProfileFenceWithholdsAllBridgeMutations() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);

    DeploymentRolloutService.RetirementResult result =
        new DeploymentRolloutService().stopBridges(client, plan, () -> false);

    assertEquals(false, result.stopped());
    assertEquals(false, result.gatewayStopped());
    assertEquals(false, result.proxyStopped());
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void runtimeProfileFenceStopsRetirementAfterDeploymentReadBeforeEdit() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> gateway = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("spring-cloud-gateway")).thenReturn(gateway);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(gateway.get()).thenReturn(readyDeployment("spring-cloud-gateway", Map.of(), 3L));
    java.util.concurrent.atomic.AtomicInteger guardCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    DeploymentRolloutService.RetirementResult result =
        new DeploymentRolloutService()
            .stopBridges(client, plan, () -> guardCalls.getAndIncrement() == 0);

    assertEquals(false, result.stopped());
    assertEquals(false, result.gatewayStopped());
    assertEquals(false, result.proxyStopped());
    verify(gateway).get();
    verify(gateway, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(proxy, never()).get();
    verify(proxy, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void retirementCasRequiresObservedResourceVersion() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> gateway = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("spring-cloud-gateway")).thenReturn(gateway);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    Deployment observed = readyDeployment("spring-cloud-gateway", Map.of(), 3L);
    observed.getMetadata().setResourceVersion(null);
    when(gateway.get()).thenReturn(observed);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new DeploymentRolloutService().stopBridges(client, plan, () -> true));

    assertEquals("Deployment has no resourceVersion for CAS", failure.getMessage());
    verify(gateway).get();
    verify(gateway, never()).lockResourceVersion(org.mockito.ArgumentMatchers.anyString());
    verify(gateway, never()).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(proxy, never()).get();
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void stopBridgesCasReplacesBothDeploymentsThenObservesBothStopped() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> gateway = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("spring-cloud-gateway")).thenReturn(gateway);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    Deployment runningGateway = readyDeployment("spring-cloud-gateway", Map.of(), 3L);
    Deployment runningProxy = readyDeployment("tcp-proxy-service", Map.of(), 3L);
    when(gateway.get()).thenReturn(runningGateway);
    when(proxy.get()).thenReturn(runningProxy);
    ReplaceDeletable<Deployment> lockedGateway = mock(ReplaceDeletable.class);
    ReplaceDeletable<Deployment> lockedProxy = mock(ReplaceDeletable.class);
    when(gateway.lockResourceVersion("rv-3")).thenReturn(lockedGateway);
    when(proxy.lockResourceVersion("rv-3")).thenReturn(lockedProxy);

    DeploymentRolloutService service = new DeploymentRolloutService();
    DeploymentRolloutService.RetirementResult first = service.stopBridges(client, plan, () -> true);

    assertEquals(false, first.stopped());
    assertEquals(false, first.gatewayStopped());
    assertEquals(false, first.proxyStopped());
    ArgumentCaptor<Deployment> gatewayReplacement = ArgumentCaptor.forClass(Deployment.class);
    ArgumentCaptor<Deployment> proxyReplacement = ArgumentCaptor.forClass(Deployment.class);
    verify(gateway).lockResourceVersion("rv-3");
    verify(proxy).lockResourceVersion("rv-3");
    verify(lockedGateway).replace(gatewayReplacement.capture());
    verify(lockedProxy).replace(proxyReplacement.capture());
    Deployment editedGateway = gatewayReplacement.getValue();
    Deployment editedProxy = proxyReplacement.getValue();
    assertEquals(0, editedGateway.getSpec().getReplicas());
    assertEquals(0, editedProxy.getSpec().getReplicas());
    assertEquals(
        runningGateway.getMetadata().getResourceVersion(),
        editedGateway.getMetadata().getResourceVersion());
    assertEquals(
        runningProxy.getMetadata().getResourceVersion(),
        editedProxy.getMetadata().getResourceVersion());

    Deployment stoppedGateway = stoppedDeployment(editedGateway);
    Deployment stoppedProxy = stoppedDeployment(editedProxy);
    when(gateway.get()).thenReturn(stoppedGateway);
    when(proxy.get()).thenReturn(stoppedProxy);

    DeploymentRolloutService.RetirementResult second =
        service.stopBridges(client, plan, () -> true);

    assertEquals(true, second.stopped());
    assertEquals(true, second.gatewayStopped());
    assertEquals(true, second.proxyStopped());
    verify(gateway, times(2)).get();
    verify(proxy, times(2)).get();
    verify(lockedGateway, times(1)).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(lockedProxy, times(1)).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void retirementCasConflictIsRetryableAndAllowsNextBridgeRead() {
    EnvironmentIdentityPlan plan = planWithConsumers("account-service");
    KubernetesClient client = mock(KubernetesClient.class);
    AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
    MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
        mock(MixedOperation.class);
    NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
        runtimeDeployments = mock(NonNamespaceOperation.class);
    RollableScalableResource<Deployment> gateway = mock(RollableScalableResource.class);
    RollableScalableResource<Deployment> proxy = mock(RollableScalableResource.class);
    when(client.apps()).thenReturn(apps);
    when(apps.deployments()).thenReturn(deployments);
    when(deployments.inNamespace(plan.runtimeNamespace())).thenReturn(runtimeDeployments);
    when(runtimeDeployments.withName("spring-cloud-gateway")).thenReturn(gateway);
    when(runtimeDeployments.withName("tcp-proxy-service")).thenReturn(proxy);
    when(gateway.get()).thenReturn(readyDeployment("spring-cloud-gateway", Map.of(), 3L));
    when(proxy.get()).thenReturn(null);
    ReplaceDeletable<Deployment> lockedGateway = mock(ReplaceDeletable.class);
    when(gateway.lockResourceVersion("rv-3")).thenReturn(lockedGateway);
    doThrow(new KubernetesClientException("conflict", 409, null))
        .when(lockedGateway)
        .replace(org.mockito.ArgumentMatchers.any(Deployment.class));

    DeploymentRolloutService.RetirementResult result =
        new DeploymentRolloutService().stopBridges(client, plan, () -> true);

    assertEquals(false, result.stopped());
    assertEquals(false, result.gatewayStopped());
    assertEquals(true, result.proxyStopped());
    verify(gateway).lockResourceVersion("rv-3");
    verify(lockedGateway).replace(org.mockito.ArgumentMatchers.any(Deployment.class));
    verify(proxy).get();
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

  private static Deployment readyDeployment(
      String name, Map<String, String> annotations, long generation) {
    return new DeploymentBuilder()
        .withNewMetadata()
        .withName(name)
        .withGeneration(generation)
        .withResourceVersion("rv-" + generation)
        .endMetadata()
        .withNewSpec()
        .withReplicas(1)
        .withNewTemplate()
        .withNewMetadata()
        .withAnnotations(annotations)
        .endMetadata()
        .endTemplate()
        .endSpec()
        .withNewStatus()
        .withObservedGeneration(generation)
        .withUpdatedReplicas(1)
        .withAvailableReplicas(1)
        .withReplicas(1)
        .endStatus()
        .build();
  }

  private static Deployment stoppedDeployment(Deployment deployment) {
    Deployment stopped = new DeploymentBuilder(deployment).build();
    stopped.getSpec().setReplicas(0);
    stopped.getStatus().setUpdatedReplicas(0);
    stopped.getStatus().setAvailableReplicas(0);
    stopped.getStatus().setReadyReplicas(0);
    stopped.getStatus().setReplicas(0);
    return stopped;
  }

  private static EnvironmentIdentityPlan planWithConsumers(String... consumers) {
    EnvironmentIdentityPlan plan =
        new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
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
        List.of(consumers));
  }
}
