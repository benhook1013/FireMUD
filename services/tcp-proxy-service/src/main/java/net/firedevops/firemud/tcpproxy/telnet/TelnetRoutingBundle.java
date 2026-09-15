package net.firedevops.firemud.tcpproxy.telnet;

import net.firedevops.firemud.common.security.GameplayRoutingBundleValidator;
import net.firedevops.firemud.common.security.RequestIdValidation;
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
          GameplayRoutingBundleValidator.requireCanonicalSlug(worldSlug, "worldSlug"),
          GameplayRoutingBundleValidator.requireCanonicalSlug(realmSlug, "realmSlug"),
          GameplayRoutingBundleValidator.requireCanonicalPointerVersion(
              pointerVersion, "pointerVersion"));
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

    boolean gameInstanceConfigured = StringUtils.hasText(gameInstanceId);
    boolean tenantConfigured = StringUtils.hasText(tenantId);
    boolean worldConfigured = StringUtils.hasText(worldSlug);
    boolean realmConfigured = StringUtils.hasText(realmSlug);
    boolean pointerConfigured = StringUtils.hasText(pointerVersion);
    if (!gameInstanceConfigured
        && !tenantConfigured
        && !worldConfigured
        && !realmConfigured
        && !pointerConfigured) {
      return null;
    }
    if (!gameInstanceConfigured
        || !tenantConfigured
        || !worldConfigured
        || !realmConfigured
        || !pointerConfigured) {
      throw new IllegalArgumentException(
          "Configured routing defaults must include X-Game-Instance-Id, X-Tenant-Id, "
              + "X-World-Slug, X-Realm-Slug, and X-Pointer-Version together");
    }

    RequestIdValidation.requirePositiveLong(gameInstanceId, "gameInstanceId");
    RequestIdValidation.requirePositiveLong(tenantId, "tenantId");

    String canonicalWorldSlug =
        GameplayRoutingBundleValidator.requireCanonicalSlug(worldSlug, "worldSlug");
    String canonicalRealmSlug =
        GameplayRoutingBundleValidator.requireCanonicalSlug(realmSlug, "realmSlug");
    String canonicalPointerVersion =
        GameplayRoutingBundleValidator.requireCanonicalPointerVersion(
            pointerVersion, "pointerVersion");
    return new TelnetRoutingBundle(canonicalWorldSlug, canonicalRealmSlug, canonicalPointerVersion);
  }

  static void validateHeaderValue(String headerName, String value) {
    if (value == null) {
      return;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isISOControl(character) || character > 0xFF) {
        throw new IllegalArgumentException(
            "Header value for " + headerName + " contains a disallowed character");
      }
    }
  }
}
