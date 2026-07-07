package net.firedevops.firemud.tcpproxy.telnet;

import org.springframework.util.StringUtils;

record TelnetRoutingBundle(String worldSlug, String realmSlug, String pointerVersion) {
  static TelnetRoutingBundle normalize(String worldSlug, String realmSlug, String pointerVersion) {
    if (!StringUtils.hasText(worldSlug)
        || !StringUtils.hasText(realmSlug)
        || !StringUtils.hasText(pointerVersion)) {
      return null;
    }
    return new TelnetRoutingBundle(worldSlug, realmSlug, pointerVersion);
  }
}
