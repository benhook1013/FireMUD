package net.firedevops.firemud.accountservice.service.impl;

import static org.mockito.Mockito.verify;

import net.firedevops.firemud.accountservice.config.MailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailServiceImplTest {
  @Mock private JavaMailSender sender;

  private MailProperties props;
  private EmailServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    props = new MailProperties();
    props.setFrom("test@example.com");
    service = new EmailServiceImpl(sender, props);
  }

  @Test
  void sendEmailUsesJavaMailSender() {
    service.sendEmail("to@example.com", "Sub", "Body");

    verify(sender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
  }
}
