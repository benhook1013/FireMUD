package net.firedevops.firemud.hostedidentity.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.RuntimeProfile;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;

class HostedStatusServiceTest {
  @Test
  void runtimeNamespaceRecreationInvalidatesPreviouslyReadyTuple() {
    RuntimeProfile previous = new RuntimeProfile();
    previous.setRuntimeNamespaceUid("uid-before");
    previous.setDeployedHeadSha("head-before");
    var current = new RuntimeProfileService.RuntimeProfile("uid-after", "head-after", 32042, true);

    assertFalse(HostedStatusService.profileMatches(previous, current));
    assertTrue(
        HostedStatusService.profileMatches(
            previous,
            new RuntimeProfileService.RuntimeProfile("uid-before", "head-before", 32042, true)));
  }

  @Test
  void readyConditionIsClearedForAChangedTupleAtTheSameCrGeneration() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(7L)
            .build());
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    RuntimeProfile oldProfile = new RuntimeProfile();
    oldProfile.setRuntimeNamespaceUid("uid-before");
    oldProfile.setDeployedHeadSha("head-before");
    oldStatus.setProfile(oldProfile);
    resource.setStatus(oldStatus);
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));

    var changed = new RuntimeProfileService.RuntimeProfile("uid-after", "head-after", 32042, true);
    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Ready,
        "Reconciled",
        "served",
        true,
        changed,
        null,
        null,
        null);

    assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, resource.getStatus().getPhase());
  }
}
