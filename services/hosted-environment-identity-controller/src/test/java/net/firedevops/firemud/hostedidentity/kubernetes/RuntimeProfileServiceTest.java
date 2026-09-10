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
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import net.firedevops.firemud.hostedidentity.security.EnvironmentIdentityPlanner;
import org.junit.jupiter.api.Test;

class RuntimeProfileServiceTest {
  private final EnvironmentIdentityPlanner planner =
      new EnvironmentIdentityPlanner(new HostedIdentityProperties());
  private final RuntimeProfileService service =
      new RuntimeProfileService(new HostedIdentityProperties());

  @Test
  void environmentClassificationUsesCanonicalDevDemoAndPreviewValues() {
    assertTrue(HostedIdentityContract.isDevDemo(HostedIdentityContract.DEV_DEMO_NAME));
    assertFalse(HostedIdentityContract.isDevDemo("pr-42"));
    assertEquals(
        HostedIdentityContract.DEV_DEMO_ENVIRONMENT_CLASS,
        HostedIdentityContract.environmentClass(HostedIdentityContract.DEV_DEMO_NAME));
    assertEquals(
        HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS,
        HostedIdentityContract.environmentClass("pr-42"));
    assertEquals(
        HostedIdentityContract.PREVIEW_ENVIRONMENT_CLASS,
        HostedIdentityContract.environmentClass("unsupported"));
  }

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
            "firemud.dev/dev-demo",
            "true",
            "firemud.dev/environment-class",
            HostedIdentityContract.DEV_DEMO_ENVIRONMENT_CLASS));
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
  void runtimeLabelsRejectAnInvalidIdentityPlanNameBeforeReadingItsPreviewSuffix() {
    var invalidPlan = mock(EnvironmentIdentityPlan.class);
    when(invalidPlan.name()).thenReturn("x");

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> RuntimeProfileService.validateRuntimeLabels(invalidPlan, Map.of()));

    assertEquals("runtime Namespace has an invalid identity plan name", exception.getMessage());
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
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"));

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
  void runtimeProfileReadNormalizesUppercaseRequestedHeadPreservingIdentityAndPort() {
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
    assertEquals(32002, profile.telnetPort());
    assertTrue(profile.present());
    assertTrue(profile.deployedHeadMatchesRequest());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeProfileMapsAnAbsentRuntimeNamespaceToTheAbsentProfile() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get()).thenReturn(null);

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);

    assertEquals(RuntimeProfileService.RuntimeProfile.absent(), profile);
    assertFalse(profile.present());
  }

  @Test
  void exactComparisonIncludesEveryRuntimeIdentityField() {
    var expected =
        new RuntimeProfileService.RuntimeProfile(
            "runtime-uid", "a".repeat(40), "a".repeat(40), 32002, true);

    assertTrue(RuntimeProfileService.exactlyMatches(expected, expected));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "other-uid", "a".repeat(40), "a".repeat(40), 32002, true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid", "b".repeat(40), "a".repeat(40), 32002, true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid", "a".repeat(40), "b".repeat(40), 32002, true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid", "a".repeat(40), "a".repeat(40), 32003, true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected, RuntimeProfileService.RuntimeProfile.absent()));
    assertEquals(
        "Telnet port",
        RuntimeProfileService.changedFields(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid", "a".repeat(40), "a".repeat(40), 32003, true)));
    assertEquals(
        "runtime Namespace UID, requested head, deployed head, Telnet port",
        RuntimeProfileService.changedFields(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "other-uid", "b".repeat(40), "b".repeat(40), 32003, true)));
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

    Namespace malformedHead = previewRuntimeNamespace("not-a-head", "a".repeat(40), "32001");
    when(namespace.get()).thenReturn(malformedHead);
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
                    "firemud.dev/dev-demo",
                    "true",
                    "firemud.dev/environment-class",
                    HostedIdentityContract.DEV_DEMO_ENVIRONMENT_CLASS))
            .addToAnnotations("firemud.dev/last-dev-demo-telnet-port", "32016");
    if (requestedHead != null) {
      builder.addToAnnotations("firemud.dev/requested-dev-demo-head-sha", requestedHead);
    }
    if (deployedHead != null) {
      builder.addToAnnotations("firemud.dev/last-dev-demo-head-sha", deployedHead);
    }
    return builder.endMetadata().build();
  }
}
