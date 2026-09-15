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
  void normalizeReturnsNullWhenSlugIsNotCanonical() {
    assertNull(TelnetRoutingBundle.normalize("Demo", "production", "17"));
    assertNull(TelnetRoutingBundle.normalize("demo_world", "production", "17"));
  }

  @Test
  void normalizeReturnsNullWhenSlugExceedsUtf8ByteLimit() {
    assertNull(TelnetRoutingBundle.normalize("a".repeat(119) + "é", "production", "17"));
  }

  @Test
  void normalizeReturnsNullWhenPointerVersionOverflowsLong() {
    assertNull(TelnetRoutingBundle.normalize("demo", "production", "9223372036854775808"));
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
            "42", "7", "demo", "production", "17");

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
    String[] controlOnlyValues = {"\t", "\r", "\n"};
    String[] headers = {
      "X-Game-Instance-Id", "X-Tenant-Id", "X-World-Slug", "X-Realm-Slug", "X-Pointer-Version"
    };
    for (String controlOnly : controlOnlyValues) {
      for (int headerIndex = 0; headerIndex < headers.length; headerIndex++) {
        String[] configuredValues = new String[headers.length];
        configuredValues[headerIndex] = controlOnly;
        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    TelnetRoutingBundle.validateConfiguredDefaults(
                        configuredValues[0],
                        configuredValues[1],
                        configuredValues[2],
                        configuredValues[3],
                        configuredValues[4]));
        assertEquals(
            "Header value for " + headers[headerIndex] + " contains a disallowed character",
            exception.getMessage());
      }
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
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "42", "7", "demo", "production", "0"));

    assertEquals("Malformed routing pointer version: pointerVersion", exception.getMessage());
  }

  @Test
  void configuredDefaultsRejectMalformedPointerVersion() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "42", "7", "demo", "production", "not-a-number"));

    assertEquals("Malformed routing pointer version: pointerVersion", exception.getMessage());
  }

  @Test
  void configuredDefaultsIdentifyMalformedWorldSlug() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "42", "7", "Demo", "production", "17"));

    assertEquals("Malformed routing slug: worldSlug", exception.getMessage());
  }

  @Test
  void configuredDefaultsIdentifyMalformedRealmSlug() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "42", "7", "demo", "Production", "17"));

    assertEquals("Malformed routing slug: realmSlug", exception.getMessage());
  }

  @Test
  void configuredDefaultsRejectMalformedIdentityIds() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "not-a-number", "7", "demo", "production", "17"));

    assertEquals("gameInstanceId must be numeric", exception.getMessage());
  }

  @Test
  void configuredDefaultsRejectNonPositiveIdentityIds() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "0", "7", "demo", "production", "17"));

    assertEquals("gameInstanceId must be positive", exception.getMessage());
  }

  @Test
  void configuredDefaultsRejectNonPositiveTenantId() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TelnetRoutingBundle.validateConfiguredDefaults(
                    "42", "-1", "demo", "production", "17"));

    assertEquals("tenantId must be positive", exception.getMessage());
  }
}
