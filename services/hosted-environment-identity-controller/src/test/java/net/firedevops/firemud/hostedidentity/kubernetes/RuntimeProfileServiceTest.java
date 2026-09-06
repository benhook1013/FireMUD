package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
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
  }
}
