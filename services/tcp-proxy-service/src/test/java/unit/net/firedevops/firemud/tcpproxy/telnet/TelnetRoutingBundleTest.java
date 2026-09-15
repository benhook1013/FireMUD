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
  void configuredDefaultsRejectGameInstanceIdWithoutTenantId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TelnetRoutingBundle.validateConfiguredDefaults(
                "game-instance", null, null, null, null));
  }

  @Test
  void configuredDefaultsRejectTenantIdWithoutGameInstanceId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TelnetRoutingBundle.validateConfiguredDefaults(null, "tenant", null, null, null));
  }

  @Test
  void configuredDefaultsAllowCompleteFiveFieldBundle() {
    TelnetRoutingBundle routingBundle =
        TelnetRoutingBundle.validateConfiguredDefaults(
            "game-instance", "tenant", "demo", "production", "17");

    assertEquals("demo", routingBundle.worldSlug());
    assertEquals("production", routingBundle.realmSlug());
    assertEquals("17", routingBundle.pointerVersion());
  }

  @Test
  void configuredDefaultsRejectIdentityPairWithoutRoutingGroup() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "game-instance", "tenant", null, null, null));

    assertEquals(
        "Configured routing defaults must include X-Game-Instance-Id, X-Tenant-Id, "
            + "X-World-Slug, X-Realm-Slug, and X-Pointer-Version together",
        exception.getMessage());
  }

  @Test
  void configuredDefaultsRejectRoutingGroupWithoutIdentityPair() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    null, null, "demo", "production", "17"));

    assertEquals(
        "Configured routing defaults must include X-Game-Instance-Id, X-Tenant-Id, "
            + "X-World-Slug, X-Realm-Slug, and X-Pointer-Version together",
        exception.getMessage());
  }

  @Test
  void configuredDefaultsRejectControlOnlyHeaderValues() {
    for (String controlOnly : new String[] {"\t", "\r", "\n"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              TelnetRoutingBundle.validateConfiguredDefaults(controlOnly, null, null, null, null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              TelnetRoutingBundle.validateConfiguredDefaults(null, null, controlOnly, null, null));
    }
  }

  @Test
  void configuredDefaultsRejectHeaderCharactersAboveByteRange() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> TelnetRoutingBundle.validateConfiguredDefaults("\u0100", null, null, null, null));

    assertEquals(
        "Header value for X-Game-Instance-Id contains a disallowed character",
        exception.getMessage());
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
            TelnetRoutingBundle.validateConfiguredDefaults(
                "game-instance", "tenant", "demo", "production", "0"));
  }

  @Test
  void configuredDefaultsRejectMalformedPointerVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TelnetRoutingBundle.validateConfiguredDefaults(
                "game-instance", "tenant", "demo", "production", "not-a-number"));
  }
}
