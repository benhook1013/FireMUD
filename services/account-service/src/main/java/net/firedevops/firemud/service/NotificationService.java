package net.firedevops.firemud.service;

public interface NotificationService {
  void sendNotification(Long tenantId, Long accountId, String message);
}
