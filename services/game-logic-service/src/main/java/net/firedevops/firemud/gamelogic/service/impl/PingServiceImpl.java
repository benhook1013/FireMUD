package net.firedevops.firemud.gamelogic.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamelogic.service.PingService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class PingServiceImpl implements PingService {
  private static final Logger logger = LoggingUtil.getLogger(PingServiceImpl.class);

  @Override
  @Timed(value = "game_logic.ping")
  public String ping() {
    logger.info("Ping called");
    return "pong";
  }
}
