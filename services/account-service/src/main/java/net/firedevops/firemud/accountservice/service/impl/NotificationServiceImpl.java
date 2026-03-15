package net.firedevops.firemud.accountservice.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {
  private static final Logger logger = LoggingUtil.getLogger(NotificationServiceImpl.class);

  @Override
  @Timed(value = "notification.send")
  public void sendNotification(Long tenantId, Long accountId, String message) {
    logger.info("Send notification to account {} tenant {}: {}", accountId, tenantId, message);
  }
}
