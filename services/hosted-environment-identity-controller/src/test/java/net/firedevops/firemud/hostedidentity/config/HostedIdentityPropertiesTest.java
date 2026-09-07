package net.firedevops.firemud.hostedidentity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HostedIdentityPropertiesTest {
  @Test
  void activationDefaultsAndInvalidValuesFailClosedToPaused() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    properties.setActivationMode("observe");
    assertEquals(HostedIdentityProperties.ActivationMode.OBSERVE, properties.activationMode());
    properties.setActivationMode("unexpected");
    assertEquals(HostedIdentityProperties.ActivationMode.PAUSED, properties.activationMode());
    assertEquals(Duration.ofDays(7), properties.getGrpcRenewBefore());
  }
}
