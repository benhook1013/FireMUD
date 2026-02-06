package net.firedevops.firemud.tcpproxy.telnet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Holds the session metadata negotiated over Telnet so it can be forwarded to the gateway and Game
 * Session Service.
 */
final class TelnetSessionContext {
  private static final Logger logger = LoggerFactory.getLogger(TelnetSessionContext.class);

  private volatile String gameInstanceId;
  private volatile String tenantId;

  boolean captureFromEnvelope(String envelope) {
    if (!StringUtils.hasText(envelope)) {
      return false;
    }
    String trimmed = envelope.trim();
    if (!trimmed.toUpperCase().startsWith("SESSION ")) {
      return false;
    }
    String payload = trimmed.substring("SESSION".length()).trim();
    if (!StringUtils.hasText(payload)) {
      logger.warn("Ignoring empty session envelope");
      return false;
    }

    if (payload.contains(":")) {
      String[] tokens = payload.split(":", 2);
      gameInstanceId = tokens[0];
      tenantId = tokens.length > 1 ? tokens[1] : null;
    } else {
      String[] parts = payload.split("\\s+");
      if (parts.length < 2) {
        logger.warn("Ignoring malformed session envelope: {}", envelope);
        return false;
      }
      gameInstanceId = parts[0];
      tenantId = parts[1];
    }

    if (!StringUtils.hasText(gameInstanceId) || !StringUtils.hasText(tenantId)) {
      logger.warn("Ignoring session envelope missing gameInstanceId or tenantId: {}", envelope);
      gameInstanceId = null;
      tenantId = null;
      return false;
    }

    logger.info("Captured Telnet gameInstance {} for tenant {}", gameInstanceId, tenantId);
    return true;
  }

  boolean isReady() {
    return StringUtils.hasText(gameInstanceId) && StringUtils.hasText(tenantId);
  }

  String gameInstanceId() {
    return gameInstanceId;
  }

  String tenantId() {
    return tenantId;
  }
}
