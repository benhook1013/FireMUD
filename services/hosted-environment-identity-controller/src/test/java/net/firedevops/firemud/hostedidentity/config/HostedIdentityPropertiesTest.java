package net.firedevops.firemud.hostedidentity.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.BiConsumer;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(OutputCaptureExtension.class)
class HostedIdentityPropertiesTest {
  @Test
  void applicationYamlBindsAllPreviewAndDevDemoAnnotationKeys() throws Exception {
    var environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    for (var source :
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))) {
      sources.addLast(source);
    }
    var properties = new HostedIdentityProperties();

    var binding =
        Binder.get(environment).bind("firemud.hosted-identity", Bindable.ofInstance(properties));

    assertTrue(binding.isBound());
    assertDoesNotThrow(properties::afterPropertiesSet);
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    assertEquals("preview.firedevops.net", properties.getPreviewDomain());
    assertEquals("dev.preview.firedevops.net", properties.getDevDemoHostname());
    assertEquals(
        "firemud.dev/requested-preview-head-sha", properties.getPreviewRequestedHeadAnnotation());
    assertEquals(
        "firemud.dev/last-preview-head-sha", properties.getPreviewDeployedHeadAnnotation());
    assertEquals(
        "firemud.dev/requested-dev-demo-head-sha", properties.getDevDemoRequestedHeadAnnotation());
    assertEquals("firemud.dev/last-dev-demo-head-sha", properties.getDevDemoHeadAnnotation());
    assertEquals(
        "firemud.dev/last-preview-telnet-port", properties.getPreviewTelnetPortAnnotation());
    assertEquals(
        "firemud.dev/last-dev-demo-telnet-port", properties.getDevDemoTelnetPortAnnotation());
  }

  @Test
  void usesAndRequiresCanonicalGrpcCaSecretName() {
    var properties = new HostedIdentityProperties();
    assertEquals(HostedIdentityContract.GRPC_CA_SECRET_NAME, properties.getCaSecretName());
    properties.afterPropertiesSet();
    properties.setCaSecretName("other-ca");
    assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
  }

  @Test
  void validatesCanonicalControlNamespaceBeforeTelnetPortAllocations() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    assertDoesNotThrow(properties::afterPropertiesSet);

    properties.setPreviewTelnetPortBase(32016);
    properties.setDevDemoTelnetPort(32000);
    properties.setControlNamespace("other-system");
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    assertEquals(
        "hosted identity control namespace must be firemud-system", exception.getMessage());
  }

  @Test
  void validatesConfiguredHostnamesAgainstTheCrdStatusContract() {
    for (String invalidHostname :
        new String[] {
          null,
          "",
          "Preview.firedevops.net",
          "preview_firedevops_net",
          "-preview.firedevops.net",
          "preview.firedevops.net-",
          "preview..firedevops.net",
          "preview.-firedevops.net",
          "preview-.firedevops.net",
          "a".repeat(64) + ".firedevops.net",
          "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + "." + "b".repeat(62),
          "a".repeat(254)
        }) {
      HostedIdentityProperties previewProperties = new HostedIdentityProperties();
      previewProperties.setPreviewDomain(invalidHostname);
      IllegalStateException previewFailure =
          assertThrows(IllegalStateException.class, previewProperties::afterPropertiesSet);
      assertEquals(
          "preview domain must match the lowercase hostname contract and contain at most 253 characters",
          previewFailure.getMessage());

      HostedIdentityProperties devDemoProperties = new HostedIdentityProperties();
      devDemoProperties.setDevDemoHostname(invalidHostname);
      IllegalStateException devDemoFailure =
          assertThrows(IllegalStateException.class, devDemoProperties::afterPropertiesSet);
      assertEquals(
          "dev-demo hostname must match the lowercase hostname contract and contain at most 253 characters",
          devDemoFailure.getMessage());
    }

    HostedIdentityProperties maximum = new HostedIdentityProperties();
    String maximumHostname =
        "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + "." + "b".repeat(61);
    maximum.setPreviewDomain(maximumHostname);
    maximum.setDevDemoHostname(maximumHostname);
    assertDoesNotThrow(maximum::afterPropertiesSet);
  }

  @Test
  void rejectsEveryNoncanonicalTelnetPortAllocation() {
    for (int invalidPort : new int[] {31999, 32001, 32016, 32017}) {
      HostedIdentityProperties previewProperties = new HostedIdentityProperties();
      previewProperties.setPreviewTelnetPortBase(invalidPort);
      IllegalStateException exception =
          assertThrows(IllegalStateException.class, previewProperties::afterPropertiesSet);
      assertEquals("preview Telnet port base must be 32000", exception.getMessage());
    }

    for (int invalidPort : new int[] {31999, 32000, 32015, 32017}) {
      HostedIdentityProperties devDemoProperties = new HostedIdentityProperties();
      devDemoProperties.setDevDemoTelnetPort(invalidPort);
      IllegalStateException exception =
          assertThrows(IllegalStateException.class, devDemoProperties::afterPropertiesSet);
      assertEquals("dev-demo Telnet port must be 32016", exception.getMessage());
    }
  }

  @Test
  void rejectsInvalidGrpcRenewalWindowsAtConfigurationStartup() {
    for (Duration invalidRenewBefore :
        new Duration[] {
          null,
          Duration.ZERO,
          Duration.ofMinutes(-1),
          Duration.ofMinutes(5).minusNanos(1),
          Duration.ofDays(30),
          Duration.ofDays(31)
        }) {
      HostedIdentityProperties properties = new HostedIdentityProperties();
      properties.setGrpcRenewBefore(invalidRenewBefore);
      IllegalStateException exception =
          assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
      assertEquals(
          "gRPC renewal window must be at least 5 minutes and leave at least 5 minutes before the 30-day certificate expiry",
          exception.getMessage());
    }
  }

  @Test
  void acceptsGrpcRenewalWindowBoundariesInsideCanonicalRange() {
    HostedIdentityProperties minimum = new HostedIdentityProperties();
    minimum.setGrpcRenewBefore(Duration.ofMinutes(5));
    assertDoesNotThrow(minimum::afterPropertiesSet);

    HostedIdentityProperties maximum = new HostedIdentityProperties();
    maximum.setGrpcRenewBefore(
        HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION.minus(
            HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK));
    assertDoesNotThrow(maximum::afterPropertiesSet);

    HostedIdentityProperties beyondMaximum = new HostedIdentityProperties();
    beyondMaximum.setGrpcRenewBefore(
        HostedIdentityProperties.INTERNAL_CERTIFICATE_DURATION
            .minus(HostedIdentityProperties.INTERNAL_CERTIFICATE_RENEWAL_SLACK)
            .plusNanos(1));
    assertThrows(IllegalStateException.class, beyondMaximum::afterPropertiesSet);
  }

  @Test
  void rejectsReconcileIntervalsBelowOneSecond() {
    for (Duration interval :
        new Duration[] {null, Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(-1)}) {
      HostedIdentityProperties properties = new HostedIdentityProperties();
      properties.setReconcileInterval(interval);
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
      assertEquals("reconcile interval must be at least 1 second", failure.getMessage());
    }

    HostedIdentityProperties minimum = new HostedIdentityProperties();
    minimum.setReconcileInterval(Duration.ofSeconds(1));
    assertDoesNotThrow(minimum::afterPropertiesSet);
  }

  @Test
  void validatesOptionalSha256PinsWhenConfigured() {
    assertInvalidPin(HostedIdentityProperties::setIngressTrustAnchorSha256);
    assertInvalidPin(HostedIdentityProperties::setTelnetTrustAnchorSha256);
    assertInvalidPin(HostedIdentityProperties::setGrpcTrustAnchorSha256);
    assertInvalidPin(HostedIdentityProperties::setIngressLeafSha256);
    assertInvalidPin(HostedIdentityProperties::setTelnetLeafSha256);

    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setIngressTrustAnchorSha256("a".repeat(64));
    properties.setTelnetTrustAnchorSha256("b".repeat(64));
    properties.setGrpcTrustAnchorSha256("c".repeat(64));
    properties.setIngressLeafSha256("d".repeat(64));
    properties.setTelnetLeafSha256("e".repeat(64));
    assertDoesNotThrow(properties::afterPropertiesSet);
  }

  private static void assertInvalidPin(BiConsumer<HostedIdentityProperties, String> setter) {
    for (String invalidPin : new String[] {"A".repeat(64), "g".repeat(64)}) {
      HostedIdentityProperties properties = new HostedIdentityProperties();
      setter.accept(properties, invalidPin);
      assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }
  }

  @Test
  void activeModeRequiresANonemptyCanonicalGrpcTrustAnchorAtStartup() {
    for (String trustAnchor : new String[] {null, ""}) {
      HostedIdentityProperties properties = new HostedIdentityProperties();
      properties.setActivationMode("active");
      properties.setGrpcTrustAnchorSha256(trustAnchor);

      IllegalStateException failure =
          assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
      assertEquals(
          "gRPC trust-anchor SHA-256 pin must be a nonempty 64 lowercase hexadecimal value when activation is active",
          failure.getMessage());
    }
  }

  @Test
  void nonActiveModesRetainOptionalGrpcTrustAnchorAndActiveAcceptsCanonicalPin() {
    for (String mode : new String[] {"paused", "observe"}) {
      HostedIdentityProperties properties = new HostedIdentityProperties();
      properties.setActivationMode(mode);
      assertDoesNotThrow(properties::afterPropertiesSet);
    }

    HostedIdentityProperties active = new HostedIdentityProperties();
    active.setActivationMode("active");
    active.setGrpcTrustAnchorSha256("a".repeat(64));
    assertDoesNotThrow(active::afterPropertiesSet);
    assertEquals(HostedIdentityProperties.ActivationMode.ACTIVE, active.activationMode());
  }

  @Test
  void activationDefaultsAndInvalidValuesFailClosedToPaused() {
    assertActivationMode(null, HostedIdentityProperties.ActivationMode.PAUSED);
    assertActivationMode("observe", HostedIdentityProperties.ActivationMode.OBSERVE);
    assertActivationMode("unexpected", HostedIdentityProperties.ActivationMode.PAUSED);
    assertActivationMode("active", HostedIdentityProperties.ActivationMode.ACTIVE);
    assertActivationMode("ACTIVE", HostedIdentityProperties.ActivationMode.ACTIVE);
    assertActivationMode(" \t ", HostedIdentityProperties.ActivationMode.PAUSED);
    assertActivationMode(" active ", HostedIdentityProperties.ActivationMode.ACTIVE);
  }

  @Test
  void invalidActivationModeLogsTheRejectedNonSecretSelector(CapturedOutput output) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("unexpected-mode");
    properties.afterPropertiesSet();

    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    String warning =
        "Rejected hosted identity activation mode 'unexpected-mode'; defaulting to paused";
    assertTrue(output.getOut().contains(warning));
    assertEquals(1, countOccurrences(output.getOut(), warning));
  }

  private static void assertActivationMode(
      String configured, HostedIdentityProperties.ActivationMode expected) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode(configured);
    if (expected == HostedIdentityProperties.ActivationMode.ACTIVE) {
      properties.setGrpcTrustAnchorSha256("a".repeat(64));
    }
    properties.afterPropertiesSet();
    assertEquals(expected, properties.activationMode());
    assertEquals(Duration.ofDays(7), properties.getGrpcRenewBefore());
  }

  private static int countOccurrences(String value, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }
}
