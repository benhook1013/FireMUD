package net.firedevops.firemud.hostedidentity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import org.junit.jupiter.api.Test;

class EnvironmentIdentityPlannerTest {
  private final EnvironmentIdentityPlanner planner =
      new EnvironmentIdentityPlanner(new HostedIdentityProperties());

  @Test
  void derivesPreviewTargetsFromOnlyTheClosedNameContract() {
    var plan = planner.plan("pr-42");

    assertEquals("firemud-system", plan.controlNamespace());
    assertEquals("pr-42-identity", plan.identityNamespace());
    assertEquals("pr-42", plan.runtimeNamespace());
    assertEquals("pr-42.preview.firedevops.net", plan.hostname());
    assertEquals("pr-42-tls", plan.ingressSecretName());
    assertEquals("pr-42-telnet-tls", plan.telnetSecretName());
    assertEquals("firemud-grpc-tls", plan.grpcSecretName());
    assertEquals(11, plan.grpcConsumers().size());
  }

  @Test
  void derivesDevDemoWithoutAcceptingUserSuppliedTopology() {
    var plan = planner.plan("dev-demo");

    assertEquals("dev-identity", plan.identityNamespace());
    assertEquals("dev", plan.runtimeNamespace());
    assertEquals("dev.preview.firedevops.net", plan.hostname());
    assertEquals("dev-tls", plan.ingressCertificateName());
    assertEquals("dev-tls", plan.ingressSecretName());
    assertEquals("dev-telnet-tls", plan.telnetCertificateName());
    assertEquals("dev-telnet-tls", plan.telnetSecretName());
  }

  @Test
  void rejectsNamesOutsideTheFixedEnvironmentSet() {
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-0"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("production"));
    assertThrows(IllegalArgumentException.class, () -> planner.plan("pr-42-other"));
  }
}
