package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.entity.MailMessage;
import net.firedevops.firemud.socialgroups.mapper.MailMessageMapper;
import net.firedevops.firemud.socialgroups.repository.MailMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class MailServiceImplTest {
  private MailMessageRepository repository;
  private MailServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(MailMessageRepository.class);
    MailMessageMapper mapper = Mappers.getMapper(MailMessageMapper.class);
    service = new MailServiceImpl(repository, mapper);
  }

  @Test
  void sendMailReturnsDto() {
    SendMailRequest request = new SendMailRequest(1L, 2L, 3L, "hello", "body");
    MailMessage saved = new MailMessage();
    saved.setId(1L);
    saved.setTenantId(1L);
    saved.setSenderAccountId(2L);
    saved.setRecipientAccountId(3L);
    saved.setSubject("hello");
    saved.setContent("body");
    saved.setSentAt(Instant.now());
    when(repository.save(any(MailMessage.class))).thenReturn(saved);

    MailMessageDto result = service.sendMail(request);
    assertEquals("hello", result.subject());
    assertEquals(3L, result.recipientAccountId());
  }
}
