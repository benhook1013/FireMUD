package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceList;
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
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HostedIdentityScopeServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void missingRuntimeNamespaceDoesNotCreateRetainedIdentityNamespace() {
    EnvironmentIdentityPlan plan = plan();
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces =
        mock(NonNamespaceOperation.class);
    Resource<Namespace> runtimeNamespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(runtimeNamespace);
    when(runtimeNamespace.get()).thenReturn(null);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new HostedIdentityScopeService().ensure(client, plan));

    assertEquals("runtime Namespace is absent or has no UID", failure.getMessage());
    verify(namespaces, never()).withName(plan.identityNamespace());
    verify(namespaces, never()).resource(org.mockito.ArgumentMatchers.any(Namespace.class));
  }

  @Test
  void identityRoleNamesOnlyCoverCertManagerCertificates() {
    EnvironmentIdentityPlan plan = plan();

    assertEquals(
        java.util.List.of(
            "pr-42-tls", "pr-42-telnet-tls", "pr-42-gateway-internal-ws", "pr-42-tcp-proxy-bridge"),
        HostedIdentityScopeService.requiredCertificateNames(plan));
  }

  @Test
  void runtimeRoleCoversBothBridgeDeploymentsAndEveryGrpcConsumer() {
    EnvironmentIdentityPlan plan = plan();

    assertEquals(
        java.util.Set.of(
            "spring-cloud-gateway",
            "tcp-proxy-service",
            "account-service",
            "automation-scripting-service",
            "entity-management-service",
            "game-design-service",
            "game-logic-service",
            "game-session-service",
            "logging-admin-service",
            "social-groups-service",
            "world-management-service"),
        new java.util.HashSet<>(HostedIdentityScopeService.requiredDeploymentNames(plan)));
    assertEquals(plan.grpcConsumers(), HostedIdentityScopeService.requiredDeploymentNames(plan));
  }

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
          HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS);
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
  void identityNamespaceAllowsUnrelatedAnnotationsButRequiresOwnedLabels() {
    EnvironmentIdentityPlan plan = plan();
    Namespace exact = identityNamespace(plan);
    Namespace annotated =
        new NamespaceBuilder(exact)
            .editMetadata()
            .addToAnnotations("tooling.example/managed-by", "cluster-tool")
            .endMetadata()
            .build();
    assertTrue(HostedIdentityScopeService.isExpectedIdentityNamespace(annotated, plan));

    Namespace wrongOwnedLabel =
        new NamespaceBuilder(exact)
            .editMetadata()
            .addToLabels(HostedIdentityContract.MANAGED_BY_LABEL, "other-controller")
            .endMetadata()
            .build();
    assertFalse(HostedIdentityScopeService.isExpectedIdentityNamespace(wrongOwnedLabel, plan));
  }

  @Test
  void retainedIdentityNamespaceRequiresItsDerivedControllerLabelsAndAllowsExternalLabels() {
    var devPlan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("dev-demo");
    var valid =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("dev-identity")
            .withLabels(
                Map.of(
                    "firemud.dev/managed-by",
                    "hosted-identity-controller",
                    "firemud.dev/identity-name",
                    "dev-demo",
                    "firemud.dev/retention",
                    "retained",
                    "firemud.dev/environment-class",
                    HostedIdentityContract.DEV_DEMO_ENVIRONMENT_CLASS))
            .endMetadata()
            .build();
    assertTrue(HostedIdentityScopeService.isExpectedIdentityNamespace(valid, devPlan));

    Map<String, String> injectedLabels = new HashMap<>(valid.getMetadata().getLabels());
    injectedLabels.put("kubernetes.io/metadata.name", "dev-identity");
    injectedLabels.put("tooling.example/managed-by", "cluster-tool");
    var apiRoundTripped =
        new NamespaceBuilder(valid)
            .editMetadata()
            .withLabels(injectedLabels)
            .withUid("api-uid")
            .withResourceVersion("9")
            .endMetadata()
            .build();
    assertTrue(HostedIdentityScopeService.isExpectedIdentityNamespace(apiRoundTripped, devPlan));

    assertFalse(
        HostedIdentityScopeService.isExpectedIdentityNamespace(
            new NamespaceBuilder(valid)
                .editMetadata()
                .addToLabels("kubernetes.io/metadata.name", "other-identity")
                .endMetadata()
                .build(),
            devPlan));

    Map<String, String> labels = new HashMap<>(valid.getMetadata().getLabels());
    labels.put("firemud.dev/other", "unexpected");
    var unexpectedLabels =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("dev-identity")
            .withLabels(labels)
            .endMetadata()
            .build();
    assertFalse(HostedIdentityScopeService.isExpectedIdentityNamespace(unexpectedLabels, devPlan));

    var unexpectedOwner =
        new NamespaceBuilder(valid)
            .editMetadata()
            .withOwnerReferences(new OwnerReferenceBuilder().withName("other").build())
            .endMetadata()
            .build();
    assertFalse(HostedIdentityScopeService.isExpectedIdentityNamespace(unexpectedOwner, devPlan));

    var previewPlan = new EnvironmentIdentityPlanner(new HostedIdentityProperties()).plan("pr-42");
    var preview =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("pr-42-identity")
            .withLabels(
                Map.of(
                    "firemud.dev/managed-by",
                    "hosted-identity-controller",
                    "firemud.dev/identity-name",
                    "pr-42",
                    "firemud.dev/retention",
                    "retained",
                    "firemud.dev/environment-class",
                    HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS))
            .endMetadata()
            .build();
    assertTrue(HostedIdentityScopeService.isExpectedIdentityNamespace(preview, previewPlan));
    assertFalse(
        HostedIdentityScopeService.isExpectedIdentityNamespace(
            new NamespaceBuilder(preview)
                .editMetadata()
                .withName("pr-43-identity")
                .endMetadata()
                .build(),
            previewPlan));
    assertFalse(
        HostedIdentityScopeService.isExpectedIdentityNamespace(
            new NamespaceBuilder(preview)
                .editMetadata()
                .withGenerateName("pr-42-")
                .endMetadata()
                .build(),
            previewPlan));
    assertTrue(
        HostedIdentityScopeService.isExpectedIdentityNamespace(
            new NamespaceBuilder(preview)
                .editMetadata()
                .addToAnnotations("external", "unexpected")
                .endMetadata()
                .build(),
            previewPlan));
    assertFalse(
        HostedIdentityScopeService.isExpectedIdentityNamespace(
            new NamespaceBuilder(preview)
                .editMetadata()
                .withFinalizers("external/finalizer")
                .endMetadata()
                .build(),
            previewPlan));
  }

  @Test
  void rbacRoundTripNormalizesOnlyNullEmptyFieldsAndInjectedServerMetadata() {
    Map<String, String> labels = Map.of("firemud.dev/managed-by", "hosted-identity-controller");
    var desired =
        new RoleBuilder()
            .withNewMetadata()
            .withName("scope")
            .withNamespace("pr-42")
            .withLabels(labels)
            .endMetadata()
            .withRules(
                new PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("secrets")
                    .withResourceNames()
                    .withVerbs("create")
                    .build())
            .build();
    var roundTripped = new RoleBuilder(desired).build();
    roundTripped.getMetadata().setUid("api-uid");
    roundTripped.getMetadata().setResourceVersion("7");
    roundTripped.getMetadata().getLabels().put("tooling.example/managed-by", "cluster-tool");
    roundTripped.getRules().get(0).setResourceNames(null);
    roundTripped.getRules().get(0).setNonResourceURLs(null);
    assertTrue(HostedIdentityScopeService.roleEquivalent(roundTripped, desired));

    var unexpectedControllerLabel = new RoleBuilder(roundTripped).build();
    unexpectedControllerLabel.getMetadata().getLabels().put("firemud.dev/unexpected", "ownership");
    assertFalse(HostedIdentityScopeService.roleEquivalent(unexpectedControllerLabel, desired));

    roundTripped
        .getMetadata()
        .setOwnerReferences(
            java.util.List.of(new OwnerReferenceBuilder().withName("other").build()));
    assertFalse(HostedIdentityScopeService.roleEquivalent(roundTripped, desired));

    var verbDrift = new RoleBuilder(desired).build();
    verbDrift.getRules().get(0).setVerbs(java.util.List.of("get"));
    assertFalse(HostedIdentityScopeService.roleEquivalent(verbDrift, desired));

    var resourceDrift = new RoleBuilder(desired).build();
    resourceDrift.getRules().get(0).setResources(java.util.List.of("configmaps"));
    assertFalse(HostedIdentityScopeService.roleEquivalent(resourceDrift, desired));

    var ruleCountDrift =
        new RoleBuilder(desired)
            .addToRules(
                new PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("configmaps")
                    .withVerbs("get")
                    .build())
            .build();
    assertFalse(HostedIdentityScopeService.roleEquivalent(ruleCountDrift, desired));
  }

  @Test
  void roleBindingRoundTripAllowsOnlyNullEmptySubjectApiGroupDifference() {
    Map<String, String> labels = Map.of("firemud.dev/managed-by", "hosted-identity-controller");
    var desired =
        new RoleBindingBuilder()
            .withNewMetadata()
            .withName("scope")
            .withNamespace("pr-42")
            .withLabels(labels)
            .endMetadata()
            .withNewRoleRef("rbac.authorization.k8s.io", "Role", "scope")
            .addNewSubject()
            .withKind("ServiceAccount")
            .withName("controller")
            .withNamespace("system")
            .endSubject()
            .build();
    var roundTripped = new RoleBindingBuilder(desired).build();
    roundTripped.getSubjects().get(0).setApiGroup("");
    roundTripped.getMetadata().getLabels().put("tooling.example/managed-by", "cluster-tool");
    assertTrue(HostedIdentityScopeService.bindingEquivalent(roundTripped, desired));
    roundTripped.getMetadata().getLabels().put("firemud.dev/unexpected", "ownership");
    assertFalse(HostedIdentityScopeService.bindingEquivalent(roundTripped, desired));
    roundTripped.getMetadata().getLabels().remove("firemud.dev/unexpected");
    roundTripped.getMetadata().setAnnotations(Map.of("other.example/claim", "external"));
    assertTrue(HostedIdentityScopeService.bindingEquivalent(roundTripped, desired));
    roundTripped.getMetadata().setAnnotations(Map.of("firemud.dev/unexpected", "ownership"));
    assertFalse(HostedIdentityScopeService.bindingEquivalent(roundTripped, desired));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void controllerOwnedRoleSpecUpgradeConvergesWithoutAnotherEdit() {
    RoleClient fixture = roleClient();
    KubernetesClient client = fixture.client();
    Resource<Role> operation = fixture.operation();
    Role desired = role("get");
    Role oldSpec = role("list");
    oldSpec.getMetadata().getLabels().put("tooling.example/managed-by", "cluster-tool");
    oldSpec.getMetadata().setAnnotations(Map.of("other.example/claim", "external"));
    when(operation.get()).thenReturn(oldSpec);

    HostedIdentityScopeService.ensureRole(client, "pr-42", desired);

    ArgumentCaptor<UnaryOperator<Role>> editor = ArgumentCaptor.forClass(UnaryOperator.class);
    verify(operation).edit(editor.capture());
    Role converged = editor.getValue().apply(new RoleBuilder(oldSpec).build());
    assertEquals("get", converged.getRules().get(0).getVerbs().get(0));
    assertEquals(
        "cluster-tool", converged.getMetadata().getLabels().get("tooling.example/managed-by"));
    assertEquals("external", converged.getMetadata().getAnnotations().get("other.example/claim"));

    when(operation.get()).thenReturn(converged);
    HostedIdentityScopeService.ensureRole(client, "pr-42", desired);
    verify(operation, times(1)).edit(org.mockito.ArgumentMatchers.<UnaryOperator<Role>>any());
  }

  @Test
  void missingRoleIsCreatedWithCanonicalSpecWithoutEdit() {
    RoleClient fixture = roleClient();
    Role desired = role("get");
    when(fixture.operation().get()).thenReturn(null);

    HostedIdentityScopeService.ensureRole(fixture.client(), "pr-42", desired);

    ArgumentCaptor<Role> createdResource = ArgumentCaptor.forClass(Role.class);
    verify(fixture.namespaceRoles()).resource(createdResource.capture());
    verify(fixture.createOperation()).create();
    verify(fixture.operation(), never())
        .edit(org.mockito.ArgumentMatchers.<UnaryOperator<Role>>any());
    Role created = createdResource.getValue();
    assertEquals(ROLE_LABELS, created.getMetadata().getLabels());
    assertEquals(desired.getRules(), created.getRules());
  }

  @Test
  void roleOwnershipDriftRemainsFailClosed() {
    Role desired = role("get");
    Role wrongOwner =
        new RoleBuilder(desired)
            .editMetadata()
            .addToLabels("firemud.dev/managed-by", "other")
            .endMetadata()
            .build();

    IllegalStateException failure = assertRoleDriftFailsClosed(wrongOwner, desired);
    assertEquals("hosted identity scope Role drifted", failure.getMessage());
  }

  @Test
  void roleControllerAnnotationDriftRemainsFailClosed() {
    Role desired = role("get");
    Role unexpectedAnnotation =
        new RoleBuilder(desired)
            .editMetadata()
            .addToAnnotations("firemud.dev/unexpected", "ownership")
            .endMetadata()
            .build();

    IllegalStateException failure = assertRoleDriftFailsClosed(unexpectedAnnotation, desired);
    assertEquals("hosted identity scope Role drifted", failure.getMessage());
  }

  @Test
  void roleUnexpectedOwnerReferenceDriftRemainsFailClosed() {
    Role desired = role("get");
    Role unexpectedOwnerReference =
        new RoleBuilder(desired)
            .editMetadata()
            .withOwnerReferences(new OwnerReferenceBuilder().withName("attacker").build())
            .endMetadata()
            .build();

    IllegalStateException failure = assertRoleDriftFailsClosed(unexpectedOwnerReference, desired);
    assertEquals("hosted identity scope Role drifted", failure.getMessage());
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
    oldSpec.getMetadata().getLabels().put("tooling.example/managed-by", "cluster-tool");
    oldSpec.getMetadata().setAnnotations(Map.of("other.example/claim", "external"));
    when(operation.get()).thenReturn(oldSpec);

    HostedIdentityScopeService.ensureBinding(client, "pr-42", "scope", ROLE_LABELS, "scope", plan);

    ArgumentCaptor<UnaryOperator<RoleBinding>> editor =
        ArgumentCaptor.forClass(UnaryOperator.class);
    verify(operation).edit(editor.capture());
    RoleBinding converged = editor.getValue().apply(new RoleBindingBuilder(oldSpec).build());
    assertEquals("scope", converged.getRoleRef().getName());
    assertEquals("firemud-hosted-identity-controller", converged.getSubjects().get(0).getName());
    assertEquals(
        "cluster-tool", converged.getMetadata().getLabels().get("tooling.example/managed-by"));
    assertEquals("external", converged.getMetadata().getAnnotations().get("other.example/claim"));

    when(operation.get()).thenReturn(converged);
    HostedIdentityScopeService.ensureBinding(client, "pr-42", "scope", ROLE_LABELS, "scope", plan);
    verify(operation, times(1))
        .edit(org.mockito.ArgumentMatchers.<UnaryOperator<RoleBinding>>any());
  }

  @Test
  void missingBindingIsCreatedWithCanonicalSpecWithoutEdit() {
    EnvironmentIdentityPlan plan = plan();
    BindingClient fixture = bindingClient();
    when(fixture.operation().get()).thenReturn(null);

    HostedIdentityScopeService.ensureBinding(
        fixture.client(), "pr-42", "scope", ROLE_LABELS, "scope", plan);

    ArgumentCaptor<RoleBinding> createdResource = ArgumentCaptor.forClass(RoleBinding.class);
    verify(fixture.namespaceBindings()).resource(createdResource.capture());
    verify(fixture.createOperation()).create();
    verify(fixture.operation(), never())
        .edit(org.mockito.ArgumentMatchers.<UnaryOperator<RoleBinding>>any());
    RoleBinding created = createdResource.getValue();
    assertEquals(BINDING_LABELS, created.getMetadata().getLabels());
    assertEquals("rbac.authorization.k8s.io", created.getRoleRef().getApiGroup());
    assertEquals("Role", created.getRoleRef().getKind());
    assertEquals("scope", created.getRoleRef().getName());
    assertEquals(1, created.getSubjects().size());
    assertEquals("ServiceAccount", created.getSubjects().get(0).getKind());
    assertEquals("firemud-hosted-identity-controller", created.getSubjects().get(0).getName());
    assertEquals(plan.controlNamespace(), created.getSubjects().get(0).getNamespace());
  }

  @Test
  void bindingIdentityRoleRefAndControllerMetadataDriftRemainFailClosed() {
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
    RoleBinding controllerMetadata =
        new RoleBindingBuilder(exactMetadata)
            .editMetadata()
            .addToAnnotations("firemud.dev/unexpected", "ownership")
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
    IllegalStateException controllerMetadataFailure =
        assertBindingDriftFailsClosed(controllerMetadata, plan);
    assertEquals(
        "hosted identity scope RoleBinding drifted", controllerMetadataFailure.getMessage());
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
    verify(fixture.createOperation(), never()).create();
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
    verify(fixture.createOperation(), never()).create();
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

  private static Namespace identityNamespace(EnvironmentIdentityPlan plan) {
    return new NamespaceBuilder()
        .withNewMetadata()
        .withName(plan.identityNamespace())
        .withLabels(
            Map.of(
                HostedIdentityContract.MANAGED_BY_LABEL,
                HostedIdentityContract.CONTROLLER_NAME,
                HostedIdentityContract.ENVIRONMENT_LABEL,
                plan.name(),
                HostedIdentityContract.RETENTION_LABEL,
                HostedIdentityContract.RETAINED,
                "firemud.dev/environment-class",
                HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS))
        .endMetadata()
        .build();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static RoleClient roleClient() {
    KubernetesClient client = mock(KubernetesClient.class);
    RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
    MixedOperation<Role, RoleList, Resource<Role>> roles = mock(MixedOperation.class);
    NonNamespaceOperation<Role, RoleList, Resource<Role>> namespaceRoles =
        mock(NonNamespaceOperation.class);
    Resource<Role> operation = mock(Resource.class);
    Resource<Role> createOperation = mock(Resource.class);
    when(client.rbac()).thenReturn(rbac);
    when(rbac.roles()).thenReturn(roles);
    when(roles.inNamespace("pr-42")).thenReturn(namespaceRoles);
    when(namespaceRoles.withName("scope")).thenReturn(operation);
    when(namespaceRoles.resource(org.mockito.ArgumentMatchers.any(Role.class)))
        .thenReturn(createOperation);
    return new RoleClient(client, namespaceRoles, operation, createOperation);
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
    Resource<RoleBinding> createOperation = mock(Resource.class);
    when(client.rbac()).thenReturn(rbac);
    when(rbac.roleBindings()).thenReturn(bindings);
    when(bindings.inNamespace("pr-42")).thenReturn(namespaceBindings);
    when(namespaceBindings.withName("scope")).thenReturn(operation);
    when(namespaceBindings.resource(org.mockito.ArgumentMatchers.any(RoleBinding.class)))
        .thenReturn(createOperation);
    return new BindingClient(client, namespaceBindings, operation, createOperation);
  }

  private record RoleClient(
      KubernetesClient client,
      NonNamespaceOperation<Role, RoleList, Resource<Role>> namespaceRoles,
      Resource<Role> operation,
      Resource<Role> createOperation) {}

  private record BindingClient(
      KubernetesClient client,
      NonNamespaceOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> namespaceBindings,
      Resource<RoleBinding> operation,
      Resource<RoleBinding> createOperation) {}
}
