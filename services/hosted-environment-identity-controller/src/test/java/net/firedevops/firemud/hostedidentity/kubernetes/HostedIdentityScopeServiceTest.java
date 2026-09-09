package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingList;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleList;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HostedIdentityScopeServiceTest {
  private static final Map<String, String> ROLE_LABELS =
      Map.of(
          "app.kubernetes.io/name",
          "hosted-environment-identity-controller",
          "app.kubernetes.io/component",
          "controller-scope",
          "app.kubernetes.io/part-of",
          "firemud",
          "firemud.dev/managed-by",
          "hosted-identity-controller",
          "firemud.dev/identity-name",
          "pr-42",
          "firemud.dev/environment-class",
          "pr-preview");
  private static final Map<String, String> BINDING_LABELS =
      Map.of(
          "app.kubernetes.io/name",
          "hosted-environment-identity-controller",
          "app.kubernetes.io/component",
          "controller-scope",
          "app.kubernetes.io/part-of",
          "firemud",
          "firemud.dev/managed-by",
          "hosted-identity-controller",
          "firemud.dev/identity-name",
          "pr-42");

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void controllerOwnedRoleSpecUpgradeConvergesWithoutAnotherEdit() {
    RoleClient fixture = roleClient();
    KubernetesClient client = fixture.client();
    Resource<Role> operation = fixture.operation();
    Role desired = role("get");
    Role oldSpec = role("list");
    when(operation.get()).thenReturn(oldSpec);

    HostedIdentityScopeService.ensureRole(client, "pr-42", desired);

    ArgumentCaptor<UnaryOperator<Role>> editor = ArgumentCaptor.forClass(UnaryOperator.class);
    verify(operation).edit(editor.capture());
    Role converged = editor.getValue().apply(new RoleBuilder(oldSpec).build());
    assertEquals("get", converged.getRules().get(0).getVerbs().get(0));
    assertEquals(ROLE_LABELS, converged.getMetadata().getLabels());

    when(operation.get()).thenReturn(converged);
    HostedIdentityScopeService.ensureRole(client, "pr-42", desired);
    verify(operation, times(1)).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Role>>any());
  }

  @Test
  void roleOwnershipAndUnknownMetadataDriftRemainFailClosed() {
    Role desired = role("get");
    Role wrongOwner =
        new RoleBuilder(desired)
            .editMetadata()
            .addToLabels("firemud.dev/managed-by", "other")
            .endMetadata()
            .build();
    Role unknownMetadata =
        new RoleBuilder(desired)
            .editMetadata()
            .addToAnnotations("other.example/claim", "unexpected")
            .withOwnerReferences(new OwnerReferenceBuilder().withName("attacker").build())
            .endMetadata()
            .build();

    IllegalStateException ownershipFailure = assertRoleDriftFailsClosed(wrongOwner, desired);
    assertEquals("hosted identity scope Role drifted", ownershipFailure.getMessage());
    IllegalStateException unknownMetadataFailure =
        assertRoleDriftFailsClosed(unknownMetadata, desired);
    assertEquals("hosted identity scope Role drifted", unknownMetadataFailure.getMessage());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void controllerOwnedBindingSubjectUpgradeConvergesWithoutAnotherEdit() {
    EnvironmentIdentityPlan plan = plan();
    BindingClient fixture = bindingClient();
    KubernetesClient client = fixture.client();
    Resource<RoleBinding> operation = fixture.operation();
    RoleBinding oldSpec =
        binding(
            new RoleRefBuilder()
                .withApiGroup("rbac.authorization.k8s.io")
                .withKind("Role")
                .withName("scope")
                .build(),
            new SubjectBuilder()
                .withKind("ServiceAccount")
                .withName("old-controller")
                .withNamespace(plan.controlNamespace())
                .build());
    when(operation.get()).thenReturn(oldSpec);

    HostedIdentityScopeService.ensureBinding(client, "pr-42", "scope", ROLE_LABELS, "scope", plan);

    ArgumentCaptor<UnaryOperator<RoleBinding>> editor =
        ArgumentCaptor.forClass(UnaryOperator.class);
    verify(operation).edit(editor.capture());
    RoleBinding converged = editor.getValue().apply(new RoleBindingBuilder(oldSpec).build());
    assertEquals("scope", converged.getRoleRef().getName());
    assertEquals("firemud-hosted-identity-controller", converged.getSubjects().get(0).getName());
    assertEquals(BINDING_LABELS, converged.getMetadata().getLabels());

    when(operation.get()).thenReturn(converged);
    HostedIdentityScopeService.ensureBinding(client, "pr-42", "scope", ROLE_LABELS, "scope", plan);
    verify(operation, times(1))
        .edit(org.mockito.ArgumentMatchers.<UnaryOperator<RoleBinding>>any());
  }

  @Test
  void bindingIdentityRoleRefAndUnknownMetadataDriftRemainFailClosed() {
    EnvironmentIdentityPlan plan = plan();
    RoleBinding exactMetadata =
        binding(
            new RoleRefBuilder()
                .withApiGroup("rbac.authorization.k8s.io")
                .withKind("Role")
                .withName("scope")
                .build(),
            new SubjectBuilder()
                .withKind("ServiceAccount")
                .withName("firemud-hosted-identity-controller")
                .withNamespace(plan.controlNamespace())
                .build());
    RoleBinding wrongIdentity =
        new RoleBindingBuilder(exactMetadata)
            .editMetadata()
            .withName("other-scope")
            .endMetadata()
            .build();
    RoleBinding unknownMetadata =
        new RoleBindingBuilder(exactMetadata)
            .editMetadata()
            .addToAnnotations("other.example/claim", "unexpected")
            .endMetadata()
            .build();
    RoleBinding wrongRoleRef =
        new RoleBindingBuilder(exactMetadata)
            .withRoleRef(
                new RoleRefBuilder()
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("Role")
                    .withName("other-scope")
                    .build())
            .build();

    IllegalStateException identityFailure = assertBindingDriftFailsClosed(wrongIdentity, plan);
    assertEquals("hosted identity scope RoleBinding drifted", identityFailure.getMessage());
    IllegalStateException unknownMetadataFailure =
        assertBindingDriftFailsClosed(unknownMetadata, plan);
    assertEquals("hosted identity scope RoleBinding drifted", unknownMetadataFailure.getMessage());
    IllegalStateException roleRefFailure = assertBindingDriftFailsClosed(wrongRoleRef, plan);
    assertEquals("hosted identity scope RoleBinding roleRef drifted", roleRefFailure.getMessage());
  }

  private static IllegalStateException assertRoleDriftFailsClosed(Role current, Role desired) {
    RoleClient fixture = roleClient();
    when(fixture.operation().get()).thenReturn(current);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> HostedIdentityScopeService.ensureRole(fixture.client(), "pr-42", desired));

    verify(fixture.operation(), never())
        .edit(org.mockito.ArgumentMatchers.<UnaryOperator<Role>>any());
    return failure;
  }

  private static IllegalStateException assertBindingDriftFailsClosed(
      RoleBinding current, EnvironmentIdentityPlan plan) {
    BindingClient fixture = bindingClient();
    when(fixture.operation().get()).thenReturn(current);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                HostedIdentityScopeService.ensureBinding(
                    fixture.client(), "pr-42", "scope", ROLE_LABELS, "scope", plan));

    verify(fixture.operation(), never())
        .edit(org.mockito.ArgumentMatchers.<UnaryOperator<RoleBinding>>any());
    return failure;
  }

  private static Role role(String verb) {
    return new RoleBuilder()
        .withNewMetadata()
        .withName("scope")
        .withNamespace("pr-42")
        .withLabels(ROLE_LABELS)
        .endMetadata()
        .withRules(
            new PolicyRuleBuilder()
                .withApiGroups("")
                .withResources("secrets")
                .withVerbs(verb)
                .build())
        .build();
  }

  private static RoleBinding binding(
      io.fabric8.kubernetes.api.model.rbac.RoleRef roleRef,
      io.fabric8.kubernetes.api.model.rbac.Subject subject) {
    return new RoleBindingBuilder()
        .withNewMetadata()
        .withName("scope")
        .withNamespace("pr-42")
        .withLabels(BINDING_LABELS)
        .endMetadata()
        .withRoleRef(roleRef)
        .withSubjects(subject)
        .build();
  }

  private static EnvironmentIdentityPlan plan() {
    return new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static RoleClient roleClient() {
    KubernetesClient client = mock(KubernetesClient.class);
    RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
    MixedOperation<Role, RoleList, Resource<Role>> roles = mock(MixedOperation.class);
    NonNamespaceOperation<Role, RoleList, Resource<Role>> namespaceRoles =
        mock(NonNamespaceOperation.class);
    Resource<Role> operation = mock(Resource.class);
    when(client.rbac()).thenReturn(rbac);
    when(rbac.roles()).thenReturn(roles);
    when(roles.inNamespace("pr-42")).thenReturn(namespaceRoles);
    when(namespaceRoles.withName("scope")).thenReturn(operation);
    return new RoleClient(client, operation);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static BindingClient bindingClient() {
    KubernetesClient client = mock(KubernetesClient.class);
    RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
    MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> bindings =
        mock(MixedOperation.class);
    NonNamespaceOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> namespaceBindings =
        mock(NonNamespaceOperation.class);
    Resource<RoleBinding> operation = mock(Resource.class);
    when(client.rbac()).thenReturn(rbac);
    when(rbac.roleBindings()).thenReturn(bindings);
    when(bindings.inNamespace("pr-42")).thenReturn(namespaceBindings);
    when(namespaceBindings.withName("scope")).thenReturn(operation);
    return new BindingClient(client, operation);
  }

  private record RoleClient(KubernetesClient client, Resource<Role> operation) {}

  private record BindingClient(KubernetesClient client, Resource<RoleBinding> operation) {}
}
