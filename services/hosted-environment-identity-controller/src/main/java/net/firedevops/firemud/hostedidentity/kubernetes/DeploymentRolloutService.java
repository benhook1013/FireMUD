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
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
      String grpcRevision,
      Supplier<Boolean> runtimeProfileCurrent) {
    if (runtimeProfileCurrent == null) {
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
        .computeIfAbsent("tcp-proxy-service", ignored -> new LinkedHashMap<>())
        .put(HostedIdentityContract.TELNET_REVISION_ANNOTATION, telnetRevision);
    for (String consumer : plan.grpcConsumers()) {
      revisionsByDeployment
          .computeIfAbsent(consumer, ignored -> new LinkedHashMap<>())
          .put(HostedIdentityContract.GRPC_REVISION_ANNOTATION, grpcRevision);
    }
    Map<String, Boolean> readinessByDeployment = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : revisionsByDeployment.entrySet()) {
      SyncOneResult result =
          syncOne(
              client,
              plan.runtimeNamespace(),
              entry.getKey(),
              entry.getValue(),
              runtimeProfileCurrent);
      readinessByDeployment.put(entry.getKey(), result.ready());
      if (!result.guardPassed()) {
        break;
      }
    }
    boolean telnetReady = readinessByDeployment.getOrDefault("tcp-proxy-service", false);
    boolean grpcReady =
        plan.grpcConsumers().stream()
            .allMatch(consumer -> readinessByDeployment.getOrDefault(consumer, false));
    return new RolloutResult(telnetReady && grpcReady, telnetReady, grpcReady);
  }

  /**
   * Terminates both bridge endpoints for the monotonic Retired identity-removal intent while
   * fencing every read/replace boundary with the current runtime identity. A false guard means the
   * namespace identity is no longer safe to mutate; callers may throw from the guard to preserve
   * the precise failure reason.
   */
  public RetirementResult stopBridges(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      Supplier<Boolean> runtimeProfileCurrent) {
    if (runtimeProfileCurrent == null) {
      throw new IllegalArgumentException("runtime profile guard is required");
    }
    StopOneResult gateway =
        stopOne(client, plan.runtimeNamespace(), BRIDGE_DEPLOYMENTS.get(0), runtimeProfileCurrent);
    StopOneResult proxy =
        gateway.guardPassed()
            ? stopOne(
                client, plan.runtimeNamespace(), BRIDGE_DEPLOYMENTS.get(1), runtimeProfileCurrent)
            : new StopOneResult(false, false);
    boolean gatewayStopped = gateway.stopped();
    boolean proxyStopped = proxy.stopped();
    return new RetirementResult(gatewayStopped && proxyStopped, gatewayStopped, proxyStopped);
  }

  private StopOneResult stopOne(
      KubernetesClient client,
      String namespace,
      String deploymentName,
      Supplier<Boolean> runtimeProfileCurrent) {
    if (!guardPassed(runtimeProfileCurrent)) {
      return new StopOneResult(false, false);
    }
    var operation = client.apps().deployments().inNamespace(namespace).withName(deploymentName);
    Deployment deployment = operation.get();
    if (deployment == null) {
      return new StopOneResult(true, true);
    }
    if (deployment.getSpec() == null) {
      throw new IllegalStateException("Deployment has no spec: " + deploymentName);
    }
    Integer replicas = deployment.getSpec().getReplicas();
    if (replicas == null || replicas != 0) {
      if (!guardPassed(runtimeProfileCurrent)) {
        return new StopOneResult(false, false);
      }
      replaceWithCas(operation, deployment, DeploymentRolloutService::applyRetirementScaleDown);
      return new StopOneResult(false, true);
    }
    return new StopOneResult(retirementScaleDownObserved(deployment), true);
  }

  private SyncOneResult syncOne(
      KubernetesClient client,
      String namespace,
      String deploymentName,
      Map<String, String> desiredRevisions,
      Supplier<Boolean> runtimeProfileCurrent) {
    if (!guardPassed(runtimeProfileCurrent)) {
      return new SyncOneResult(false, false);
    }
    var operation = client.apps().deployments().inNamespace(namespace).withName(deploymentName);
    Deployment deployment = operation.get();
    if (deployment == null
        || deployment.getSpec() == null
        || deployment.getSpec().getTemplate() == null) {
      return new SyncOneResult(false, true);
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
      if (!guardPassed(runtimeProfileCurrent)) {
        return new SyncOneResult(false, false);
      }
      replaceWithCas(operation, deployment, current -> applyRevisions(current, desiredRevisions));
      return new SyncOneResult(false, true);
    }
    return new SyncOneResult(activeRolloutObserved(deployment), true);
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

  /** Replaces an observed Deployment with its resourceVersion as the Kubernetes CAS fence. */
  private static void replaceWithCas(
      RollableScalableResource<Deployment> operation,
      Deployment observed,
      UnaryOperator<Deployment> mutation) {
    requireResourceVersion(observed);
    Deployment replacement = new DeploymentBuilder(observed).build();
    mutation.apply(replacement);
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

  private static boolean guardPassed(Supplier<Boolean> runtimeProfileCurrent) {
    return Boolean.TRUE.equals(runtimeProfileCurrent.get());
  }

  private record SyncOneResult(boolean ready, boolean guardPassed) {}

  private record StopOneResult(boolean stopped, boolean guardPassed) {}

  public record RolloutResult(boolean ready, boolean telnetReady, boolean grpcReady) {}

  public record RetirementResult(boolean stopped, boolean gatewayStopped, boolean proxyStopped) {}
}
