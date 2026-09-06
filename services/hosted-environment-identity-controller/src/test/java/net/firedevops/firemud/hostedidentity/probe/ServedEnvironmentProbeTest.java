package net.firedevops.firemud.hostedidentity.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import org.junit.jupiter.api.Test;

class ServedEnvironmentProbeTest {
  @Test
  void configuredPortsOwnTheProbeDerivationBoundary() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setPreviewTelnetPortBase(41000);
    properties.setDevDemoTelnetPort(42000);
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);

    assertEquals(42000, probe.telnetPort("dev-demo"));
    assertEquals(41001, probe.telnetPort("pr-1"));
    assertEquals(41015, probe.telnetPort("pr-15"));
    assertThrows(IllegalArgumentException.class, () -> probe.telnetPort("pr-16"));
  }

  @Test
  void invalidConfiguredPortRangesFailClosed() {
    HostedIdentityProperties properties = new HostedIdentityProperties();
    properties.setPreviewTelnetPortBase(65521);
    properties.setDevDemoTelnetPort(0);
    ServedEnvironmentProbe probe = new ServedEnvironmentProbe(properties);

    assertThrows(IllegalArgumentException.class, () -> probe.telnetPort("dev-demo"));
    assertThrows(IllegalArgumentException.class, () -> probe.telnetPort("pr-1"));
  }
}
