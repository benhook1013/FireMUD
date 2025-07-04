package net.fire_devops.firemud.service.impl;

import net.fire_devops.firemud.common.LoggingUtil;
import net.fire_devops.firemud.service.PingService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class PingServiceImpl implements PingService {
    private static final Logger logger = LoggingUtil.getLogger(PingServiceImpl.class);

    @Override
    public String ping() {
        logger.info("Ping called");
        return "pong";
    }
}
