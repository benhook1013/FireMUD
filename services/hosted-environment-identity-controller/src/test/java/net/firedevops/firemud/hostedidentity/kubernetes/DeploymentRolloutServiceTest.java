package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
  @SuppressWarnings({"unchecked", "rawtypes"})
  void tcpProxyTelnetAndGrpcChangesUseOneEditAndConvergeTogether() {
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
    DeploymentRolloutService service = new DeploymentRolloutService();

    DeploymentRolloutService.RolloutResult first =
        service.sync(client, plan, "telnet-new", "grpc-new", () -> true);

    assertEquals(false, first.ready());
    assertEquals(false, first.telnetReady());
    assertEquals(false, first.grpcReady());
    ArgumentCaptor<UnaryOperator<Deployment>> editor = ArgumentCaptor.forClass(UnaryOperator.class);
    verify(proxy, times(1)).edit(editor.capture());
    Deployment converged = editor.getValue().apply(new DeploymentBuilder(oldProxy).build());
    Map<String, String> annotations =
        converged.getSpec().getTemplate().getMetadata().getAnnotations();
    assertEquals("keep", annotations.get("other"));
    assertEquals("telnet-new", annotations.get(HostedIdentityContract.TELNET_REVISION_ANNOTATION));
    assertEquals("grpc-new", annotations.get(HostedIdentityContract.GRPC_REVISION_ANNOTATION));

    converged.getMetadata().setGeneration(4L);
    converged.getStatus().setObservedGeneration(4L);
    when(proxy.get()).thenReturn(converged);
    DeploymentRolloutService.RolloutResult second =
        service.sync(client, plan, "telnet-new", "grpc-new", () -> true);

    assertEquals(true, second.ready());
    assertEquals(true, second.telnetReady());
    assertEquals(true, second.grpcReady());
    verify(proxy, times(1)).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
    verify(account, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
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
    verify(proxy, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
    verify(account, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
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
    verify(proxy, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
    verify(account, never()).get();
    verify(account, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
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
    verify(gateway, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
    verify(proxy, never()).get();
    verify(proxy, never()).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Deployment>>any());
  }

  private static Deployment readyDeployment(
      String name, Map<String, String> annotations, long generation) {
    return new DeploymentBuilder()
        .withNewMetadata()
        .withName(name)
        .withGeneration(generation)
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
