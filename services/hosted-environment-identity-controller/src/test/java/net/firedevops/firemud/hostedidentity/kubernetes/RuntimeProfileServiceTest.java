package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import java.util.HashMap;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;

class RuntimeProfileServiceTest {
  private final EnvironmentIdentityPlanner planner =
      new EnvironmentIdentityPlanner(new HostedIdentityProperties());

  @Test
  void previewPortsAreLimitedToTheAllocatedSixteenPortWindow() {
    var plan = planner.plan("pr-42");
    assertTrue(RuntimeProfileService.isValidTelnetPort(plan, 32000));
    assertTrue(RuntimeProfileService.isValidTelnetPort(plan, 32015));
    assertFalse(RuntimeProfileService.isValidTelnetPort(plan, 31999));
    assertFalse(RuntimeProfileService.isValidTelnetPort(plan, 32016));
    assertFalse(RuntimeProfileService.isValidTelnetPort(plan, 32042));
  }

  @Test
  void devDemoUsesOnlyItsFixedPort() {
    var plan = planner.plan("dev-demo");
    assertTrue(RuntimeProfileService.isValidTelnetPort(plan, 32016));
    assertFalse(RuntimeProfileService.isValidTelnetPort(plan, 32015));
    assertFalse(RuntimeProfileService.isValidTelnetPort(plan, 32116));
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
}
