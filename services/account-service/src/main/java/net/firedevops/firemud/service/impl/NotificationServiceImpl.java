package net.firedevops.firemud.service.impl;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.NotificationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {
  private static final Logger logger = LoggingUtil.getLogger(NotificationServiceImpl.class);

  @Override
  public void sendNotification(Long tenantId, Long accountId, String message) {
    logger.info("Send notification to account {} tenant {}: {}", accountId, tenantId, message);
  }
}
