package net.firedevops.firemud.service.impl;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.MailMessageDto;
import net.firedevops.firemud.dto.SendMailRequest;
import net.firedevops.firemud.entity.MailMessage;
import net.firedevops.firemud.mapper.MailMessageMapper;
import net.firedevops.firemud.repository.MailMessageRepository;
import net.firedevops.firemud.service.MailService;
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
