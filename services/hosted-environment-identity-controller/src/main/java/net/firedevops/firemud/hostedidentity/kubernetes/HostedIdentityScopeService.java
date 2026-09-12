package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
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
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    requireNamespace(client.namespaces().withName(plan.runtimeNamespace()).get(), "runtime");
    ensureIdentityNamespace(client, plan);
    ensureIdentity(client, plan);
    ensureRuntime(client, plan);
  }

  static void ensureIdentityNamespace(KubernetesClient client, EnvironmentIdentityPlan plan) {
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
      try {
        client.namespaces().resource(desired).create();
        return;
      } catch (KubernetesClientException exception) {
        if (exception.getCode() != 409) {
          throw exception;
        }
        current = operation.get();
        if (current == null) {
          throw new IllegalStateException(
              "identity Namespace create conflict winner is absent", exception);
        }
      }
    }
    if (current.getMetadata() != null && current.getMetadata().getDeletionTimestamp() != null) {
      throw new IllegalStateException("identity Namespace is terminating");
    }
    if (!isExpectedIdentityNamespace(current, plan)) {
      throw new IllegalStateException("identity Namespace ownership or labels drifted");
    }
  }

  public static boolean isExpectedIdentityNamespace(
      Namespace namespace, EnvironmentIdentityPlan plan) {
    return hasExpectedIdentityNamespaceMetadata(namespace, plan)
        && namespace.getMetadata().getDeletionTimestamp() == null;
  }

  public static boolean hasExpectedIdentityNamespaceMetadata(
      Namespace namespace, EnvironmentIdentityPlan plan) {
    if (namespace == null || namespace.getMetadata() == null) {
      return false;
    }
    Map<String, String> labels = namespace.getMetadata().getLabels();
    if (labels == null) {
      return false;
    }
    Map<String, String> expectedLabels = identityNamespaceLabels(plan);
    boolean labelsMatch =
        expectedLabels.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())))
            && labels.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("firemud.dev/"))
                .allMatch(entry -> entry.getValue().equals(expectedLabels.get(entry.getKey())))
            && (!labels.containsKey("kubernetes.io/metadata.name")
                || plan.identityNamespace().equals(labels.get("kubernetes.io/metadata.name")));
    if (!plan.identityNamespace().equals(namespace.getMetadata().getName())
        || (namespace.getMetadata().getGenerateName() != null
            && !namespace.getMetadata().getGenerateName().isBlank())
        || !labelsMatch) {
      return false;
    }
    return (namespace.getMetadata().getOwnerReferences() == null
            || namespace.getMetadata().getOwnerReferences().isEmpty())
        && (namespace.getMetadata().getFinalizers() == null
            || namespace.getMetadata().getFinalizers().isEmpty());
  }

  private static Map<String, String> identityNamespaceLabels(EnvironmentIdentityPlan plan) {
    return Map.of(
        HostedIdentityContract.MANAGED_BY_LABEL,
        HostedIdentityContract.CONTROLLER_NAME,
        HostedIdentityContract.ENVIRONMENT_LABEL,
        plan.name(),
        HostedIdentityContract.RETENTION_LABEL,
        HostedIdentityContract.RETAINED,
        "firemud.dev/environment-class",
        HostedIdentityContract.environmentClass(plan.name()));
  }

  private static void ensureIdentity(KubernetesClient client, EnvironmentIdentityPlan plan) {
    List<String> identitySecretNames =
        List.of(
            plan.ingressSecretName(),
            plan.telnetSecretName(),
            plan.gatewayInternalWsSecretName(),
            plan.tcpProxyBridgeSecretName(),
            plan.grpcSecretName(),
            plan.ingressSecretName() + "-previous",
            plan.telnetSecretName() + "-previous",
            plan.gatewayInternalWsSecretName() + "-previous",
            plan.tcpProxyBridgeSecretName() + "-previous",
            plan.grpcSecretName() + "-previous");
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
                    List.of("certificaterequests"),
                    List.of(),
                    List.of("list")),
                rule(
                    List.of("cert-manager.io"),
                    List.of("certificates"),
                    requiredCertificateNames(plan),
                    List.of("get", "update", "patch", "delete")),
                rule(
                    List.of("cert-manager.io"),
                    List.of("certificates"),
                    List.of(),
                    List.of("create")),
                rule(List.of(""), List.of("secrets"), identitySecretNames, List.of("get")),
                rule(
                    List.of(""),
                    List.of("secrets"),
                    identitySecretNames,
                    List.of("update", "patch", "delete")),
                // Kubernetes ignores resourceNames for CREATE, so Secret creation cannot be
                // restricted to the named identity Secrets.
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
                        plan.ingressSecretName(),
                        plan.telnetSecretName(),
                        plan.gatewayInternalWsSecretName(),
                        plan.tcpProxyBridgeSecretName(),
                        plan.grpcSecretName()),
                    List.of("get", "update", "patch", "delete")),
                // Kubernetes ignores resourceNames for CREATE, so Secret creation cannot be
                // restricted to the named runtime Secrets.
                rule(List.of(""), List.of("secrets"), List.of(), List.of("create")),
                rule(
                    List.of("apps"),
                    List.of("deployments"),
                    requiredDeploymentNames(plan),
                    List.of("get", "update", "patch"))));
    ensureRole(client, plan.runtimeNamespace(), desired);
    ensureBinding(
        client, plan.runtimeNamespace(), RUNTIME_ROLE_NAME, labels(plan), RUNTIME_ROLE_NAME, plan);
  }

  static List<String> requiredDeploymentNames(EnvironmentIdentityPlan plan) {
    LinkedHashSet<String> names = new LinkedHashSet<>(plan.grpcConsumers());
    names.addAll(DeploymentRolloutService.BRIDGE_DEPLOYMENTS);
    return List.copyOf(names);
  }

  static List<String> requiredCertificateNames(EnvironmentIdentityPlan plan) {
    return List.of(
        plan.ingressCertificateName(),
        plan.telnetCertificateName(),
        plan.gatewayInternalWsCertificateName(),
        plan.tcpProxyBridgeCertificateName());
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
        HostedIdentityContract.ENVIRONMENT_LABEL,
        plan.name(),
        "firemud.dev/environment-class",
        HostedIdentityContract.environmentClass(plan.name()));
  }

  static void ensureRole(KubernetesClient client, String namespace, Role desired) {
    // Managed metadata drift fails closed. Recover by deleting the affected controller-owned Role;
    // reconciliation then recreates it from the canonical plan.
    var operation =
        client.rbac().roles().inNamespace(namespace).withName(desired.getMetadata().getName());
    Role current = operation.get();
    if (current == null) {
      try {
        client.rbac().roles().inNamespace(namespace).resource(desired).create();
        return;
      } catch (KubernetesClientException exception) {
        if (exception.getCode() != 409) {
          throw exception;
        }
        current = operation.get();
        if (current == null) {
          throw new IllegalStateException(
              "hosted identity scope Role create conflict winner is absent", exception);
        }
      }
    }
    if (!managedMetadataEquivalent(current.getMetadata(), desired.getMetadata())) {
      throw new IllegalStateException("hosted identity scope Role drifted");
    } else if (!roleRulesEquivalent(current, desired)) {
      operation.edit(resource -> applyDesiredRoleSpec(resource, desired));
    }
  }

  static void ensureBinding(
      KubernetesClient client,
      String namespace,
      String name,
      Map<String, String> labels,
      String roleName,
      EnvironmentIdentityPlan plan) {
    // Managed metadata or roleRef drift fails closed. Recover by deleting the affected
    // controller-owned RoleBinding; reconciliation then recreates it from the canonical plan.
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
      try {
        client.rbac().roleBindings().inNamespace(namespace).resource(desired).create();
        return;
      } catch (KubernetesClientException exception) {
        if (exception.getCode() != 409) {
          throw exception;
        }
        current = operation.get();
        if (current == null) {
          throw new IllegalStateException(
              "hosted identity scope RoleBinding create conflict winner is absent", exception);
        }
      }
    }
    if (!managedMetadataEquivalent(current.getMetadata(), desired.getMetadata())) {
      throw new IllegalStateException("hosted identity scope RoleBinding drifted");
    } else if (!roleRefEquivalent(current, desired)) {
      throw new IllegalStateException("hosted identity scope RoleBinding roleRef drifted");
    } else if (!bindingSubjectsEquivalent(current, desired)) {
      operation.edit(resource -> applyDesiredBindingSpec(resource, desired));
    }
  }

  static boolean roleEquivalent(Role current, Role desired) {
    if (current == null
        || desired == null
        || !managedMetadataEquivalent(current.getMetadata(), desired.getMetadata())) {
      return false;
    }
    return roleRulesEquivalent(current, desired);
  }

  private static boolean roleRulesEquivalent(Role current, Role desired) {
    List<PolicyRule> currentRules = emptyIfNull(current.getRules());
    List<PolicyRule> desiredRules = emptyIfNull(desired.getRules());
    if (currentRules.size() != desiredRules.size()) {
      return false;
    }
    for (int index = 0; index < currentRules.size(); index++) {
      if (!policyRuleEquivalent(currentRules.get(index), desiredRules.get(index))) {
        return false;
      }
    }
    return true;
  }

  static boolean bindingEquivalent(RoleBinding current, RoleBinding desired) {
    if (current == null
        || desired == null
        || !managedMetadataEquivalent(current.getMetadata(), desired.getMetadata())) {
      return false;
    }
    return bindingSpecEquivalent(current, desired);
  }

  private static boolean bindingSpecEquivalent(RoleBinding current, RoleBinding desired) {
    return roleRefEquivalent(current, desired) && bindingSubjectsEquivalent(current, desired);
  }

  private static boolean roleRefEquivalent(RoleBinding current, RoleBinding desired) {
    return current != null
        && desired != null
        && current.getRoleRef() != null
        && desired.getRoleRef() != null
        && java.util.Objects.equals(
            current.getRoleRef().getApiGroup(), desired.getRoleRef().getApiGroup())
        && java.util.Objects.equals(current.getRoleRef().getKind(), desired.getRoleRef().getKind())
        && java.util.Objects.equals(current.getRoleRef().getName(), desired.getRoleRef().getName());
  }

  private static boolean bindingSubjectsEquivalent(RoleBinding current, RoleBinding desired) {
    if (current == null || desired == null) {
      return false;
    }
    var currentSubjects = emptyIfNull(current.getSubjects());
    var desiredSubjects = emptyIfNull(desired.getSubjects());
    if (currentSubjects.size() != desiredSubjects.size()) {
      return false;
    }
    for (int index = 0; index < currentSubjects.size(); index++) {
      var left = currentSubjects.get(index);
      var right = desiredSubjects.get(index);
      if (!normalized(left.getApiGroup()).equals(normalized(right.getApiGroup()))
          || !java.util.Objects.equals(left.getKind(), right.getKind())
          || !java.util.Objects.equals(left.getName(), right.getName())
          || !java.util.Objects.equals(left.getNamespace(), right.getNamespace())) {
        return false;
      }
    }
    return true;
  }

  private static Role applyDesiredRoleSpec(Role current, Role desired) {
    if (current == null
        || !managedMetadataEquivalent(current.getMetadata(), desired.getMetadata())) {
      throw new IllegalStateException("hosted identity scope Role drifted during reconciliation");
    }
    current.setRules(desired.getRules());
    return current;
  }

  private static RoleBinding applyDesiredBindingSpec(RoleBinding current, RoleBinding desired) {
    if (current == null
        || !managedMetadataEquivalent(current.getMetadata(), desired.getMetadata())
        || !roleRefEquivalent(current, desired)) {
      throw new IllegalStateException(
          "hosted identity scope RoleBinding drifted during reconciliation");
    }
    current.setSubjects(desired.getSubjects());
    return current;
  }

  static boolean policyRuleEquivalent(PolicyRule current, PolicyRule desired) {
    return current != null
        && desired != null
        && emptyIfNull(current.getApiGroups()).equals(emptyIfNull(desired.getApiGroups()))
        && emptyIfNull(current.getResources()).equals(emptyIfNull(desired.getResources()))
        && emptyIfNull(current.getResourceNames()).equals(emptyIfNull(desired.getResourceNames()))
        && emptyIfNull(current.getVerbs()).equals(emptyIfNull(desired.getVerbs()))
        && emptyIfNull(current.getNonResourceURLs())
            .equals(emptyIfNull(desired.getNonResourceURLs()));
  }

  private static boolean managedMetadataEquivalent(ObjectMeta current, ObjectMeta desired) {
    return current != null
        && desired != null
        && java.util.Objects.equals(current.getName(), desired.getName())
        && java.util.Objects.equals(current.getNamespace(), desired.getNamespace())
        && (current.getGenerateName() == null || current.getGenerateName().isBlank())
        && managedLabelsEquivalent(current.getLabels(), desired.getLabels())
        && controllerAnnotations(current).equals(controllerAnnotations(desired))
        && emptyIfNull(current.getOwnerReferences()).isEmpty()
        && emptyIfNull(current.getFinalizers()).isEmpty();
  }

  private static boolean managedLabelsEquivalent(
      Map<String, String> current, Map<String, String> desired) {
    return current != null
        && desired != null
        && desired.entrySet().stream()
            .allMatch(
                entry -> java.util.Objects.equals(entry.getValue(), current.get(entry.getKey())))
        && current.keySet().stream()
            .filter(key -> key.startsWith("firemud.dev/"))
            .allMatch(desired::containsKey);
  }

  private static Map<String, String> controllerAnnotations(ObjectMeta metadata) {
    return emptyMapIfNull(metadata.getAnnotations()).entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("firemud.dev/"))
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static <T> List<T> emptyIfNull(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static Map<String, String> emptyMapIfNull(Map<String, String> values) {
    return values == null ? Map.of() : values;
  }

  private static String normalized(String value) {
    return value == null ? "" : value;
  }

  private static Map<String, String> labelsWithoutClass(Map<String, String> labels) {
    // The RoleBinding admission boundary derives the environment class from its exact
    // namespace/name tuple and permits only managed-by and identity-name FireMUD labels.
    // Omitting the duplicate class label keeps that single classification authority intact.
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
    if (namespace.getMetadata().getDeletionTimestamp() != null) {
      throw new IllegalStateException(kind + " Namespace is terminating");
    }
  }
}
