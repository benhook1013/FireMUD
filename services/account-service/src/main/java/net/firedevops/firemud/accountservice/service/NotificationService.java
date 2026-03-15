package net.firedevops.firemud.accountservice.service;

public interface NotificationService {
  void sendNotification(Long tenantId, Long accountId, String message);
}
