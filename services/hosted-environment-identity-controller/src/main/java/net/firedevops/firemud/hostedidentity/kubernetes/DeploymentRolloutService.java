package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Applies one deterministic pod-template revision to the Telnet proxy and all gRPC consumers. */
@Component
public class DeploymentRolloutService {
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
    if (templateMetadata == null) {
      templateMetadata = new ObjectMeta();
      deployment.getSpec().getTemplate().setMetadata(templateMetadata);
    }
    Map<String, String> annotations =
        templateMetadata.getAnnotations() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(templateMetadata.getAnnotations());
    if (!revision.equals(annotations.get(annotationKey))) {
      annotations.put(annotationKey, revision);
      templateMetadata.setAnnotations(annotations);
      client.apps().deployments().inNamespace(namespace).resource(deployment).replace();
      return false;
    }
    if (deployment.getStatus() == null || deployment.getSpec().getReplicas() == null) {
      return false;
    }
    int replicas = deployment.getSpec().getReplicas();
    return deployment.getStatus().getObservedGeneration() != null
        && deployment.getMetadata().getGeneration() != null
        && deployment.getStatus().getObservedGeneration()
            >= deployment.getMetadata().getGeneration()
        && replicas == value(deployment.getStatus().getUpdatedReplicas())
        && replicas <= value(deployment.getStatus().getAvailableReplicas());
  }

  private static int value(Integer value) {
    return value == null ? 0 : value;
  }

  public record RolloutResult(boolean ready, boolean telnetReady, boolean grpcReady) {}
}
