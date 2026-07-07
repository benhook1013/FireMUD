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
}
