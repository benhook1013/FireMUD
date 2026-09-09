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

    Binder.get(environment).bind("firemud.hosted-identity", Bindable.ofInstance(properties));

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
    maximum.setPreviewDomain("a".repeat(253));
    maximum.setDevDemoHostname("b".repeat(253));
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
          "gRPC renewal window must be at least 5 minutes and shorter than 30 days",
          exception.getMessage());
    }
  }

  @Test
  void acceptsGrpcRenewalWindowBoundariesInsideCanonicalRange() {
    HostedIdentityProperties minimum = new HostedIdentityProperties();
    minimum.setGrpcRenewBefore(Duration.ofMinutes(5));
    assertDoesNotThrow(minimum::afterPropertiesSet);

    HostedIdentityProperties maximum = new HostedIdentityProperties();
    maximum.setGrpcRenewBefore(Duration.ofDays(30).minusNanos(1));
    assertDoesNotThrow(maximum::afterPropertiesSet);
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
    HostedIdentityProperties properties = new HostedIdentityProperties();
    setter.accept(properties, "A".repeat(64));
    assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
  }

  @Test
  void activationDefaultsAndInvalidValuesFailClosedToPaused() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    properties.setActivationMode("observe");
    assertEquals(HostedIdentityProperties.ActivationMode.OBSERVE, properties.activationMode());
    properties.setActivationMode("unexpected");
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    properties.setActivationMode("active");
    assertEquals(HostedIdentityProperties.ActivationMode.ACTIVE, properties.activationMode());
    properties.setActivationMode("ACTIVE");
    assertEquals(HostedIdentityProperties.ActivationMode.ACTIVE, properties.activationMode());
    properties.setActivationMode(null);
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    properties.setActivationMode(" \t ");
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    properties.setActivationMode(" active ");
    assertEquals(HostedIdentityProperties.ActivationMode.ACTIVE, properties.activationMode());
    assertEquals(Duration.ofDays(7), properties.getGrpcRenewBefore());
  }

  @Test
  void invalidActivationModeLogsTheRejectedNonSecretSelector(CapturedOutput output) {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setActivationMode("unexpected-mode");

    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    assertTrue(
        output
            .getOut()
            .contains(
                "Rejected hosted identity activation mode 'unexpected-mode'; defaulting to paused"));
  }
}
