package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Establishes the exact per-environment scope and retained identity Namespace. */
@Component
public class HostedIdentityScopeService {
  private static final String ROLE_NAME = "firemud-hosted-identity-scope";
  private static final String RUNTIME_ROLE_NAME = "firemud-hosted-runtime-scope";
  private static final String CONTROLLER_SERVICE_ACCOUNT = "firemud-hosted-identity-controller";

  public void ensure(KubernetesClient client, EnvironmentIdentityPlan plan) {
    ensureIdentityNamespace(client, plan);
    requireNamespace(client.namespaces().withName(plan.runtimeNamespace()).get(), "runtime");
    ensureIdentity(client, plan);
    ensureRuntime(client, plan);
  }

  private static void ensureIdentityNamespace(
      KubernetesClient client, EnvironmentIdentityPlan plan) {
    var operation = client.namespaces().withName(plan.identityNamespace());
    Namespace current = operation.get();
    if (current == null) {
      Namespace desired =
          new NamespaceBuilder()
              .withNewMetadata()
              .withName(plan.identityNamespace())
              .withLabels(identityNamespaceLabels(plan))
              .endMetadata()
              .build();
      client.namespaces().resource(desired).create();
      return;
    }
    if (!isExpectedIdentityNamespace(current, plan)) {
      throw new IllegalStateException("identity Namespace ownership or labels drifted");
    }
  }

  public static boolean isExpectedIdentityNamespace(
      Namespace namespace, EnvironmentIdentityPlan plan) {
    if (namespace == null || namespace.getMetadata() == null) {
      return false;
    }
    Map<String, String> labels = namespace.getMetadata().getLabels();
    if (labels == null) {
      return false;
    }
    if (!plan.identityNamespace().equals(namespace.getMetadata().getName())
        || !identityNamespaceLabels(plan).entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())))
        || labels.entrySet().stream()
            .anyMatch(
                entry ->
                    !(entry.getKey().equals("kubernetes.io/metadata.name")
                            && entry.getValue().equals(plan.identityNamespace()))
                        && !identityNamespaceLabels(plan).containsKey(entry.getKey()))) {
      return false;
    }
    return (namespace.getMetadata().getAnnotations() == null
            || namespace.getMetadata().getAnnotations().isEmpty())
        && (namespace.getMetadata().getOwnerReferences() == null
            || namespace.getMetadata().getOwnerReferences().isEmpty())
        && (namespace.getMetadata().getFinalizers() == null
            || namespace.getMetadata().getFinalizers().isEmpty());
  }

  private static Map<String, String> identityNamespaceLabels(EnvironmentIdentityPlan plan) {
    return Map.of(
        HostedIdentityContract.MANAGED_BY_LABEL,
        HostedIdentityContract.CONTROLLER_NAME,
        HostedIdentityContract.IDENTITY_FOR_LABEL,
        plan.name(),
        HostedIdentityContract.RETENTION_LABEL,
        HostedIdentityContract.RETAINED,
        "firemud.dev/environment-class",
        "dev-demo".equals(plan.name()) ? "dev-demo-cluster" : "pr-preview");
  }

  private static void ensureIdentity(KubernetesClient client, EnvironmentIdentityPlan plan) {
    Role desired =
        role(
            plan.identityNamespace(),
            ROLE_NAME,
            labels(plan),
            List.of(
                rule(
                    List.of("cert-manager.io"),
                    List.of("certificates"),
                    List.of(),
                    List.of("list", "watch")),
                rule(
                    List.of("cert-manager.io"),
                    List.of("certificates"),
                    List.of(
                        plan.ingressCertificateName(),
                        plan.telnetCertificateName(),
                        plan.grpcCertificateName()),
                    List.of("get", "update", "patch", "delete")),
                rule(
                    List.of("cert-manager.io"),
                    List.of("certificates"),
                    List.of(),
                    List.of("create")),
                rule(
                    List.of(""),
                    List.of("secrets"),
                    List.of(
                        plan.ingressSecretName(),
                        plan.telnetSecretName(),
                        plan.grpcSecretName(),
                        plan.ingressSecretName() + "-previous",
                        plan.telnetSecretName() + "-previous",
                        plan.grpcSecretName() + "-previous",
                        plan.caSecretName()),
                    List.of("get")),
                rule(
                    List.of(""),
                    List.of("secrets"),
                    List.of(
                        plan.ingressSecretName(),
                        plan.telnetSecretName(),
                        plan.grpcSecretName(),
                        plan.ingressSecretName() + "-previous",
                        plan.telnetSecretName() + "-previous",
                        plan.grpcSecretName() + "-previous"),
                    List.of("update", "patch", "delete")),
                rule(List.of(""), List.of("secrets"), List.of(), List.of("create"))));
    ensureRole(client, plan.identityNamespace(), desired);
    ensureBinding(client, plan.identityNamespace(), ROLE_NAME, labels(plan), ROLE_NAME, plan);
  }

  private static void ensureRuntime(KubernetesClient client, EnvironmentIdentityPlan plan) {
    Role desired =
        role(
            plan.runtimeNamespace(),
            RUNTIME_ROLE_NAME,
            labels(plan),
            List.of(
                rule(
                    List.of(""),
                    List.of("secrets"),
                    List.of(
                        plan.ingressSecretName(), plan.telnetSecretName(), plan.grpcSecretName()),
                    List.of("get", "update", "patch", "delete")),
                rule(List.of(""), List.of("secrets"), List.of(), List.of("create")),
                rule(List.of("apps"), List.of("deployments"), List.of(), List.of("list", "watch")),
                rule(
                    List.of("apps"),
                    List.of("deployments"),
                    List.of(
                        "account-service",
                        "automation-scripting-service",
                        "entity-management-service",
                        "game-design-service",
                        "game-logic-service",
                        "game-session-service",
                        "logging-admin-service",
                        "social-groups-service",
                        "spring-cloud-gateway",
                        "tcp-proxy-service",
                        "world-management-service"),
                    List.of("get", "update", "patch")),
                rule(
                    List.of(""),
                    List.of("services", "pods"),
                    List.of(),
                    List.of("get", "list", "watch")),
                rule(
                    List.of("networking.k8s.io"),
                    List.of("ingresses"),
                    List.of(),
                    List.of("get", "list", "watch"))));
    ensureRole(client, plan.runtimeNamespace(), desired);
    ensureBinding(
        client, plan.runtimeNamespace(), RUNTIME_ROLE_NAME, labels(plan), RUNTIME_ROLE_NAME, plan);
  }

  private static Role role(
      String namespace, String name, Map<String, String> labels, List<PolicyRule> rules) {
    return new RoleBuilder()
        .withMetadata(
            new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(labels)
                .build())
        .withRules(rules)
        .build();
  }

  private static PolicyRule rule(
      List<String> apiGroups,
      List<String> resources,
      List<String> resourceNames,
      List<String> verbs) {
    return new PolicyRuleBuilder()
        .withApiGroups(apiGroups)
        .withResources(resources)
        .withResourceNames(resourceNames)
        .withVerbs(verbs)
        .build();
  }

  private static Map<String, String> labels(EnvironmentIdentityPlan plan) {
    return Map.of(
        "app.kubernetes.io/name",
        "hosted-environment-identity-controller",
        "app.kubernetes.io/component",
        "controller-scope",
        "app.kubernetes.io/part-of",
        "firemud",
        HostedIdentityContract.MANAGED_BY_LABEL,
        HostedIdentityContract.CONTROLLER_NAME,
        HostedIdentityContract.IDENTITY_FOR_LABEL,
        plan.name(),
        "firemud.dev/environment-class",
        "dev-demo".equals(plan.name()) ? "dev-demo-cluster" : "pr-preview");
  }

  private static void ensureRole(KubernetesClient client, String namespace, Role desired) {
    var operation =
        client.rbac().roles().inNamespace(namespace).withName(desired.getMetadata().getName());
    Role current = operation.get();
    if (current == null) {
      client.rbac().roles().inNamespace(namespace).resource(desired).create();
    } else if (!Objects.equals(current.getMetadata().getLabels(), desired.getMetadata().getLabels())
        || !Objects.equals(current.getRules(), desired.getRules())) {
      throw new IllegalStateException("hosted identity scope Role drifted");
    }
  }

  private static void ensureBinding(
      KubernetesClient client,
      String namespace,
      String name,
      Map<String, String> labels,
      String roleName,
      EnvironmentIdentityPlan plan) {
    var operation = client.rbac().roleBindings().inNamespace(namespace).withName(name);
    RoleBinding desired =
        new RoleBindingBuilder()
            .withMetadata(
                new ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(labelsWithoutClass(labels))
                    .build())
            .withRoleRef(
                new RoleRefBuilder()
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("Role")
                    .withName(roleName)
                    .build())
            .withSubjects(
                new SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(CONTROLLER_SERVICE_ACCOUNT)
                    .withNamespace(plan.controlNamespace())
                    .build())
            .build();
    RoleBinding current = operation.get();
    if (current == null) {
      client.rbac().roleBindings().inNamespace(namespace).resource(desired).create();
    } else if (!Objects.equals(current.getMetadata().getLabels(), desired.getMetadata().getLabels())
        || !Objects.equals(current.getRoleRef(), desired.getRoleRef())
        || !Objects.equals(current.getSubjects(), desired.getSubjects())) {
      throw new IllegalStateException("hosted identity scope RoleBinding drifted");
    }
  }

  private static Map<String, String> labelsWithoutClass(Map<String, String> labels) {
    return labels.entrySet().stream()
        .filter(entry -> !"firemud.dev/environment-class".equals(entry.getKey()))
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static void requireNamespace(Namespace namespace, String kind) {
    if (namespace == null
        || namespace.getMetadata() == null
        || namespace.getMetadata().getUid() == null) {
      throw new IllegalStateException(kind + " Namespace is absent or has no UID");
    }
  }
}
