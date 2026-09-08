package net.firedevops.firemud.tcpproxy.telnet;

import net.firedevops.firemud.common.security.JwtClaims;
import org.springframework.util.StringUtils;

record TelnetRoutingBundle(String worldSlug, String realmSlug, String pointerVersion) {
  static TelnetRoutingBundle normalize(String worldSlug, String realmSlug, String pointerVersion) {
    if (!StringUtils.hasText(worldSlug)
        || !StringUtils.hasText(realmSlug)
        || !StringUtils.hasText(pointerVersion)) {
      return null;
    }
    try {
      return new TelnetRoutingBundle(
          worldSlug,
          realmSlug,
          Long.toString(JwtClaims.requireLong(pointerVersion, "pointerVersion", false)));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  static TelnetRoutingBundle validateConfiguredDefaults(
      String gameInstanceId,
      String tenantId,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {
    validateHeaderValue("X-Game-Instance-Id", gameInstanceId);
    validateHeaderValue("X-Tenant-Id", tenantId);
    validateHeaderValue("X-World-Slug", worldSlug);
    validateHeaderValue("X-Realm-Slug", realmSlug);
    validateHeaderValue("X-Pointer-Version", pointerVersion);

    boolean worldConfigured = StringUtils.hasText(worldSlug);
    boolean realmConfigured = StringUtils.hasText(realmSlug);
    boolean pointerConfigured = StringUtils.hasText(pointerVersion);
    if (!worldConfigured && !realmConfigured && !pointerConfigured) {
      return null;
    }
    if (!worldConfigured || !realmConfigured || !pointerConfigured) {
      throw new IllegalArgumentException(
          "Configured routing defaults must include X-World-Slug, X-Realm-Slug, and "
              + "X-Pointer-Version together");
    }

    TelnetRoutingBundle routingBundle = normalize(worldSlug, realmSlug, pointerVersion);
    if (routingBundle == null) {
      throw new IllegalArgumentException(
          "Configured routing defaults must use a positive numeric X-Pointer-Version");
    }
    return routingBundle;
  }

  static void validateHeaderValue(String headerName, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) {
        throw new IllegalArgumentException(
            "Header value for " + headerName + " contains a disallowed control character");
      }
    }
  }
}
