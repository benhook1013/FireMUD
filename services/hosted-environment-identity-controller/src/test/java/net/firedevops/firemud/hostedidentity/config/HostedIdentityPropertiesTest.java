package net.firedevops.firemud.hostedidentity.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HostedIdentityPropertiesTest {
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
}
