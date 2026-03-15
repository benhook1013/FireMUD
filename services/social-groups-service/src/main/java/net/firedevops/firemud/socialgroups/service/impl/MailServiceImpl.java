package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.entity.MailMessage;
import net.firedevops.firemud.socialgroups.mapper.MailMessageMapper;
import net.firedevops.firemud.socialgroups.repository.MailMessageRepository;
import net.firedevops.firemud.socialgroups.service.MailService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {
  private static final Logger logger = LoggingUtil.getLogger(MailServiceImpl.class);

  private final MailMessageRepository mailRepository;
  private final MailMessageMapper mapper;

  @Override
  @Timed(value = "mail.send")
  @Transactional
  public MailMessageDto sendMail(SendMailRequest request) {
    logger.info("Mail from {} to {}", request.senderAccountId(), request.recipientAccountId());
    MailMessage msg = new MailMessage();
    msg.setTenantId(request.tenantId());
    msg.setSenderAccountId(request.senderAccountId());
    msg.setRecipientAccountId(request.recipientAccountId());
    msg.setSubject(request.subject());
    msg.setContent(request.content());
    msg.setSentAt(Instant.now());
    return mapper.toDto(mailRepository.save(msg));
  }
}
