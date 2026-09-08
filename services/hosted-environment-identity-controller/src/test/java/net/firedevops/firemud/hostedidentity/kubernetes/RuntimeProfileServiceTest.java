package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.HashMap;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;

class RuntimeProfileServiceTest {
  private final EnvironmentIdentityPlanner planner =
      new EnvironmentIdentityPlanner(new HostedIdentityProperties());
  private final RuntimeProfileService service =
      new RuntimeProfileService(new HostedIdentityProperties());

  @Test
  void previewPortsAreLimitedToTheAllocatedSixteenPortWindow() {
    var plan = planner.plan("pr-42");
    assertTrue(service.isValidTelnetPort(plan, 32000));
    assertTrue(service.isValidTelnetPort(plan, 32015));
    assertFalse(service.isValidTelnetPort(plan, 31999));
    assertFalse(service.isValidTelnetPort(plan, 32016));
    assertFalse(service.isValidTelnetPort(plan, 32042));
  }

  @Test
  void devDemoUsesOnlyItsFixedPort() {
    var plan = planner.plan("dev-demo");
    assertTrue(service.isValidTelnetPort(plan, 32016));
    assertFalse(service.isValidTelnetPort(plan, 32015));
    assertFalse(service.isValidTelnetPort(plan, 32116));
  }

  @Test
  void configuredPortsOwnTheRuntimeValidationBoundary() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setPreviewTelnetPortBase(41000);
    properties.setDevDemoTelnetPort(42000);
    RuntimeProfileService configured = new RuntimeProfileService(properties);

    assertTrue(configured.isValidTelnetPort(planner.plan("pr-42"), 41000));
    assertTrue(configured.isValidTelnetPort(planner.plan("pr-42"), 41015));
    assertFalse(configured.isValidTelnetPort(planner.plan("pr-42"), 32000));
    assertTrue(configured.isValidTelnetPort(planner.plan("dev-demo"), 42000));
    assertFalse(configured.isValidTelnetPort(planner.plan("dev-demo"), 32016));
  }

  @Test
  void runtimeLabelsMustBelongToTheDerivedEnvironment() {
    var devPlan = planner.plan("dev-demo");
    RuntimeProfileService.validateRuntimeLabels(
        devPlan,
        Map.of(
            "firemud.dev/dev-demo", "true", "firemud.dev/environment-class", "dev-demo-cluster"));
    assertThrows(
        IllegalStateException.class,
        () ->
            RuntimeProfileService.validateRuntimeLabels(
                devPlan, Map.of("firemud.dev/dev-demo", "true")));

    var previewPlan = planner.plan("pr-42");
    RuntimeProfileService.validateRuntimeLabels(
        previewPlan, Map.of("firemud.dev/preview", "true", "firemud.dev/pr-number", "42"));
    assertThrows(
        IllegalStateException.class,
        () ->
            RuntimeProfileService.validateRuntimeLabels(
                previewPlan, Map.of("firemud.dev/preview", "true", "firemud.dev/pr-number", "43")));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeProfileReadsTheExactPresentIdentityTuple() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("A".repeat(40), "a".repeat(40), "32002"));

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);

    assertEquals("runtime-uid", profile.runtimeNamespaceUid());
    assertEquals("a".repeat(40), profile.requestedHeadSha());
    assertEquals("a".repeat(40), profile.deployedHeadSha());
    assertTrue(profile.deployedHeadMatchesRequest());
    assertEquals(32002, profile.telnetPort());
    assertTrue(profile.present());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void previewSeparatesRequestedHeadFromSuccessfulDeploymentEvidence() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            previewRuntimeNamespace("a".repeat(40), null, "32002"),
            previewRuntimeNamespace("a".repeat(40), "b".repeat(40), "32002"),
            previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
            previewRuntimeNamespace("a".repeat(40), "not-a-head", "32002"));

    RuntimeProfileService.RuntimeProfile beforeDeployment = service.read(client, plan);
    assertEquals("a".repeat(40), beforeDeployment.requestedHeadSha());
    assertEquals(null, beforeDeployment.deployedHeadSha());
    assertFalse(beforeDeployment.deployedHeadMatchesRequest());

    RuntimeProfileService.RuntimeProfile staleDeployment = service.read(client, plan);
    assertEquals("b".repeat(40), staleDeployment.deployedHeadSha());
    assertFalse(staleDeployment.deployedHeadMatchesRequest());

    RuntimeProfileService.RuntimeProfile deployed = service.read(client, plan);
    assertTrue(deployed.deployedHeadMatchesRequest());

    assertEquals(
        "runtime Namespace has an invalid canonical deployed head identity",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void devDemoSeparatesRequestedHeadFromSuccessfulDeploymentEvidence() {
    var plan = planner.plan("dev-demo");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            devDemoRuntimeNamespace("a".repeat(40), null),
            devDemoRuntimeNamespace("a".repeat(40), "b".repeat(40)),
            devDemoRuntimeNamespace("a".repeat(40), "a".repeat(40)),
            devDemoRuntimeNamespace("a".repeat(40), "not-a-head"));

    RuntimeProfileService.RuntimeProfile beforeHelm = service.read(client, plan);
    assertEquals("a".repeat(40), beforeHelm.requestedHeadSha());
    assertEquals(null, beforeHelm.deployedHeadSha());
    assertFalse(beforeHelm.deployedHeadMatchesRequest());

    RuntimeProfileService.RuntimeProfile staleDeployment = service.read(client, plan);
    assertEquals("b".repeat(40), staleDeployment.deployedHeadSha());
    assertFalse(staleDeployment.deployedHeadMatchesRequest());

    RuntimeProfileService.RuntimeProfile deployed = service.read(client, plan);
    assertTrue(deployed.deployedHeadMatchesRequest());

    assertEquals(
        "runtime Namespace has an invalid canonical deployed head identity",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeProfileRejectsIncompleteOrInvalidRuntimeIdentity() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(anyString())).thenReturn(namespace);

    Namespace missingUid =
        new NamespaceBuilder().withNewMetadata().withName("pr-42").endMetadata().build();
    when(namespace.get()).thenReturn(missingUid);
    assertEquals(
        "runtime Namespace has no stable UID",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());

    Namespace missingHead = previewRuntimeNamespace(null, "a".repeat(40), "32001");
    when(namespace.get()).thenReturn(missingHead);
    assertEquals(
        "runtime Namespace has no canonical requested head identity",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());

    Namespace missingPort = previewRuntimeNamespace("a".repeat(40), "a".repeat(40), null);
    when(namespace.get()).thenReturn(missingPort);
    assertEquals(
        "runtime Namespace has no canonical Telnet port identity",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());

    Namespace invalidPort = previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32016");
    when(namespace.get()).thenReturn(invalidPort);
    assertEquals(
        "runtime Namespace has an invalid Telnet port identity",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());
  }

  @Test
  void retainedIdentityNamespaceMustHaveOnlyItsDerivedControllerLabels() {
    var devPlan = planner.plan("dev-demo");
    var valid =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("dev-identity")
            .withLabels(
                Map.of(
                    "firemud.dev/managed-by", "hosted-identity-controller",
                    "firemud.dev/identity-name", "dev-demo",
                    "firemud.dev/retention", "retained",
                    "firemud.dev/environment-class", "dev-demo-cluster"))
            .endMetadata()
            .build();
    assertTrue(HostedIdentityScopeService.isExpectedIdentityNamespace(valid, devPlan));

    Map<String, String> injectedLabels = new HashMap<>(valid.getMetadata().getLabels());
    injectedLabels.put("kubernetes.io/metadata.name", "dev-identity");
    var apiRoundTripped =
        new NamespaceBuilder(valid)
            .editMetadata()
            .withLabels(injectedLabels)
            .withUid("api-uid")
            .withResourceVersion("9")
            .endMetadata()
            .build();
    assertTrue(HostedIdentityScopeService.isExpectedIdentityNamespace(apiRoundTripped, devPlan));

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

    var previewPlan = planner.plan("pr-42");
    var preview =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("pr-42-identity")
            .withLabels(
                Map.of(
                    "firemud.dev/managed-by", "hosted-identity-controller",
                    "firemud.dev/identity-name", "pr-42",
                    "firemud.dev/retention", "retained",
                    "firemud.dev/environment-class", "pr-preview"))
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
    assertFalse(
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
    roundTripped.getRules().get(0).setResourceNames(null);
    roundTripped.getRules().get(0).setNonResourceURLs(null);
    assertTrue(HostedIdentityScopeService.roleEquivalent(roundTripped, desired));

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
    assertTrue(HostedIdentityScopeService.bindingEquivalent(roundTripped, desired));
    roundTripped.getMetadata().setAnnotations(Map.of("unexpected", "ownership"));
    assertFalse(HostedIdentityScopeService.bindingEquivalent(roundTripped, desired));
  }

  private static Namespace previewRuntimeNamespace(
      String requestedHead, String deployedHead, String port) {
    var builder =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("pr-42")
            .withUid("runtime-uid")
            .withLabels(Map.of("firemud.dev/preview", "true", "firemud.dev/pr-number", "42"));
    if (requestedHead != null) {
      builder.addToAnnotations("firemud.dev/requested-preview-head-sha", requestedHead);
    }
    if (deployedHead != null) {
      builder.addToAnnotations("firemud.dev/last-preview-head-sha", deployedHead);
    }
    if (port != null) {
      builder.addToAnnotations("firemud.dev/last-preview-telnet-port", port);
    }
    return builder.endMetadata().build();
  }

  private static Namespace devDemoRuntimeNamespace(String requestedHead, String deployedHead) {
    var builder =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("dev")
            .withUid("runtime-uid")
            .withLabels(
                Map.of(
                    "firemud.dev/dev-demo", "true",
                    "firemud.dev/environment-class", "dev-demo-cluster"))
            .addToAnnotations("firemud.dev/requested-dev-demo-head-sha", requestedHead)
            .addToAnnotations("firemud.dev/last-dev-demo-telnet-port", "32016");
    if (deployedHead != null) {
      builder.addToAnnotations("firemud.dev/last-dev-demo-head-sha", deployedHead);
    }
    return builder.endMetadata().build();
  }
}
