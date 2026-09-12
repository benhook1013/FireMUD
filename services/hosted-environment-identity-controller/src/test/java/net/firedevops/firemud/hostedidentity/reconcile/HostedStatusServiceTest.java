package net.firedevops.firemud.hostedidentity.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.kubernetes.RuntimeProfileService;
import net.firedevops.firemud.hostedidentity.model.HostedCondition;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentity;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus;
import net.firedevops.firemud.hostedidentity.model.HostedEnvironmentIdentityStatus.RuntimeProfile;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class HostedStatusServiceTest {
  @Test
  void runtimeNamespaceRecreationInvalidatesPreviouslyReadyTuple() {
    RuntimeProfile previous = new RuntimeProfile();
    previous.setRuntimeNamespaceUid("uid-before");
    previous.setRequestedHeadSha("head-before");
    previous.setDeployedHeadSha("head-before");
    previous.setTelnetPort(32002);
    var current =
        new RuntimeProfileService.RuntimeProfile(
            "uid-after", "head-after", "head-after", 32002, true);

    assertFalse(HostedStatusService.profileMatches(previous, current));
    assertTrue(
        HostedStatusService.profileMatches(
            previous,
            new RuntimeProfileService.RuntimeProfile(
                "uid-before", "head-before", "head-before", 32002, true)));
  }

  @Test
  void readyConditionIsClearedForTelnetPortDrift() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(12L)
            .build());
    RuntimeProfile previous = new RuntimeProfile();
    previous.setRuntimeNamespaceUid("uid");
    previous.setRequestedHeadSha("a".repeat(40));
    previous.setDeployedHeadSha("a".repeat(40));
    previous.setTelnetPort(32002);
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    oldStatus.setProfile(previous);
    oldStatus.setConditions(
        java.util.List.of(new HostedCondition("Ready", "True", "Reconciled", "served")));
    resource.setStatus(oldStatus);
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    RuntimeProfileService.RuntimeProfile current =
        new RuntimeProfileService.RuntimeProfile(
            "uid", "a".repeat(40), "a".repeat(40), 32003, true);
    HostedEnvironmentIdentityStatus.RoleStatus role =
        HostedStatusService.role(
            "sha256:" + "b".repeat(64), 1L, 1L, "c".repeat(64), "cert-manager", "accepted");

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Ready,
        "Reconciled",
        "served",
        true,
        current,
        role,
        role,
        role,
        role,
        role);

    assertFalse(HostedStatusService.profileMatches(previous, current));
    assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, resource.getStatus().getPhase());
    assertEquals("RuntimeIdentityChanged", resource.getStatus().getConditions().get(0).getReason());
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
    oldProfile.setRequestedHeadSha("head-before");
    oldProfile.setDeployedHeadSha("head-before");
    oldStatus.setProfile(oldProfile);
    resource.setStatus(oldStatus);
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));

    var changed =
        new RuntimeProfileService.RuntimeProfile(
            "uid-after", "head-after", "head-after", 32002, true);
    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Ready,
        "Reconciled",
        "served",
        true,
        changed,
        null,
        null,
        null,
        null,
        null);

    assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, resource.getStatus().getPhase());
    assertEquals("RuntimeIdentityChanged", resource.getStatus().getConditions().get(0).getReason());
  }

  @Test
  void firstRuntimeProfileObservationUsesDistinctPendingReason() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(7L)
            .build());
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    var observed =
        new RuntimeProfileService.RuntimeProfile(
            "uid-observed", "head-observed", "head-observed", 32002, true);

    HostedEnvironmentIdentityStatus status =
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Ready,
            "Reconciled",
            "served",
            true,
            observed,
            null,
            null,
            null,
            null,
            null);

    HostedCondition condition = status.getConditions().get(0);
    assertEquals("False", condition.getStatus());
    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, status.getPhase());
    assertEquals("RuntimeIdentityObserved", condition.getReason());
    assertEquals(
        "runtime identity was observed for the first time; fresh convergence is required",
        condition.getMessage());
  }

  @Test
  void missingOrExplicitAbsencePreservesRuntimeTuple() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(8L)
            .build());
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    RuntimeProfile oldProfile = new RuntimeProfile();
    oldProfile.setRuntimeNamespaceUid("uid-recorded");
    oldProfile.setRequestedHeadSha("head-recorded");
    oldProfile.setDeployedHeadSha("head-recorded");
    oldProfile.setTelnetPort(32007);
    oldStatus.setProfile(oldProfile);
    resource.setStatus(oldStatus);
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        "Error",
        "error",
        false,
        null,
        null,
        null,
        null,
        null,
        null);
    assertEquals("uid-recorded", resource.getStatus().getProfile().getRuntimeNamespaceUid());
    assertEquals("head-recorded", resource.getStatus().getProfile().getRequestedHeadSha());
    assertEquals("head-recorded", resource.getStatus().getProfile().getDeployedHeadSha());
    assertEquals(32007, resource.getStatus().getProfile().getTelnetPort());

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.RuntimeAbsent,
        "Absent",
        "absent",
        false,
        RuntimeProfileService.RuntimeProfile.absent(),
        null,
        null,
        null,
        null,
        null);
    assertEquals("uid-recorded", resource.getStatus().getProfile().getRuntimeNamespaceUid());
    assertEquals("head-recorded", resource.getStatus().getProfile().getRequestedHeadSha());
    assertEquals("head-recorded", resource.getStatus().getProfile().getDeployedHeadSha());
    assertEquals(32007, resource.getStatus().getProfile().getTelnetPort());
  }

  @Test
  void unplannableResourceStillPublishesBlockedStatus() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("not-a-valid-hosted-identity-name")
            .withNamespace("firemud-system")
            .withGeneration(8L)
            .build());
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    RuntimeProfile oldProfile = new RuntimeProfile();
    oldProfile.setRuntimeNamespaceUid("uid-recorded");
    oldProfile.setRequestedHeadSha("head-recorded");
    oldProfile.setDeployedHeadSha("head-recorded");
    oldProfile.setTelnetPort(32007);
    oldStatus.setProfile(oldProfile);
    resource.setStatus(oldStatus);

    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        "ReconciliationBlocked",
        "invalid resource name",
        false,
        null,
        null,
        null,
        null,
        null,
        null);

    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, resource.getStatus().getPhase());
    assertEquals("uid-recorded", resource.getStatus().getProfile().getRuntimeNamespaceUid());
    assertEquals("head-recorded", resource.getStatus().getProfile().getRequestedHeadSha());
    assertEquals("head-recorded", resource.getStatus().getProfile().getDeployedHeadSha());
    assertEquals(32007, resource.getStatus().getProfile().getTelnetPort());
  }

  @Test
  void missingMetadataStillBuildsBlockedStatus(CapturedOutput output) {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(null);
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    RuntimeProfile oldProfile = new RuntimeProfile();
    oldProfile.setName("pr-42");
    oldProfile.setRuntimeNamespace("pr-42");
    oldStatus.setProfile(oldProfile);
    resource.setStatus(oldStatus);
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));

    HostedEnvironmentIdentityStatus status =
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Blocked,
            "ReconciliationBlocked",
            "HostedEnvironmentIdentity metadata is required",
            false,
            null,
            null,
            null,
            null,
            null,
            null);

    assertEquals(HostedEnvironmentIdentityStatus.Phase.Blocked, status.getPhase());
    assertEquals("ReconciliationBlocked", status.getConditions().get(0).getReason());
    assertNull(status.getObservedGeneration());
    assertNull(status.getConditions().get(0).getObservedGeneration());
    assertEquals("pr-42", status.getProfile().getName());
    assertEquals("pr-42", status.getProfile().getRuntimeNamespace());
    assertTrue(
        output
            .getOut()
            .contains(
                "Unable to plan hosted identity resource '<unknown>'; preserving previous runtime profile"));
  }

  @Test
  void plannerRuntimeFailureStillPreservesThePreviousProfile(CapturedOutput output) {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(8L)
            .build());
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    RuntimeProfile oldProfile = new RuntimeProfile();
    oldProfile.setName("pr-42");
    oldProfile.setEnvironmentClass(HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS);
    oldProfile.setIdentityNamespace("pr-42-identity");
    oldProfile.setRuntimeNamespace("pr-42");
    oldProfile.setHostname("pr-42.preview.firedevops.net");
    oldProfile.setRuntimeNamespaceUid("uid-recorded");
    oldProfile.setRequestedHeadSha("head-recorded");
    oldProfile.setDeployedHeadSha("head-recorded");
    oldProfile.setTelnetPort(32007);
    oldStatus.setProfile(oldProfile);
    resource.setStatus(oldStatus);

    EnvironmentIdentityPlanner planner = mock(EnvironmentIdentityPlanner.class);
    when(planner.plan("pr-42")).thenThrow(new IllegalStateException("unexpected planner failure"));
    var service = new HostedStatusService(planner);

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        "ReconciliationBlocked",
        "planner failed",
        false,
        null,
        null,
        null,
        null,
        null,
        null);

    RuntimeProfile profile = resource.getStatus().getProfile();
    assertEquals("pr-42", profile.getName());
    assertEquals(HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS, profile.getEnvironmentClass());
    assertEquals("pr-42-identity", profile.getIdentityNamespace());
    assertEquals("pr-42", profile.getRuntimeNamespace());
    assertEquals("pr-42.preview.firedevops.net", profile.getHostname());
    assertEquals("uid-recorded", profile.getRuntimeNamespaceUid());
    assertEquals("head-recorded", profile.getRequestedHeadSha());
    assertEquals("head-recorded", profile.getDeployedHeadSha());
    assertEquals(32007, profile.getTelnetPort());
    assertTrue(
        output
            .getOut()
            .contains(
                "Unable to plan hosted identity resource 'pr-42'; preserving previous runtime profile"));
    assertTrue(output.getOut().contains("IllegalStateException: unexpected planner failure"));
    assertFalse(output.getOut().contains("head-recorded"));
    assertFalse(output.getOut().contains("pr-42.preview.firedevops.net"));
  }

  @Test
  void readyTransitionTimeChangesOnlyWhenStatusChanges() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(9L)
            .build());
    HostedEnvironmentIdentityStatus oldStatus = new HostedEnvironmentIdentityStatus();
    HostedCondition previous = new HostedCondition("Ready", "False", "Waiting", "not ready");
    previous.setLastTransitionTime("2026-01-01T00:00:00Z");
    oldStatus.setConditions(java.util.List.of(previous));
    resource.setStatus(oldStatus);
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    var runtimeProfile =
        new RuntimeProfileService.RuntimeProfile(
            "uid", "a".repeat(40), "a".repeat(40), 32001, true);

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Pending,
        "Waiting",
        "not ready",
        false,
        runtimeProfile,
        null,
        null,
        null,
        null,
        null);
    assertEquals(
        "2026-01-01T00:00:00Z",
        resource.getStatus().getConditions().get(0).getLastTransitionTime());

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        "Blocked",
        "not ready",
        false,
        runtimeProfile,
        null,
        null,
        null,
        null,
        null);
    assertEquals(
        "2026-01-01T00:00:00Z",
        resource.getStatus().getConditions().get(0).getLastTransitionTime());

    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Blocked,
        "Blocked",
        "still not ready",
        false,
        runtimeProfile,
        null,
        null,
        null,
        null,
        null);
    assertEquals(
        "2026-01-01T00:00:00Z",
        resource.getStatus().getConditions().get(0).getLastTransitionTime());

    var role = new HostedEnvironmentIdentityStatus.RoleStatus();
    role.setRevision("sha256:" + "a".repeat(64));
    service.status(
        resource,
        HostedEnvironmentIdentityStatus.Phase.Ready,
        "Reconciled",
        "ready",
        true,
        runtimeProfile,
        role,
        role,
        role,
        role,
        role);
    assertEquals("True", resource.getStatus().getConditions().get(0).getStatus());
    assertNotEquals(
        "2026-01-01T00:00:00Z",
        resource.getStatus().getConditions().get(0).getLastTransitionTime());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void readyRequiresAllFiveIdentityRevisionsIncludingBothWebSocketRoles(int missingRole) {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("pr-42")
            .withNamespace("firemud-system")
            .withGeneration(10L)
            .build());
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    var profile =
        new RuntimeProfileService.RuntimeProfile(
            "uid", "a".repeat(40), "a".repeat(40), 32001, true);
    var role = new HostedEnvironmentIdentityStatus.RoleStatus();
    role.setRevision("sha256:" + "b".repeat(64));
    HostedEnvironmentIdentityStatus.RoleStatus[] roles = {role, role, role, role, role};
    roles[missingRole] = null;

    resource.setStatus(
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Pending,
            "Syncing",
            "syncing",
            false,
            profile,
            role,
            role,
            role,
            role,
            role));
    resource.setStatus(
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Ready,
            "Reconciled",
            "served",
            true,
            profile,
            roles[0],
            roles[1],
            roles[2],
            roles[3],
            roles[4]));

    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, resource.getStatus().getPhase());
    assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    assertEquals(
        "IdentityEvidenceIncomplete", resource.getStatus().getConditions().get(0).getReason());
  }

  @Test
  void readyRequiresDeployedHeadToMatchTheRequestedHead() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("dev-demo")
            .withNamespace("firemud-system")
            .withGeneration(11L)
            .build());
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    var profile =
        new RuntimeProfileService.RuntimeProfile(
            "uid", "a".repeat(40), "b".repeat(40), 32016, true);
    var role = new HostedEnvironmentIdentityStatus.RoleStatus();
    role.setRevision("sha256:" + "c".repeat(64));

    resource.setStatus(
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Verifying,
            "Reconciled",
            "waiting",
            false,
            profile,
            role,
            role,
            role,
            role,
            role));
    resource.setStatus(
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Ready,
            "Reconciled",
            "served",
            true,
            profile,
            role,
            role,
            role,
            role,
            role));

    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, resource.getStatus().getPhase());
    assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    assertEquals(
        "RuntimeDeploymentPending", resource.getStatus().getConditions().get(0).getReason());
    assertEquals("a".repeat(40), resource.getStatus().getProfile().getRequestedHeadSha());
    assertEquals("b".repeat(40), resource.getStatus().getProfile().getDeployedHeadSha());
  }

  @Test
  void readyRequiresAPresentRuntimeProfile() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName("dev-demo")
            .withNamespace("firemud-system")
            .withGeneration(11L)
            .build());
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    var role = new HostedEnvironmentIdentityStatus.RoleStatus();
    role.setRevision("sha256:" + "c".repeat(64));

    HostedEnvironmentIdentityStatus status =
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Ready,
            "Reconciled",
            "served",
            true,
            null,
            role,
            role,
            role,
            role,
            role);

    assertEquals(HostedEnvironmentIdentityStatus.Phase.Pending, status.getPhase());
    assertEquals("False", status.getConditions().get(0).getStatus());
    assertEquals("RuntimeDeploymentPending", status.getConditions().get(0).getReason());
  }

  @Test
  void statusPreservesRoleRevisionOrderAcrossAllFiveRoles() {
    HostedEnvironmentIdentity resource = new HostedEnvironmentIdentity();
    resource.setMetadata(
        new ObjectMetaBuilder().withName("pr-42").withNamespace("firemud-system").build());
    var service =
        new HostedStatusService(new EnvironmentIdentityPlanner(new HostedIdentityProperties()));
    var status =
        service.status(
            resource,
            HostedEnvironmentIdentityStatus.Phase.Pending,
            "Syncing",
            "syncing",
            false,
            null,
            role("sha256:" + "1".repeat(64)),
            role("sha256:" + "2".repeat(64)),
            role("sha256:" + "3".repeat(64)),
            role("sha256:" + "4".repeat(64)),
            role("sha256:" + "5".repeat(64)));

    assertEquals("sha256:" + "1".repeat(64), status.getIngress().getRevision());
    assertEquals("sha256:" + "2".repeat(64), status.getTelnet().getRevision());
    assertEquals("sha256:" + "3".repeat(64), status.getGatewayInternalWs().getRevision());
    assertEquals("sha256:" + "4".repeat(64), status.getTcpProxyBridge().getRevision());
    assertEquals("sha256:" + "5".repeat(64), status.getGrpc().getRevision());
  }

  @ParameterizedTest
  @EnumSource(RoleField.class)
  void statusDtoDefensivelyCopiesEveryRole(RoleField roleField) {
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    HostedEnvironmentIdentityStatus.RoleStatus input =
        new HostedEnvironmentIdentityStatus.RoleStatus();
    input.setRevision("sha256:" + "a".repeat(64));
    setRole(status, roleField, input);

    input.setRevision("mutated-input");
    HostedEnvironmentIdentityStatus.RoleStatus returned = getRole(status, roleField);
    returned.setRevision("mutated-output");

    assertEquals("sha256:" + "a".repeat(64), getRole(status, roleField).getRevision());
  }

  @Test
  void statusDtoDefensivelyCopiesNestedConditionAndProfileState() {
    HostedEnvironmentIdentityStatus status = new HostedEnvironmentIdentityStatus();
    HostedCondition condition = new HostedCondition("Ready", "False", "Pending", "pending");
    RuntimeProfile profile = new RuntimeProfile();
    profile.setRuntimeNamespaceUid("uid-before");
    profile.setRequestedHeadSha("head-before");
    status.setConditions(java.util.List.of(condition));
    status.setProfile(profile);

    condition.setReason("mutated-input");
    profile.setRuntimeNamespaceUid("mutated-input");
    profile.setRequestedHeadSha("mutated-input");
    HostedCondition returnedCondition = status.getConditions().get(0);
    RuntimeProfile returnedProfile = status.getProfile();
    returnedCondition.setReason("mutated-output");
    returnedProfile.setRuntimeNamespaceUid("mutated-output");
    returnedProfile.setRequestedHeadSha("mutated-output");

    assertEquals("Pending", status.getConditions().get(0).getReason());
    assertEquals("uid-before", status.getProfile().getRuntimeNamespaceUid());
    assertEquals("head-before", status.getProfile().getRequestedHeadSha());
  }

  private static void setRole(
      HostedEnvironmentIdentityStatus status,
      RoleField roleField,
      HostedEnvironmentIdentityStatus.RoleStatus role) {
    switch (roleField) {
      case INGRESS -> status.setIngress(role);
      case TELNET -> status.setTelnet(role);
      case GATEWAY_INTERNAL_WS -> status.setGatewayInternalWs(role);
      case TCP_PROXY_BRIDGE -> status.setTcpProxyBridge(role);
      case GRPC -> status.setGrpc(role);
    }
  }

  private static HostedEnvironmentIdentityStatus.RoleStatus getRole(
      HostedEnvironmentIdentityStatus status, RoleField roleField) {
    return switch (roleField) {
      case INGRESS -> status.getIngress();
      case TELNET -> status.getTelnet();
      case GATEWAY_INTERNAL_WS -> status.getGatewayInternalWs();
      case TCP_PROXY_BRIDGE -> status.getTcpProxyBridge();
      case GRPC -> status.getGrpc();
    };
  }

  private enum RoleField {
    INGRESS,
    TELNET,
    GATEWAY_INTERNAL_WS,
    TCP_PROXY_BRIDGE,
    GRPC
  }

  private static HostedEnvironmentIdentityStatus.RoleStatus role(String revision) {
    var role = new HostedEnvironmentIdentityStatus.RoleStatus();
    role.setRevision(revision);
    return role;
  }
}
