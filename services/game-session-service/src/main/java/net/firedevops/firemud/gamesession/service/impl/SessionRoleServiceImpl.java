package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.service.SessionRoleService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

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
