package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class PingServiceImpl implements PingService {
  private static final Logger logger = LoggingUtil.getLogger(PingServiceImpl.class);

  @Override
  @Timed(value = "automation.ping")
  public String ping() {
    logger.info("Ping called");
    return "pong";
  }
}
