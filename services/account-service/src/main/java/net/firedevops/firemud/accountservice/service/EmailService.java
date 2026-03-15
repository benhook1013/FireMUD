package net.firedevops.firemud.accountservice.service;

public interface EmailService {
  void sendEmail(String to, String subject, String body);
}
