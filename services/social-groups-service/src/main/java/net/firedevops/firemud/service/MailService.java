package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.MailMessageDto;
import net.firedevops.firemud.dto.SendMailRequest;

public interface MailService {
  MailMessageDto sendMail(SendMailRequest request);
}
