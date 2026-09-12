package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Applies one deterministic pod-template revision to the Telnet proxy and all gRPC consumers. */
@Component
public class DeploymentRolloutService {
  static final String GATEWAY_DEPLOYMENT = "spring-cloud-gateway";
  static final String TCP_PROXY_DEPLOYMENT = "tcp-proxy-service";
  static final List<String> BRIDGE_DEPLOYMENTS = List.of(GATEWAY_DEPLOYMENT, TCP_PROXY_DEPLOYMENT);

  public RolloutResult sync(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String telnetRevision,
      String grpcRevision,
      Runnable runtimeProfileFence) {
    if (runtimeProfileFence == null) {
      throw new IllegalArgumentException("runtime profile guard is required");
    }
    if (telnetRevision == null) {
      throw new IllegalArgumentException("telnet revision is required");
    }
    if (grpcRevision == null) {
      throw new IllegalArgumentException("gRPC revision is required");
    }
    Map<String, Map<String, String>> revisionsByDeployment = new LinkedHashMap<>();
    revisionsByDeployment
        .computeIfAbsent(TCP_PROXY_DEPLOYMENT, ignored -> new LinkedHashMap<>())
        .put(HostedIdentityContract.TELNET_REVISION_ANNOTATION, telnetRevision);
    for (String consumer : plan.grpcConsumers()) {
      revisionsByDeployment
          .computeIfAbsent(consumer, ignored -> new LinkedHashMap<>())
          .put(HostedIdentityContract.GRPC_REVISION_ANNOTATION, grpcRevision);
    }
    Map<String, Boolean> readinessByDeployment = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : revisionsByDeployment.entrySet()) {
      boolean ready =
          syncOne(
              client,
              plan.runtimeNamespace(),
              entry.getKey(),
              entry.getValue(),
              runtimeProfileFence);
      readinessByDeployment.put(entry.getKey(), ready);
    }
    boolean telnetReady = readinessByDeployment.getOrDefault(TCP_PROXY_DEPLOYMENT, false);
    boolean grpcReady =
        plan.grpcConsumers().stream()
            .allMatch(consumer -> readinessByDeployment.getOrDefault(consumer, false));
    return new RolloutResult(telnetReady && grpcReady, telnetReady, grpcReady);
  }

  /**
   * Terminates both bridge endpoints for the monotonic Retired identity-removal intent while
   * fencing every read/replace boundary with the current runtime identity.
   */
  public RetirementResult stopBridges(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      Runnable runtimeProfileFence) {
    if (runtimeProfileFence == null) {
      throw new IllegalArgumentException("runtime profile guard is required");
    }
    boolean gatewayStopped =
        stopOne(client, plan.runtimeNamespace(), GATEWAY_DEPLOYMENT, runtimeProfileFence);
    boolean proxyStopped =
        stopOne(client, plan.runtimeNamespace(), TCP_PROXY_DEPLOYMENT, runtimeProfileFence);
    return new RetirementResult(gatewayStopped && proxyStopped, gatewayStopped, proxyStopped);
  }

  private boolean stopOne(
      KubernetesClient client,
      String namespace,
      String deploymentName,
      Runnable runtimeProfileFence) {
    runtimeProfileFence.run();
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
      runtimeProfileFence.run();
      replaceWithCas(operation, deployment, DeploymentRolloutService::applyRetirementScaleDown);
      return false;
    }
    return retirementScaleDownObserved(deployment);
  }

  private boolean syncOne(
      KubernetesClient client,
      String namespace,
      String deploymentName,
      Map<String, String> desiredRevisions,
      Runnable runtimeProfileFence) {
    runtimeProfileFence.run();
    var operation = client.apps().deployments().inNamespace(namespace).withName(deploymentName);
    Deployment deployment = operation.get();
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
    boolean revisionChanged =
        desiredRevisions.entrySet().stream()
            .anyMatch(entry -> !entry.getValue().equals(annotations.get(entry.getKey())));
    if (revisionChanged) {
      runtimeProfileFence.run();
      replaceWithCas(operation, deployment, current -> applyRevisions(current, desiredRevisions));
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
    return applyRevisions(deployment, Map.of(annotationKey, revision));
  }

  static Deployment applyRevisions(Deployment deployment, Map<String, String> desiredRevisions) {
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
    annotations.putAll(desiredRevisions);
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
        && deployment.getMetadata() != null
        && deployment.getSpec() != null
        && Integer.valueOf(0).equals(deployment.getSpec().getReplicas())
        && deployment.getStatus() != null
        && deployment.getMetadata().getGeneration() != null
        && deployment.getStatus().getObservedGeneration() != null
        && deployment.getStatus().getObservedGeneration()
            >= deployment.getMetadata().getGeneration()
        && value(deployment.getStatus().getAvailableReplicas()) == 0
        && value(deployment.getStatus().getReadyReplicas()) == 0
        && value(deployment.getStatus().getReplicas()) == 0;
  }

  private static int value(Integer value) {
    return value == null ? 0 : value;
  }

  /** Replaces an observed Deployment with its resourceVersion as the Kubernetes CAS fence. */
  static void replaceWithCas(
      RollableScalableResource<Deployment> operation,
      Deployment observed,
      UnaryOperator<Deployment> mutation) {
    requireResourceVersion(observed);
    Deployment replacement = mutation.apply(new DeploymentBuilder(observed).build());
    replacement.getMetadata().setResourceVersion(observed.getMetadata().getResourceVersion());
    try {
      operation
          .lockResourceVersion(observed.getMetadata().getResourceVersion())
          .replace(replacement);
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 409) {
        throw exception;
      }
      // A 409 means another controller won the CAS; the caller returns its retryable
      // not-ready/not-stopped result while preserving the already-passed runtime guard.
    }
  }

  private static void requireResourceVersion(Deployment deployment) {
    if (deployment == null
        || deployment.getMetadata() == null
        || deployment.getMetadata().getResourceVersion() == null
        || deployment.getMetadata().getResourceVersion().isBlank()) {
      throw new IllegalStateException("Deployment has no resourceVersion for CAS");
    }
  }

  public record RolloutResult(boolean ready, boolean telnetReady, boolean grpcReady) {}

  public record RetirementResult(boolean stopped, boolean gatewayStopped, boolean proxyStopped) {}
}
