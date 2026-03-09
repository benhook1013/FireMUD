package net.firedevops.firemud.socialgroups.service;

import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;

public interface MailService {
  MailMessageDto sendMail(SendMailRequest request);
}
