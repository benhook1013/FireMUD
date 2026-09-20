package net.firedevops.firemud.hostedidentity.kubernetes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import java.util.List;
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
        previewPlan,
        Map.of(
            "firemud.dev/preview",
            "true",
            "firemud.dev/pr-number",
            "42",
            HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL,
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE));
    assertThrows(
        IllegalStateException.class,
        () ->
            RuntimeProfileService.validateRuntimeLabels(
                previewPlan,
                Map.of(
                    "firemud.dev/preview",
                    "true",
                    "firemud.dev/pr-number",
                    "43",
                    HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL,
                    HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE)));
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
    stubTcpProxyService(client, plan, "NodePort", 32002);

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
    stubTcpProxyService(client, plan, "NodePort", 32002);

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
  void runtimeProfileReadNormalizesUppercaseDeployedHeadPreservingIdentityAndPort() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "A".repeat(40), "32002"));
    stubTcpProxyService(client, plan, "NodePort", 32002);

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);

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
            "runtime-uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
            32002,
            true);

    assertTrue(RuntimeProfileService.exactlyMatches(expected, expected));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "other-uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32002,
                true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid",
                "b".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32002,
                true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid",
                "a".repeat(40),
                "b".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32002,
                true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32003,
                true)));
    assertFalse(
        RuntimeProfileService.exactlyMatches(
            expected, RuntimeProfileService.RuntimeProfile.absent()));
    assertEquals(
        "Telnet port",
        RuntimeProfileService.changedFields(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "runtime-uid",
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32003,
                true)));
    assertEquals(
        "runtime Namespace UID, requested head, deployed head, Telnet port",
        RuntimeProfileService.changedFields(
            expected,
            new RuntimeProfileService.RuntimeProfile(
                "other-uid",
                "b".repeat(40),
                "b".repeat(40),
                HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE,
                32003,
                true)));
    var privateProfile =
        new RuntimeProfileService.RuntimeProfile(
            "runtime-uid",
            "a".repeat(40),
            "a".repeat(40),
            HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE,
            0,
            true);
    assertFalse(RuntimeProfileService.exactlyMatches(expected, privateProfile));
    assertEquals(
        "exposure mode, Telnet port",
        RuntimeProfileService.changedFields(expected, privateProfile));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void privatePreviewUsesAZeroSentinelAndDoesNotRequireATelnetAnnotation() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            previewRuntimeNamespace(
                "a".repeat(40),
                "a".repeat(40),
                null,
                HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE));
    stubTcpProxyService(client, plan, "ClusterIP", null);

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);

    assertEquals(HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE, profile.exposureMode());
    assertEquals(0, profile.telnetPort());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void privatePreviewRejectsAContradictoryTelnetAnnotation() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            previewRuntimeNamespace(
                "a".repeat(40),
                "a".repeat(40),
                "32002",
                HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> service.read(client, plan));

    assertEquals(
        "private runtime Namespace cannot carry a canonical Telnet port identity",
        failure.getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void previewExposureModeIsRequiredAndMustBeCanonical() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002", null));
    assertEquals(
        "runtime Namespace has an invalid firemud.dev/preview-exposure-mode label",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());

    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002", "internal"));
    assertEquals(
        "runtime Namespace has an invalid firemud.dev/preview-exposure-mode label",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());
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
    stubTcpProxyService(client, plan, "NodePort", 32002);

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
    stubTcpProxyService(client, plan, "NodePort", 32016);

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
  void devDemoIsAlwaysPublicAndRejectsPrivateExposureLabel() {
    var plan = planner.plan("dev-demo");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            devDemoRuntimeNamespace(
                "a".repeat(40),
                "a".repeat(40),
                HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE));

    assertEquals(
        "runtime Namespace has an invalid firemud.dev/preview-exposure-mode label",
        assertThrows(IllegalStateException.class, () -> service.read(client, plan)).getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeProfileRejectsAnAbsentTcpProxyService() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"));
    stubTcpProxyService(client, plan, (Service) null);

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.validateTcpProxyService(client, plan, profile));

    assertEquals("runtime tcp-proxy-service Service is absent or malformed", failure.getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeProfileFailsClosedWhenTcpProxyServiceCannotBeRead() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"));
    stubTcpProxyService(client, plan, new KubernetesClientException("forbidden", 403, null));

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.validateTcpProxyService(client, plan, profile));

    assertEquals("runtime tcp-proxy-service Service could not be read", failure.getMessage());
    assertInstanceOf(KubernetesClientException.class, failure.getCause());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void privateRuntimeRejectsAnyTcpProxyNodePort() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            previewRuntimeNamespace(
                "a".repeat(40),
                "a".repeat(40),
                null,
                HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE));
    stubTcpProxyService(client, plan, tcpProxyService("ClusterIP", 32002));

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.validateTcpProxyService(client, plan, profile));

    assertEquals(
        "private runtime tcp-proxy-service Service cannot carry a NodePort", failure.getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void publicRuntimeRejectsAServicePortWithTheWrongAllocatedNodePort() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"));
    stubTcpProxyService(client, plan, tcpProxyService("NodePort", 32001));

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.validateTcpProxyService(client, plan, profile));

    assertEquals(
        "public runtime tcp-proxy-service Service Telnet NodePort does not match the trusted allocation",
        failure.getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void publicRuntimeRejectsAClusterIpService() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"));
    stubTcpProxyService(client, plan, tcpProxyService("ClusterIP", null));

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.validateTcpProxyService(client, plan, profile));

    assertEquals(
        "runtime tcp-proxy-service Service type does not match exposure mode: expected NodePort",
        failure.getMessage());
  }

  @Test
  void runtimeProfileRejectsWrongAndEmptyTcpProxySelectors() {
    var plan = planner.plan("pr-42");
    String expectedMessage =
        "runtime tcp-proxy-service Service selector does not match canonical selector";

    assertEquals(
        expectedMessage,
        validateTcpProxyServiceFailure(
                plan,
                previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
                tcpProxyServiceWithSelector("NodePort", 32002, Map.of("app", "wrong-tcp-proxy")))
            .getMessage());
    assertEquals(
        expectedMessage,
        validateTcpProxyServiceFailure(
                plan,
                previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
                tcpProxyServiceWithSelector("NodePort", 32002, Map.of()))
            .getMessage());
  }

  @Test
  void runtimeProfileRejectsNonCanonicalTcpProxyPortFields() {
    var plan = planner.plan("pr-42");
    Namespace namespace = previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002");

    assertEquals(
        "runtime tcp-proxy-service Service Telnet port must be named tcp-2323",
        validateTcpProxyServiceFailure(
                plan,
                namespace,
                tcpProxyServiceWithPort(
                    "NodePort",
                    32002,
                    tcpProxyPort(32002, "tcp-wrong", "TCP", new IntOrString(2323))))
            .getMessage());
    assertEquals(
        "runtime tcp-proxy-service Service Telnet port protocol must be TCP",
        validateTcpProxyServiceFailure(
                plan,
                namespace,
                tcpProxyServiceWithPort(
                    "NodePort",
                    32002,
                    tcpProxyPort(32002, "tcp-2323", "UDP", new IntOrString(2323))))
            .getMessage());
    assertEquals(
        "runtime tcp-proxy-service Service Telnet targetPort must be 2323",
        validateTcpProxyServiceFailure(
                plan,
                namespace,
                tcpProxyServiceWithPort(
                    "NodePort",
                    32002,
                    tcpProxyPort(32002, "tcp-2323", "TCP", new IntOrString(2324))))
            .getMessage());
  }

  @Test
  void runtimeProfileRejectsExtraTcpProxyServicePorts() {
    var plan = planner.plan("pr-42");
    Service serviceWithExtraPort =
        new ServiceBuilder()
            .withNewSpec()
            .withType("NodePort")
            .withSelector(Map.of("app", "tcp-proxy-service"))
            .withPorts(
                List.of(
                    canonicalTcpProxyPort(32002),
                    new ServicePortBuilder()
                        .withName("metrics")
                        .withPort(9090)
                        .withProtocol("TCP")
                        .withTargetPort(new IntOrString(9090))
                        .build()))
            .endSpec()
            .build();

    IllegalStateException failure =
        validateTcpProxyServiceFailure(
            plan,
            previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
            serviceWithExtraPort);

    assertEquals(
        "runtime tcp-proxy-service Service must expose exactly one canonical Telnet port",
        failure.getMessage());
  }

  @Test
  void runtimeProfileRejectsMissingTcpProxyServicePortsWithCanonicalPortDiagnostic() {
    var plan = planner.plan("pr-42");
    Namespace namespace = previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002");
    String expected =
        "runtime tcp-proxy-service Service must expose exactly one canonical Telnet port";

    Service serviceWithoutPorts =
        new ServiceBuilder()
            .withNewSpec()
            .withType("NodePort")
            .withSelector(Map.of("app", "tcp-proxy-service"))
            .endSpec()
            .build();
    assertEquals(
        expected, validateTcpProxyServiceFailure(plan, namespace, serviceWithoutPorts).getMessage());

    Service serviceWithEmptyPorts =
        new ServiceBuilder()
            .withNewSpec()
            .withType("NodePort")
            .withSelector(Map.of("app", "tcp-proxy-service"))
            .withPorts(List.of())
            .endSpec()
            .build();
    assertEquals(
        expected, validateTcpProxyServiceFailure(plan, namespace, serviceWithEmptyPorts).getMessage());
  }

  @Test
  void publicRuntimeRejectsExternalIps() {
    var plan = planner.plan("pr-42");
    Service serviceWithExternalIp =
        new ServiceBuilder()
            .withNewSpec()
            .withType("NodePort")
            .withSelector(Map.of("app", "tcp-proxy-service"))
            .withExternalIPs("203.0.113.10")
            .withPorts(canonicalTcpProxyPort(32002))
            .endSpec()
            .build();

    IllegalStateException failure =
        validateTcpProxyServiceFailure(
            plan,
            previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
            serviceWithExternalIp);

    assertEquals(
        "public runtime tcp-proxy-service Service cannot carry external IPs", failure.getMessage());
  }

  @Test
  void canonicalTcpProxyServicePassesForPublicAndPrivateModes() {
    var publicPlan = planner.plan("pr-42");
    assertDoesNotThrow(
        () ->
            validateTcpProxyService(
                publicPlan,
                previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
                tcpProxyService("NodePort", 32002)));

    var privatePlan = planner.plan("pr-42");
    assertDoesNotThrow(
        () ->
            validateTcpProxyService(
                privatePlan,
                previewRuntimeNamespace(
                    "a".repeat(40),
                    "a".repeat(40),
                    null,
                    HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE),
                tcpProxyService("ClusterIP", null)));
  }

  @Test
  void runtimeProfileAcceptsKubernetesDefaultedProtocolAndTargetPort() {
    var plan = planner.plan("pr-42");
    Service defaultedPortService =
        tcpProxyServiceWithPort(
            "NodePort",
            32002,
            new ServicePortBuilder().withName("tcp-2323").withPort(2323).build());

    assertDoesNotThrow(
        () ->
            validateTcpProxyService(
                plan,
                previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "32002"),
                defaultedPortService));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void preDeployRuntimeProfileDoesNotRequireTcpProxyService() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get()).thenReturn(previewRuntimeNamespace("a".repeat(40), null, "32002"));

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);

    assertFalse(profile.deployedHeadMatchesRequest());
    assertEquals(null, profile.deployedHeadSha());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void privateRuntimeRejectsExternalIpsEvenWhenItHasNoNodePort() {
    var plan = planner.plan("pr-42");
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespace = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespace);
    when(namespace.get())
        .thenReturn(
            previewRuntimeNamespace(
                "a".repeat(40),
                "a".repeat(40),
                null,
                HostedIdentityContract.PRIVATE_PREVIEW_EXPOSURE_MODE));
    stubTcpProxyService(
        client,
        plan,
        new ServiceBuilder()
            .withNewSpec()
            .withType("ClusterIP")
            .withSelector(Map.of("app", "tcp-proxy-service"))
            .withExternalIPs("203.0.113.10")
            .withPorts(canonicalTcpProxyPort(null))
            .endSpec()
            .build());

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.validateTcpProxyService(client, plan, profile));

    assertEquals(
        "private runtime tcp-proxy-service Service cannot carry external IPs",
        failure.getMessage());
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
    IllegalStateException invalidPortFailure =
        assertThrows(IllegalStateException.class, () -> service.read(client, plan));
    assertEquals(
        "runtime Namespace has an invalid Telnet port identity: 32016",
        invalidPortFailure.getMessage());
    assertInstanceOf(IllegalArgumentException.class, invalidPortFailure.getCause());
    assertEquals(
        "parsed Telnet port is outside the configured allocation",
        invalidPortFailure.getCause().getMessage());

    Namespace malformedPort = previewRuntimeNamespace("a".repeat(40), "a".repeat(40), "not-a-port");
    when(namespace.get()).thenReturn(malformedPort);
    IllegalStateException malformedPortFailure =
        assertThrows(IllegalStateException.class, () -> service.read(client, plan));
    assertEquals(
        "runtime Namespace has an invalid Telnet port identity: not-a-port",
        malformedPortFailure.getMessage());
    assertInstanceOf(NumberFormatException.class, malformedPortFailure.getCause());
  }

  private static Namespace previewRuntimeNamespace(
      String requestedHead, String deployedHead, String port) {
    return previewRuntimeNamespace(
        requestedHead, deployedHead, port, HostedIdentityContract.PUBLIC_PREVIEW_EXPOSURE_MODE);
  }

  private static Namespace previewRuntimeNamespace(
      String requestedHead, String deployedHead, String port, String exposureMode) {
    Map<String, String> labels =
        new java.util.LinkedHashMap<>(
            Map.of("firemud.dev/preview", "true", "firemud.dev/pr-number", "42"));
    if (exposureMode != null) {
      labels.put(HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL, exposureMode);
    }
    var builder =
        new NamespaceBuilder()
            .withNewMetadata()
            .withName("pr-42")
            .withUid("runtime-uid")
            .withLabels(labels);
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
    return devDemoRuntimeNamespace(requestedHead, deployedHead, null);
  }

  private static Namespace devDemoRuntimeNamespace(
      String requestedHead, String deployedHead, String exposureMode) {
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
    if (exposureMode != null) {
      builder.addToLabels(HostedIdentityContract.PREVIEW_EXPOSURE_MODE_LABEL, exposureMode);
    }
    if (requestedHead != null) {
      builder.addToAnnotations("firemud.dev/requested-dev-demo-head-sha", requestedHead);
    }
    if (deployedHead != null) {
      builder.addToAnnotations("firemud.dev/last-dev-demo-head-sha", deployedHead);
    }
    return builder.endMetadata().build();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private IllegalStateException validateTcpProxyServiceFailure(
      EnvironmentIdentityPlan plan, Namespace namespace, Service tcpProxyService) {
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespaceResource = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespaceResource);
    when(namespaceResource.get()).thenReturn(namespace);
    stubTcpProxyService(client, plan, tcpProxyService);

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    return assertThrows(
        IllegalStateException.class, () -> service.validateTcpProxyService(client, plan, profile));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void validateTcpProxyService(
      EnvironmentIdentityPlan plan, Namespace namespace, Service tcpProxyService) {
    KubernetesClient client = mock(KubernetesClient.class);
    NonNamespaceOperation namespaces = mock(NonNamespaceOperation.class);
    Resource<Namespace> namespaceResource = mock(Resource.class);
    when(client.namespaces()).thenReturn(namespaces);
    when(namespaces.withName(plan.runtimeNamespace())).thenReturn(namespaceResource);
    when(namespaceResource.get()).thenReturn(namespace);
    stubTcpProxyService(client, plan, tcpProxyService);

    RuntimeProfileService.RuntimeProfile profile = service.read(client, plan);
    service.validateTcpProxyService(client, plan, profile);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void stubTcpProxyService(
      KubernetesClient client, EnvironmentIdentityPlan plan, String type, Integer nodePort) {
    stubTcpProxyService(client, plan, type == null ? null : tcpProxyService(type, nodePort));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void stubTcpProxyService(
      KubernetesClient client, EnvironmentIdentityPlan plan, Service service) {
    MixedOperation services = mock(MixedOperation.class);
    NonNamespaceOperation scopedServices = mock(NonNamespaceOperation.class);
    ServiceResource<Service> serviceResource = mock(ServiceResource.class);
    when(client.services()).thenReturn(services);
    when(services.inNamespace(plan.runtimeNamespace())).thenReturn(scopedServices);
    when(scopedServices.withName("tcp-proxy-service")).thenReturn(serviceResource);
    when(serviceResource.get()).thenReturn(service);
  }

  private static void stubTcpProxyService(
      KubernetesClient client, EnvironmentIdentityPlan plan, KubernetesClientException failure) {
    MixedOperation services = mock(MixedOperation.class);
    NonNamespaceOperation scopedServices = mock(NonNamespaceOperation.class);
    ServiceResource<Service> serviceResource = mock(ServiceResource.class);
    when(client.services()).thenReturn(services);
    when(services.inNamespace(plan.runtimeNamespace())).thenReturn(scopedServices);
    when(scopedServices.withName("tcp-proxy-service")).thenReturn(serviceResource);
    when(serviceResource.get()).thenThrow(failure);
  }

  private static Service tcpProxyService(String type, Integer nodePort) {
    return tcpProxyServiceWithPort(type, nodePort, canonicalTcpProxyPort(nodePort));
  }

  private static Service tcpProxyServiceWithSelector(
      String type, Integer nodePort, Map<String, String> selector) {
    return new ServiceBuilder()
        .withNewSpec()
        .withType(type)
        .withSelector(selector)
        .withPorts(canonicalTcpProxyPort(nodePort))
        .endSpec()
        .build();
  }

  private static Service tcpProxyServiceWithPort(
      String type, Integer nodePort, ServicePort servicePort) {
    ServicePort effectivePort = servicePort;
    if (nodePort != null && servicePort.getNodePort() == null) {
      effectivePort = servicePort.toBuilder().withNodePort(nodePort).build();
    }
    return new ServiceBuilder()
        .withNewSpec()
        .withType(type)
        .withSelector(Map.of("app", "tcp-proxy-service"))
        .withPorts(effectivePort)
        .endSpec()
        .build();
  }

  private static ServicePort canonicalTcpProxyPort(Integer nodePort) {
    return tcpProxyPort(nodePort, "tcp-2323", "TCP", new IntOrString(2323));
  }

  private static ServicePort tcpProxyPort(
      Integer nodePort, String name, String protocol, IntOrString targetPort) {
    ServicePortBuilder servicePort =
        new ServicePortBuilder()
            .withName(name)
            .withPort(2323)
            .withProtocol(protocol)
            .withTargetPort(targetPort);
    if (nodePort != null) {
      servicePort.withNodePort(nodePort);
    }
    return servicePort.build();
  }
}
