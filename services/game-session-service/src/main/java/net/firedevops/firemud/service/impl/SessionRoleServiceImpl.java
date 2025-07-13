package net.firedevops.firemud.service.impl;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.SessionRoleService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import io.micrometer.core.annotation.Timed;

@Service
public class SessionRoleServiceImpl implements SessionRoleService {
  private static final Logger logger = LoggingUtil.getLogger(SessionRoleServiceImpl.class);

  @Override
  @Timed(value = "gamesession.roles.refresh")
  public String refreshRoles(long sessionId) {
    logger.info("Refreshing roles for session {}", sessionId);
    return "refreshed";
  }
}
