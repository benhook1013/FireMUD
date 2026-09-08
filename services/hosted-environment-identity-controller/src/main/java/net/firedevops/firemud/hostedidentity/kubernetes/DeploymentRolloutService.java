package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Applies one deterministic pod-template revision to the Telnet proxy and all gRPC consumers. */
@Component
public class DeploymentRolloutService {
  static final List<String> BRIDGE_DEPLOYMENTS =
      List.of("spring-cloud-gateway", "tcp-proxy-service");

  public RolloutResult sync(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String telnetRevision,
      String grpcRevision) {
    boolean telnetReady =
        syncOne(
            client,
            plan.runtimeNamespace(),
            "tcp-proxy-service",
            HostedIdentityContract.TELNET_REVISION_ANNOTATION,
            telnetRevision);
    boolean grpcReady = true;
    for (String consumer : plan.grpcConsumers()) {
      grpcReady &=
          syncOne(
              client,
              plan.runtimeNamespace(),
              consumer,
              HostedIdentityContract.GRPC_REVISION_ANNOTATION,
              grpcRevision);
    }
    return new RolloutResult(telnetReady && grpcReady, telnetReady, grpcReady);
  }

  /** Terminates both bridge endpoints for the monotonic Retired identity-removal intent. */
  public RetirementResult stopBridges(KubernetesClient client, EnvironmentIdentityPlan plan) {
    boolean gatewayStopped = stopOne(client, plan.runtimeNamespace(), BRIDGE_DEPLOYMENTS.get(0));
    boolean proxyStopped = stopOne(client, plan.runtimeNamespace(), BRIDGE_DEPLOYMENTS.get(1));
    return new RetirementResult(gatewayStopped && proxyStopped, gatewayStopped, proxyStopped);
  }

  private boolean stopOne(KubernetesClient client, String namespace, String deploymentName) {
    var operation = client.apps().deployments().inNamespace(namespace).withName(deploymentName);
    Deployment deployment = operation.get();
    if (deployment == null) {
      return true;
    }
    if (deployment.getSpec() == null) {
      throw new IllegalStateException("Deployment has no spec: " + deploymentName);
    }
    Integer replicas = deployment.getSpec().getReplicas();
    if (replicas == null || replicas != 0) {
      operation.edit(DeploymentRolloutService::applyRetirementScaleDown);
      return false;
    }
    return retirementScaleDownObserved(deployment);
  }

  private boolean syncOne(
      KubernetesClient client,
      String namespace,
      String deploymentName,
      String annotationKey,
      String revision) {
    Deployment deployment =
        client.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
    if (deployment == null
        || deployment.getSpec() == null
        || deployment.getSpec().getTemplate() == null) {
      return false;
    }
    ObjectMeta templateMetadata = deployment.getSpec().getTemplate().getMetadata();
    Map<String, String> annotations =
        templateMetadata == null || templateMetadata.getAnnotations() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(templateMetadata.getAnnotations());
    if (!revision.equals(annotations.get(annotationKey))) {
      client
          .apps()
          .deployments()
          .inNamespace(namespace)
          .withName(deploymentName)
          .edit(current -> applyRevision(current, annotationKey, revision));
      return false;
    }
    return activeRolloutObserved(deployment);
  }

  static boolean activeRolloutObserved(Deployment deployment) {
    if (deployment == null
        || deployment.getMetadata() == null
        || deployment.getSpec() == null
        || deployment.getSpec().getReplicas() == null
        || deployment.getStatus() == null) {
      return false;
    }
    int replicas = deployment.getSpec().getReplicas();
    return replicas > 0
        && deployment.getStatus().getObservedGeneration() != null
        && deployment.getMetadata().getGeneration() != null
        && deployment.getStatus().getObservedGeneration()
            >= deployment.getMetadata().getGeneration()
        && replicas == value(deployment.getStatus().getUpdatedReplicas())
        && replicas == value(deployment.getStatus().getAvailableReplicas())
        && replicas == value(deployment.getStatus().getReplicas());
  }

  static Deployment applyRevision(Deployment deployment, String annotationKey, String revision) {
    if (deployment == null
        || deployment.getSpec() == null
        || deployment.getSpec().getTemplate() == null) {
      throw new IllegalStateException("Deployment has no pod template");
    }
    ObjectMeta metadata = deployment.getSpec().getTemplate().getMetadata();
    if (metadata == null) {
      metadata = new ObjectMeta();
      deployment.getSpec().getTemplate().setMetadata(metadata);
    }
    Map<String, String> annotations =
        metadata.getAnnotations() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(metadata.getAnnotations());
    annotations.put(annotationKey, revision);
    metadata.setAnnotations(annotations);
    return deployment;
  }

  static Deployment applyRetirementScaleDown(Deployment deployment) {
    if (deployment == null || deployment.getSpec() == null) {
      throw new IllegalStateException("Deployment has no spec");
    }
    deployment.getSpec().setReplicas(0);
    return deployment;
  }

  static boolean retirementScaleDownObserved(Deployment deployment) {
    return deployment != null
        && deployment.getSpec() != null
        && Integer.valueOf(0).equals(deployment.getSpec().getReplicas())
        && deployment.getStatus() != null
        && value(deployment.getStatus().getAvailableReplicas()) == 0
        && value(deployment.getStatus().getReadyReplicas()) == 0
        && value(deployment.getStatus().getReplicas()) == 0;
  }

  private static int value(Integer value) {
    return value == null ? 0 : value;
  }

  public record RolloutResult(boolean ready, boolean telnetReady, boolean grpcReady) {}

  public record RetirementResult(boolean stopped, boolean gatewayStopped, boolean proxyStopped) {}
}
