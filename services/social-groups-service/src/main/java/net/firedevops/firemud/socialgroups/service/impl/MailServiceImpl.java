package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtClaims;
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
    String senderAccountId =
        JwtClaims.requireAccountId(request.senderAccountId(), "senderAccountId");
    String recipientAccountId =
        JwtClaims.requireAccountId(request.recipientAccountId(), "recipientAccountId");
    logger.info("Mail from {} to {}", senderAccountId, recipientAccountId);
    MailMessage msg = new MailMessage();
    msg.setTenantId(request.tenantId());
    msg.setSenderAccountId(UUID.fromString(senderAccountId));
    msg.setRecipientAccountId(UUID.fromString(recipientAccountId));
    msg.setSubject(request.subject());
    msg.setContent(request.content());
    msg.setSentAt(Instant.now());
    return mapper.toDto(mailRepository.save(msg));
  }
}
