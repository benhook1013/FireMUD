package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TelnetRoutingBundleTest {

  @Test
  void normalizeReturnsBundleWhenComplete() {
    TelnetRoutingBundle routingBundle = TelnetRoutingBundle.normalize("demo", "production", "17");

    assertEquals("demo", routingBundle.worldSlug());
    assertEquals("production", routingBundle.realmSlug());
    assertEquals("17", routingBundle.pointerVersion());
  }

  @Test
  void normalizeReturnsNullWhenRealmSlugIsBlank() {
    assertNull(TelnetRoutingBundle.normalize("demo", " ", "17"));
  }

  @Test
  void normalizeReturnsNullWhenPointerVersionIsBlank() {
    assertNull(TelnetRoutingBundle.normalize("demo", "production", ""));
  }

  @Test
  void normalizeReturnsNullWhenPointerVersionIsMalformed() {
    assertNull(TelnetRoutingBundle.normalize("demo", "production", "abc"));
  }

  @Test
  void normalizeReturnsNullWhenPointerVersionIsNonPositive() {
    assertNull(TelnetRoutingBundle.normalize("demo", "production", "0"));
  }

  @Test
  void configuredDefaultsPreserveOptionalEmptyBundle() {
    assertNull(TelnetRoutingBundle.validateConfiguredDefaults(null, null, null, "", " "));
  }

  @Test
  void configuredDefaultsRejectPartialRoutingBundle() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TelnetRoutingBundle.validateConfiguredDefaults(null, null, "demo", "", "17"));
  }

  @Test
  void configuredDefaultsRejectNonPositivePointerVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TelnetRoutingBundle.validateConfiguredDefaults(null, null, "demo", "production", "0"));
  }
}
